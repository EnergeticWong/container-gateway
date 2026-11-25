package com.example.gateway.error;

import org.springframework.http.HttpStatus;

/**
 * 网关统一业务异常，携带 HTTP 状态与稳定错误码（错误码是给客户端做分支判断用的契约）。
 */
public class GatewayException extends RuntimeException {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String OWNERSHIP_DENIED = "OWNERSHIP_DENIED";
    public static final String IMAGE_NOT_ALLOWED = "IMAGE_NOT_ALLOWED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String STATE_CONFLICT = "STATE_CONFLICT";
    /** 同一 Idempotency-Key 被用于不同的创建参数（参数漂移）。 */
    public static final String IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String NOT_ACCEPTABLE = "NOT_ACCEPTABLE";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String PORT_EXHAUSTED = "PORT_EXHAUSTED";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String DOCKER_ERROR = "DOCKER_ERROR";
    public static final String DOCKER_UNAVAILABLE = "DOCKER_UNAVAILABLE";
    public static final String NODE_UNAVAILABLE = "NODE_UNAVAILABLE";

    private final HttpStatus status;
    private final String code;
    /** 限流类错误的建议重试等待秒数；0 表示不返回 Retry-After。 */
    private final long retryAfterSeconds;

    public GatewayException(HttpStatus status, String code, String message) {
        this(status, code, message, 0L);
    }

    public GatewayException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public GatewayException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = 0L;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, message);
    }

    public static GatewayException unauthorized(String message) {
        return new GatewayException(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, message);
    }

    public static GatewayException ownershipDenied(String message) {
        return new GatewayException(HttpStatus.FORBIDDEN, OWNERSHIP_DENIED, message);
    }

    public static GatewayException imageNotAllowed(String message) {
        return new GatewayException(HttpStatus.FORBIDDEN, IMAGE_NOT_ALLOWED, message);
    }

    public static GatewayException notFound(String message) {
        return new GatewayException(HttpStatus.NOT_FOUND, NOT_FOUND, message);
    }

    public static GatewayException conflict(String message) {
        return new GatewayException(HttpStatus.CONFLICT, STATE_CONFLICT, message);
    }

    /** 同一 Idempotency-Key 复用到了不同参数（409）。 */
    public static GatewayException idempotencyKeyReused(String message) {
        return new GatewayException(HttpStatus.CONFLICT, IDEMPOTENCY_KEY_REUSED, message);
    }

    public static GatewayException quotaExceeded(String message) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, QUOTA_EXCEEDED, message);
    }

    public static GatewayException rateLimited(String message) {
        return rateLimited(message, 0L);
    }

    public static GatewayException rateLimited(String message, long retryAfterSeconds) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, message, retryAfterSeconds);
    }

    public static GatewayException portExhausted(String message) {
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, PORT_EXHAUSTED, message);
    }

    /** 请求体/归档超过大小上限（413）。 */
    public static GatewayException payloadTooLarge(String message) {
        return new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, PAYLOAD_TOO_LARGE, message);
    }

    public static GatewayException docker(String message) {
        return new GatewayException(HttpStatus.INTERNAL_SERVER_ERROR, DOCKER_ERROR, message);
    }

    public static GatewayException dockerUnavailable(String message, Throwable cause) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, DOCKER_UNAVAILABLE, message, cause);
    }

    /** 目标 Docker 节点不可达/不存在/已饱和（区别于“容器不存在”的 404）。 */
    public static GatewayException nodeUnavailable(String message) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, NODE_UNAVAILABLE, message);
    }

    public static GatewayException nodeUnavailable(String message, Throwable cause) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, NODE_UNAVAILABLE, message, cause);
    }

    /**
     * Docker 后端在响应超时内没有返回数据（读超时）。
     *
     * <p>对外 HTTP 语义与 {@link #nodeUnavailable} 完全一致（503 + NODE_UNAVAILABLE，
     * 保持现有客户端契约不变），仅用于在服务端把“执行超时”和“节点不可达”区分开，
     * 供审计计数与压测报表使用。
     */
    public static final class BackendTimeoutException extends GatewayException {
        BackendTimeoutException(String message, Throwable cause) {
            super(HttpStatus.SERVICE_UNAVAILABLE, NODE_UNAVAILABLE, message, cause);
        }
    }

    public static GatewayException backendTimeout(String message, Throwable cause) {
        return new BackendTimeoutException(message, cause);
    }
}
