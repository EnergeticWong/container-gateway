package com.example.gateway.metrics;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * exporter 快照端点：{@code GET /internal/exporter/snapshot}，仅监听 loopback。
 *
 * <p>刻意不复用 3010 端口与 TokenAuthFilter：管理面数据不应出现在公网入口，
 * 也不应受业务鉴权/限流影响。绑定失败只告警，不影响网关启动。
 */
@Component
public class ExporterSnapshotServer {

    private static final Logger log = LoggerFactory.getLogger(ExporterSnapshotServer.class);
    private static final String PATH = "/internal/exporter/snapshot";

    private final ExporterSnapshotService snapshot;
    private final ObjectMapper mapper;
    private final com.example.gateway.config.GatewayProperties props;

    private HttpServer server;
    private ExecutorService executor;

    public ExporterSnapshotServer(ExporterSnapshotService snapshot, ObjectMapper mapper,
                                  com.example.gateway.config.GatewayProperties props) {
        this.snapshot = snapshot;
        this.mapper = mapper;
        this.props = props;
    }

    @PostConstruct
    void start() {
        int port = props.getExporterSnapshotPort();
        if (port <= 0) {
            log.info("exporter 快照端点已关闭（gateway.exporter-snapshot-port={}）", port);
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    if (!PATH.equals(path)) {
                        respond(exchange, 404, "text/plain; charset=utf-8", "not found");
                        return;
                    }
                    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        exchange.getResponseHeaders().set("Allow", "GET");
                        respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed");
                        return;
                    }
                    byte[] body;
                    try {
                        body = mapper.writeValueAsBytes(snapshot.build());
                    } catch (RuntimeException e) {
                        log.warn("构建 exporter 快照失败: {}", e.getMessage());
                        respond(exchange, 500, "text/plain; charset=utf-8", "snapshot error");
                        return;
                    }
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    respond(exchange, 200, "application/json; charset=utf-8", body);
                }
            });
            server.start();
            log.info("exporter 快照端点已启动: http://127.0.0.1:{}{}", port, PATH);
        } catch (IOException e) {
            log.warn("exporter 快照端点启动失败（端口 {}）：{}", port, e.getMessage());
            stop();
        }
    }

    @PreDestroy
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, String text) throws IOException {
        respond(exchange, status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
