package com.example.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 绑定 application.yml 中 gateway.* 配置（值来自 /etc/container-gateway.env 注入的环境变量）。
 *
 * <p>所有策略/规格/上限都通过这里注入，换环境只改 env，不改代码。
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    // ------------------------------------------------------------------ 节点（多 Docker 端点）
    /**
     * 节点定义（逗号分隔多个节点，每个节点 {@code id=<dockerUri>[;<k>=<v>...]}）。
     *
     * <p>可选键：{@code proxyHost}（网关访问该节点映射端口的地址，缺省 unix→127.0.0.1、tcp→URI host）、
     * {@code tlsVerify}、{@code certPath}、{@code enabled}。
     *
     * <p>示例：{@code GATEWAY_NODES=local=unix:///var/run/docker.sock,w1=tcp://192.0.2.11:2376;tlsVerify=true;certPath=/etc/container-gateway/tls/w1;proxyHost=192.0.2.11}
     * <br>未配置时回退到 {@link #dockerHost} 合成的单节点 {@code local}。
     */
    private String nodes = "";

    /** 远程 tcp 节点的默认证书目录（含 ca.pem/cert.pem/key.pem），可被节点级 certPath 覆盖。 */
    private String tlsCertPath = "";
    /** 远程 tcp 节点默认是否校验 TLS 证书。 */
    private boolean tlsVerify = false;
    /** 节点健康探测间隔。 */
    private long nodeHealthIntervalMs = 5000L;
    /** 健康探测的响应超时（毫秒），避免节点失联时探测挂满 60s。 */
    private long nodeProbeTimeoutMs = 3000L;
    /** 单节点运行中托管容器数量上限（按本机容量评估）。 */
    private int nodeMaxTasks = 30;
    /** 单节点允许的最大 SSE 流数量，防止耗尽 docker 连接池。 */
    private int maxStreamsPerNode = 50;
    /** 每个 Docker 节点的 HTTP 连接池上限（总连接与每路由相同）。 */
    private int dockerMaxConnections = 100;

    // ------------------------------------------------------------------ 兼容：单节点 Docker
    /** 兼容旧配置：未配置 {@link #nodes} 时作为唯一节点。 */
    private String dockerHost = "unix:///var/run/docker.sock";

    // ------------------------------------------------------------------ 鉴权 / 镜像
    /** 格式：&lt;token&gt;:&lt;clientId&gt;[, ...]；为空则拒绝所有请求。 */
    private String tokens = "";

    /** 镜像白名单，前缀匹配，deny-by-default，逗号分隔；支持 repo / repo:tag / repo@sha256:digest。 */
    private String imageWhitelist = "";

    private boolean allowPull = false;

    /** 启动期预拉镜像列表（逗号分隔），如 alpine:3.20。 */
    private String prepullImages = "";

    // ------------------------------------------------------------------ 宿主端口池
    private int portRangeStart = 30000;
    private int portRangeEnd = 30999;

    // ------------------------------------------------------------------ 配额与限流
    private int maxContainers = 60;
    private int maxPerOwner = 30;
    private long defaultTtlSeconds = 300L;
    private long maxTtlSeconds = 86400L;
    private long defaultMemoryBytes = 268435456L;
    private long maxMemoryBytes = 4294967296L;
    private double defaultCpus = 1.0d;
    private double maxCpus = 4d;
    private long defaultPids = 256L;
    private long maxPids = 512L;
    private long minMemoryBytes = 16L * 1024 * 1024;
    private long minTtlSeconds = 30L;
    private int maxPortsPerContainer = 16;
    private int rateLimitPerMinute = 2000;
    private int createRateLimitPerMinute = 60;
    /** 公网健康检查按源 IP 的限流，防止 /healthz 被刷。 */
    private int healthRateLimitPerMinute = 240;

    // ------------------------------------------------------------------ 容器策略（HostConfig）
    /** 网络模式：none | bridge | 自定义网络名。 */
    private String networkMode = "bridge";
    /** 容器退出后自动删除（沙箱短任务模型；开启后端口池靠对账回收）。 */
    private boolean autoRemove = true;
    /** 额外丢弃的能力，逗号分隔；空=内置危险能力清单；支持 ALL。 */
    private String capDrop = "";
    /** 额外添加的能力，逗号分隔（会校验黑名单，禁止重新加回危险项）。 */
    private String capAdd = "";
    /** securityOpt，逗号分隔；默认 no-new-privileges:true。 */
    private String securityOpts = "no-new-privileges:true";
    /** seccomp profile：空 | runtime/default | unconfined | 节点上存在的路径。 */
    private String seccompProfile = "";
    /** 只读根文件系统。 */
    private boolean readonlyRootfs = false;
    /** tmpfs 挂载，逗号分隔，如 /tmp:rw,size=64m。 */
    private String tmpfs = "";
    /** 是否允许客户端声明的 bind mount（仍拒绝 docker.sock 与宿主系统路径）。 */
    private boolean bindMountsEnabled = false;

    // ------------------------------------------------------------------ exec / 日志 / 代理
    private int execDefaultTimeoutSeconds = 30;
    private int execMaxTimeoutSeconds = 600;
    private int execMaxOutputBytes = 65536;
    /** SSE 心跳间隔。 */
    private long streamHeartbeatMs = 15000L;

    private int proxyMaxBodyBytes = 8 * 1024 * 1024;
    private int proxyTimeoutSeconds = 120;

    // ------------------------------------------------------------------ TTL / 审计 / 通告
    private long ttlScanIntervalMs = 60000L;

    private String auditLog = "/opt/container-gateway/logs/audit.log";

    private boolean proxyEnabled = true;
    private String advertiseHost = "";
    private String advertiseScheme = "http";
    /** 宿主地址，用于生成直连 URL（调用方网络可路由到该地址时可用）；留空则不生成。 */
    private String directHost = "";

    // ------------------------------------------------------------------ 文件收发（阶段 B）
    /** 是否开启容器文件收发 API（GATEWAY_FILE_TRANSFER_ENABLED，默认开启）。 */
    private boolean fileTransferEnabled = true;
    /** 文件根目录（容器内绝对路径），上传/产物目录都必须落在它下面。 */
    private String fileRoot = "/workspace";
    /** 上传目标目录（必须位于 fileRoot 下）。 */
    private String fileUploadDir = "/workspace/input";
    /** 产物目录；下载与列举只允许在此目录下。 */
    private String fileOutputDir = "/workspace/out";
    /** 单文件上限（字节）。 */
    private long fileMaxBytes = 33554432L;
    /** 单次请求总字节上限（上传体 / 下载体）。 */
    private long fileMaxTotalBytes = 67108864L;
    /** 单次请求文件数上限（tar entry）。 */
    private int fileMaxCount = 64;

    // ------------------------------------------------------------------ 治理：创建幂等 / 业务上下文
    /** 是否开启创建幂等（客户端可用 {@code Idempotency-Key} 头重放）。 */
    private boolean createIdempotencyEnabled = true;
    /** 幂等记录保留秒数。 */
    private long createIdempotencyTtlSeconds = 600L;
    /** 是否要求创建时携带业务上下文（meta.tenantId 与 meta.userId）。 */
    private boolean requireBusinessContext = false;

    // ------------------------------------------------------------------ 加固：运行用户
    /** 容器运行用户（{@code user[:group]}）；留空使用镜像默认用户。 */
    private String runAsUser = "";

    // ------------------------------------------------------------------ 加固：网关托管工作区
    /**
     * 网关托管工作区宿主目录（GATEWAY_WORKSPACE_HOST_DIR）。
     *
     * <p>非空时启用：每个容器创建时在其下建独立子目录并 chown 到运行 uid，
     * 以 rw bind 挂载到 {@link #fileRoot}（默认 {@code /workspace}），容器销毁/回收时删除。
     * 这样「非 root + 只读 RootFS + 文件收发 API」三者才能并存（Docker archive 无法写
     * tmpfs，只读 rootfs 下又被拒）。
     *
     * <p>该目录必须位于网关本机、且网关进程对其有写权限（systemd ReadWritePaths 需包含它）；
     * 远程 Docker 节点不支持。留空=不托管（保持原有行为）。
     */
    private String workspaceHostDir = "";

    // ------------------------------------------------------------------ getter/setter

    public String getNodes() {
        return nodes;
    }

    public void setNodes(String nodes) {
        this.nodes = nodes;
    }

    public String getTlsCertPath() {
        return tlsCertPath;
    }

    public void setTlsCertPath(String tlsCertPath) {
        this.tlsCertPath = tlsCertPath;
    }

    public boolean isTlsVerify() {
        return tlsVerify;
    }

    public void setTlsVerify(boolean tlsVerify) {
        this.tlsVerify = tlsVerify;
    }

    public long getNodeHealthIntervalMs() {
        return nodeHealthIntervalMs;
    }

    public long getNodeProbeTimeoutMs() {
        return nodeProbeTimeoutMs;
    }

    public void setNodeProbeTimeoutMs(long nodeProbeTimeoutMs) {
        this.nodeProbeTimeoutMs = nodeProbeTimeoutMs;
    }

    public void setNodeHealthIntervalMs(long nodeHealthIntervalMs) {
        this.nodeHealthIntervalMs = nodeHealthIntervalMs;
    }

    public int getNodeMaxTasks() {
        return nodeMaxTasks;
    }

    public void setNodeMaxTasks(int nodeMaxTasks) {
        this.nodeMaxTasks = nodeMaxTasks;
    }

    public int getMaxStreamsPerNode() {
        return maxStreamsPerNode;
    }

    public int getDockerMaxConnections() {
        return dockerMaxConnections;
    }

    public void setDockerMaxConnections(int dockerMaxConnections) {
        this.dockerMaxConnections = dockerMaxConnections;
    }

    public void setMaxStreamsPerNode(int maxStreamsPerNode) {
        this.maxStreamsPerNode = maxStreamsPerNode;
    }

    public String getDockerHost() {
        return dockerHost;
    }

    public void setDockerHost(String dockerHost) {
        this.dockerHost = dockerHost;
    }

    public String getTokens() {
        return tokens;
    }

    public void setTokens(String tokens) {
        this.tokens = tokens;
    }

    public String getImageWhitelist() {
        return imageWhitelist;
    }

    public void setImageWhitelist(String imageWhitelist) {
        this.imageWhitelist = imageWhitelist;
    }

    public boolean isAllowPull() {
        return allowPull;
    }

    public void setAllowPull(boolean allowPull) {
        this.allowPull = allowPull;
    }

    public String getPrepullImages() {
        return prepullImages;
    }

    public void setPrepullImages(String prepullImages) {
        this.prepullImages = prepullImages;
    }

    public int getPortRangeStart() {
        return portRangeStart;
    }

    public void setPortRangeStart(int portRangeStart) {
        this.portRangeStart = portRangeStart;
    }

    public int getPortRangeEnd() {
        return portRangeEnd;
    }

    public void setPortRangeEnd(int portRangeEnd) {
        this.portRangeEnd = portRangeEnd;
    }

    public int getMaxContainers() {
        return maxContainers;
    }

    public void setMaxContainers(int maxContainers) {
        this.maxContainers = maxContainers;
    }

    public int getMaxPerOwner() {
        return maxPerOwner;
    }

    public void setMaxPerOwner(int maxPerOwner) {
        this.maxPerOwner = maxPerOwner;
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public long getMaxTtlSeconds() {
        return maxTtlSeconds;
    }

    public void setMaxTtlSeconds(long maxTtlSeconds) {
        this.maxTtlSeconds = maxTtlSeconds;
    }

    public long getDefaultMemoryBytes() {
        return defaultMemoryBytes;
    }

    public void setDefaultMemoryBytes(long defaultMemoryBytes) {
        this.defaultMemoryBytes = defaultMemoryBytes;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public void setMaxMemoryBytes(long maxMemoryBytes) {
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public double getDefaultCpus() {
        return defaultCpus;
    }

    public void setDefaultCpus(double defaultCpus) {
        this.defaultCpus = defaultCpus;
    }

    public double getMaxCpus() {
        return maxCpus;
    }

    public void setMaxCpus(double maxCpus) {
        this.maxCpus = maxCpus;
    }

    public long getDefaultPids() {
        return defaultPids;
    }

    public void setDefaultPids(long defaultPids) {
        this.defaultPids = defaultPids;
    }

    public long getMaxPids() {
        return maxPids;
    }

    public void setMaxPids(long maxPids) {
        this.maxPids = maxPids;
    }

    public long getMinMemoryBytes() {
        return minMemoryBytes;
    }

    public void setMinMemoryBytes(long minMemoryBytes) {
        this.minMemoryBytes = minMemoryBytes;
    }

    public long getMinTtlSeconds() {
        return minTtlSeconds;
    }

    public void setMinTtlSeconds(long minTtlSeconds) {
        this.minTtlSeconds = minTtlSeconds;
    }

    public int getMaxPortsPerContainer() {
        return maxPortsPerContainer;
    }

    public void setMaxPortsPerContainer(int maxPortsPerContainer) {
        this.maxPortsPerContainer = maxPortsPerContainer;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public int getCreateRateLimitPerMinute() {
        return createRateLimitPerMinute;
    }

    public void setCreateRateLimitPerMinute(int createRateLimitPerMinute) {
        this.createRateLimitPerMinute = createRateLimitPerMinute;
    }

    public int getHealthRateLimitPerMinute() {
        return healthRateLimitPerMinute;
    }

    public void setHealthRateLimitPerMinute(int healthRateLimitPerMinute) {
        this.healthRateLimitPerMinute = healthRateLimitPerMinute;
    }

    public String getNetworkMode() {
        return networkMode;
    }

    public void setNetworkMode(String networkMode) {
        this.networkMode = networkMode;
    }

    public boolean isAutoRemove() {
        return autoRemove;
    }

    public void setAutoRemove(boolean autoRemove) {
        this.autoRemove = autoRemove;
    }

    public String getCapDrop() {
        return capDrop;
    }

    public void setCapDrop(String capDrop) {
        this.capDrop = capDrop;
    }

    public String getCapAdd() {
        return capAdd;
    }

    public void setCapAdd(String capAdd) {
        this.capAdd = capAdd;
    }

    public String getSecurityOpts() {
        return securityOpts;
    }

    public void setSecurityOpts(String securityOpts) {
        this.securityOpts = securityOpts;
    }

    public String getSeccompProfile() {
        return seccompProfile;
    }

    public void setSeccompProfile(String seccompProfile) {
        this.seccompProfile = seccompProfile;
    }

    public boolean isReadonlyRootfs() {
        return readonlyRootfs;
    }

    public void setReadonlyRootfs(boolean readonlyRootfs) {
        this.readonlyRootfs = readonlyRootfs;
    }

    public String getTmpfs() {
        return tmpfs;
    }

    public void setTmpfs(String tmpfs) {
        this.tmpfs = tmpfs;
    }

    public boolean isBindMountsEnabled() {
        return bindMountsEnabled;
    }

    public void setBindMountsEnabled(boolean bindMountsEnabled) {
        this.bindMountsEnabled = bindMountsEnabled;
    }

    public int getExecDefaultTimeoutSeconds() {
        return execDefaultTimeoutSeconds;
    }

    public void setExecDefaultTimeoutSeconds(int execDefaultTimeoutSeconds) {
        this.execDefaultTimeoutSeconds = execDefaultTimeoutSeconds;
    }

    public int getExecMaxTimeoutSeconds() {
        return execMaxTimeoutSeconds;
    }

    /**
     * exec 专用 Docker 客户端的读超时（秒）。
     *
     * <p>必须覆盖单次 exec 的最长静默期，否则命令只要 N 秒不输出就会被判定为
     * “节点不可达”。取 {@code execMaxTimeoutSeconds + 60}，保证读超时永远晚于
     * 网关自身的执行超时（到点由 awaitCompletion 返回 409）。
     */
    public long getDockerExecResponseTimeoutSeconds() {
        return Math.max(60L, (long) execMaxTimeoutSeconds + 60L);
    }

    public void setExecMaxTimeoutSeconds(int execMaxTimeoutSeconds) {
        this.execMaxTimeoutSeconds = execMaxTimeoutSeconds;
    }

    public int getExecMaxOutputBytes() {
        return execMaxOutputBytes;
    }

    public void setExecMaxOutputBytes(int execMaxOutputBytes) {
        this.execMaxOutputBytes = execMaxOutputBytes;
    }

    public long getStreamHeartbeatMs() {
        return streamHeartbeatMs;
    }

    public void setStreamHeartbeatMs(long streamHeartbeatMs) {
        this.streamHeartbeatMs = streamHeartbeatMs;
    }

    public int getProxyMaxBodyBytes() {
        return proxyMaxBodyBytes;
    }

    public void setProxyMaxBodyBytes(int proxyMaxBodyBytes) {
        this.proxyMaxBodyBytes = proxyMaxBodyBytes;
    }

    public int getProxyTimeoutSeconds() {
        return proxyTimeoutSeconds;
    }

    public void setProxyTimeoutSeconds(int proxyTimeoutSeconds) {
        this.proxyTimeoutSeconds = proxyTimeoutSeconds;
    }

    public long getTtlScanIntervalMs() {
        return ttlScanIntervalMs;
    }

    public void setTtlScanIntervalMs(long ttlScanIntervalMs) {
        this.ttlScanIntervalMs = ttlScanIntervalMs;
    }

    public String getAuditLog() {
        return auditLog;
    }

    public void setAuditLog(String auditLog) {
        this.auditLog = auditLog;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public String getAdvertiseHost() {
        return advertiseHost;
    }

    public void setAdvertiseHost(String advertiseHost) {
        this.advertiseHost = advertiseHost;
    }

    public String getAdvertiseScheme() {
        return advertiseScheme;
    }

    public void setAdvertiseScheme(String advertiseScheme) {
        this.advertiseScheme = advertiseScheme;
    }

    public String getDirectHost() {
        return directHost;
    }

    public void setDirectHost(String directHost) {
        this.directHost = directHost;
    }

    public boolean isFileTransferEnabled() {
        return fileTransferEnabled;
    }

    public void setFileTransferEnabled(boolean fileTransferEnabled) {
        this.fileTransferEnabled = fileTransferEnabled;
    }

    public String getFileRoot() {
        return fileRoot;
    }

    public void setFileRoot(String fileRoot) {
        this.fileRoot = fileRoot;
    }

    public String getFileUploadDir() {
        return fileUploadDir;
    }

    public void setFileUploadDir(String fileUploadDir) {
        this.fileUploadDir = fileUploadDir;
    }

    public String getFileOutputDir() {
        return fileOutputDir;
    }

    public void setFileOutputDir(String fileOutputDir) {
        this.fileOutputDir = fileOutputDir;
    }

    public long getFileMaxBytes() {
        return fileMaxBytes;
    }

    public void setFileMaxBytes(long fileMaxBytes) {
        this.fileMaxBytes = fileMaxBytes;
    }

    public long getFileMaxTotalBytes() {
        return fileMaxTotalBytes;
    }

    public void setFileMaxTotalBytes(long fileMaxTotalBytes) {
        this.fileMaxTotalBytes = fileMaxTotalBytes;
    }

    public int getFileMaxCount() {
        return fileMaxCount;
    }

    public void setFileMaxCount(int fileMaxCount) {
        this.fileMaxCount = fileMaxCount;
    }

    public boolean isCreateIdempotencyEnabled() {
        return createIdempotencyEnabled;
    }

    public void setCreateIdempotencyEnabled(boolean createIdempotencyEnabled) {
        this.createIdempotencyEnabled = createIdempotencyEnabled;
    }

    public long getCreateIdempotencyTtlSeconds() {
        return createIdempotencyTtlSeconds;
    }

    public void setCreateIdempotencyTtlSeconds(long createIdempotencyTtlSeconds) {
        this.createIdempotencyTtlSeconds = createIdempotencyTtlSeconds;
    }

    public boolean isRequireBusinessContext() {
        return requireBusinessContext;
    }

    public void setRequireBusinessContext(boolean requireBusinessContext) {
        this.requireBusinessContext = requireBusinessContext;
    }

    public String getRunAsUser() {
        return runAsUser;
    }

    public void setRunAsUser(String runAsUser) {
        this.runAsUser = runAsUser;
    }

    public String getWorkspaceHostDir() {
        return workspaceHostDir;
    }

    public void setWorkspaceHostDir(String workspaceHostDir) {
        this.workspaceHostDir = workspaceHostDir;
    }

    // ------------------------------------------------------------------ 监控快照

    /**
     * exporter 只读快照端口，绑定 127.0.0.1；<=0 表示关闭。
     * 供同机 cgw-exporter 抓取，不对外暴露。
     */
    private int exporterSnapshotPort = 18091;

    public int getExporterSnapshotPort() {
        return exporterSnapshotPort;
    }

    public void setExporterSnapshotPort(int exporterSnapshotPort) {
        this.exporterSnapshotPort = exporterSnapshotPort;
    }
}
