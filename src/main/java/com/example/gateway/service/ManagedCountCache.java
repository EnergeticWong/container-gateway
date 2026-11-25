package com.example.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 托管容器计数缓存：健康检查只读缓存，刷新在后台虚拟线程执行，
 * 避免 /healthz 因某个节点失联而阻塞在 Docker list 调用上。
 *
 * <p>单飞（single-flight）：同一时间只允许一个刷新任务，节点不可达时
 * 最多阻塞一个后台线程，不会堆积。刷新失败保留上一次成功值。
 */
@Component
public class ManagedCountCache {

    private static final Logger log = LoggerFactory.getLogger(ManagedCountCache.class);

    /** 缓存有效期：与原来 /healthz 的 4s 缓存一致。 */
    private static final long TTL_MS = 4000L;

    private final ContainerService service;
    private final com.example.gateway.metrics.GatewayMetrics metrics;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** 初始值 0：避免应用刚启动、首次刷新尚未完成时把 /healthz 误判为 DOWN。 */
    private volatile long count = 0L;
    private volatile long refreshedAt = 0L;

    public ManagedCountCache(ContainerService service, com.example.gateway.metrics.GatewayMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    /** 返回缓存值；过期时触发后台刷新，本方法绝不阻塞。 */
    public long current() {
        long now = System.currentTimeMillis();
        if (now - refreshedAt >= TTL_MS) {
            refreshAsync();
        }
        return count;
    }

    /** 异步刷新（单飞）。 */
    public void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("managed-count-refresh").start(() -> {
            try {
                refresh();
            } finally {
                refreshing.set(false);
            }
        });
    }

    /** 同步刷新，仅供测试与需要显式刷新的场景使用。 */
    public void refresh() {
        long startedAt = System.nanoTime();
        try {
            long fresh = service.managedCount();
            count = fresh;
            refreshedAt = System.currentTimeMillis();
            metrics.job("managed_count_refresh", true, (System.nanoTime() - startedAt) / 1_000_000L);
        } catch (RuntimeException e) {
            // 保留上一次成功值，避免瞬时故障把管理面健康状态打挂
            refreshedAt = System.currentTimeMillis();
            metrics.managedRefreshFailure();
            metrics.job("managed_count_refresh", false, (System.nanoTime() - startedAt) / 1_000_000L);
            log.warn("刷新托管容器计数失败（保留旧值 {}）: {}", count, e.getMessage());
        }
    }
}
