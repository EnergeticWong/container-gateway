package com.example.gateway.docker;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.web.dto.ExecResult;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.LogContainerResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 所有 docker-java 调用的唯一出口，<b>按节点</b>执行。其余代码不直接接触 DockerClient，
 * 便于统一做异常翻译与安全约束。
 *
 * <p>HostConfig 的安全项由 {@link #create} 依据 {@link GatewayProperties} 策略构造，调用方无法覆盖；
 * 安全不变量（privileged=false、禁 host ns、禁挂 docker.sock）永远锁定。
 */
@Component
public class DockerOps {

    private static final Logger log = LoggerFactory.getLogger(DockerOps.class);

    /**
     * 额外丢弃的危险 capability（当策略未显式配置 capDrop 时的默认集）。
     *
     * <p>不直接 capDrop=ALL：Docker 默认能力集（CHOWN/SETUID/SETGID/NET_BIND_SERVICE…）
     * 是绝大多数镜像正常工作的必要前提。这里在 Docker 默认集之上再丢危险项。
     */
    private static final List<Capability> DANGEROUS_CAPABILITIES = List.of(
            Capability.AUDIT_CONTROL,
            Capability.AUDIT_READ,
            Capability.BLOCK_SUSPEND,
            Capability.BPF,
            Capability.CHECKPOINT_RESTORE,
            Capability.DAC_READ_SEARCH,
            Capability.IPC_LOCK,
            Capability.IPC_OWNER,
            Capability.LEASE,
            Capability.LINUX_IMMUTABLE,
            Capability.MAC_ADMIN,
            Capability.MAC_OVERRIDE,
            Capability.NET_ADMIN,
            Capability.NET_BROADCAST,
            Capability.NET_RAW,
            Capability.PERFMON,
            Capability.SYSLOG,
            Capability.SYS_ADMIN,
            Capability.SYS_BOOT,
            Capability.SYS_MODULE,
            Capability.SYS_NICE,
            Capability.SYS_PACCT,
            Capability.SYS_PTRACE,
            Capability.SYS_RAWIO,
            Capability.SYS_RESOURCE,
            Capability.SYS_TIME,
            Capability.SYS_TTY_CONFIG,
            Capability.WAKE_ALARM);

    /** 绝对禁止通过 capAdd 重新加回的 capability（含默认危险集 + ALL）。 */
    private static final Set<String> FORBIDDEN_CAP_ADD = Set.of(
            "ALL", "SYS_ADMIN", "SYS_PTRACE", "SYS_MODULE", "SYS_RAWIO", "SYS_BOOT",
            "SYS_RESOURCE", "SYS_TIME", "SYS_TTY_CONFIG", "SYSLOG", "SYS_NICE", "SYS_PACCT",
            "MAC_ADMIN", "MAC_OVERRIDE", "NET_ADMIN", "NET_RAW", "NET_BROADCAST",
            "DAC_READ_SEARCH", "IPC_OWNER", "IPC_LOCK", "BPF", "PERFMON",
            "AUDIT_CONTROL", "AUDIT_READ", "BLOCK_SUSPEND", "CHECKPOINT_RESTORE",
            "LEASE", "LINUX_IMMUTABLE", "WAKE_ALARM");

    /** 永远禁止 bind mount 的宿主路径前缀（即使 bind-mounts 打开）。 */
    private static final List<String> FORBIDDEN_MOUNT_PREFIXES = List.of(
            "/var/run/docker.sock", "/run/docker.sock", "/proc", "/sys", "/dev", "/boot",
            "/etc", "/root", "/var/lib/docker");

    private final GatewayProperties props;

    public DockerOps(GatewayProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建容器所需的参数。HostConfig 的安全项由 {@link #create} 固定写入，调用方无法覆盖。
     */
    public record CreateSpec(
            String image,
            String name,
            Map<String, String> labels,
            List<String> env,
            List<String> cmd,
            List<PortBinding> portBindings,
            List<ExposedPort> exposedPorts,
            List<Bind> binds,
            long memoryBytes,
            long nanoCpus,
            long pidsLimit) {
    }

    public String create(DockerNode node, CreateSpec spec) {
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(spec.portBindings())
                // memorySwap = memory 表示禁用 swap，防止用 swap 绕过内存上限（固定不变量）
                .withMemory(spec.memoryBytes())
                .withMemorySwap(spec.memoryBytes())
                .withNanoCPUs(spec.nanoCpus())
                .withPidsLimit(spec.pidsLimit())
                .withNetworkMode(networkMode())
                .withAutoRemove(props.isAutoRemove())
                .withPrivileged(false)
                .withReadonlyRootfs(props.isReadonlyRootfs())
                .withCapDrop(resolveCapDrop().toArray(new Capability[0]))
                .withSecurityOpts(resolveSecurityOpts());

        List<Capability> capAdd = resolveCapAdd();
        if (!capAdd.isEmpty()) {
            hostConfig.withCapAdd(capAdd.toArray(new Capability[0]));
        }
        Map<String, String> tmpfs = parseTmpfs(props.getTmpfs());
        if (!tmpfs.isEmpty()) {
            hostConfig.withTmpFs(tmpfs);
        }
        if (spec.binds() != null && !spec.binds().isEmpty()) {
            hostConfig.withBinds(spec.binds());
        }

        try {
            var cmd = node.client().createContainerCmd(spec.image())
                    .withLabels(spec.labels())
                    .withHostConfig(hostConfig);

            if (spec.name() != null && !spec.name().isBlank()) {
                cmd.withName(spec.name());
            }
            // 加固：按配置指定运行用户（留空则用镜像默认用户）
            String runAsUser = props.getRunAsUser() == null ? "" : props.getRunAsUser().trim();
            if (!runAsUser.isEmpty()) {
                cmd.withUser(runAsUser);
            }
            if (spec.env() != null && !spec.env().isEmpty()) {
                cmd.withEnv(spec.env());
            }
            if (spec.cmd() != null && !spec.cmd().isEmpty()) {
                cmd.withCmd(spec.cmd());
            }
            if (spec.exposedPorts() != null && !spec.exposedPorts().isEmpty()) {
                cmd.withExposedPorts(spec.exposedPorts());
            }

            return cmd.exec().getId();
        } catch (DockerException e) {
            throw translate("创建容器失败", e);
        }
    }

    // ------------------------------------------------------------------ 生命周期

    public void start(DockerNode node, String id) {
        try {
            node.client().startContainerCmd(id).exec();
        } catch (NotModifiedException e) {
            log.debug("容器 {} 已在运行", id);
        } catch (NotFoundException e) {
            throw GatewayException.conflict("容器已不存在（可能已自动删除）: " + shortId(id));
        } catch (DockerException e) {
            throw translate("启动容器失败", e);
        }
    }

    public void stop(DockerNode node, String id, int timeoutSeconds) {
        try {
            node.client().stopContainerCmd(id).withTimeout(timeoutSeconds).exec();
        } catch (NotModifiedException e) {
            log.debug("容器 {} 已处于停止状态", id);
        } catch (NotFoundException e) {
            throw GatewayException.conflict("容器已不存在（可能已自动删除）: " + shortId(id));
        } catch (DockerException e) {
            throw translate("停止容器失败", e);
        }
    }

    public void restart(DockerNode node, String id, int timeoutSeconds) {
        try {
            node.client().restartContainerCmd(id).withTimeout(timeoutSeconds).exec();
        } catch (NotFoundException e) {
            throw GatewayException.conflict("容器已不存在（可能已自动删除）: " + shortId(id));
        } catch (DockerException e) {
            throw translate("重启容器失败", e);
        }
    }

    /**
     * 删除容器。force=false 时对运行中的容器 Docker 会返回 409。
     *
     * @return true=删除已生效；false=容器本就不存在（幂等）
     */
    public boolean remove(DockerNode node, String id, boolean force, boolean removeVolumes) {
        try {
            node.client().removeContainerCmd(id)
                    .withForce(force)
                    .withRemoveVolumes(removeVolumes)
                    .exec();
            return true;
        } catch (NotFoundException e) {
            // AutoRemove=true 时容器退出即消失，删除视为幂等成功
            return false;
        } catch (ConflictException e) {
            // AutoRemove 竞态：我们 stop 之后 Docker 已开始自动移除，显式 remove 会撞 409。
            // 容器实际已在消失，视为删除成功。
            String detail = e.getMessage() == null ? "" : e.getMessage();
            if (detail.contains("already in progress") || detail.contains("removal of container")) {
                log.debug("容器 {} 已在移除中（AutoRemove 竞态），视为删除成功", shortId(id));
                return true;
            }
            throw translate("删除容器失败", e);
        } catch (DockerException e) {
            throw translate("删除容器失败", e);
        }
    }

    // ------------------------------------------------------------------ 查询

    public InspectContainerResponse inspect(DockerNode node, String id) {
        try {
            return node.client().inspectContainerCmd(id).exec();
        } catch (NotFoundException e) {
            throw GatewayException.notFound("容器不存在: " + id);
        } catch (GatewayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate("查询容器失败", e);
        }
    }

    /** 只在本节点查找；未找到或节点不可达都返回 empty（不抛 503），供跨节点扇出使用。 */
    public Optional<InspectContainerResponse> tryInspectLocal(DockerNode node, String id) {
        try {
            return Optional.ofNullable(node.client().inspectContainerCmd(id).exec());
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (DockerException e) {
            log.debug("节点 {} 查询容器 {} 失败: {}", node.id(), shortId(id), e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<InspectContainerResponse> tryInspect(DockerNode node, String id) {
        try {
            return Optional.ofNullable(node.client().inspectContainerCmd(id).exec());
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (DockerException e) {
            throw translate("查询容器失败", e);
        }
    }

    public List<Container> listAll(DockerNode node) {
        try {
            return node.client().listContainersCmd().withShowAll(true).exec();
        } catch (DockerException e) {
            throw translate("列举容器失败", e);
        }
    }

    public List<Container> listManaged(DockerNode node, String owner) {
        Map<String, String> filter = (owner == null)
                ? Map.of(Ownership.MANAGED, "true")
                : Map.of(Ownership.MANAGED, "true", Ownership.OWNER, owner);
        try {
            return node.client().listContainersCmd().withShowAll(true).withLabelFilter(filter).exec();
        } catch (DockerException e) {
            throw translate("列举容器失败", e);
        }
    }

    public String statusOf(DockerNode node, String id) {
        InspectContainerResponse c = inspect(node, id);
        return c.getState() == null ? "unknown" : c.getState().getStatus();
    }

    public boolean isRunning(InspectContainerResponse c) {
        return c.getState() != null && Boolean.TRUE.equals(c.getState().getRunning());
    }

    // ------------------------------------------------------------------ 镜像

    public boolean imageExists(DockerNode node, String image) {
        try {
            node.client().inspectImageCmd(image).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (DockerException e) {
            throw translate("查询镜像失败", e);
        }
    }

    /** 允许联网拉镜像时使用（默认关闭，镜像应在安装期预拉）。 */
    public void pullImage(DockerNode node, String image) {
        String repo = image;
        String tag = null;
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        if (colon > slash) {
            repo = image.substring(0, colon);
            tag = image.substring(colon + 1);
        }
        try {
            var cmd = node.client().pullImageCmd(repo);
            if (tag != null) {
                cmd.withTag(tag);
            }
            try (ResultCallback.Adapter<?> cb = cmd.start()) {
                cb.awaitCompletion(120, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.docker("拉取镜像被中断: " + image);
        } catch (Exception e) {
            throw translate("拉取镜像失败: " + image, e);
        }
    }

    // ------------------------------------------------------------------ 日志

    public String logs(DockerNode node, String id, int tail, boolean timestamps, int maxBytes, int timeoutSeconds) {
        StringBuilder sb = new StringBuilder();
        boolean[] truncated = {false};

        LogContainerResultCallback callback = new LogContainerResultCallback() {
            @Override
            public void onNext(Frame frame) {
                if (frame.getStreamType() == StreamType.STDERR) {
                    append(frame);
                } else if (frame.getStreamType() == StreamType.STDOUT || frame.getStreamType() == StreamType.RAW) {
                    append(frame);
                }
            }

            private void append(Frame frame) {
                if (truncated[0]) {
                    return;
                }
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (sb.length() + text.length() > maxBytes) {
                    sb.append(text, 0, Math.max(0, maxBytes - sb.length()));
                    sb.append("\n...[日志已截断]");
                    truncated[0] = true;
                } else {
                    sb.append(text);
                }
            }
        };

        try {
            node.client().logContainerCmd(id)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .withTimestamps(timestamps)
                    .withFollowStream(false)
                    .exec(callback);
            callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.docker("读取日志被中断: " + id);
        } catch (DockerException e) {
            throw translate("读取日志失败", e);
        } finally {
            try {
                callback.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ exec（同步）

    /** 创建 exec 会话，返回 execId。 */
    public String createExec(DockerNode node, String id, List<String> cmd, List<String> env) {
        return createExec(node, id, cmd, env, null);
    }

    /** 以指定用户创建 exec（仅用于远程工作区属主修正，固定命令、不对外暴露）。 */
    public String createExec(DockerNode node, String id, List<String> cmd, List<String> env, String user) {
        if (cmd == null || cmd.isEmpty()) {
            throw GatewayException.badRequest("cmd 不能为空");
        }
        try {
            var create = node.client().execCreateCmd(id)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd(cmd.toArray(new String[0]));
            if (env != null && !env.isEmpty()) {
                create.withEnv(env);
            }
            if (user != null && !user.isBlank()) {
                create.withUser(user);
            }
            return create.exec().getId();
        } catch (DockerException e) {
            throw translate("创建 exec 会话失败", e);
        }
    }

    /** 以 attach 方式启动 exec，流式回调交给调用方。 */
    public void streamExec(DockerNode node, String execId, ResultCallback<Frame> callback) {
        try {
            node.execClient().execStartCmd(execId).withDetach(false).withTty(false).exec(callback);
        } catch (DockerException e) {
            throw translate("执行命令失败", e);
        }
    }

    /** 查询 exec 退出码（null → -1）。查询失败也返回 -1，不抛异常（可能已 detach/连接被关）。 */
    public int inspectExecExitCode(DockerNode node, String execId) {
        try {
            Integer code = node.client().inspectExecCmd(execId).exec().getExitCode();
            return code == null ? -1 : code;
        } catch (RuntimeException e) {
            log.debug("查询 exec 退出码失败: {}", e.getMessage());
            return -1;
        }
    }

    public ExecResult exec(DockerNode node, String id, List<String> cmd, List<String> env, int timeoutSeconds, int maxOutputBytes) {
        return exec(node, id, cmd, env, timeoutSeconds, maxOutputBytes, null);
    }

    /** 以指定用户执行命令（仅用于远程工作区属主修正）。 */
    public ExecResult execAsUser(DockerNode node, String id, List<String> cmd, List<String> env,
                                 int timeoutSeconds, int maxOutputBytes, String user) {
        return exec(node, id, cmd, env, timeoutSeconds, maxOutputBytes, user);
    }

    private ExecResult exec(DockerNode node, String id, List<String> cmd, List<String> env,
                            int timeoutSeconds, int maxOutputBytes, String user) {
        if (cmd == null || cmd.isEmpty()) {
            throw GatewayException.badRequest("cmd 不能为空");
        }
        long startedAt = System.nanoTime();
        String execId = createExec(node, id, cmd, env, user);

        BoundedOutputStream stdout = new BoundedOutputStream(maxOutputBytes);
        BoundedOutputStream stderr = new BoundedOutputStream(maxOutputBytes);
        ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr);

        boolean finished;
        try {
            node.execClient().execStartCmd(execId).withDetach(false).withTty(false).exec(callback);
            finished = callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.docker("执行命令被中断");
        } catch (DockerException e) {
            throw translate("执行命令失败", e);
        } finally {
            try {
                callback.close();
            } catch (Exception ignored) {
                // 关闭失败不影响结果
            }
        }

        if (!finished) {
            throw GatewayException.conflict("命令执行超时（" + timeoutSeconds + "s），容器仍在运行该命令");
        }

        InspectExecResponse insp;
        try {
            insp = node.client().inspectExecCmd(execId).exec();
        } catch (DockerException e) {
            throw translate("查询 exec 结果失败", e);
        }

        Integer exitCode = insp.getExitCode();
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
        return new ExecResult(
                exitCode == null ? -1 : exitCode,
                stdout.asString(),
                stderr.asString(),
                stdout.truncated() || stderr.truncated(),
                durationMs);
    }

    // ------------------------------------------------------------------ 归档读写（文件收发）

    /**
     * 把 tar 流解压到容器内 {@code remotePath}（对应 Docker {@code PUT /containers/{id}/archive}）。
     * 目标目录不存在时由 Docker 创建。
     */
    public void copyArchiveToContainer(DockerNode node, String id, String remotePath, InputStream tar) {
        try {
            node.client().copyArchiveToContainerCmd(id)
                    .withRemotePath(remotePath)
                    .withTarInputStream(tar)
                    .exec();
        } catch (NotFoundException e) {
            throw GatewayException.notFound("容器不存在: " + shortId(id));
        } catch (DockerException e) {
            throw translate("上传文件到容器失败", e);
        }
    }

    /**
     * 从容器读取 {@code path} 的 tar 归档（对应 Docker {@code GET /containers/{id}/archive}）。
     * 调用方负责关闭返回的流。
     */
    public InputStream copyArchiveFromContainer(DockerNode node, String id, String path) {
        try {
            return node.client().copyArchiveFromContainerCmd(id, path).exec();
        } catch (NotFoundException e) {
            throw GatewayException.notFound("容器内路径不存在: " + path);
        } catch (DockerException e) {
            throw translate("从容器读取文件失败", e);
        }
    }

    // ------------------------------------------------------------------ 工作区卷

    /** 在指定节点创建命名卷（远程节点工作区）。 */
    public void createVolume(DockerNode node, String name, Map<String, String> labels) {
        try {
            node.client().createVolumeCmd().withName(name).withLabels(labels).exec();
        } catch (DockerException e) {
            throw translate("创建工作区卷失败", e);
        }
    }

    /** 删除命名卷；卷已不存在视为成功（幂等）。 */
    public void removeVolume(DockerNode node, String name) {
        try {
            node.client().removeVolumeCmd(name).exec();
        } catch (NotFoundException e) {
            // 已不存在：幂等成功
        } catch (DockerException e) {
            throw translate("删除工作区卷失败", e);
        }
    }

    /** 列出节点上带指定 label 的卷，供孤儿卷回收。 */
    public List<InspectVolumeResponse> listVolumesByLabel(DockerNode node, String key, String value) {
        try {
            List<InspectVolumeResponse> result = new ArrayList<>();
            ListVolumesResponse response = node.client().listVolumesCmd().exec();
            if (response.getVolumes() == null) {
                return result;
            }
            for (InspectVolumeResponse volume : response.getVolumes()) {
                Map<String, String> labels = volume.getLabels();
                if (labels != null && value.equals(labels.get(key))) {
                    result.add(volume);
                }
            }
            return result;
        } catch (DockerException e) {
            throw translate("列举工作区卷失败", e);
        }
    }

    /** 列出节点上所有容器（含已停止），供卷引用检查。 */
    public List<Container> listAllContainers(DockerNode node) {
        try {
            return node.client().listContainersCmd().withShowAll(true).exec();
        } catch (DockerException e) {
            throw translate("列举节点容器失败", e);
        }
    }

    // ------------------------------------------------------------------ 端口

    /**
     * 取容器的“容器端口 -> 宿主端口”映射。
     *
     * <p>优先读 HostConfig.PortBindings：这是创建时写入的配置态，容器停止后依然存在；
     * 而 NetworkSettings.Ports 在容器停止后会变成空对象。
     */
    public static Map<ExposedPort, Ports.Binding[]> bindingsOf(InspectContainerResponse c) {
        if (c.getHostConfig() != null && c.getHostConfig().getPortBindings() != null) {
            Map<ExposedPort, Ports.Binding[]> configured = c.getHostConfig().getPortBindings().getBindings();
            if (configured != null && !configured.isEmpty()) {
                return configured;
            }
        }
        if (c.getNetworkSettings() != null && c.getNetworkSettings().getPorts() != null) {
            Map<ExposedPort, Ports.Binding[]> actual = c.getNetworkSettings().getPorts().getBindings();
            if (actual != null) {
                return actual;
            }
        }
        return Map.of();
    }

    /** 从 inspect 结果里解析“容器端口 -> 宿主端口”的映射。 */
    public static Optional<Integer> findHostPort(InspectContainerResponse c, int containerPort, String protocol) {
        InternetProtocol ip = "udp".equalsIgnoreCase(protocol) ? InternetProtocol.UDP : InternetProtocol.TCP;
        for (Map.Entry<ExposedPort, Ports.Binding[]> e : bindingsOf(c).entrySet()) {
            ExposedPort ep = e.getKey();
            if (ep.getPort() == containerPort && ep.getProtocol() == ip
                    && e.getValue() != null && e.getValue().length > 0) {
                String spec = e.getValue()[0].getHostPortSpec();
                if (spec != null) {
                    try {
                        return Optional.of(Integer.parseInt(spec));
                    } catch (NumberFormatException ignored) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 某节点上已占用的宿主端口（用于端口池重建/对账）。 */
    public List<Integer> hostPortsInUse(DockerNode node) {
        List<Integer> result = new ArrayList<>();
        for (Container c : listAll(node)) {
            ContainerPort[] ports = c.getPorts();
            if (ports == null) {
                continue;
            }
            for (ContainerPort p : ports) {
                if (p.getPublicPort() != null) {
                    result.add(p.getPublicPort());
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ------------------------------------------------------------------ 策略解析

    private String networkMode() {
        String mode = props.getNetworkMode();
        return (mode == null || mode.isBlank()) ? "bridge" : mode.trim();
    }

    private List<Capability> resolveCapDrop() {
        List<String> raw = parseCsv(props.getCapDrop());
        if (raw.isEmpty()) {
            return DANGEROUS_CAPABILITIES;
        }
        return parseCapabilities(raw);
    }

    private List<Capability> resolveCapAdd() {
        List<String> raw = parseCsv(props.getCapAdd());
        if (raw.isEmpty()) {
            return List.of();
        }
        List<Capability> result = new ArrayList<>();
        for (String name : raw) {
            if (FORBIDDEN_CAP_ADD.contains(name.toUpperCase(Locale.ROOT))) {
                log.error("capAdd 配置包含禁止的能力 {}，已忽略", name);
                continue;
            }
            Capability cap = toCapability(name);
            if (cap != null) {
                result.add(cap);
            }
        }
        return result;
    }

    private List<String> resolveSecurityOpts() {
        LinkedHashSet<String> opts = new LinkedHashSet<>(parseCsv(props.getSecurityOpts()));
        String seccomp = props.getSeccompProfile();
        if (seccomp != null && !seccomp.isBlank()) {
            boolean present = opts.stream().anyMatch(o -> o.toLowerCase(Locale.ROOT).startsWith("seccomp="));
            if (!present) {
                opts.add("seccomp=" + seccomp.trim());
            }
        }
        return new ArrayList<>(opts);
    }

    /**
     * 解析 tmpfs 配置。
     *
     * <p>条目之间用 {@code ;} 分隔，条目内为 {@code 路径:选项,选项...}（选项可含逗号，
     * 如 {@code size=64m,uid=1000,mode=1777}），例如：
     * {@code /tmp:rw,size=32m,uid=1000;\/workspace:rw,size=128m}。
     *
     * <p>为兼容既有配置：当整串不含 {@code ;} 时，若按逗号切分后<b>每段都含冒号</b>
     * （即旧的「多个 {@code 路径:选项}」写法，选项内不含逗号），按旧行为拆分；
     * 否则视为<b>单个条目</b>（选项可含逗号），例如 {@code /tmp:rw,size=32m,uid=1000}。
     */
    public static Map<String, String> parseTmpfs(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String trimmed = raw.trim();
        List<String> entries;
        if (trimmed.contains(";")) {
            entries = splitOnSemicolon(trimmed);
        } else {
            List<String> parts = parseCsv(trimmed);
            boolean legacyCommaSeparated = !parts.isEmpty()
                    && parts.stream().allMatch(p -> p.indexOf(':') > 0);
            entries = legacyCommaSeparated ? parts : List.of(trimmed);
        }
        for (String entry : entries) {
            String part = entry.trim();
            if (part.isEmpty()) {
                continue;
            }
            int colon = part.indexOf(':');
            if (colon > 0) {
                result.put(part.substring(0, colon).trim(), part.substring(colon + 1).trim());
            } else {
                result.put(part, "");
            }
        }
        return result;
    }

    private static List<String> splitOnSemicolon(String raw) {
        List<String> result = new ArrayList<>();
        for (String s : raw.split(";")) {
            result.add(s);
        }
        return result;
    }

    /** 校验并转换客户端声明的 bind mount（仅当策略允许时调用）。 */
    public static List<Bind> parseBinds(List<com.example.gateway.web.dto.MountSpec> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return List.of();
        }
        List<Bind> result = new ArrayList<>(mounts.size());
        for (com.example.gateway.web.dto.MountSpec m : mounts) {
            String host = m.hostPath() == null ? "" : m.hostPath().trim();
            String target = m.containerPath() == null ? "" : m.containerPath().trim();
            if (host.isEmpty() || !host.startsWith("/")) {
                throw GatewayException.badRequest("mount.hostPath 必须是绝对路径: " + m.hostPath());
            }
            if (target.isEmpty() || !target.startsWith("/")) {
                throw GatewayException.badRequest("mount.containerPath 必须是绝对路径: " + m.containerPath());
            }
            for (String forbidden : FORBIDDEN_MOUNT_PREFIXES) {
                if (host.equals(forbidden) || host.startsWith(forbidden + "/")) {
                    throw GatewayException.badRequest("禁止挂载宿主敏感路径: " + host);
                }
            }
            AccessMode mode = m.readOnly() ? AccessMode.ro : AccessMode.rw;
            result.add(new Bind(host, new Volume(target), mode));
        }
        return result;
    }

    private static List<Capability> parseCapabilities(List<String> names) {
        List<Capability> result = new ArrayList<>();
        for (String name : names) {
            Capability cap = toCapability(name);
            if (cap != null) {
                result.add(cap);
            }
        }
        return result;
    }

    private static Capability toCapability(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return Capability.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            log.warn("未知 capability: {}（已忽略）", name);
            return null;
        }
    }

    public static List<String> parseCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 异常翻译

    private GatewayException translate(String action, Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof NotFoundException) {
            return GatewayException.notFound(action + ": " + detail);
        }
        if (e instanceof ConflictException) {
            return GatewayException.conflict(action + ": " + detail);
        }
        if (e instanceof BadRequestException) {
            return GatewayException.badRequest(action + ": " + detail);
        }
        if (isReadTimeout(e)) {
            // 后端在响应超时内没有返回数据：不是节点不可达，单独归类（对外仍是 503）。
            return GatewayException.backendTimeout("Docker 节点响应超时: " + detail, e);
        }
        if (isTransportFailure(e)) {
            return GatewayException.nodeUnavailable("无法连接 Docker 节点: " + detail, e);
        }
        return GatewayException.docker(action + ": " + detail);
    }

    /**
     * 判断异常链是否属于「节点网络/连接不可达」，用于统一映射 503。
     * docker-java 传输层可能抛出 RuntimeException 包装的 HttpHostConnectException 等。
     */
    /** 是否为“读超时”（连接已建立，但对端在超时内没有数据）。 */
    public static boolean isReadTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) {
                return true;
            }
            String name = t.getClass().getName();
            if (name.contains("SocketTimeoutException")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    public static boolean isTransportFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.io.IOException) {
                return true;
            }
            String name = t.getClass().getName();
            if (name.contains("HttpHostConnectException")
                    || name.contains("ConnectException")
                    || name.contains("SocketTimeoutException")
                    || name.contains("NoHttpResponseException")
                    || name.contains("ConnectionClosedException")
                    || name.contains("SSLException")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }

    /** 限制 exec 输出大小的输出流。 */
    private static final class BoundedOutputStream extends ByteArrayOutputStream {

        private final int limit;
        private boolean truncated;

        private BoundedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            if (count >= limit) {
                truncated = true;
                return;
            }
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int remaining = limit - count;
            if (len > remaining) {
                truncated = true;
                if (remaining > 0) {
                    super.write(b, off, remaining);
                }
                return;
            }
            super.write(b, off, len);
        }

        boolean truncated() {
            return truncated;
        }

        String asString() {
            return toString(StandardCharsets.UTF_8);
        }
    }
}
