package com.example.gateway.exporter.collect;

import com.example.gateway.exporter.client.SnapshotSource;
import com.fasterxml.jackson.databind.JsonNode;
import io.prometheus.metrics.model.snapshots.ClassicHistogramBuckets;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次抓取构建全部指标：先取一次上游快照，再从同一份快照派生所有 family。
 *
 * <p>不持有领域指标的全局状态；自监控计数除外（跨抓取累计）。
 */
public final class CgwCollector {

    public static final String VERSION = "1.0.0";

    /** 固定动作集合，上游故障时预初始化 0 值，避免 Dashboard 断层。 */
    private static final String[] ACTIONS = {
            "create", "get", "list", "start", "stop", "restart", "remove",
            "destroy", "exec", "logs", "ttl-reap"};
    private static final String[] RESULTS = {"ok", "error"};

    private final SnapshotSource source;
    private final SelfMetrics self = new SelfMetrics();
    private final Map<String, String> globalLabels;

    public CgwCollector(SnapshotSource source, Map<String, String> globalLabels) {
        this.source = source;
        this.globalLabels = sanitizeLabels(globalLabels);
    }

    public MetricSnapshots collect(long budgetMs) {
        long started = System.nanoTime();
        JsonNode snapshot = null;
        try {
            snapshot = source.fetch(budgetMs);
        } catch (Exception ignored) {
            // 上游失败：只输出自监控与预初始化的 0 值
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        self.record(snapshot != null, durationMs);

        List<MetricSnapshot> out = new ArrayList<>();
        String gatewayVersion = "dev";
        if (snapshot != null) {
            gatewayVersion = snapshot.path("gateway").path("version").asText("dev");
            self.gateway(true, ageSeconds(snapshot));
            appendDomain(out, snapshot);
        } else {
            self.gateway(false, -1);
            appendEmptyActionSeries(out);
        }
        appendSelf(out, gatewayVersion);
        return new MetricSnapshots(out);
    }

    public SelfMetrics self() {
        return self;
    }

    // ------------------------------------------------------------------ 领域指标

    private void appendDomain(List<MetricSnapshot> out, JsonNode snapshot) {
        JsonNode gateway = snapshot.path("gateway");
        List<MetricSnapshot> list = new ArrayList<>();
        gauge(list, "cgw_ready", "网关是否存在健康 Docker 节点（1=就绪）。",
                Map.of(), gateway.path("ready").asBoolean() ? 1 : 0);
        gauge(list, "cgw_containers_managed", "网关托管的容器总数（含已停止）。",
                Map.of(), gateway.path("managed_containers").asLong());
        gauge(list, "cgw_index_containers", "容器→节点索引中的条目数。",
                Map.of(), gateway.path("index_containers").asLong());
        counter(list, "cgw_audit_write_failures", "审计日志写盘失败次数（此后仅输出到应用日志）。",
                Map.of(), gateway.path("audit_write_failures").asLong());
        counter(list, "cgw_managed_refresh_failures", "托管容器计数刷新失败次数（保留旧值）。",
                Map.of(), gateway.path("managed_refresh_failures").asLong());
        out.addAll(list);

        JsonNode nodes = snapshot.path("nodes");
        if (nodes.isArray() && !nodes.isEmpty()) {
            appendNodeGauges(out, nodes);
        }

        JsonNode jobs = snapshot.path("jobs");
        if (jobs.isArray() && !jobs.isEmpty()) {
            GaugeSnapshot.Builder builder = GaugeSnapshot.builder()
                    .name("cgw_job_last_success_timestamp_seconds")
                    .help("后台任务最近一次成功完成的时间戳（Unix 秒；0=从未成功）。");
            for (JsonNode job : jobs) {
                builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels(Map.of("task", job.path("name").asText(""))))
                        .value(job.path("last_success_ms").asLong() / 1000.0)
                        .build());
            }
            out.add(builder.build());
        }

        JsonNode counters = snapshot.path("counters");
        appendCounter(out, counters.path("http_requests"), "cgw_http_requests",
                "网关 API 请求总数（按审计动作与结果）。",
                "action", "result", "node");
        appendCounter(out, counters.path("http_errors"), "cgw_http_request_errors",
                "网关 API 失败请求数，按错误码分类。",
                "action", "error_code");
        appendCounter(out, counters.path("auth_failures"), "cgw_auth_failures",
                "鉴权失败次数（不含 token 与客户端标识）。", "reason");
        appendCounter(out, counters.path("rate_limit_rejections"), "cgw_rate_limit_rejections",
                "限流拒绝次数。", "scope");
        appendCounter(out, counters.path("quota_rejections"), "cgw_quota_rejections",
                "配额/容量拒绝次数。", "reason");
        appendCounter(out, counters.path("docker_calls"), "cgw_docker_calls",
                "对 Docker 守护进程的调用次数。", "op", "node", "result", "reason");
        appendCounter(out, counters.path("exec"), "cgw_exec",
                "exec 调用次数（sync/stream）。", "mode", "node", "result");
        appendCounter(out, counters.path("exec_timeouts"), "cgw_exec_timeouts",
                "exec 超时次数。", "mode", "node");
        appendCounter(out, counters.path("exec_truncated"), "cgw_exec_output_truncated",
                "exec 输出超过上限被截断的次数。", "mode", "node");
        appendCounter(out, counters.path("exec_nonzero"), "cgw_exec_nonzero_exits",
                "exec 退出码非零的次数。", "mode", "node");
        appendCounter(out, counters.path("proxy_requests"), "cgw_proxy_requests",
                "容器反向代理请求数。", "method", "status_class");
        appendCounter(out, counters.path("proxy_errors"), "cgw_proxy_upstream_errors",
                "反向代理上游连接失败次数。", "reason");
        appendCounter(out, counters.path("file"), "cgw_file_transfers",
                "文件收发次数。", "direction", "result");
        appendCounter(out, counters.path("file_bytes"), "cgw_file_transfer_bytes",
                "文件收发字节数。", "direction");
        appendCounter(out, counters.path("file_rejections"), "cgw_file_rejections",
                "文件收发被拒绝次数。", "reason");
        appendCounter(out, counters.path("port_reclaimed"), "cgw_port_reclaimed",
                "端口池对账回收的端口数。", "node");
        appendCounter(out, counters.path("node_probe_failures"), "cgw_node_probe_failures",
                "节点探测失败次数。", "node", "reason");
        appendJobRuns(out, counters.path("job_runs"));

        JsonNode histograms = snapshot.path("histograms");
        appendHistogram(out, histograms.path("http_request_duration_seconds"),
                "cgw_http_request_duration_seconds", "网关 API 端到端耗时（秒）。",
                "action", "node");
        appendHistogram(out, histograms.path("docker_call_duration_seconds"),
                "cgw_docker_call_duration_seconds", "Docker 调用耗时（秒）。",
                "op", "node");
        appendHistogram(out, histograms.path("exec_duration_seconds"),
                "cgw_exec_duration_seconds", "exec 耗时（秒）。", "mode", "node");
        appendHistogram(out, histograms.path("proxy_duration_seconds"),
                "cgw_proxy_duration_seconds", "反向代理耗时（秒）。");
        appendHistogram(out, histograms.path("node_probe_duration_seconds"),
                "cgw_node_probe_duration_seconds", "节点探测耗时（秒）。", "node");
        appendJobDuration(out, histograms.path("job_duration_seconds"));
    }

    /** 节点级 Gauge：每个指标一个 family、每个节点一个 data point。 */
    private void appendNodeGauges(List<MetricSnapshot> out, JsonNode nodes) {
        record Def(String name, String help, java.util.function.ToDoubleFunction<JsonNode> value) {
        }
        List<Def> defs = List.of(
                new Def("cgw_node_up", "最近一次节点探测是否成功（1=UP）。",
                        n -> n.path("up").asBoolean() ? 1 : 0),
                new Def("cgw_node_assigned", "节点上运行中的托管容器数。",
                        n -> n.path("assigned").asLong()),
                new Def("cgw_node_inflight", "节点上创建中的占位数。",
                        n -> n.path("inflight").asLong()),
                new Def("cgw_node_capacity", "节点并发上限（gateway.node-max-tasks）。",
                        n -> n.path("capacity").asLong()),
                new Def("cgw_node_docker_running", "节点上运行中的容器总数（含非托管）。",
                        n -> n.path("docker_running").asLong()),
                new Def("cgw_node_active_streams", "节点当前 SSE 流数量。",
                        n -> n.path("active_streams").asLong()),
                new Def("cgw_node_stream_capacity", "节点 SSE 流并发上限。",
                        n -> n.path("stream_capacity").asLong()),
                new Def("cgw_port_pool_used", "节点已分配的宿主端口数。",
                        n -> n.path("port_used").asLong()),
                new Def("cgw_port_pool_capacity", "节点端口池容量。",
                        n -> n.path("port_capacity").asLong()));
        for (Def def : defs) {
            GaugeSnapshot.Builder builder = GaugeSnapshot.builder().name(def.name()).help(def.help());
            for (JsonNode node : nodes) {
                builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels(Map.of("node", node.path("id").asText(""))))
                        .value(def.value().applyAsDouble(node))
                        .build());
            }
            out.add(builder.build());
        }
    }

    // ------------------------------------------------------------------ 自监控

    private void appendSelf(List<MetricSnapshot> out, String gatewayVersion) {
        String result = self.gatewayUp() ? "ok" : "error";
        counter(out, "cgw_exporter_scrapes", "Exporter 抓取次数。",
                Map.of("result", result), self.count());
        counter(out, "cgw_exporter_upstream_requests", "Exporter 向上游发起的请求次数。",
                Map.of("result", result), self.count());
        gauge(out, "cgw_exporter_snapshot_age_seconds",
                "最近一次成功快照的生成时间距现在的秒数（-1=未知）。", Map.of(),
                self.snapshotAgeSeconds());
        gauge(out, "cgw_gateway_up", "上游网关快照是否可用（1/0）。", Map.of(),
                self.gatewayUp() ? 1 : 0);
        info(out, "cgw_exporter_build", "Exporter 构建信息。", Map.of("version", VERSION));
        info(out, "cgw_build", "被监控网关的版本信息。", Map.of("version", gatewayVersion));

        double[] bounds = new double[SelfMetrics.SCRAPE_BUCKETS.length + 1];
        long[] counts = new long[bounds.length];
        for (int i = 0; i < SelfMetrics.SCRAPE_BUCKETS.length; i++) {
            bounds[i] = SelfMetrics.SCRAPE_BUCKETS[i];
            counts[i] = self.bucket(i);
        }
        bounds[bounds.length - 1] = Double.POSITIVE_INFINITY;
        counts[counts.length - 1] = self.bucket(counts.length - 1);
        HistogramSnapshot hist = HistogramSnapshot.builder()
                .name("cgw_exporter_scrape_duration_seconds")
                .help("单次抓取的耗时分布（含上游请求与序列化，秒）。")
                .dataPoint(new HistogramSnapshot.HistogramDataPointSnapshot(
                        ClassicHistogramBuckets.of(bounds, counts), self.sum(), Labels.EMPTY, null, 0L))
                .build();
        out.add(hist);
    }

    private void appendEmptyActionSeries(List<MetricSnapshot> out) {
        CounterSnapshot.Builder builder = CounterSnapshot.builder()
                .name("cgw_http_requests")
                .help("网关 API 请求总数（按审计动作与结果）。");
        boolean any = false;
        for (String action : ACTIONS) {
            for (String result : RESULTS) {
                builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(labels(Map.of("action", action, "result", result, "node", "")))
                        .value(0)
                        .build());
                any = true;
            }
        }
        if (any) {
            out.add(builder.build());
        }
    }

    /** 快照中的键是 job（任务名），对外标签统一用 task，避免与 Prometheus 的 job 标签冲突。 */
    private void appendJobRuns(List<MetricSnapshot> out, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }
        CounterSnapshot.Builder builder = CounterSnapshot.builder()
                .name("cgw_job_runs")
                .help("后台任务执行次数。");
        boolean any = false;
        for (JsonNode item : array) {
            String task = item.path("job").asText("");
            if (task.isEmpty()) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("task", task);
            labels.put("result", item.path("result").asText(""));
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .labels(labels(labels))
                    .value(item.path("value").asDouble())
                    .build());
            any = true;
        }
        if (any) {
            out.add(builder.build());
        }
    }

    private void appendJobDuration(List<MetricSnapshot> out, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }
        HistogramSnapshot.Builder builder = HistogramSnapshot.builder()
                .name("cgw_job_duration_seconds")
                .help("后台任务执行耗时（秒）。");
        boolean any = false;
        for (JsonNode item : array) {
            String task = item.path("job").asText("");
            JsonNode rawBounds = item.path("bounds");
            JsonNode rawCounts = item.path("bucket_counts");
            if (task.isEmpty() || !rawBounds.isArray() || !rawCounts.isArray()
                    || rawCounts.size() != rawBounds.size() + 1) {
                continue;
            }
            double[] bounds = new double[rawBounds.size() + 1];
            long[] counts = new long[rawCounts.size()];
            for (int i = 0; i < rawBounds.size(); i++) {
                bounds[i] = rawBounds.get(i).asDouble();
                counts[i] = rawCounts.get(i).asLong();
            }
            bounds[bounds.length - 1] = Double.POSITIVE_INFINITY;
            counts[counts.length - 1] = rawCounts.get(rawCounts.size() - 1).asLong();
            builder.dataPoint(new HistogramSnapshot.HistogramDataPointSnapshot(
                    ClassicHistogramBuckets.of(bounds, counts),
                    item.path("sum").asDouble(),
                    labels(Map.of("task", task)), null, 0L));
            any = true;
        }
        if (any) {
            out.add(builder.build());
        }
    }

    // ------------------------------------------------------------------ 构建工具

    private void appendCounter(List<MetricSnapshot> out, JsonNode array, String name, String help,
                               String... labelNames) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }
        CounterSnapshot.Builder builder = CounterSnapshot.builder().name(name).help(help);
        for (JsonNode item : array) {
            builder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                    .labels(labels(item, labelNames))
                    .value(item.path("value").asDouble())
                    .build());
        }
        out.add(builder.build());
    }

    private void appendHistogram(List<MetricSnapshot> out, JsonNode array, String name, String help,
                                 String... labelNames) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }
        HistogramSnapshot.Builder builder = HistogramSnapshot.builder().name(name).help(help);
        boolean any = false;
        for (JsonNode item : array) {
            JsonNode rawBounds = item.path("bounds");
            JsonNode rawCounts = item.path("bucket_counts");
            if (!rawBounds.isArray() || !rawCounts.isArray()
                    || rawCounts.size() != rawBounds.size() + 1) {
                continue;
            }
            double[] bounds = new double[rawBounds.size() + 1];
            long[] counts = new long[rawCounts.size()];
            for (int i = 0; i < rawBounds.size(); i++) {
                bounds[i] = rawBounds.get(i).asDouble();
                counts[i] = rawCounts.get(i).asLong();
            }
            bounds[bounds.length - 1] = Double.POSITIVE_INFINITY;
            counts[counts.length - 1] = rawCounts.get(rawCounts.size() - 1).asLong();
            builder.dataPoint(new HistogramSnapshot.HistogramDataPointSnapshot(
                    ClassicHistogramBuckets.of(bounds, counts),
                    item.path("sum").asDouble(),
                    labels(item, labelNames), null, 0L));
            any = true;
        }
        if (any) {
            out.add(builder.build());
        }
    }

    private void gauge(List<MetricSnapshot> out, String name, String help,
                       Map<String, String> extra, double value) {
        out.add(GaugeSnapshot.builder().name(name).help(help)
                .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels(extra)).value(value).build())
                .build());
    }

    private void info(List<MetricSnapshot> out, String name, String help,
                      Map<String, String> extra) {
        out.add(InfoSnapshot.builder().name(name).help(help)
                .dataPoint(InfoSnapshot.InfoDataPointSnapshot.builder()
                        .labels(labels(extra)).build())
                .build());
    }

    private void counter(List<MetricSnapshot> out, String name, String help,
                         Map<String, String> extra, double value) {
        out.add(CounterSnapshot.builder().name(name).help(help)
                .dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                        .labels(labels(extra)).value(value).build())
                .build());
    }

    private Labels labels(JsonNode item, String... names) {
        Map<String, String> map = new LinkedHashMap<>(globalLabels);
        for (String name : names) {
            map.put(name, item.path(name).asText(""));
        }
        return labels(map);
    }

    private Labels labels(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(globalLabels);
        merged.putAll(extra);
        if (merged.isEmpty()) {
            return Labels.EMPTY;
        }
        String[] kv = new String[merged.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : merged.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return Labels.of(kv);
    }

    private static Map<String, String> sanitizeLabels(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().trim();
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                continue;
            }
            if (name.startsWith("__")) {
                continue;
            }
            out.put(name, e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static double ageSeconds(JsonNode snapshot) {
        long generatedAt = snapshot.path("generated_at_ms").asLong(0);
        if (generatedAt <= 0) {
            return -1;
        }
        return Math.max(0, (System.currentTimeMillis() - generatedAt) / 1000.0);
    }
}
