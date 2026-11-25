package com.example.gateway.exporter.web;

import com.example.gateway.exporter.collect.CgwCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** /healthz：只反映最近一次抓取的上游可用性，不主动探测。 */
public final class HealthHandler implements HttpHandler {

    private final CgwCollector collector;

    public HealthHandler(CgwCollector collector) {
        this.collector = collector;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean up = collector.self().gatewayUp();
            String body = "{\"status\":\"" + (up ? "UP" : "DOWN") + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(up ? 200 : 503, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
