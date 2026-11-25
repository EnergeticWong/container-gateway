package com.example.gateway.schedule;

import com.example.gateway.docker.ContainerGuard;
import com.example.gateway.docker.ContainerIndex;
import com.example.gateway.docker.Ownership;
import com.example.gateway.service.ContainerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * TTL 回收：扫描各节点上本网关托管的容器，超过 expiresAt 的自动销毁，避免客户端申请后不释放。
 *
 * <p>只处理带 {@code com.example.gateway.managed=true} 的容器，永远不会碰到节点上原有容器。
 * 顺带周期性重建 {@link ContainerIndex}，覆盖 AutoRemove 导致的容器消失。
 */
@Component
public class TtlReaper {

    private static final Logger log = LoggerFactory.getLogger(TtlReaper.class);

    private final ContainerService service;
    private final ContainerIndex index;
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public TtlReaper(ContainerService service, ContainerIndex index,
                     com.example.gateway.metrics.GatewayMetrics metrics) {
        this.service = service;
        this.index = index;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${gateway.ttl-scan-interval-ms:60000}",
            initialDelayString = "${gateway.ttl-scan-interval-ms:60000}")
    public void reapExpired() {
        long startedAt = System.nanoTime();
        boolean ok = true;
        try {
            index.rebuild();
        } catch (RuntimeException e) {
            log.warn("容器索引重建失败: {}", e.getMessage());
        }

        java.util.List<ContainerService.Located> containers;
        try {
            containers = service.managedContainers();
        } catch (RuntimeException e) {
            log.warn("TTL 扫描失败（Docker 不可用？）: {}", e.getMessage());
            metrics.job("ttl_reaper", false, (System.nanoTime() - startedAt) / 1_000_000L);
            return;
        }

        Instant now = Instant.now();
        int reaped = 0;
        for (ContainerService.Located located : containers) {
            String expiresAt = ContainerGuard.labelsOf(located.container()).get(Ownership.EXPIRES_AT);
            if (expiresAt == null) {
                continue;
            }
            try {
                if (Instant.parse(expiresAt).isAfter(now)) {
                    continue;
                }
            } catch (DateTimeParseException e) {
                log.warn("容器 {} 的 expiresAt 标签无法解析（{}），跳过",
                        shortId(located.container().getId()), expiresAt);
                continue;
            }
            try {
                service.reap(located.node(), located.container(), UUID.randomUUID().toString());
                reaped++;
            } catch (RuntimeException e) {
                log.warn("TTL 回收容器 {} 失败: {}", shortId(located.container().getId()), e.getMessage());
            }
        }
        if (reaped > 0) {
            log.info("TTL 回收完成：销毁了 {} 个过期容器（审计动作 {}）", reaped, ContainerService.ACTION_TTL_REAP);
        }
        if (log.isDebugEnabled()) {
            log.debug("TTL 扫描：托管容器 {} 个，回收 {} 个", containers.size(), reaped);
        }
        metrics.job("ttl_reaper", true, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String shortId(String id) {
        return id != null && id.length() > 12 ? id.substring(0, 12) : String.valueOf(id);
    }
}
