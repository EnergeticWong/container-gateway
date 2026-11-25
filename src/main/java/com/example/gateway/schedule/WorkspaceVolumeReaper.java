package com.example.gateway.schedule;

import com.example.gateway.docker.DockerNode;
import com.example.gateway.docker.DockerOps;
import com.example.gateway.docker.NodeRegistry;
import com.example.gateway.docker.Ownership;
import com.example.gateway.service.WorkspaceManager;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 孤儿工作区卷回收：只处理带 {@code com.example.gateway.workspace=true} 的命名卷，
 * 且必须满足「没有容器挂载 + 创建超过 grace」才删除，避免误删创建中的卷。
 */
@Component
public class WorkspaceVolumeReaper {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceVolumeReaper.class);

    /** 创建中的卷可能还没挂到容器上，10 分钟内不回收。 */
    private static final long GRACE_MS = 10 * 60 * 1000L;

    private final DockerOps docker;
    private final NodeRegistry nodes;
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public WorkspaceVolumeReaper(DockerOps docker, NodeRegistry nodes,
                                 com.example.gateway.metrics.GatewayMetrics metrics) {
        this.docker = docker;
        this.nodes = nodes;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${gateway.workspace-reap-interval-ms:600000}",
            initialDelayString = "${gateway.workspace-reap-interval-ms:600000}")
    public void reap() {
        long startedAt = System.nanoTime();
        boolean ok = true;
        for (DockerNode node : nodes.healthyNodes()) {
            try {
                reapNode(node);
            } catch (RuntimeException e) {
                ok = false;
                log.warn("节点 {} 孤儿工作区卷回收失败: {}", node.id(), e.getMessage());
            }
        }
        metrics.job("workspace_volume_reaper", ok, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void reapNode(DockerNode node) {
        Set<String> used = new HashSet<>();
        List<Container> containers = docker.listAllContainers(node);
        for (Container container : containers) {
            if (container.getMounts() == null) {
                continue;
            }
            for (ContainerMount mount : container.getMounts()) {
                if (mount.getName() != null && !mount.getName().isBlank()) {
                    used.add(mount.getName());
                }
            }
        }

        for (InspectVolumeResponse volume : docker.listVolumesByLabel(
                node, Ownership.WORKSPACE_MANAGED, "true")) {
            String name = volume.getName();
            if (name == null || !name.startsWith(WorkspaceManager.VOLUME_PREFIX)) {
                continue;
            }
            if (used.contains(name)) {
                continue;
            }
            String createdAt = volume.getLabels() == null
                    ? null : volume.getLabels().get(Ownership.CREATED_AT);
            if (!olderThanGrace(createdAt)) {
                continue;
            }
            try {
                docker.removeVolume(node, name);
                log.info("已回收孤儿工作区卷 {}（节点 {}）", name, node.id());
            } catch (RuntimeException e) {
                log.warn("回收孤儿工作区卷 {} 失败: {}", name, e.getMessage());
            }
        }
    }

    private static boolean olderThanGrace(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return false;
        }
        try {
            return Duration.between(Instant.parse(createdAt), Instant.now()).toMillis() > GRACE_MS;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
