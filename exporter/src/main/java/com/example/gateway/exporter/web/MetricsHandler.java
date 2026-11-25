package com.example.gateway.exporter.web;

import com.example.gateway.exporter.collect.CgwCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** /metrics：按抓取预算同步拉取一次上游，生成 Prometheus 文本格式。 */
public final class MetricsHandler implements HttpHandler {

    private static final PrometheusTextFormatWriter WRITER = PrometheusTextFormatWriter.create();

    private final CgwCollector collector;
    private final int defaultTimeoutMs;

    public MetricsHandler(CgwCollector collector, int defaultTimeoutMs) {
        this.collector = collector;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, "text/plain; charset=utf-8",
                        "method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            long budgetMs = budget(exchange);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 * 1024);
            try {
                WRITER.write(buffer, collector.collect(budgetMs), EscapingScheme.UNDERSCORE_ESCAPING);
            } catch (RuntimeException e) {
                respond(exchange, 500, "text/plain; charset=utf-8",
                        ("collect error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            respond(exchange, 200, WRITER.getContentType(), buffer.toByteArray());
        }
    }

    /** 遵守 Prometheus 的抓取超时头，给上游留 500ms 余量。 */
    private long budget(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Prometheus-Scrape-Timeout-Seconds");
        if (header == null || header.isBlank()) {
            return defaultTimeoutMs;
        }
        try {
            long headerMs = (long) Math.ceil(Double.parseDouble(header.trim()) * 1000);
            return Math.max(200, Math.min(defaultTimeoutMs, headerMs - 500));
        } catch (NumberFormatException e) {
            return defaultTimeoutMs;
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
