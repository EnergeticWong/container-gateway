package com.example.gateway.exporter;

import com.example.gateway.exporter.client.SnapshotClient;
import com.example.gateway.exporter.collect.CgwCollector;
import com.example.gateway.exporter.config.ExporterConfig;
import com.example.gateway.exporter.web.HealthHandler;
import com.example.gateway.exporter.web.LandingPage;
import com.example.gateway.exporter.web.MetricsHandler;
import com.sun.net.httpserver.HttpServer;
import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** 入口：加载配置、装配 collector 与 HTTP 服务。 */
public final class Main {

    private static final Logger LOG = Logger.getLogger("cgw-exporter");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(option(args, "--config.file", "config.yml"));
        ExporterConfig config = ExporterConfig.load(configPath);
        String listen = option(args, "--listen", null);
        if (listen != null && !listen.isBlank()) {
            config.listen = listen;
        }
        config.validate();

        SnapshotClient client = new SnapshotClient(URI.create(config.upstreamUrl),
                config.schema, config.timeoutMs);
        CgwCollector collector = new CgwCollector(client, config.labels);

        if (hasFlag(args, "--check")) {
            System.exit(check(collector, config));
        }

        // 启动时先取一次，保证 /healthz 在首个抓取前就有确定状态
        collector.collect(config.timeoutMs);
        LOG.info("上游初始探测: " + (collector.self().gatewayUp() ? "UP" : "DOWN")
                + " (" + config.upstreamUrl + ")");

        HttpServer server = HttpServer.create(config.listenAddress(), 64);
        server.createContext("/metrics", new MetricsHandler(collector, config.timeoutMs));
        server.createContext("/healthz", new HealthHandler(collector));
        server.createContext("/", new LandingPage(config.upstreamUrl, CgwCollector.VERSION));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "cgw-exporter-shutdown"));
        server.start();
        LOG.info("cgw-exporter " + CgwCollector.VERSION + " 已启动: http://" + config.listen
                + "/metrics, upstream=" + config.upstreamUrl);
    }

    /** --check：抓一次并打印，用于部署前验证。上游不可达返回退出码 1。 */
    private static int check(CgwCollector collector, ExporterConfig config) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrometheusTextFormatWriter.create().write(out, collector.collect(config.timeoutMs),
                    EscapingScheme.UNDERSCORE_ESCAPING);
            System.out.print(out.toString(StandardCharsets.UTF_8));
            return collector.self().gatewayUp() ? 0 : 1;
        } catch (Exception e) {
            System.err.println("check failed: " + e.getMessage());
            return 2;
        }
    }

    private static String option(String[] args, String name, String fallback) {
        for (String arg : args) {
            if (arg.equals(name) && !arg.contains("=")) {
                continue;
            }
            if (arg.startsWith(name + "=")) {
                return arg.substring(name.length() + 1);
            }
        }
        return fallback;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String arg : args) {
            if (arg.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
