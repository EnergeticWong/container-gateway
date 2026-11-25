package com.example.gateway.service;

import com.example.gateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 创建幂等记录：{@code clientId + Idempotency-Key → containerId}，带 TTL。
 *
 * <p>内存实现（网关重启即失效）；同一幂等键还会写入容器 label，重启后可按 label 人工/后台核对。
 * 用固定条带锁把同一 key 的并发创建串行化，避免重复建容器。
 */
@Component
public class CreateIdempotencyStore {

    private static final int STRIPES = 64;

    private record Entry(String containerId, String digest, long expiresAtMillis) {
    }

    /**
     * 幂等命中的只读视图：容器 id 与创建时的请求摘要。
     * 调用方凭 digest 判断「同键是否同参」，不一致应拒绝而不是复用旧容器。
     */
    public record Lookup(String containerId, String digest) {
    }

    private final GatewayProperties props;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Object[] stripes = new Object[STRIPES];

    public CreateIdempotencyStore(GatewayProperties props) {
        this.props = props;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new Object();
        }
    }

    /** 取同一 (clientId, key) 的条带锁，用于串行化并发创建。 */
    public Object lockFor(String clientId, String key) {
        return stripes[Math.floorMod((clientId + "|" + key).hashCode(), STRIPES)];
    }

    /** 命中且未过期时返回既有 containerId，否则返回 null。 */
    public String lookup(String clientId, String key) {
        Entry e = validEntry(clientId, key);
        return e == null ? null : e.containerId();
    }

    /** 命中且未过期时返回含请求摘要的视图，否则返回 null。 */
    public Lookup lookupEntry(String clientId, String key) {
        Entry e = validEntry(clientId, key);
        return e == null ? null : new Lookup(e.containerId(), e.digest());
    }

    private Entry validEntry(String clientId, String key) {
        String k = mapKey(clientId, key);
        Entry e = entries.get(k);
        if (e == null) {
            return null;
        }
        if (e.expiresAtMillis() <= System.currentTimeMillis()) {
            entries.remove(k, e);
            return null;
        }
        return e;
    }

    /** 兼容旧调用：不记录请求摘要（摘要为 null）。 */
    public void remember(String clientId, String key, String containerId) {
        remember(clientId, key, containerId, null);
    }

    public void remember(String clientId, String key, String containerId, String digest) {
        long ttl = Math.max(1L, props.getCreateIdempotencyTtlSeconds());
        entries.put(mapKey(clientId, key),
                new Entry(containerId, digest, System.currentTimeMillis() + ttl * 1000L));
        evictExpired();
    }

    public void forget(String clientId, String key) {
        entries.remove(mapKey(clientId, key));
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
    }

    private static String mapKey(String clientId, String key) {
        return clientId + "|" + key;
    }
}
