package com.example.gateway.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个被网关管理的 Docker 节点（worker）运行时视图。
 *
 * <p>持有到该节点 dockerd 的 {@link DockerClient}、健康状态与最近一次负载快照。
 * 所有 docker-java 业务调用仍统一走 {@link DockerOps}，本类只负责节点身份与状态。
 */
public final class DockerNode {

    /** 节点连通状态。 */
    public enum Status {
        /** 尚未探测。 */
        UNKNOWN,
        /** 健康。 */
        UP,
        /** 不可达。 */
        DOWN
    }

    private final String id;
    private final URI dockerHost;
    /** 网关访问该节点映射宿主端口的地址（反向代理目标）；unix 节点为 127.0.0.1。 */
    private final String proxyHost;
    /** 是否为网关本机节点（决定是否做本地端口探测/直连 URL）。 */
    private final boolean local;
    private final DockerClient client;
    /** 探测专用连接（短超时），避免节点失联时健康检查挂满业务客户端的 60s 超时。 */
    private final DockerClient probeClient;
    /**
     * exec 专用连接（长超时）：exec 的输出流可能在命令结束前长时间静默，
     * 不能用业务客户端的 60s 读超时，否则会被误判为节点不可达。
     */
    private final DockerClient execClient;

    private volatile Status status = Status.UNKNOWN;
    private volatile String lastError;
    private volatile long lastProbeEpochMs;
    private volatile long lastProbeDurationMs = -1;

    // 最近一次 /info 快照
    private volatile int runningContainers = -1;
    private volatile int ncpu = -1;
    private volatile long memoryTotalBytes = -1;

    /** 当前节点上正在进行的 SSE 流数量。 */
    private final AtomicInteger activeStreams = new AtomicInteger();

    public DockerNode(String id, URI dockerHost, String proxyHost, boolean local, DockerClient client) {
        this(id, dockerHost, proxyHost, local, client, client, client);
    }

    public DockerNode(String id, URI dockerHost, String proxyHost, boolean local,
                      DockerClient client, DockerClient probeClient) {
        this(id, dockerHost, proxyHost, local, client, probeClient, client);
    }

    public DockerNode(String id, URI dockerHost, String proxyHost, boolean local,
                      DockerClient client, DockerClient probeClient, DockerClient execClient) {
        this.id = id;
        this.dockerHost = dockerHost;
        this.proxyHost = proxyHost;
        this.local = local;
        this.client = client;
        this.probeClient = probeClient == null ? client : probeClient;
        this.execClient = execClient == null ? client : execClient;
    }

    /** 关闭该节点的 Docker 客户端（探测/业务/exec 客户端可能不同）。 */
    public void close() {
        closeQuietly(probeClient);
        if (client != probeClient) {
            closeQuietly(client);
        }
        if (execClient != probeClient && execClient != client) {
            closeQuietly(execClient);
        }
    }

    private static void closeQuietly(DockerClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }

    public String id() {
        return id;
    }

    public URI dockerHost() {
        return dockerHost;
    }

    public String proxyHost() {
        return proxyHost;
    }

    public boolean local() {
        return local;
    }

    public DockerClient client() {
        return client;
    }

    /** exec 专用客户端：读超时覆盖命令最长静默期。 */
    public DockerClient execClient() {
        return execClient;
    }

    public Status status() {
        return status;
    }

    public boolean healthy() {
        return status == Status.UP;
    }

    public String lastError() {
        return lastError;
    }

    public long lastProbeEpochMs() {
        return lastProbeEpochMs;
    }

    /** 最近一次 /info 探测耗时（毫秒）；尚未探测时为 -1。 */
    public long lastProbeDurationMs() {
        return lastProbeDurationMs;
    }

    public int runningContainers() {
        return runningContainers;
    }

    public int ncpu() {
        return ncpu;
    }

    public long memoryTotalBytes() {
        return memoryTotalBytes;
    }

    public AtomicInteger activeStreams() {
        return activeStreams;
    }

    /** 探测一次连通性与负载（由健康检查线程调用）。 */
    public void probe() {
        long startedAt = System.nanoTime();
        try {
            Info info = probeClient.infoCmd().exec();
            this.runningContainers = info.getContainersRunning() == null ? -1 : info.getContainersRunning();
            this.ncpu = info.getNCPU() == null ? -1 : info.getNCPU();
            this.memoryTotalBytes = info.getMemTotal() == null ? -1 : info.getMemTotal();
            this.status = Status.UP;
            this.lastError = null;
        } catch (RuntimeException e) {
            this.status = Status.DOWN;
            this.lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            this.lastProbeEpochMs = System.currentTimeMillis();
            this.lastProbeDurationMs = (System.nanoTime() - startedAt) / 1_000_000L;
        }
    }

    /** 展示用的端点（脱敏，仅 scheme://host:port）。 */
    public String endpointLabel() {
        String s = dockerHost == null ? "" : dockerHost.toString();
        return s;
    }
}
