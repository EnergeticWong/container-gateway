package com.example.gateway.docker;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import com.example.gateway.metrics.GatewayMetrics;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多 Docker 节点注册表：负责建连、健康探测、负载快照与选路。
 *
 * <p>节点来自 {@code gateway.nodes}（{@code GATEWAY_NODES_<id>=<uri>[;k=v...]}）；
 * 未配置时回退到 {@code gateway.docker-host} 合成的单节点 {@code local}。
 */
@Component
public class NodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final GatewayProperties props;
    private final DockerOps docker;
    private final GatewayMetrics metrics;
    private final Map<String, DockerNode> nodes = new LinkedHashMap<>();
    /** 每节点：已确认运行中的托管容器数 + 正在创建中的占位数。 */
    private final Map<String, NodeLoad> loads = new ConcurrentHashMap<>();

    private volatile Map<String, Integer> runningCounts = Map.of();
    private volatile long countsRefreshedAt;
    /** 选路负载快照的缓存时长，避免每次 create 都打节点。 */
    private static final long COUNTS_TTL_MS = 2000L;

    public NodeRegistry(GatewayProperties props, DockerOps docker, GatewayMetrics metrics) {
        this.props = props;
        this.docker = docker;
        this.metrics = metrics;
    }

    @PostConstruct
    void init() {
        List<String> definitions = parseNodeDefinitions(props.getNodes());
        if (definitions.isEmpty()) {
            definitions = List.of("local=" + props.getDockerHost());
            log.info("未配置 GATEWAY_NODES，回退单节点 local -> {}", props.getDockerHost());
        }
        for (String definition : definitions) {
            DockerNode node = buildNode(definition);
            if (node != null) {
                nodes.put(node.id(), node);
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("没有可用的 Docker 节点配置（GATEWAY_NODES / GATEWAY_DOCKER_HOST 均为空）");
        }
        for (DockerNode node : nodes.values()) {
            node.probe();
            if (node.healthy()) {
                log.info("节点 {} UP  endpoint={} proxyHost={} running={} ncpu={}",
                        node.id(), node.endpointLabel(), node.proxyHost(), node.runningContainers(), node.ncpu());
            } else {
                log.warn("节点 {} DOWN endpoint={} err={}", node.id(), node.endpointLabel(), node.lastError());
            }
        }
        log.info("每节点 Docker 连接池 maxConnections={}，每节点 SSE 流上限={}，exec 读超时={}s",
                props.getDockerMaxConnections(), props.getMaxStreamsPerNode(),
                props.getDockerExecResponseTimeoutSeconds());
        reconcileLoads();
    }

    @PreDestroy
    void close() {
        for (DockerNode node : nodes.values()) {
            node.close();
        }
    }

    /** 周期性健康探测 + 负载刷新。 */
    @Scheduled(fixedDelayString = "${gateway.node-health-interval-ms:5000}",
            initialDelayString = "${gateway.node-health-interval-ms:5000}")
    public void healthCheck() {
        for (DockerNode node : nodes.values()) {
            DockerNode.Status before = node.status();
            long probeStart = System.nanoTime();
            node.probe();
            long probeMs = (System.nanoTime() - probeStart) / 1_000_000L;
            metrics.nodeProbe(node.id(), node.healthy(), probeMs, probeReason(node));
            if (before != node.status()) {
                if (node.healthy()) {
                    log.info("节点 {} 恢复 UP", node.id());
                } else {
                    log.warn("节点 {} 变为 DOWN: {}", node.id(), node.lastError());
                }
            }
        }
        countsRefreshedAt = 0L; // 让旧的负载快照失效
        reconcileLoads();
    }

    // ------------------------------------------------------------------ 查询 / 选路

    public List<DockerNode> all() {
        return new ArrayList<>(nodes.values());
    }

    public List<DockerNode> healthyNodes() {
        return nodes.values().stream().filter(DockerNode::healthy).collect(Collectors.toList());
    }

    public DockerNode get(String id) {
        return id == null ? null : nodes.get(id);
    }

    public DockerNode require(String id) {
        DockerNode node = get(id);
        if (node == null) {
            throw GatewayException.nodeUnavailable("未知节点: " + id);
        }
        if (!node.healthy()) {
            throw GatewayException.nodeUnavailable(
                    "节点 " + id + " 不可用: " + (node.lastError() == null ? "未连通" : node.lastError()));
        }
        return node;
    }

    /**
     * 选一个健康节点并原子占位。容量判断与占位在同一临界区，
     * 并发创建不会同时看到旧计数而穿透 node-max-tasks。
     */
    public synchronized Reservation reserveNode() {
        List<DockerNode> healthy = healthyNodes();
        if (healthy.isEmpty()) {
            throw GatewayException.nodeUnavailable("没有可用的 Docker 节点");
        }
        int cap = Math.max(1, props.getNodeMaxTasks());
        DockerNode best = null;
        double bestScore = Double.MAX_VALUE;
        for (DockerNode node : healthy) {
            NodeLoad load = load(node.id());
            int used = load.assigned.get() + load.inFlight.get();
            if (used >= cap) {
                continue;
            }
            double score = used * 1000.0 + Math.max(0, node.runningContainers());
            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }
        if (best == null) {
            throw GatewayException.nodeUnavailable(
                    "所有 Docker 节点均已达到单节点并发上限 " + cap + "，请稍后重试");
        }
        load(best.id()).inFlight.incrementAndGet();
        return new Reservation(best);
    }

    /** 创建占位凭证：confirm 表示创建成功，release 表示创建失败。 */
    public final class Reservation {
        private final DockerNode node;
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private Reservation(DockerNode node) {
            this.node = node;
        }

        public DockerNode node() {
            return node;
        }

        public void confirm() {
            if (finished.compareAndSet(false, true)) {
                onConfirmed(node);
            }
        }

        public void release() {
            if (finished.compareAndSet(false, true)) {
                onReleased(node);
            }
        }
    }

    private synchronized void onConfirmed(DockerNode node) {
        NodeLoad load = load(node.id());
        load.inFlight.updateAndGet(v -> Math.max(0, v - 1));
        load.assigned.incrementAndGet();
    }

    private synchronized void onReleased(DockerNode node) {
        load(node.id()).inFlight.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 容器 start 成功后调用：运行中的托管容器 +1。 */
    public synchronized void onStarted(DockerNode node) {
        load(node.id()).assigned.incrementAndGet();
    }

    /** 容器 stop 成功后调用：运行中的托管容器 -1（不低于 0）。 */
    public synchronized void onStopped(DockerNode node) {
        load(node.id()).assigned.updateAndGet(v -> Math.max(0, v - 1));
    }

    /** 容器销毁后调用；仅销毁前处于运行状态时才扣减。 */
    public synchronized void onDestroyed(DockerNode node, boolean wasRunning) {
        if (wasRunning) {
            load(node.id()).assigned.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /**
     * 与 Docker 实际运行中的托管容器数对账。列表调用不持锁，避免阻塞创建；
     * 有创建中的节点跳过本轮，防止覆盖未完成的占位。
     */
    public void reconcileLoads() {
        Map<String, LoadSnapshot> actual = new LinkedHashMap<>();
        for (DockerNode node : healthyNodes()) {
            NodeLoad load = load(node.id());
            if (load.inFlight.get() > 0) {
                continue;
            }
            int assignedBefore = load.assigned.get();
            try {
                int running = (int) docker.listManaged(node, null).stream()
                        .filter(c -> "running".equalsIgnoreCase(c.getState()))
                        .count();
                actual.put(node.id(), new LoadSnapshot(running, assignedBefore));
            } catch (RuntimeException e) {
                log.warn("节点 {} 负载对账失败: {}", node.id(), e.getMessage());
            }
        }
        if (actual.isEmpty()) {
            return;
        }
        synchronized (this) {
            for (Map.Entry<String, LoadSnapshot> e : actual.entrySet()) {
                NodeLoad load = load(e.getKey());
                LoadSnapshot snapshot = e.getValue();
                // 列举期间有创建确认/销毁时放弃本轮，避免用旧快照覆盖本地计数
                if (load.inFlight.get() == 0 && load.assigned.get() == snapshot.assignedBefore()) {
                    load.assigned.set(snapshot.running());
                }
            }
        }
    }

    private record LoadSnapshot(int running, int assignedBefore) {
    }

    private NodeLoad load(String nodeId) {
        return loads.computeIfAbsent(nodeId, k -> new NodeLoad());
    }

    private static final class NodeLoad {
        final AtomicInteger assigned = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
    }

    /** 各节点“运行中的托管容器数”，用于选路与单节点并发上限判定；带 2s 缓存。 */
    public Map<String, Integer> runningManagedCounts() {
        long now = System.currentTimeMillis();
        if (now - countsRefreshedAt < COUNTS_TTL_MS) {
            return runningCounts;
        }
        refreshCounts(now);
        return runningCounts;
    }

    private synchronized void refreshCounts(long now) {
        if (now - countsRefreshedAt < COUNTS_TTL_MS) {
            return;
        }
        Map<String, Integer> fresh = new ConcurrentHashMap<>();
        for (DockerNode node : nodes.values()) {
            if (!node.healthy()) {
                fresh.put(node.id(), Integer.MAX_VALUE / 2);
                continue;
            }
            try {
                int running = (int) docker.listManaged(node, null).stream()
                        .filter(c -> "running".equalsIgnoreCase(c.getState()))
                        .count();
                fresh.put(node.id(), running);
            } catch (RuntimeException e) {
                fresh.put(node.id(), Integer.MAX_VALUE / 2);
            }
        }
        this.runningCounts = Map.copyOf(fresh);
        this.countsRefreshedAt = now;
    }

    // ------------------------------------------------------------------ 构建

    private static List<String> parseNodeDefinitions(String raw) {
        List<String> definitions = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return definitions;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                definitions.add(s);
            }
        }
        return definitions;
    }

    private DockerNode buildNode(String definition) {
        int sep = definition.indexOf('=');
        if (sep <= 0 || sep == definition.length() - 1) {
            log.warn("忽略非法节点定义（应形如 id=<dockerUri>[;k=v...]）: {}", definition);
            return null;
        }
        String id = definition.substring(0, sep).trim();
        String rest = definition.substring(sep + 1).trim();
        if (id.isEmpty() || rest.isEmpty()) {
            log.warn("忽略非法节点定义: {}", definition);
            return null;
        }
        String[] parts = rest.split(";");
        String uriStr = parts[0].trim();
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) {
                continue;
            }
            int eq = seg.indexOf('=');
            if (eq <= 0) {
                log.warn("节点 {} 的选项非法，已忽略: {}", id, seg);
                continue;
            }
            opt.put(seg.substring(0, eq).trim().toLowerCase(Locale.ROOT), seg.substring(eq + 1).trim());
        }
        if ("false".equalsIgnoreCase(opt.getOrDefault("enabled", "true"))) {
            log.info("节点 {} 已禁用（enabled=false），跳过", id);
            return null;
        }

        URI uri;
        try {
            uri = URI.create(uriStr);
        } catch (RuntimeException e) {
            log.warn("节点 {} 的 dockerHost 非法: {} -> {}", id, uriStr, e.getMessage());
            return null;
        }
        boolean isUnix = "unix".equalsIgnoreCase(uri.getScheme());
        boolean local = isUnix || isLoopbackHost(uri.getHost());

        String proxyHost = opt.get("proxyhost");
        if (proxyHost == null || proxyHost.isBlank()) {
            proxyHost = isUnix ? "127.0.0.1" : uri.getHost();
        }

        boolean tlsVerify = Boolean.parseBoolean(opt.getOrDefault("tlsverify", String.valueOf(props.isTlsVerify())));
        String certPath = opt.getOrDefault("certpath", props.getTlsCertPath());

        try {
            int maxConnections = Math.max(2, props.getDockerMaxConnections());
            DockerClient client = buildClient(id, uriStr, isUnix, tlsVerify, certPath,
                    Duration.ofSeconds(10), Duration.ofSeconds(60), maxConnections, true);
            DockerClient probeClient = buildClient(id, uriStr, isUnix, tlsVerify, certPath,
                    Duration.ofSeconds(2), Duration.ofMillis(Math.max(1000L, props.getNodeProbeTimeoutMs())), 2, false);
            // exec 的输出流可能长时间静默（命令不打印任何内容），单独用长读超时；
            // 上限仍由 execMaxTimeoutSeconds 控制，到点返回 409。
            DockerClient execClient = buildClient(id, uriStr, isUnix, tlsVerify, certPath,
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(props.getDockerExecResponseTimeoutSeconds()),
                    Math.max(8, props.getMaxStreamsPerNode()), true);
            return new DockerNode(id, uri, proxyHost, local, client, probeClient, execClient);
        } catch (RuntimeException e) {
            log.warn("节点 {} 建连失败: {}", id, e.getMessage());
            return null;
        }
    }

    private DockerClient buildClient(String nodeId, String dockerHost, boolean isUnix, boolean tlsVerify,
                                     String certPath, Duration connectionTimeout, Duration responseTimeout,
                                     int maxConnections, boolean recordMetrics) {
        DefaultDockerClientConfig.Builder builder = new DefaultDockerClientConfig.Builder()
                .withDockerHost(dockerHost);
        if (!isUnix) {
            if (certPath != null && !certPath.isBlank()) {
                builder.withDockerCertPath(certPath);
            }
            builder.withDockerTlsVerify(tlsVerify);
        }
        DefaultDockerClientConfig config = builder.build();

        DockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(maxConnections)
                .connectionTimeout(connectionTimeout)
                .responseTimeout(responseTimeout)
                .build();
        if (recordMetrics) {
            httpClient = new com.example.gateway.metrics.MetricsDockerHttpClient(httpClient, nodeId, metrics);
        }

        return DockerClientImpl.getInstance(config, httpClient);
    }

    /** 选路计数（assigned/inflight）快照，供 exporter 读取。 */
    public Map<String, int[]> loadSnapshot() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (DockerNode node : nodes.values()) {
            NodeLoad load = load(node.id());
            out.put(node.id(), new int[]{load.assigned.get(), load.inFlight.get()});
        }
        return out;
    }

    /** 探测失败原因的粗分类，仅用于指标标签。 */
    public static String probeReason(DockerNode node) {
        if (node.healthy()) {
            return "";
        }
        String err = node.lastError() == null ? "" : node.lastError().toLowerCase(Locale.ROOT);
        if (err.contains("certificate") || err.contains("ssl") || err.contains("tls")
                || err.contains("handshake")) {
            return "tls";
        }
        if (err.contains("timed out") || err.contains("timeout")) {
            return "timeout";
        }
        if (err.contains("refused") || err.contains("connect")) {
            return "connect";
        }
        return "http";
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost") || host.equals("::1");
    }
}
