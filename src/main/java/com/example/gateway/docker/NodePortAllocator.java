package com.example.gateway.docker;

import com.example.gateway.config.GatewayProperties;
import com.example.gateway.error.GatewayException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每个 Docker 节点一份的宿主端口池（默认 30000-30999）。
 *
 * <p>内存 BitSet 只是缓存，真源是「该节点上所有容器已映射的宿主端口」：
 * 启动时全量重建；{@link #reconcileAll()} 周期性对账，回收 AutoRemove 等
 * 非显式删除导致的漏端口（带 grace，避免误回收创建中的端口）。
 *
 * <p>端口探测只对网关本机节点有意义；远程节点的端口占用交给 Docker 绑定失败 + 重试。
 */
@Component
public class NodePortAllocator {

    private static final Logger log = LoggerFactory.getLogger(NodePortAllocator.class);

    /** 新建端口在 grace 内即使尚未在节点上出现也不回收（覆盖创建中窗口）。 */
    private static final long RECONCILE_GRACE_MS = 120_000L;

    private static final class Pool {
        final BitSet used;
        final Map<Integer, Long> allocatedAt = new HashMap<>();
        final ReentrantLock lock = new ReentrantLock();

        Pool(int size) {
            this.used = new BitSet(size);
        }
    }

    private final DockerOps docker;
    private final NodeRegistry nodes;
    private final int start;
    private final int end;
    private final int size;
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final com.example.gateway.metrics.GatewayMetrics metrics;

    public NodePortAllocator(DockerOps docker, NodeRegistry nodes, GatewayProperties props,
                             com.example.gateway.metrics.GatewayMetrics metrics) {
        this.docker = docker;
        this.nodes = nodes;
        this.metrics = metrics;
        this.start = props.getPortRangeStart();
        this.end = props.getPortRangeEnd();
        if (start < 1 || end > 65535 || start > end) {
            throw new IllegalStateException("非法端口段: " + start + "-" + end);
        }
        this.size = end - start + 1;
    }

    @PostConstruct
    void init() {
        rebuildAll();
    }

    @Scheduled(fixedDelayString = "${gateway.ttl-scan-interval-ms:60000}",
            initialDelayString = "${gateway.ttl-scan-interval-ms:60000}")
    public void reconcileAll() {
        long startedAt = System.nanoTime();
        boolean ok = true;
        for (DockerNode node : nodes.all()) {
            try {
                reconcile(node);
            } catch (RuntimeException e) {
                ok = false;
                log.warn("节点 {} 端口池对账失败: {}", node.id(), e.getMessage());
            }
        }
        metrics.job("port_reconcile", ok, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private Pool pool(String nodeId) {
        return pools.computeIfAbsent(nodeId, k -> new Pool(size));
    }

    /** 启动时从各节点实际映射的端口重建占用表。 */
    public void rebuildAll() {
        for (DockerNode node : nodes.all()) {
            Pool pool = pool(node.id());
            pool.lock.lock();
            try {
                pool.used.clear();
                pool.allocatedAt.clear();
                int occupied = 0;
                try {
                    for (Integer port : docker.hostPortsInUse(node)) {
                        if (port != null && inRange(port)) {
                            pool.used.set(port - start);
                            pool.allocatedAt.put(port, System.currentTimeMillis());
                            occupied++;
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("节点 {} 端口池重建失败（节点可能不可达）: {}", node.id(), e.getMessage());
                }
                log.info("节点 {} 端口池重建完成: {} 个端口已占用 ({}-{})", node.id(), occupied, start, end);
            } finally {
                pool.lock.unlock();
            }
        }
    }

    /**
     * 在指定节点上分配 {@code count} 个空闲宿主端口。分配失败会回滚已占位。
     */
    public List<Integer> allocate(DockerNode node, int count) {
        if (count <= 0) {
            return List.of();
        }
        Pool pool = pool(node.id());
        pool.lock.lock();
        try {
            List<Integer> result = new ArrayList<>(count);
            long now = System.currentTimeMillis();
            for (int i = 0; i < size && result.size() < count; i++) {
                if (pool.used.get(i)) {
                    continue;
                }
                int port = start + i;
                if (node.local() && !isFreeOnHost(port)) {
                    // 可能是 TIME_WAIT 或非容器进程占用；只跳过本轮，不永久占位
                    log.debug("节点 {} 端口 {} 当前不可绑定，本轮跳过", node.id(), port);
                    continue;
                }
                pool.used.set(i);
                pool.allocatedAt.put(port, now);
                result.add(port);
            }
            if (result.size() < count) {
                release(node, result);
                throw GatewayException.portExhausted(
                        "节点 " + node.id() + " 端口池 " + start + "-" + end
                                + " 已无足够空闲端口（需要 " + count + " 个）");
            }
            return result;
        } finally {
            pool.lock.unlock();
        }
    }

    public void release(DockerNode node, Collection<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return;
        }
        Pool pool = pool(node.id());
        pool.lock.lock();
        try {
            for (Integer port : ports) {
                if (port != null && inRange(port)) {
                    pool.used.clear(port - start);
                    pool.allocatedAt.remove(port);
                }
            }
        } finally {
            pool.lock.unlock();
        }
    }

    public void release(DockerNode node, int port) {
        release(node, List.of(port));
    }

    /**
     * 对账：把「池里标记为占用、但节点上实际已不存在（且超过 grace）」的端口回收。
     */
    private void reconcile(DockerNode node) {
        if (!node.healthy()) {
            return;
        }
        List<Integer> live = docker.hostPortsInUse(node);
        boolean[] liveBits = new boolean[size];
        for (Integer port : live) {
            if (port != null && inRange(port)) {
                liveBits[port - start] = true;
            }
        }
        Pool pool = pool(node.id());
        pool.lock.lock();
        try {
            long now = System.currentTimeMillis();
            int reclaimed = 0;
            for (int i = pool.used.nextSetBit(0); i >= 0; i = pool.used.nextSetBit(i + 1)) {
                if (liveBits[i]) {
                    continue;
                }
                int port = start + i;
                Long at = pool.allocatedAt.get(port);
                if (at != null && now - at < RECONCILE_GRACE_MS) {
                    continue; // 创建中，暂不回收
                }
                pool.used.clear(i);
                pool.allocatedAt.remove(port);
                reclaimed++;
            }
            if (reclaimed > 0) {
                metrics.portReclaimed(node.id(), reclaimed);
                log.info("节点 {} 端口池对账：回收 {} 个已释放端口", node.id(), reclaimed);
            }
        } finally {
            pool.lock.unlock();
        }
    }

    public int usedCount(String nodeId) {
        Pool pool = pools.get(nodeId);
        if (pool == null) {
            return 0;
        }
        pool.lock.lock();
        try {
            return pool.used.cardinality();
        } finally {
            pool.lock.unlock();
        }
    }

    public int capacity() {
        return size;
    }

    private boolean inRange(int port) {
        return port >= start && port <= end;
    }

    /** 探测该端口当前是否有进程监听（仅本机节点有意义）。 */
    private boolean isFreeOnHost(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port), 1);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
