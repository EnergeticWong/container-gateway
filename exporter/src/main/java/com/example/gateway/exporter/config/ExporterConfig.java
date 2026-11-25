package com.example.gateway.exporter.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** YAML 配置；缺省值保证开箱即用。 */
public final class ExporterConfig {

    public static final String DEFAULT_LISTEN = "0.0.0.0:18092";

    public String listen = DEFAULT_LISTEN;
    public String upstreamUrl = "http://127.0.0.1:18091/internal/exporter/snapshot";
    public int timeoutMs = 8000;
    public String schema = "cgw.exporter.snapshot/v1";
    public Map<String, String> labels = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public static ExporterConfig load(Path path) throws IOException {
        ExporterConfig config = new ExporterConfig();
        if (path == null || !Files.exists(path)) {
            return config;
        }
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                return config;
            }
            if (root.get("listen") != null) {
                config.listen = String.valueOf(root.get("listen"));
            }
            Object upstream = root.get("upstream");
            if (upstream instanceof Map<?, ?> up) {
                if (up.get("url") != null) {
                    config.upstreamUrl = String.valueOf(up.get("url"));
                }
                if (up.get("timeout_ms") != null) {
                    config.timeoutMs = Integer.parseInt(String.valueOf(up.get("timeout_ms")));
                }
                if (up.get("schema") != null) {
                    config.schema = String.valueOf(up.get("schema"));
                }
            }
            Object labels = root.get("labels");
            if (labels instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    config.labels.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return config;
    }

    public void validate() {
        if (timeoutMs < 100 || timeoutMs > 30_000) {
            throw new IllegalArgumentException("upstream.timeout_ms 必须在 [100, 30000]");
        }
        URI.create(upstreamUrl);
        listenAddress();
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("upstream.schema 不能为空");
        }
    }

    public InetSocketAddress listenAddress() {
        int idx = listen.lastIndexOf(':');
        if (idx <= 0) {
            throw new IllegalArgumentException("listen 应形如 host:port，实际: " + listen);
        }
        String host = listen.substring(0, idx);
        int port = Integer.parseInt(listen.substring(idx + 1));
        return new InetSocketAddress(host, port);
    }
}
