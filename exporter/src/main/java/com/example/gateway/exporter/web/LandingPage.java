package com.example.gateway.exporter.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 根路径落地页：说明用途、上游地址与常用查询。 */
public final class LandingPage implements HttpHandler {

    private final String upstreamUrl;
    private final String version;

    public LandingPage(String upstreamUrl, String version) {
        this.upstreamUrl = upstreamUrl;
        this.version = version;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !"/index.html".equals(path)) {
                byte[] body = "not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            byte[] body = html().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private String html() {
        return """
                <!doctype html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <title>cgw-exporter</title>
                <style>body{font:14px/1.6 -apple-system,Segoe UI,Roboto,sans-serif;max-width:880px;margin:40px auto;padding:0 20px;color:#222}
                code{background:#f4f4f4;padding:1px 4px;border-radius:3px}th,td{text-align:left;padding:4px 10px 4px 0}</style>
                </head><body>
                <h1>cgw-exporter %s</h1>
                <p>把 <code>container-gateway</code> 的内部指标翻译成 Prometheus 格式。每次抓取同步向网关取一份只读快照，不做本地缓存。</p>
                <table>
                <tr><th>指标端点</th><td><a href="/metrics">/metrics</a></td></tr>
                <tr><th>健康检查</th><td><a href="/healthz">/healthz</a>（上游不可达返回 503）</td></tr>
                <tr><th>上游快照</th><td><code>%s</code></td></tr>
                </table>
                <h2>常用查询</h2>
                <pre>sum by (action) (rate(cgw_http_requests_total[5m]))
                histogram_quantile(0.99, sum by (le,action) (rate(cgw_http_request_duration_seconds_bucket[5m])))
                cgw_port_pool_used / cgw_port_pool_capacity
                cgw_node_active_streams / cgw_node_capacity
                rate(cgw_exec_timeouts_total[5m])</pre>
                <h2>说明</h2>
                <ul>
                <li>本 exporter 与被监控网关一对一，建议同机部署。</li>
                <li>不导出宿主资源、JVM 与容器级资源（分别由 node_exporter / JMX / dockerd 负责）。</li>
                <li>不含容器 ID、客户端标识、命令与 env，避免高基数与敏感信息。</li>
                </ul>
                </body></html>
                """.formatted(version, upstreamUrl);
    }
}
