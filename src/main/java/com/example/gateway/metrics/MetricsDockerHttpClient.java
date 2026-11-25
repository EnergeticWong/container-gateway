package com.example.gateway.metrics;

import com.github.dockerjava.transport.DockerHttpClient;

import java.io.IOException;

/**
 * 传输层拦截器：包住 docker-java 的 {@link DockerHttpClient}，按 URL 归类操作，
 * 记录每次调用耗时与结果。
 *
 * <p>放在这一层而不是逐个 {@code DockerOps} 方法埋点，可以无遗漏覆盖业务、exec、
 * 回收任务等所有 Docker 调用，且不侵入业务代码。
 */
public final class MetricsDockerHttpClient implements DockerHttpClient {

    private final DockerHttpClient delegate;
    private final String nodeId;
    private final GatewayMetrics metrics;

    public MetricsDockerHttpClient(DockerHttpClient delegate, String nodeId, GatewayMetrics metrics) {
        this.delegate = delegate;
        this.nodeId = nodeId;
        this.metrics = metrics;
    }

    @Override
    public Response execute(Request request) {
        long startNanos = System.nanoTime();
        String op = opOf(request == null ? null : request.path());
        try {
            Response response = delegate.execute(request);
            int status = response.getStatusCode();
            metrics.dockerCall(op, nodeId, status >= 400 ? "error" : "ok",
                    status >= 400 ? "api" : null, elapsedMs(startNanos));
            return response;
        } catch (RuntimeException e) {
            metrics.dockerCall(op, nodeId, "error", classify(e), elapsedMs(startNanos));
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /** 把 Docker API 路径归到有限的操作名，未知归 other。 */
    static String opOf(String path) {
        if (path == null || path.isEmpty()) {
            return "other";
        }
        String p = path;
        int query = p.indexOf('?');
        if (query >= 0) {
            p = p.substring(0, query);
        }
        // 去掉 /v1.xx 版本前缀
        if (p.startsWith("/v")) {
            int slash = p.indexOf('/', 1);
            if (slash > 0) {
                p = p.substring(slash);
            }
        }
        if (p.contains("/exec")) {
            return p.endsWith("/start") ? "exec" : "exec.create";
        }
        if (p.contains("/containers/create")) {
            return "create";
        }
        if (p.endsWith("/start")) {
            return "start";
        }
        if (p.endsWith("/stop")) {
            return "stop";
        }
        if (p.endsWith("/restart")) {
            return "restart";
        }
        if (p.endsWith("/kill") || p.endsWith("/wait") || p.endsWith("/attach")) {
            return p.substring(p.lastIndexOf('/') + 1);
        }
        if (p.contains("/archive")) {
            return "archive";
        }
        if (p.endsWith("/logs")) {
            return "logs";
        }
        if (p.startsWith("/containers/json")) {
            return "list";
        }
        if (p.startsWith("/containers/") && p.endsWith("/json")) {
            return "inspect";
        }
        if (p.startsWith("/containers/")) {
            return "remove";
        }
        if (p.startsWith("/images/create")) {
            return "pull";
        }
        if (p.startsWith("/images/") && p.endsWith("/json")) {
            return "image.inspect";
        }
        if (p.startsWith("/volumes")) {
            return "volume";
        }
        if (p.equals("/info")) {
            return "info";
        }
        if (p.equals("/version")) {
            return "version";
        }
        return "other";
    }

    static String classify(Throwable e) {
        if (e == null) {
            return "api";
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        String type = e.getClass().getName().toLowerCase();
        if (type.contains("timeout") || msg.contains("timed out") || msg.contains("timeout")) {
            return "timeout";
        }
        if (type.contains("connect") || type.contains("ssl") || type.contains("handshake")
                || msg.contains("connection refused") || msg.contains("connect failed")
                || msg.contains("no such host") || msg.contains("broken pipe")) {
            return "transport";
        }
        return "api";
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
