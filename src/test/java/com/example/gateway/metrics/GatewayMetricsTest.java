package com.example.gateway.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayMetricsTest {

    @Test
    void auditBecomesRequestCountersAndHistogram() {
        GatewayMetrics m = new GatewayMetrics();
        m.recordAudit("create", "ok", null, 250, "local");
        m.recordAudit("create", "error", "QUOTA_EXCEEDED", 1000, "local");

        List<Map<String, Object>> requests = list(m.counterSnapshot().get("http_requests"));
        assertEquals(2, requests.size());
        Map<String, Object> ok = requests.stream()
                .filter(r -> "ok".equals(r.get("result"))).findFirst().orElseThrow();
        assertEquals(1L, ok.get("value"));
        assertEquals("local", ok.get("node"));

        List<Map<String, Object>> errors = list(m.counterSnapshot().get("http_errors"));
        assertEquals("QUOTA_EXCEEDED", errors.get(0).get("error_code"));

        List<Map<String, Object>> hist = list(
                m.histogramSnapshot().get("http_request_duration_seconds"));
        assertEquals(1, hist.size());
        assertEquals(2L, hist.get(0).get("count"));
        assertEquals(1.25, (double) hist.get(0).get("sum"), 1e-9);
        assertEquals(GatewayMetrics.HTTP_BUCKETS.length + 1,
                listRaw(hist.get(0).get("bucket_counts")).size());
    }

    @Test
    void unknownActionFallsBackToOther() {
        GatewayMetrics m = new GatewayMetrics();
        m.recordAudit("weird", "ok", null, 1, null);
        List<Map<String, Object>> requests = list(m.counterSnapshot().get("http_requests"));
        assertEquals("OTHER", requests.get(0).get("action"));
        assertEquals("", requests.get(0).get("node"));
    }

    @Test
    void authAndRateLimitAreSplitOut() {
        GatewayMetrics m = new GatewayMetrics();
        m.recordAudit("auth", "error", "UNAUTHORIZED", 1, null);
        m.recordAudit("rate-limit", "error", "RATE_LIMITED", 1, null);
        m.recordAudit("create", "error", "RATE_LIMITED", 1, null);

        // 鉴权/限流审计动作不进请求指标；create 被限流仍算一次请求
        assertEquals(1, list(m.counterSnapshot().get("http_requests")).size());
        assertEquals(1, list(m.counterSnapshot().get("auth_failures")).size());
        List<Map<String, Object>> limits = list(m.counterSnapshot().get("rate_limit_rejections"));
        assertEquals(2, limits.size());
        assertTrue(limits.stream().anyMatch(l -> "create".equals(l.get("scope"))));
        assertTrue(limits.stream().anyMatch(l -> "global".equals(l.get("scope"))));
    }

    @Test
    void execDerivedCountersAreSeparated() {
        GatewayMetrics m = new GatewayMetrics();
        m.exec("stream", "w1", "error", true, true, 0, 600_000);
        assertEquals(1L, list(m.counterSnapshot().get("exec_timeouts")).get(0).get("value"));
        assertEquals(1L, list(m.counterSnapshot().get("exec_truncated")).get(0).get("value"));
        assertEquals(0, list(m.counterSnapshot().get("exec_nonzero")).size());
    }

    @Test
    void jobFailureIsCounted() {
        GatewayMetrics m = new GatewayMetrics();
        m.job("ttl_reaper", false, 30);
        Map<String, Object> job = m.jobSnapshot().get(0);
        assertEquals(1L, job.get("runs"));
        assertEquals(1L, job.get("failures"));
        assertEquals(0L, job.get("last_success_ms"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> listRaw(Object value) {
        return (List<Long>) value;
    }
}
