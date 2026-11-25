package com.example.gateway.service;

import com.example.gateway.audit.AuditLogger;
import com.example.gateway.auth.RateLimiter;
import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.ContainerGuard;
import com.example.gateway.docker.ContainerIndex;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.docker.NodePortAllocator;
import com.example.gateway.docker.NodeRegistry;
import com.example.gateway.docker.Ownership;
import com.example.gateway.error.GatewayException;
import com.example.gateway.policy.ImagePolicy;
import com.example.gateway.web.AdvertiseContext;
import com.example.gateway.web.dto.ContainerInfo;
import com.example.gateway.web.dto.CreateContainerRequest;
import com.example.gateway.web.dto.Endpoint;
import com.example.gateway.web.dto.ExecRequest;
import com.example.gateway.web.dto.ExecResult;
import com.example.gateway.web.dto.PortSpec;
import com.example.gateway.web.dto.StatusView;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 容器生命周期编排：鉴权 -&gt; 校验 -&gt; 配额 -&gt; 选节点 -&gt; 端口分配 -&gt; Docker 调用 -&gt; 审计。
 *
 * <p>多节点：创建时按负载选节点并写 label {@code com.example.gateway.node}；其余操作经
 * {@link ContainerIndex} 路由到容器所在节点。
 */
@Service
public class ContainerService {

    private static final Logger log = LoggerFactory.getLogger(ContainerService.class);

    public static final String ACTION_CREATE = "create";
    public static final String ACTION_GET = "get";
    public static final String ACTION_LIST = "list";
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_RESTART = "restart";
    public static final String ACTION_REMOVE = "remove";
    public static final String ACTION_DESTROY = "destroy";
    public static final String ACTION_EXEC = "exec";
    public static final String ACTION_LOGS = "logs";
    public static final String ACTION_TTL_REAP = "ttl-reap";
    public static final String ACTION_PROXY = "proxy";

    private final DockerOps docker;
    private final NodeRegistry nodes;
    private final ContainerIndex index;
    private final NodePortAllocator portAllocator;
    private final ContainerGuard guard;
    private final ImagePolicy imagePolicy;
    private final AuditLogger audit;
    private final RateLimiter rateLimiter;
    private final GatewayProperties props;
    private final CreateIdempotencyStore idempotency;
    private final WorkspaceManager workspace;
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public ContainerService(DockerOps docker, NodeRegistry nodes, ContainerIndex index,
                            NodePortAllocator portAllocator, ContainerGuard guard, ImagePolicy imagePolicy,
                            AuditLogger audit, RateLimiter rateLimiter, GatewayProperties props,
                            CreateIdempotencyStore idempotency, WorkspaceManager workspace,
                            com.example.gateway.metrics.GatewayMetrics metrics) {
        this.docker = docker;
        this.nodes = nodes;
        this.index = index;
        this.portAllocator = portAllocator;
        this.guard = guard;
        this.imagePolicy = imagePolicy;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.idempotency = idempotency;
        this.workspace = workspace;
        this.metrics = metrics;
    }

    /** 定位到的托管容器（节点 + inspect 结果）。 */
    public record Located(DockerNode node, InspectContainerResponse container) {
    }

    /** 反向代理目标。 */
    public record ProxyTarget(DockerNode node, int hostPort) {
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建容器。若客户端提供 {@code Idempotency-Key} 且开启幂等，则同一 (clientId, key)
     * 在 TTL 内重放会返回既有容器，避免「响应丢失 → 重复创建」。
     */
    public ContainerInfo create(String clientId, String requestId, String sourceIp,
                                CreateContainerRequest req, AdvertiseContext adv, String idempotencyKey) {
        String key = (idempotencyKey == null || idempotencyKey.isBlank()) ? null : idempotencyKey.trim();
        long startedAt = AuditLogger.startNanos();
        Effective eff;
        try {
            eff = resolveEffective(req);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_CREATE, null, req.image(), null,
                    requestParams(req), errorCode(e), startedAt);
            throw e;
        }
        if (key == null || !props.isCreateIdempotencyEnabled()) {
            return doCreate(clientId, requestId, sourceIp, req, adv, key, null, eff);
        }
        if (key.length() > 128) {
            Map<String, Object> tooLong = requestParams(req);
            tooLong.put("idempotencyKey", key);
            audit.error(requestId, clientId, sourceIp, ACTION_CREATE, null, req.image(), null,
                    tooLong, GatewayException.VALIDATION_ERROR, startedAt);
            throw GatewayException.badRequest("Idempotency-Key 过长（最多 128 字符）");
        }
        String digest = CreateRequestDigest.compute(req, eff.memoryBytes(), eff.cpus(),
                eff.pidsLimit(), eff.ttlSeconds());
        synchronized (idempotency.lockFor(clientId, key)) {
            CreateIdempotencyStore.Lookup existing = idempotency.lookupEntry(clientId, key);
            if (existing != null) {
                try {
                    ContainerGuard.Owned owned = guard.requireOwned(existing.containerId(), clientId);
                    if (existing.digest() != null && !existing.digest().equals(digest)) {
                        // 同一 Key 换参：拒绝复用旧容器，避免创建错误请求对应的资源
                        Map<String, Object> conflictParams = baseCreateParams(req, eff);
                        conflictParams.put("idempotent", true);
                        conflictParams.put("idempotentReplay", owned.container().getId());
                        conflictParams.put("idempotencyKey", key);
                        conflictParams.put("conflict", true);
                        audit.record(new AuditLogger.Event(requestId, clientId, sourceIp, ACTION_CREATE,
                                owned.container().getId(), req.image(), null,
                                conflictParams, "error", GatewayException.IDEMPOTENCY_KEY_REUSED, null));
                        throw GatewayException.idempotencyKeyReused(
                                "Idempotency-Key 已用于不同的创建参数，拒绝返回旧容器 "
                                        + shortId(existing.containerId()));
                    }
                    Map<String, Object> replayParams = baseCreateParams(req, eff);
                    replayParams.put("idempotent", true);
                    replayParams.put("idempotentReplay", owned.container().getId());
                    replayParams.put("idempotencyKey", key);
                    audit.record(new AuditLogger.Event(requestId, clientId, sourceIp, ACTION_CREATE,
                            owned.container().getId(), req.image(), null,
                            replayParams, "ok", null, null));
                    return toInfo(owned.node(), owned.container(), adv);
                } catch (GatewayException e) {
                    if (GatewayException.NOT_FOUND.equals(e.getCode())) {
                        // 既有容器已消失（AutoRemove/TTL），作废并重新创建
                        idempotency.forget(clientId, key);
                    } else {
                        audit.error(requestId, clientId, sourceIp, ACTION_CREATE, existing.containerId(), req.image(), null,
                                params("idempotent", true, "idempotentReplay", existing.containerId(),
                                        "idempotencyKey", key), errorCode(e), startedAt);
                        throw e;
                    }
                }
            }
            ContainerInfo info = doCreate(clientId, requestId, sourceIp, req, adv, key, digest, eff);
            idempotency.remember(clientId, key, info.id(), digest);
            return info;
        }
    }

    /** 创建请求的 effective 值（默认已应用），供请求摘要与建容器共用，保证同源。 */
    private record Effective(long memoryBytes, double cpus, long pidsLimit, long ttlSeconds) {
    }

    private Effective resolveEffective(CreateContainerRequest req) {
        long memoryBytes = req.memoryBytes() == null ? props.getDefaultMemoryBytes() : req.memoryBytes();
        if (memoryBytes < props.getMinMemoryBytes()) {
            throw GatewayException.badRequest("memoryBytes 过小，至少 " + props.getMinMemoryBytes());
        }
        if (memoryBytes > props.getMaxMemoryBytes()) {
            throw GatewayException.badRequest("memoryBytes 超过上限 " + props.getMaxMemoryBytes());
        }

        double cpus = req.cpus() == null ? props.getDefaultCpus() : req.cpus();
        if (cpus <= 0 || cpus > props.getMaxCpus()) {
            throw GatewayException.badRequest("cpus 必须在 (0, " + props.getMaxCpus() + "] 范围内");
        }

        long pidsLimit = req.pidsLimit() == null ? props.getDefaultPids() : req.pidsLimit();
        if (pidsLimit < 16 || pidsLimit > props.getMaxPids()) {
            throw GatewayException.badRequest("pidsLimit 必须在 [16, " + props.getMaxPids() + "] 范围内");
        }

        long ttlSeconds = req.ttlSeconds() == null ? props.getDefaultTtlSeconds() : req.ttlSeconds();
        if (ttlSeconds < props.getMinTtlSeconds()) {
            throw GatewayException.badRequest("ttlSeconds 过小，至少 " + props.getMinTtlSeconds());
        }
        if (ttlSeconds > props.getMaxTtlSeconds()) {
            throw GatewayException.badRequest("ttlSeconds 超过上限 " + props.getMaxTtlSeconds());
        }
        return new Effective(memoryBytes, cpus, pidsLimit, ttlSeconds);
    }

    private ContainerInfo doCreate(String clientId, String requestId, String sourceIp,
                                   CreateContainerRequest req, AdvertiseContext adv,
                                   String idempotencyKey, String requestDigest, Effective eff) {
        long startedAt = AuditLogger.startNanos();
        String containerId = null;
        String name = null;
        DockerNode node = null;
        List<Integer> hostPorts = null;
        WorkspaceManager.Workspace workspaceRef = null;
        NodeRegistry.Reservation reservation = null;
        boolean containerAlive = true;
        Map<String, Object> auditParams = baseCreateParams(req, eff);

        try {
        RateLimiter.Result createLimit = rateLimiter.tryAcquire("create:" + clientId, props.getCreateRateLimitPerMinute());
        if (!createLimit.allowed()) {
            throw GatewayException.rateLimited(
                    "创建容器过于频繁，限制 " + props.getCreateRateLimitPerMinute() + " 次/分钟",
                    createLimit.retryAfterSeconds());
        }

        Map<String, String> meta = validateMeta(req);

        List<PortSpec> portSpecs = validatePorts(req.portsOrEmpty());
        requireWhitelisted(req.image());

        long memoryBytes = eff.memoryBytes();
        double cpus = eff.cpus();
        long pidsLimit = eff.pidsLimit();
        long ttlSeconds = eff.ttlSeconds();

        List<Bind> binds = resolveBinds(req);

        // 配额（全局 + 单 owner），忽略不可达节点
        long ownerCount = countManaged(clientId);
        if (ownerCount >= props.getMaxPerOwner()) {
            throw GatewayException.quotaExceeded(
                    "该客户端已有 " + ownerCount + " 个容器，达到上限 " + props.getMaxPerOwner());
        }
        long totalCount = countManaged(null);
        if (totalCount >= props.getMaxContainers()) {
            throw GatewayException.quotaExceeded(
                    "网关托管容器总数已达上限 " + props.getMaxContainers());
        }

        // 选节点并占位 -> 确保镜像 -> 分配端口 -> 准备托管工作区
        reservation = nodes.reserveNode();
        node = reservation.node();
        ensureImageOnNode(node, req.image());

        Instant now = Instant.now();
        hostPorts = portAllocator.allocate(node, portSpecs.size());

        // 端口分配成功后再建工作区目录：此前任何失败都不会留下宿主目录
        workspaceRef = workspace.prepare(node, clientId);
        if (workspaceRef != null) {
            List<Bind> withWorkspace = new ArrayList<>(binds);
            withWorkspace.add(workspace.bind(workspaceRef));
            binds = withWorkspace;
        }

        List<PortBinding> bindings = new ArrayList<>();
        List<ExposedPort> exposed = new ArrayList<>();
        for (int i = 0; i < portSpecs.size(); i++) {
            PortSpec spec = portSpecs.get(i);
            InternetProtocol protocol = "udp".equals(spec.protocolOrDefault())
                    ? InternetProtocol.UDP : InternetProtocol.TCP;
            ExposedPort exposedPort = new ExposedPort(spec.containerPort(), protocol);
            bindings.add(new PortBinding(Ports.Binding.bindPort(hostPorts.get(i)), exposedPort));
            exposed.add(exposedPort);
        }

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(Ownership.MANAGED, "true");
        labels.put(Ownership.OWNER, clientId);
        labels.put(Ownership.NODE, node.id());
        labels.put(Ownership.CREATED_AT, now.toString());
        labels.put(Ownership.TTL_SECONDS, String.valueOf(ttlSeconds));
        labels.put(Ownership.EXPIRES_AT, now.plusSeconds(ttlSeconds).toString());
        labels.put(Ownership.HOST_PORTS, joinPorts(hostPorts));
        meta.forEach((k, v) -> labels.put(Ownership.META_PREFIX + k, v));
        if (workspaceRef != null) {
            labels.put(Ownership.WORKSPACE_KIND, workspaceRef.kind().name().toLowerCase(Locale.ROOT));
            labels.put(Ownership.WORKSPACE_REF, workspaceRef.ref());
            if (!workspaceRef.isVolume()) {
                labels.put(Ownership.WORKSPACE_HOST_DIR, workspaceRef.ref());
            }
        }
        if (idempotencyKey != null) {
            labels.put(Ownership.IDEMPOTENCY_KEY, idempotencyKey);
            if (requestDigest != null) {
                labels.put(Ownership.IDEMPOTENCY_DIGEST, requestDigest);
            }
        }

        name = (req.name() == null || req.name().isBlank())
                ? "gw-" + sanitize(clientId) + "-" + UUID.randomUUID().toString().substring(0, 8)
                : req.name();

        auditParams.put("name", name);
        auditParams.put("node", node.id());
        auditParams.put("ports", portMappings(portSpecs, hostPorts));
        auditParams.put("hostPorts", hostPorts);
        auditParams.put("expiresAt", now.plusSeconds(ttlSeconds).toString());
        if (workspaceRef != null) {
            auditParams.put("workspaceKind", workspaceRef.kind().name().toLowerCase(Locale.ROOT));
            auditParams.put("workspaceRef", workspaceRef.ref());
            if (!workspaceRef.isVolume()) {
                auditParams.put("workspaceHostDir", workspaceRef.ref());
            }
        }
        if (idempotencyKey != null) {
            auditParams.put("idempotencyKey", idempotencyKey);
        }
        auditParams.put("policy", policySnapshot());

        try {
            containerId = docker.create(node, new DockerOps.CreateSpec(
                    req.image(),
                    name,
                    labels,
                    toEnvList(req.env()),
                    req.cmd(),
                    bindings,
                    exposed,
                    binds,
                    memoryBytes,
                    Math.round(cpus * 1_000_000_000d),
                    pidsLimit));

            index.register(containerId, node.id());
            docker.start(node, containerId);
            if (workspaceRef != null && workspaceRef.isVolume()) {
                workspace.ensureVolumeWritable(node, containerId);
            }

            ContainerInfo info;
            try {
                info = toInfo(node, docker.inspect(node, containerId), adv);
            } catch (GatewayException e) {
                if (props.isAutoRemove() && GatewayException.NOT_FOUND.equals(e.getCode())) {
                    // 一次性任务：容器启动后立即退出并被 AutoRemove 删除，返回退化视图
                    index.forget(containerId);
                    workspace.cleanup(node, workspaceRef);
                    workspaceRef = null;
                    containerAlive = false;
                    info = removedInfo(node, containerId, name, req.image(), now, ttlSeconds,
                            hostPorts, portSpecs, adv, meta);
                } else {
                    throw e;
                }
            }

            Map<String, Object> okParams = new LinkedHashMap<>(auditParams);
            okParams.put("containerCreatedAt", info.createdAt());
            okParams.put("expiresAt", info.expiresAt());
            okParams.put("status", info.status());
            okParams.put("containerName", info.name());
            if (containerAlive) {
                reservation.confirm();
            } else {
                reservation.release();
            }
            reservation = null;
            audit.ok(requestId, clientId, sourceIp, ACTION_CREATE, containerId, req.image(), hostPorts,
                    okParams, startedAt);
            return info;
        } catch (RuntimeException e) {
            rollback(node, containerId, hostPorts);
            throw e;
        }
        } catch (RuntimeException e) {
            if (reservation != null) {
                reservation.release();
            }
            if (workspaceRef != null) {
                workspace.cleanup(node, workspaceRef);
            }
            auditParams.put("rolledBack", containerId != null);
            String failureKind = failureKind(ACTION_CREATE, e);
            if (failureKind != null) {
                auditParams.put("failureKind", failureKind);
            }
            audit.error(requestId, clientId, sourceIp, ACTION_CREATE, containerId, req.image(), hostPorts,
                    auditParams, errorCode(e), startedAt);
            throw e;
        }
    }

    // ------------------------------------------------------------------ 查询

    public ContainerInfo get(String clientId, String requestId, String sourceIp, String id,
                             AdvertiseContext adv) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            ContainerInfo info = toInfo(owned.node(), owned.container(), adv);
            audit.ok(requestId, clientId, sourceIp, ACTION_GET, owned.container().getId(), image, null,
                    params("status", info.status(), "node", info.nodeId()), startedAt);
            return info;
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_GET, id, image, null, null, errorCode(e), startedAt);
            throw e;
        }
    }

    public List<ContainerInfo> list(String clientId, String requestId, String sourceIp,
                                    AdvertiseContext adv) {
        long startedAt = AuditLogger.startNanos();
        try {
            List<ContainerInfo> result = new ArrayList<>();
            for (DockerNode node : nodes.healthyNodes()) {
                List<Container> containers;
                try {
                    containers = docker.listManaged(node, clientId);
                } catch (RuntimeException e) {
                    log.warn("列举节点 {} 容器失败: {}", node.id(), e.getMessage());
                    continue;
                }
                for (Container c : containers) {
                    docker.tryInspect(node, c.getId())
                            .ifPresent(full -> result.add(toInfo(node, full, adv, c.getImage())));
                }
            }
            audit.ok(requestId, clientId, sourceIp, ACTION_LIST, null, null, null,
                    params("count", result.size()), startedAt);
            return result;
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_LIST, null, null, null, null, errorCode(e), startedAt);
            throw e;
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public StatusView start(String clientId, String requestId, String sourceIp, String id) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            String before = statusOf(owned.container());
            boolean wasRunning = docker.isRunning(owned.container());
            docker.start(owned.node(), id);
            if (!wasRunning) {
                nodes.onStarted(owned.node());
            }
            String status = docker.statusOf(owned.node(), id);
            audit.ok(requestId, clientId, sourceIp, ACTION_START, id, image, null,
                    params("node", owned.node().id(), "statusBefore", before, "status", status), startedAt);
            return new StatusView(id, status);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_START, id, image, null, null, errorCode(e), startedAt);
            throw e;
        }
    }

    public StatusView stop(String clientId, String requestId, String sourceIp, String id, int timeoutSeconds) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> p = params("timeoutSeconds", timeoutSeconds);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            p.put("node", owned.node().id());
            p.put("statusBefore", statusOf(owned.container()));
            boolean wasRunning = docker.isRunning(owned.container());
            docker.stop(owned.node(), id, timeoutSeconds);
            if (wasRunning) {
                nodes.onStopped(owned.node());
            }
            String status = docker.statusOf(owned.node(), id);
            p.put("status", status);
            audit.ok(requestId, clientId, sourceIp, ACTION_STOP, id, image, null, p, startedAt);
            return new StatusView(id, status);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_STOP, id, image, null, p, errorCode(e), startedAt);
            throw e;
        }
    }

    public StatusView restart(String clientId, String requestId, String sourceIp, String id, int timeoutSeconds) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> p = params("timeoutSeconds", timeoutSeconds);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            p.put("node", owned.node().id());
            p.put("statusBefore", statusOf(owned.container()));
            boolean wasRunning = docker.isRunning(owned.container());
            docker.restart(owned.node(), id, timeoutSeconds);
            if (!wasRunning) {
                nodes.onStarted(owned.node());
            }
            String status = docker.statusOf(owned.node(), id);
            p.put("status", status);
            audit.ok(requestId, clientId, sourceIp, ACTION_RESTART, id, image, null, p, startedAt);
            return new StatusView(id, status);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_RESTART, id, image, null, p, errorCode(e), startedAt);
            throw e;
        }
    }

    /** 删除：软删，要求容器已停止，保留镜像。 */
    public void remove(String clientId, String requestId, String sourceIp, String id, boolean removeVolumes) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> p = params("removeVolumes", removeVolumes);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            p.putAll(containerParams(owned.container(), hostPortsOf(owned.container())));
            if (docker.isRunning(owned.container())) {
                throw GatewayException.conflict("容器正在运行，请先 stop，或使用 POST /{id}/destroy 强制销毁");
            }
            List<Integer> hostPorts = hostPortsOf(owned.container());
            docker.remove(owned.node(), id, false, removeVolumes);
            portAllocator.release(owned.node(), hostPorts);
            index.forget(id);
            workspace.cleanup(owned.node(), WorkspaceManager.fromLabels(ContainerGuard.labelsOf(owned.container())));
            audit.ok(requestId, clientId, sourceIp, ACTION_REMOVE, id, image, hostPorts, p, startedAt);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_REMOVE, id, image, null, p, errorCode(e), startedAt);
            throw e;
        }
    }

    /** 销毁：强停 + 强制删除 + 删除卷 + 回收端口。容器已被 AutoRemove 删除时幂等成功。 */
    public void destroy(String clientId, String requestId, String sourceIp, String id) {
        long startedAt = AuditLogger.startNanos();
        DockerNode node = null;
        InspectContainerResponse container;
        try {
            node = index.resolve(id);
            if (!node.healthy()) {
                throw GatewayException.nodeUnavailable(
                        "节点 " + node.id() + " 当前不可用，请稍后重试");
            }
            container = docker.inspect(node, id);
        } catch (RuntimeException e) {
            if (e instanceof GatewayException ge && GatewayException.NOT_FOUND.equals(ge.getCode())) {
                index.forget(id);
                audit.ok(requestId, clientId, sourceIp, ACTION_DESTROY, id, null, null,
                        params("node", node == null ? null : node.id(), "alreadyGone", true), startedAt);
                return; // 已被 AutoRemove 删除：视为销毁成功
            }
            audit.error(requestId, clientId, sourceIp, ACTION_DESTROY, id, null, null,
                    params("node", node == null ? null : node.id()), errorCode(e), startedAt);
            throw e;
        }
        String image = imageOf(container);
        List<Integer> hostPorts = hostPortsOf(container);
        Map<String, Object> p = containerParams(container, hostPorts);
        p.put("force", true);
        p.put("removeVolumes", true);
        boolean delegated = false;
        try {
            if (!ContainerGuard.isManaged(container)) {
                throw GatewayException.ownershipDenied("容器 " + shortId(id) + " 不是本网关托管的容器，拒绝操作");
            }
            String owner = ContainerGuard.labelsOf(container).get(Ownership.OWNER);
            if (owner == null || !owner.equals(clientId)) {
                throw GatewayException.ownershipDenied("容器 " + shortId(id) + " 归属其他客户端，拒绝操作");
            }
            delegated = true;
            destroyInternal(node, container, clientId, requestId, sourceIp, ACTION_DESTROY, p, startedAt);
        } catch (RuntimeException e) {
            if (!delegated) {
                audit.error(requestId, clientId, sourceIp, ACTION_DESTROY, id, image, hostPorts, p,
                        errorCode(e), startedAt);
            }
            throw e;
        }
    }

    /** 供 TTL 回收任务使用：调用方已确认是托管容器，这里只做事，不校验 owner。 */
    public void reap(DockerNode node, InspectContainerResponse container, String requestId) {
        long startedAt = AuditLogger.startNanos();
        String id = container.getId();
        List<Integer> hostPorts = hostPortsOf(container);
        Map<String, Object> p = containerParams(container, hostPorts);
        p.put("force", true);
        p.put("removeVolumes", true);
        p.put("reaped", true);
        destroyInternal(node, container, "ttl-reaper", requestId, "127.0.0.1", ACTION_TTL_REAP, p, startedAt);
    }

    private void destroyInternal(DockerNode node, InspectContainerResponse container, String clientId,
                                 String requestId, String sourceIp, String action,
                                 Map<String, Object> auditParams, long startedAt) {
        String id = container.getId();
        List<Integer> hostPorts = hostPortsOf(container);
        String image = imageOf(container);
        boolean wasRunning = docker.isRunning(container);
        try {
            // destroy 是强制销毁：直接 force remove（SIGKILL），不再先做可能白等 10s 的优雅 stop。
            docker.remove(node, id, true, true);
            nodes.onDestroyed(node, wasRunning);
            portAllocator.release(node, hostPorts);
            index.forget(id);
            workspace.cleanup(node, WorkspaceManager.fromLabels(ContainerGuard.labelsOf(container)));
            audit.ok(requestId, clientId, sourceIp, action, id, image, hostPorts, auditParams, startedAt);
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, action, id, image, hostPorts, auditParams,
                    errorCode(e), startedAt);
            throw e;
        }
    }

    // ------------------------------------------------------------------ exec / logs

    public ExecResult exec(String clientId, String requestId, String sourceIp, String id, ExecRequest req) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        String nodeId = null;
        Map<String, Object> p = params("cmd", req.cmd(), "env", maskedEnv(req.env()),
                "timeoutSeconds", req.timeoutSeconds());
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            nodeId = owned.node().id();
            int timeout = validateExecTimeout(req.timeoutSeconds());
            p.put("timeoutSeconds", timeout);
            p.put("node", owned.node().id());
            ExecResult result = docker.exec(owned.node(), id, req.cmd(), toEnvList(req.env()), timeout,
                    props.getExecMaxOutputBytes());
            p.put("exitCode", result.exitCode());
            p.put("stdoutBytes", utf8Bytes(result.stdout()));
            p.put("stderrBytes", utf8Bytes(result.stderr()));
            p.put("truncated", result.truncated());
            metrics.exec("sync", nodeId, "ok", false, result.truncated(),
                    result.exitCode(), result.durationMs());
            audit.ok(requestId, clientId, sourceIp, ACTION_EXEC, id, image, null, p, startedAt);
            return result;
        } catch (RuntimeException e) {
            String failureKind = failureKind(ACTION_EXEC, e);
            if (failureKind != null) {
                p.put("failureKind", failureKind);
            }
            boolean timeout = e instanceof GatewayException ge && ge.getMessage() != null
                    && ge.getMessage().contains("命令执行超时");
            metrics.exec("sync", nodeId, "error", timeout, false, null,
                    AuditLogger.elapsedMs(startedAt));
            audit.error(requestId, clientId, sourceIp, ACTION_EXEC, id, image, null, p,
                    auditErrorCode(ACTION_EXEC, e), startedAt);
            throw e;
        }
    }

    /** 供 SSE 流式 exec 使用：校验归属并解析超时。 */
    public ContainerGuard.Owned requireForExec(String clientId, String id) {
        return guard.requireOwned(id, clientId);
    }

    public int validateExecTimeout(Integer requestedSeconds) {
        int timeout = requestedSeconds == null
                ? props.getExecDefaultTimeoutSeconds() : requestedSeconds;
        if (timeout <= 0 || timeout > props.getExecMaxTimeoutSeconds()) {
            throw GatewayException.badRequest(
                    "timeoutSeconds 必须在 [1, " + props.getExecMaxTimeoutSeconds() + "] 范围内");
        }
        return timeout;
    }

    public String logs(String clientId, String requestId, String sourceIp, String id,
                       int tail, boolean timestamps) {
        long startedAt = AuditLogger.startNanos();
        String image = null;
        Map<String, Object> p = params("tail", tail, "timestamps", timestamps);
        try {
            ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
            image = imageOf(owned.container());
            p.put("node", owned.node().id());
            String text = docker.logs(owned.node(), id, tail, timestamps, props.getExecMaxOutputBytes(),
                    props.getExecDefaultTimeoutSeconds());
            p.put("bytes", utf8Bytes(text));
            audit.ok(requestId, clientId, sourceIp, ACTION_LOGS, id, image, null, p, startedAt);
            return text;
        } catch (RuntimeException e) {
            audit.error(requestId, clientId, sourceIp, ACTION_LOGS, id, image, null, p, errorCode(e), startedAt);
            throw e;
        }
    }

    /** 网关托管的容器总数（含已停止），用于健康检查与配额统计。 */
    public long managedCount() {
        return countManaged(null);
    }

    /** 托管容器列表（含节点），供 TTL 回收使用。 */
    public List<Located> managedContainers() {
        List<Located> result = new ArrayList<>();
        for (DockerNode node : nodes.healthyNodes()) {
            List<Container> containers;
            try {
                containers = docker.listManaged(node, null);
            } catch (RuntimeException e) {
                log.warn("列举节点 {} 容器失败: {}", node.id(), e.getMessage());
                continue;
            }
            for (Container c : containers) {
                docker.tryInspect(node, c.getId())
                        .ifPresent(full -> result.add(new Located(node, full)));
            }
        }
        return result;
    }

    /** 各节点运行中的托管容器数（健康检查展示用）。 */
    public Map<String, Integer> runningCountsByNode() {
        return nodes.runningManagedCounts();
    }

    // ------------------------------------------------------------------ 访问代理

    /**
     * 校验所有权并解析出容器端口对应的「节点 + 宿主端口」，供反向代理转发。
     * 代理调用量大，这里不写审计日志，避免刷爆 audit.log。
     */
    public ProxyTarget resolveProxyTarget(String clientId, String id, int containerPort) {
        ContainerGuard.Owned owned = guard.requireOwned(id, clientId);
        int hostPort = DockerOps.findHostPort(owned.container(), containerPort, "tcp")
                .orElseThrow(() -> GatewayException.notFound(
                        "容器 " + id + " 未映射 TCP 端口 " + containerPort));
        return new ProxyTarget(owned.node(), hostPort);
    }

    // ------------------------------------------------------------------ 组装视图

    public ContainerInfo toInfo(DockerNode node, InspectContainerResponse c, AdvertiseContext adv) {
        String image = c.getConfig() == null ? null : c.getConfig().getImage();
        return toInfo(node, c, adv, image);
    }

    private ContainerInfo toInfo(DockerNode node, InspectContainerResponse c, AdvertiseContext adv, String image) {
        Map<String, String> labels = ContainerGuard.labelsOf(c);
        return new ContainerInfo(
                c.getId(),
                c.getName() == null ? null : c.getName().replaceFirst("^/", ""),
                c.getState() == null ? "unknown" : c.getState().getStatus(),
                image,
                labels.get(Ownership.CREATED_AT),
                labels.get(Ownership.EXPIRES_AT),
                parseLong(labels.get(Ownership.TTL_SECONDS)),
                c.getState() == null || c.getState().getExitCodeLong() == null
                        ? null : c.getState().getExitCodeLong().intValue(),
                node.id(),
                endpointsOf(node, c, adv),
                metaOf(labels));
    }

    public List<Endpoint> endpointsOf(DockerNode node, InspectContainerResponse c, AdvertiseContext adv) {
        List<Endpoint> endpoints = new ArrayList<>();
        Map<ExposedPort, Ports.Binding[]> bindings = DockerOps.bindingsOf(c);
        for (Map.Entry<ExposedPort, Ports.Binding[]> e : bindings.entrySet()) {
            ExposedPort exposed = e.getKey();
            Ports.Binding[] values = e.getValue();
            if (values == null || values.length == 0 || values[0].getHostPortSpec() == null) {
                continue;
            }
            int hostPort;
            try {
                hostPort = Integer.parseInt(values[0].getHostPortSpec());
            } catch (NumberFormatException ex) {
                continue;
            }
            String directHost = node.local() ? props.getDirectHost() : node.proxyHost();
            String directUrl = (directHost == null || directHost.isBlank())
                    ? null
                    : "http://" + directHost + ":" + hostPort + "/";
            String proxyUrl = props.isProxyEnabled()
                    ? adv.baseUrl() + "/api/v1/containers/" + c.getId() + "/proxy/" + exposed.getPort() + "/"
                    : null;
            endpoints.add(new Endpoint(
                    exposed.getPort(),
                    exposed.getProtocol().name().toLowerCase(Locale.ROOT),
                    hostPort,
                    directUrl,
                    proxyUrl,
                    node.id()));
        }
        endpoints.sort(java.util.Comparator.comparingInt(Endpoint::containerPort));
        return endpoints;
    }

    /** 一次性任务容器被 AutoRemove 删除后的退化视图（无 inspect 结果时使用）。 */
    private ContainerInfo removedInfo(DockerNode node, String id, String name, String image,
                                      Instant now, long ttlSeconds, List<Integer> hostPorts,
                                      List<PortSpec> portSpecs, AdvertiseContext adv,
                                      Map<String, String> meta) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (int i = 0; i < portSpecs.size() && i < hostPorts.size(); i++) {
            PortSpec spec = portSpecs.get(i);
            int hostPort = hostPorts.get(i);
            String directHost = node.local() ? props.getDirectHost() : node.proxyHost();
            String directUrl = (directHost == null || directHost.isBlank())
                    ? null : "http://" + directHost + ":" + hostPort + "/";
            String proxyUrl = props.isProxyEnabled()
                    ? adv.baseUrl() + "/api/v1/containers/" + id + "/proxy/" + spec.containerPort() + "/"
                    : null;
            endpoints.add(new Endpoint(spec.containerPort(), spec.protocolOrDefault(), hostPort,
                    directUrl, proxyUrl, node.id()));
        }
        return new ContainerInfo(id, name, "removed", image, now.toString(),
                now.plusSeconds(ttlSeconds).toString(), ttlSeconds, null, node.id(), endpoints, meta);
    }

    /** 从容器 label 还原业务上下文 meta。 */
    private static Map<String, String> metaOf(Map<String, String> labels) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (e.getKey().startsWith(Ownership.META_PREFIX)) {
                meta.put(e.getKey().substring(Ownership.META_PREFIX.length()), e.getValue());
            }
        }
        return meta;
    }

    // ------------------------------------------------------------------ 校验辅助

    /** 校验业务上下文 meta，按需强制 tenantId/userId。 */
    private Map<String, String> validateMeta(CreateContainerRequest req) {
        Map<String, String> raw = req.metaOrEmpty();
        Map<String, String> meta = new LinkedHashMap<>();
        if (raw.size() > 16) {
            throw GatewayException.badRequest("meta 条目过多，最多 16 个");
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String k = e.getKey() == null ? "" : e.getKey().trim();
            String v = e.getValue() == null ? "" : e.getValue();
            if (!k.matches("[a-zA-Z0-9_.-]{1,32}")) {
                throw GatewayException.badRequest("meta key 非法（仅字母数字与 _.-，≤32）: " + e.getKey());
            }
            if (v.length() > 256) {
                throw GatewayException.badRequest("meta value 过长（≤256）: " + k);
            }
            meta.put(k, v);
        }
        if (props.isRequireBusinessContext()
                && (!meta.containsKey("tenantId") || !meta.containsKey("userId"))) {
            throw GatewayException.badRequest(
                    "已开启 require-business-context，meta 必须包含 tenantId 与 userId");
        }
        return meta;
    }

    private List<PortSpec> validatePorts(List<PortSpec> specs) {
        if (specs.size() > props.getMaxPortsPerContainer()) {
            throw GatewayException.badRequest(
                    "端口映射过多，最多 " + props.getMaxPortsPerContainer() + " 个");
        }
        Set<String> seen = new HashSet<>();
        for (PortSpec spec : specs) {
            try {
                spec.validate();
            } catch (IllegalArgumentException e) {
                throw GatewayException.badRequest(e.getMessage());
            }
            if (!seen.add(spec.protocolOrDefault() + "/" + spec.containerPort())) {
                throw GatewayException.badRequest("容器端口重复声明: " + spec.containerPort());
            }
        }
        return specs;
    }

    private List<Bind> resolveBinds(CreateContainerRequest req) {
        if (req.mounts() == null || req.mounts().isEmpty()) {
            return List.of();
        }
        if (!props.isBindMountsEnabled()) {
            throw GatewayException.badRequest(
                    "本网关未开启 bind mount（GATEWAY_BIND_MOUNTS_ENABLED=false）");
        }
        return DockerOps.parseBinds(req.mounts());
    }

    private void requireWhitelisted(String image) {
        if (imagePolicy.isWhitelistEmpty()) {
            throw GatewayException.imageNotAllowed("未配置镜像白名单（GATEWAY_IMAGE_WHITELIST），拒绝创建任何容器");
        }
        if (!imagePolicy.isAllowed(image)) {
            throw GatewayException.imageNotAllowed("镜像不在白名单内: " + image);
        }
    }

    private void ensureImageOnNode(DockerNode node, String image) {
        if (docker.imageExists(node, image)) {
            return;
        }
        if (!props.isAllowPull()) {
            throw GatewayException.imageNotAllowed(
                    "镜像在节点 " + node.id() + " 上不存在且未开启联网拉取: " + image + "（请先在节点上 docker pull）");
        }
        docker.pullImage(node, image);
    }

    private long countManaged(String owner) {
        long total = 0;
        for (DockerNode node : nodes.healthyNodes()) {
            try {
                total += docker.listManaged(node, owner).size();
            } catch (RuntimeException e) {
                log.warn("统计节点 {} 容器数失败: {}", node.id(), e.getMessage());
            }
        }
        return total;
    }

    private void rollback(DockerNode node, String containerId, List<Integer> hostPorts) {
        if (containerId != null) {
            try {
                docker.remove(node, containerId, true, true);
            } catch (RuntimeException e) {
                log.warn("创建失败后回滚容器 {} 失败: {}", containerId, e.getMessage());
            }
            index.forget(containerId);
        }
        portAllocator.release(node, hostPorts);
    }

    private static List<String> toEnvList(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(env.size());
        env.forEach((k, v) -> result.add(k + "=" + (v == null ? "" : v)));
        return result;
    }

    // ------------------------------------------------------------------ 审计辅助

    /** 客户端原始请求参数（未应用默认值），用于参数校验失败时的审计。 */
    private static Map<String, Object> requestParams(CreateContainerRequest req) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", req.name());
        p.put("image", req.image());
        p.put("cmd", req.cmd() == null ? List.of() : req.cmd());
        p.put("env", maskedEnv(req.env()));
        p.put("ports", req.portsOrEmpty());
        p.put("mounts", req.mounts() == null ? List.of() : req.mounts());
        p.put("meta", req.meta() == null ? Map.of() : req.meta());
        p.put("memoryBytes", req.memoryBytes());
        p.put("cpus", req.cpus());
        p.put("pidsLimit", req.pidsLimit());
        p.put("ttlSeconds", req.ttlSeconds());
        return p;
    }

    /** 审计用的创建请求快照（服务端解析出的 name/node/端口另行补充）。 */
    private Map<String, Object> baseCreateParams(CreateContainerRequest req, Effective eff) {
        Map<String, Object> p = requestParams(req);
        p.put("memoryBytes", eff.memoryBytes());
        p.put("cpus", eff.cpus());
        p.put("pidsLimit", eff.pidsLimit());
        p.put("ttlSeconds", eff.ttlSeconds());
        return p;
    }

    /** 容器端口 -> 宿主端口映射，便于事后核对实际发布情况。 */
    private static List<Map<String, Object>> portMappings(List<PortSpec> specs, List<Integer> hostPorts) {
        List<Map<String, Object>> result = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("containerPort", specs.get(i).containerPort());
            m.put("protocol", specs.get(i).protocolOrDefault());
            if (hostPorts != null && i < hostPorts.size()) {
                m.put("hostPort", hostPorts.get(i));
            }
            result.add(m);
        }
        return result;
    }

    /** env 值可能含密钥：键名命中敏感词时打码，其余原样记录，便于排查。 */
    private static Map<String, String> maskedEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        Map<String, String> masked = new LinkedHashMap<>();
        env.forEach((k, v) -> masked.put(k == null ? "null" : k, isSensitiveKey(k) ? "***" : v));
        return masked;
    }

    private static boolean isSensitiveKey(String key) {
        String upper = key == null ? "" : key.toUpperCase(Locale.ROOT);
        return upper.contains("PASS") || upper.contains("SECRET") || upper.contains("TOKEN")
                || upper.contains("KEY") || upper.contains("CREDENTIAL") || upper.contains("AUTH");
    }

    /** 创建时实际生效的全局隔离策略，保证审计记录自包含。 */
    private Map<String, Object> policySnapshot() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("readonlyRootfs", props.isReadonlyRootfs());
        p.put("runAsUser", props.getRunAsUser());
        p.put("networkMode", props.getNetworkMode());
        p.put("autoRemove", props.isAutoRemove());
        p.put("capDrop", props.getCapDrop());
        p.put("capAdd", props.getCapAdd());
        p.put("securityOpts", props.getSecurityOpts());
        p.put("tmpfs", props.getTmpfs());
        return p;
    }

    /** 已有容器的完整快照：镜像、创建/到期时间、存活时长、销毁前状态与端口。 */
    private static Map<String, Object> containerParams(InspectContainerResponse c, List<Integer> hostPorts) {
        Map<String, String> labels = ContainerGuard.labelsOf(c);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("image", imageOf(c));
        p.put("node", labels.get(Ownership.NODE));
        p.put("containerCreatedAt", labels.get(Ownership.CREATED_AT));
        p.put("expiresAt", labels.get(Ownership.EXPIRES_AT));
        p.put("ttlSeconds", parseLong(labels.get(Ownership.TTL_SECONDS)));
        p.put("lifetimeSeconds", lifetimeSeconds(labels.get(Ownership.CREATED_AT)));
        p.put("stateBefore", statusOf(c));
        if (c.getState() != null && c.getState().getExitCodeLong() != null) {
            p.put("exitCodeBefore", c.getState().getExitCodeLong().intValue());
        }
        p.put("hostPorts", hostPorts);
        return p;
    }

    private static Long lifetimeSeconds(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        try {
            return Math.max(0L, Duration.between(Instant.parse(createdAt), Instant.now()).getSeconds());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String imageOf(InspectContainerResponse c) {
        return c.getConfig() == null ? null : c.getConfig().getImage();
    }

    private static String statusOf(InspectContainerResponse c) {
        return c.getState() == null ? null : c.getState().getStatus();
    }

    private static int utf8Bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String errorCode(RuntimeException e) {
        if (e instanceof GatewayException ge) {
            return ge.getCode();
        }
        if (DockerOps.isTransportFailure(e)) {
            return GatewayException.NODE_UNAVAILABLE;
        }
        return "INTERNAL";
    }

    /**
     * 失败归类，仅写入审计参数，不改变对外错误码。
     *
     * <p>取值：exec-timeout（exec 读超时）、backend-timeout（其他动作读超时）、
     * capacity（节点并发达到上限）、node-unreachable（节点不可达）。
     * 压测报表据此把“超时失败”和“容量失败”分开。
     */
    static String auditErrorCode(String action, RuntimeException e) {
        if (ACTION_EXEC.equals(action) && e instanceof GatewayException.BackendTimeoutException) {
            // 读超时在审计里单独记为 EXEC_TIMEOUT（对外仍是 503 + NODE_UNAVAILABLE）。
            return "EXEC_TIMEOUT";
        }
        return errorCode(e);
    }

    static String failureKind(String action, RuntimeException e) {
        if (e instanceof GatewayException.BackendTimeoutException) {
            return ACTION_EXEC.equals(action) ? "exec-timeout" : "backend-timeout";
        }
        if (e instanceof GatewayException ge && GatewayException.NODE_UNAVAILABLE.equals(ge.getCode())) {
            String message = ge.getMessage() == null ? "" : ge.getMessage();
            return message.contains("并发上限") ? "capacity" : "node-unreachable";
        }
        if (DockerOps.isTransportFailure(e)) {
            return "node-unreachable";
        }
        return null;
    }

    private static List<Integer> hostPortsOf(InspectContainerResponse c) {
        String raw = ContainerGuard.labelsOf(c).get(Ownership.HOST_PORTS);
        List<Integer> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            try {
                result.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 忽略脏数据
            }
        }
        return result;
    }

    private static String joinPorts(List<Integer> ports) {
        StringBuilder sb = new StringBuilder();
        for (Integer p : ports) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String sanitize(String clientId) {
        String s = clientId.replaceAll("[^a-zA-Z0-9_.-]", "-").toLowerCase(Locale.ROOT);
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
