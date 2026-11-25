package com.example.gateway.metrics;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 网关进程内的指标聚合器。
 *
 * <p>只做原子累加，不做 IO、不打日志；读侧由 {@code ExporterSnapshotService} 在抓取时
 * 一次性导出内存快照。直方图按固定桶累计，桶边界随快照下发，避免两侧漂移。
 */
@Component
public class GatewayMetrics {

    /** 请求面耗时桶（秒）。 */
    public static final double[] HTTP_BUCKETS =
            {0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};
    /** Docker 调用耗时桶（秒）。 */
    public static final double[] DOCKER_BUCKETS =
            {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120};
    /** exec 耗时桶（秒）。 */
    public static final double[] EXEC_BUCKETS =
            {0.1, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300, 600};
    /** 反向代理耗时桶（秒）。 */
    public static final double[] PROXY_BUCKETS =
            {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120};
    /** 节点探测耗时桶（秒）。 */
    public static final double[] PROBE_BUCKETS =
            {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5};
    /** 后台任务耗时桶（秒）。 */
    public static final double[] JOB_BUCKETS =
            {0.01, 0.1, 0.5, 1, 5, 10, 30, 60};

    private static final String SEP = "\u0001";
    private static final String EMPTY = "";
    private static final String OTHER = "OTHER";

    /** 已知审计动作，未知值统一归入 OTHER，防止基数失控。 */
    private static final List<String> ACTIONS = List.of(
            "create", "get", "list", "start", "stop", "restart", "remove",
            "destroy", "exec", "logs", "ttl-reap");

    private final Map<String, LongAdder> httpRequests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> httpErrors = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> authFailures = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> rateLimitRejections = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> quotaRejections = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> dockerCalls = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> execCounters = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> execTimeouts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> execTruncated = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> execNonzero = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> proxyRequests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> proxyErrors = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> fileTransfers = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> fileBytes = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> fileRejections = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> portReclaimed = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> nodeProbeFailures = new ConcurrentHashMap<>();

    private final Map<String, Hist> httpDuration = new ConcurrentHashMap<>();
    private final Map<String, Hist> dockerDuration = new ConcurrentHashMap<>();
    private final Map<String, Hist> execDuration = new ConcurrentHashMap<>();
    private final Map<String, Hist> proxyDuration = new ConcurrentHashMap<>();
    private final Map<String, Hist> probeDuration = new ConcurrentHashMap<>();
    private final Map<String, Hist> jobDuration = new ConcurrentHashMap<>();

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();
    private final LongAdder auditWriteFailures = new LongAdder();
    private final LongAdder managedRefreshFailures = new LongAdder();

    // ------------------------------------------------------------------ 写入

    /** 审计事件统一入口：只记录已存在的动作，鉴权/限流走独立计数器。 */
    public void recordAudit(String action, String result, String errorCode, long durationMs, String node) {
        if (action == null) {
            return;
        }
        if ("auth".equals(action)) {
            counter(authFailures, "unauthorized").increment();
            return;
        }
        if ("rate-limit".equals(action)) {
            counter(rateLimitRejections, "global").increment();
            return;
        }
        if ("create".equals(action) && "RATE_LIMITED".equals(errorCode)) {
            counter(rateLimitRejections, "create").increment();
        }
        String act = ACTIONS.contains(action) ? action : OTHER;
        String res = result == null ? OTHER : result;
        String n = node == null ? EMPTY : node;
        counter(httpRequests, key(act, res, n)).increment();
        hist(httpDuration, key(act, n), HTTP_BUCKETS).observe(Math.max(0L, durationMs) / 1000.0);
        if ("error".equals(res)) {
            counter(httpErrors, key(act, errorCode == null || errorCode.isBlank() ? "INTERNAL" : errorCode))
                    .increment();
        }
    }

    public void authFailure() {
        counter(authFailures, "unauthorized").increment();
    }

    public void rateLimited(String scope) {
        counter(rateLimitRejections, scope == null ? "global" : scope).increment();
    }

    public void quotaRejected(String reason) {
        counter(quotaRejections, reason == null ? OTHER : reason).increment();
    }

    public void dockerCall(String op, String node, String result, String reason, long durationMs) {
        String o = op == null ? OTHER : op;
        String n = node == null ? EMPTY : node;
        counter(dockerCalls, key(o, n, result == null ? OTHER : result,
                reason == null ? EMPTY : reason)).increment();
        hist(dockerDuration, key(o, n), DOCKER_BUCKETS).observe(Math.max(0L, durationMs) / 1000.0);
    }

    public void exec(String mode, String node, String result, boolean timeout, boolean truncated,
                     Integer exitCode, long durationMs) {
        String m = mode == null ? OTHER : mode;
        String n = node == null ? EMPTY : node;
        counter(execCounters, key(m, n, result == null ? OTHER : result)).increment();
        hist(execDuration, key(m, n), EXEC_BUCKETS).observe(Math.max(0L, durationMs) / 1000.0);
        if (timeout) {
            counter(execTimeouts, key(m, n)).increment();
        }
        if (truncated) {
            counter(execTruncated, key(m, n)).increment();
        }
        if (exitCode != null && exitCode > 0) {
            counter(execNonzero, key(m, n)).increment();
        }
    }

    public void proxy(String method, String statusClass, long durationMs) {
        counter(proxyRequests, key(method == null ? OTHER : method,
                statusClass == null ? OTHER : statusClass)).increment();
        hist(proxyDuration, "all", PROXY_BUCKETS).observe(Math.max(0L, durationMs) / 1000.0);
    }

    public void proxyUpstreamError(String reason) {
        counter(proxyErrors, reason == null ? OTHER : reason).increment();
    }

    public void file(String direction, String result, String reason, long bytes) {
        String d = direction == null ? OTHER : direction;
        counter(fileTransfers, key(d, result == null ? OTHER : result)).increment();
        if (bytes > 0) {
            counter(fileBytes, d).add(bytes);
        }
        if (reason != null && !reason.isBlank()) {
            counter(fileRejections, reason).increment();
        }
    }

    public void portReclaimed(String node, int count) {
        if (count > 0) {
            counter(portReclaimed, node == null ? EMPTY : node).add(count);
        }
    }

    public void nodeProbe(String node, boolean up, long durationMs, String reason) {
        hist(probeDuration, node == null ? EMPTY : node, PROBE_BUCKETS)
                .observe(Math.max(0L, durationMs) / 1000.0);
        if (!up) {
            counter(nodeProbeFailures, key(node == null ? EMPTY : node,
                    reason == null || reason.isBlank() ? "unknown" : reason)).increment();
        }
    }

    public void job(String name, boolean success, long durationMs) {
        JobState state = jobs.computeIfAbsent(name, k -> new JobState());
        state.runs.increment();
        if (!success) {
            state.failures.increment();
        }
        hist(jobDuration, name, JOB_BUCKETS).observe(Math.max(0L, durationMs) / 1000.0);
        state.lastDurationMs = Math.max(0L, durationMs);
        if (success) {
            state.lastSuccessMs = System.currentTimeMillis();
        }
    }

    public void auditWriteFailure() {
        auditWriteFailures.increment();
    }

    public void managedRefreshFailure() {
        managedRefreshFailures.increment();
    }

    // ------------------------------------------------------------------ 读取快照

    /** 计数器快照：{family -> [{labels..., value}]}。 */
    public Map<String, Object> counterSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("http_requests", dump(httpRequests, "action", "result", "node"));
        out.put("http_errors", dump(httpErrors, "action", "error_code"));
        out.put("auth_failures", dump(authFailures, "reason"));
        out.put("rate_limit_rejections", dump(rateLimitRejections, "scope"));
        out.put("quota_rejections", dump(quotaRejections, "reason"));
        out.put("docker_calls", dump(dockerCalls, "op", "node", "result", "reason"));
        out.put("exec", dump(execCounters, "mode", "node", "result"));
        out.put("exec_timeouts", dump(execTimeouts, "mode", "node"));
        out.put("exec_truncated", dump(execTruncated, "mode", "node"));
        out.put("exec_nonzero", dump(execNonzero, "mode", "node"));
        out.put("proxy_requests", dump(proxyRequests, "method", "status_class"));
        out.put("proxy_errors", dump(proxyErrors, "reason"));
        out.put("file", dump(fileTransfers, "direction", "result"));
        out.put("file_bytes", dump(fileBytes, "direction"));
        out.put("file_rejections", dump(fileRejections, "reason"));
        out.put("port_reclaimed", dump(portReclaimed, "node"));
        out.put("node_probe_failures", dump(nodeProbeFailures, "node", "reason"));
        out.put("job_runs", jobRuns());
        return out;
    }

    /** 任务执行结果计数，从任务状态派生，避免额外维护一份计数器。 */
    private List<Map<String, Object>> jobRuns() {
        List<Map<String, Object>> out = new ArrayList<>(jobs.size() * 2);
        jobs.forEach((name, state) -> {
            long runs = state.runs.sum();
            long failures = state.failures.sum();
            out.add(Map.of("job", name, "result", "ok", "value", Math.max(0, runs - failures)));
            out.add(Map.of("job", name, "result", "error", "value", failures));
        });
        return out;
    }

    /** 直方图快照：{family -> [{labels..., bounds, bucket_counts, count, sum}]}。 */
    public Map<String, Object> histogramSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("http_request_duration_seconds", dumpHist(httpDuration, "action", "node"));
        out.put("docker_call_duration_seconds", dumpHist(dockerDuration, "op", "node"));
        out.put("exec_duration_seconds", dumpHist(execDuration, "mode", "node"));
        out.put("proxy_duration_seconds", dumpHist(proxyDuration));
        out.put("node_probe_duration_seconds", dumpHist(probeDuration, "node"));
        out.put("job_duration_seconds", dumpHist(jobDuration, "job"));
        return out;
    }

    /** 任务状态快照。 */
    public List<Map<String, Object>> jobSnapshot() {
        List<Map<String, Object>> out = new ArrayList<>(jobs.size());
        jobs.forEach((name, state) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("runs", state.runs.sum());
            m.put("failures", state.failures.sum());
            m.put("last_success_ms", state.lastSuccessMs);
            m.put("last_duration_seconds", state.lastDurationMs / 1000.0);
            out.add(m);
        });
        return out;
    }

    public long auditWriteFailures() {
        return auditWriteFailures.sum();
    }

    public long managedRefreshFailures() {
        return managedRefreshFailures.sum();
    }

    // ------------------------------------------------------------------ 内部

    private static List<Map<String, Object>> dump(Map<String, LongAdder> map, String... names) {
        List<Map<String, Object>> out = new ArrayList<>(map.size());
        for (Map.Entry<String, LongAdder> e : map.entrySet()) {
            String[] parts = e.getKey().split(SEP, -1);
            Map<String, Object> item = new LinkedHashMap<>();
            for (int i = 0; i < names.length && i < parts.length; i++) {
                item.put(names[i], parts[i]);
            }
            item.put("value", e.getValue().sum());
            out.add(item);
        }
        return out;
    }

    private static List<Map<String, Object>> dumpHist(Map<String, Hist> map, String... names) {
        List<Map<String, Object>> out = new ArrayList<>(map.size());
        for (Map.Entry<String, Hist> e : map.entrySet()) {
            String[] parts = e.getKey().split(SEP, -1);
            Map<String, Object> item = new LinkedHashMap<>();
            for (int i = 0; i < names.length && i < parts.length; i++) {
                item.put(names[i], parts[i]);
            }
            Hist h = e.getValue();
            List<Double> bounds = new ArrayList<>(h.bounds.length);
            for (double b : h.bounds) {
                bounds.add(b);
            }
            List<Long> counts = new ArrayList<>(h.counts.length);
            for (LongAdder c : h.counts) {
                counts.add(c.sum());
            }
            item.put("bounds", bounds);
            item.put("bucket_counts", counts);
            item.put("count", h.count.sum());
            item.put("sum", h.sum.sum());
            out.add(item);
        }
        return out;
    }

    private static LongAdder counter(Map<String, LongAdder> map, String key) {
        return map.computeIfAbsent(key, k -> new LongAdder());
    }

    private static Hist hist(Map<String, Hist> map, String key, double[] bounds) {
        return map.computeIfAbsent(key, k -> new Hist(bounds));
    }

    private static String key(String... parts) {
        return String.join(SEP, parts);
    }

    private static final class Hist {
        final double[] bounds;
        final LongAdder[] counts;
        final LongAdder count = new LongAdder();
        final DoubleAdder sum = new DoubleAdder();

        Hist(double[] bounds) {
            this.bounds = bounds;
            this.counts = new LongAdder[bounds.length + 1];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = new LongAdder();
            }
        }

        void observe(double value) {
            int i = 0;
            while (i < bounds.length && value > bounds[i]) {
                i++;
            }
            counts[i].increment();
            count.increment();
            sum.add(value);
        }
    }

    private static final class JobState {
        final LongAdder runs = new LongAdder();
        final LongAdder failures = new LongAdder();
        volatile long lastSuccessMs;
        volatile long lastDurationMs;
    }
}
