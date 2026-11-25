package com.example.gateway.web;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.NodePortAllocator;
import com.example.gateway.docker.NodeRegistry;
import com.example.gateway.service.ManagedCountCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康检查端点（免鉴权），供 CLB 健康检查与运维巡检使用。
 *
 * <p>输出每个 Docker 节点状态；只要存在健康节点即 UP。顶层保留旧字段以兼容 CLB 与旧客户端。
 */
@RestController
public class HealthController {

    private final ManagedCountCache managedCount;
    private final NodeRegistry nodes;
    private final NodePortAllocator portAllocator;
    private final GatewayProperties props;

    public HealthController(ManagedCountCache managedCount, NodeRegistry nodes,
                            NodePortAllocator portAllocator, GatewayProperties props) {
        this.managedCount = managedCount;
        this.nodes = nodes;
        this.portAllocator = portAllocator;
        this.props = props;
    }

    /** 兼容旧路径：返回完整巡检信息，保持原有 HTTP 200 语义。 */
    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return healthBody();
    }

    /** 存活探针：不访问 Docker，只要进程能响应即返回 200。 */
    @GetMapping("/healthz/live")
    public Map<String, Object> live() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("check", "live");
        return body;
    }

    /** 就绪探针：依赖节点健康与托管容器计数（缓存 4s），不健康时返回 503。 */
    @GetMapping("/healthz/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = healthBody();
        HttpStatus status = "UP".equals(body.get("status"))
                ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> healthBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<DockerNode> all = nodes.all();
        List<Map<String, Object>> nodeViews = new ArrayList<>();
        List<DockerNode> healthy = new ArrayList<>();
        int totalPortsUsed = 0;

        for (DockerNode node : all) {
            Map<String, Object> nv = new LinkedHashMap<>();
            nv.put("id", node.id());
            nv.put("endpoint", node.endpointLabel());
            nv.put("status", node.status().name());
            nv.put("runningContainers", node.runningContainers());
            nv.put("activeStreams", node.activeStreams().get());
            nv.put("portsUsed", portAllocator.usedCount(node.id()));
            if (node.lastError() != null) {
                nv.put("error", node.lastError());
            }
            nodeViews.add(nv);
            totalPortsUsed += portAllocator.usedCount(node.id());
            if (node.healthy()) {
                healthy.add(node);
            }
        }

        body.put("status", healthy.isEmpty() ? "DOWN" : "UP");
        body.put("docker", healthy.isEmpty() ? "DOWN" : "UP");
        body.put("nodes", nodeViews);
        body.put("healthyNodes", healthy.size());
        long managed = managedCount.current();
        body.put("managedContainers", managed);
        if (managed < 0) {
            body.put("status", "DOWN");
        }
        body.put("maxContainers", props.getMaxContainers());
        body.put("nodeMaxTasks", props.getNodeMaxTasks());
        body.put("portPoolUsed", totalPortsUsed);
        body.put("portPoolCapacity", portAllocator.capacity());
        body.put("autoRemove", props.isAutoRemove());
        body.put("networkMode", props.getNetworkMode());
        return body;
    }
}
