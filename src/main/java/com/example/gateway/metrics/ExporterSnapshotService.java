package com.example.gateway.metrics;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.ContainerIndex;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.NodePortAllocator;
import com.example.gateway.docker.NodeRegistry;
import com.example.gateway.service.ManagedCountCache;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组装供 exporter 抓取的只读快照。
 *
 * <p>全程只读内存字段，<b>不调用 Docker</b>，保证抓取开销恒定（微秒级）。
 */
@Component
public class ExporterSnapshotService {

    public static final String SCHEMA = "cgw.exporter.snapshot/v1";

    private final GatewayMetrics metrics;
    private final NodeRegistry nodes;
    private final NodePortAllocator ports;
    private final ManagedCountCache managedCount;
    private final ContainerIndex index;
    private final GatewayProperties props;

    public ExporterSnapshotService(GatewayMetrics metrics, NodeRegistry nodes, NodePortAllocator ports,
                                   ManagedCountCache managedCount, ContainerIndex index,
                                   GatewayProperties props) {
        this.metrics = metrics;
        this.nodes = nodes;
        this.ports = ports;
        this.managedCount = managedCount;
        this.index = index;
        this.props = props;
    }

    public Map<String, Object> build() {
        long now = System.currentTimeMillis();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("generated_at_ms", now);
        root.put("gateway", gateway());
        root.put("nodes", nodeList(now));
        root.put("jobs", metrics.jobSnapshot());
        root.put("counters", metrics.counterSnapshot());
        root.put("histograms", metrics.histogramSnapshot());
        return root;
    }

    private Map<String, Object> gateway() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("version", version());
        g.put("ready", !nodes.healthyNodes().isEmpty());
        g.put("managed_containers", managedCount.current());
        g.put("max_containers", props.getMaxContainers());
        g.put("index_containers", index.size());
        g.put("audit_write_failures", metrics.auditWriteFailures());
        g.put("managed_refresh_failures", metrics.managedRefreshFailures());
        return g;
    }

    private List<Map<String, Object>> nodeList(long now) {
        Map<String, int[]> loads = nodes.loadSnapshot();
        List<Map<String, Object>> out = new ArrayList<>();
        for (DockerNode node : nodes.all()) {
            int[] load = loads.getOrDefault(node.id(), new int[]{0, 0});
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.id());
            n.put("up", node.healthy());
            n.put("assigned", load[0]);
            n.put("inflight", load[1]);
            n.put("capacity", Math.max(1, props.getNodeMaxTasks()));
            n.put("docker_running", node.runningContainers());
            n.put("active_streams", node.activeStreams().get());
            n.put("stream_capacity", props.getMaxStreamsPerNode());
            n.put("port_used", ports.usedCount(node.id()));
            n.put("port_capacity", ports.capacity());
            n.put("probe_duration_seconds", Math.max(0L, node.lastProbeDurationMs()) / 1000.0);
            double age = node.lastProbeEpochMs() <= 0 ? -1 : (now - node.lastProbeEpochMs()) / 1000.0;
            n.put("last_probe_age_seconds", age < 0 ? -1L : age);
            n.put("probe_reason", NodeRegistry.probeReason(node));
            out.add(n);
        }
        return out;
    }

    private static String version() {
        Package pkg = ExporterSnapshotService.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }
}
