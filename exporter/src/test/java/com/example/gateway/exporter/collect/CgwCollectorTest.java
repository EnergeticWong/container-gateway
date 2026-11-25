package com.example.gateway.exporter.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.prometheus.metrics.config.EscapingScheme;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CgwCollectorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void rendersAllFamiliesFromSnapshot() throws Exception {
        String body = render(fixture(), Map.of("environment", "prod"));

        assertTrue(body.contains("# TYPE cgw_http_requests_total counter"));
        assertTrue(body.contains("cgw_http_requests_total{"), body);
        assertTrue(body.contains("123.0"));
        assertTrue(body.contains("environment=\"prod\""));

        assertTrue(body.contains("# TYPE cgw_node_up gauge"));
        assertTrue(lineFor(body, "cgw_node_up", "node=\"w1\"").endsWith(" 0.0"));
        assertTrue(lineFor(body, "cgw_node_assigned", "node=\"local\"").endsWith(" 5.0"));

        assertTrue(body.contains("cgw_http_request_duration_seconds_bucket{"));
        assertTrue(body.contains("le=\"+Inf\""));
        assertEquals(20.0, valueOf(lineFor(body, "cgw_http_request_duration_seconds_count",
                "action=\"create\"")), 1e-9);

        assertTrue(body.contains("# TYPE cgw_file_transfer_bytes_total counter"), body);
        assertTrue(body.contains("cgw_exec_timeouts_total{"));
        assertEquals(1762399990.0, valueOf(lineFor(body, "cgw_job_last_success_timestamp_seconds",
                "task=\"ttl_reaper\"")), 1.0);
        assertTrue(lineFor(body, "cgw_gateway_up", "").endsWith(" 1.0"));
        assertTrue(body.contains("cgw_build_info{") && body.contains("version=\"1.0.0\""));
    }

    @Test
    void upstreamFailureEmitsOnlySelfMetricsAndZeros() throws Exception {
        CgwCollector collector = new CgwCollector(budget -> {
            throw new IOException("connection refused");
        }, Map.of());
        String body = renderCollector(collector);

        assertTrue(lineFor(body, "cgw_gateway_up", "").endsWith(" 0.0"));
        assertTrue(body.contains("cgw_exporter_scrapes_total{result=\"error\"}"));
        assertFalse(body.contains("cgw_node_up"));
        assertEquals(22, body.lines().filter(l -> l.startsWith("cgw_http_requests_total{")).count());
    }

    @Test
    void concurrentScrapesDoNotInterfere() throws Exception {
        CgwCollector collector = new CgwCollector(budget -> fixture(), Map.of());
        Thread[] threads = new Thread[8];
        boolean[] ok = new boolean[threads.length];
        for (int i = 0; i < threads.length; i++) {
            int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    ok[idx] = renderCollector(collector).contains("cgw_http_requests_total{");
                } catch (Exception e) {
                    ok[idx] = false;
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (boolean b : ok) {
            assertTrue(b);
        }
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = CgwCollectorTest.class.getResourceAsStream("/snapshot.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少 snapshot.json");
            }
            return MAPPER.readTree(in);
        }
    }

    private static String render(JsonNode node, Map<String, String> labels) throws Exception {
        return renderCollector(new CgwCollector(budget -> node, labels));
    }

    private static String renderCollector(CgwCollector collector) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrometheusTextFormatWriter.create().write(out, collector.collect(1000),
                EscapingScheme.UNDERSCORE_ESCAPING);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String lineFor(String body, String metric, String labelFragment) {
        return body.lines()
                .filter(l -> l.startsWith(metric + "{") || l.startsWith(metric + " "))
                .filter(l -> l.contains(labelFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 " + metric + " " + labelFragment));
    }

    private static double valueOf(String line) {
        int idx = line.lastIndexOf(' ');
        return Double.parseDouble(line.substring(idx + 1));
    }
}
