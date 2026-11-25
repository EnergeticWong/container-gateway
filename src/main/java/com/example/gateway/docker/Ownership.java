package com.example.gateway.docker;

/**
 * 网关托管标记的 label 约定。
 *
 * <p>这是安全模型的基础：只有带 {@link #MANAGED}=true 的容器才允许被网关操作，
 * 宿主机上原有的 compose 栈容器（nginx/redis/neo4j/WeKnora…）永远不可被客户端触碰。
 */
public final class Ownership {

    public static final String MANAGED = "com.example.gateway.managed";
    public static final String OWNER = "com.example.gateway.owner";
    public static final String CREATED_AT = "com.example.gateway.createdAt";
    public static final String TTL_SECONDS = "com.example.gateway.ttlSeconds";
    public static final String EXPIRES_AT = "com.example.gateway.expiresAt";
    public static final String HOST_PORTS = "com.example.gateway.hostPorts";
    /** 容器所在的 Docker 节点 id，用于多节点路由（容器在哪台 worker 上）。 */
    public static final String NODE = "com.example.gateway.node";
    /** 业务上下文 label 前缀，如 {@code com.example.gateway.meta.tenantId}。 */
    public static final String META_PREFIX = "com.example.gateway.meta.";
    /** 客户端提供的创建幂等键（{@code Idempotency-Key}），用于创建响应丢失后的核对。 */
    public static final String IDEMPOTENCY_KEY = "com.example.gateway.idempotencyKey";
    /** 创建时的请求摘要（SHA-256 前 32 hex），用于重启后核对「同键是否同参」。 */
    public static final String IDEMPOTENCY_DIGEST = "com.example.gateway.idempotencyDigest";
    /** 网关托管工作区的宿主目录（per-container），用于销毁/回收时清理挂载目录。 */
    public static final String WORKSPACE_HOST_DIR = "com.example.gateway.workspaceHostDir";
    /** 工作区类型：bind（本机宿主目录）或 volume（远程节点命名卷）。 */
    public static final String WORKSPACE_KIND = "com.example.gateway.workspaceKind";
    /** 工作区引用：bind 时为宿主路径，volume 时为卷名。 */
    public static final String WORKSPACE_REF = "com.example.gateway.workspaceRef";
    /** 命名卷上的标记，用于孤儿卷回收。 */
    public static final String WORKSPACE_MANAGED = "com.example.gateway.workspace";

    private Ownership() {
    }
}
