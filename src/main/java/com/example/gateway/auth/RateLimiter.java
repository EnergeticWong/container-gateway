package com.example.gateway.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 key 的滑动窗口限流（窗口 60s）。key 为 clientId 或 clientId + 动作。
 */
@Component
public class RateLimiter {

    private static final long WINDOW_NANOS = 60_000_000_000L;
    /** 进程内最多跟踪的 key 数，避免公网按 IP 限流被海量源地址撑爆内存。 */
    private static final int MAX_KEYS = 4096;
    private static final String OVERFLOW_KEY = "__overflow__";

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong();

    /** 限流判定结果。allowed=false 时 retryAfterSeconds 为建议等待秒数（至少 1）。 */
    public record Result(boolean allowed, long retryAfterSeconds) {
        static final Result ALLOW = new Result(true, 0);
    }

    /**
     * @return 放行或拒绝；拒绝时附带 Retry-After 秒数
     */
    public Result tryAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return Result.ALLOW;
        }
        long now = System.nanoTime();
        sweepIfDue(now);
        long cutoff = now - WINDOW_NANOS;
        Deque<Long> window = windows.get(key);
        if (window == null) {
            // 超过 key 上限后新来源共用溢出桶，保证内存有界
            String effectiveKey = windows.size() >= MAX_KEYS ? OVERFLOW_KEY : key;
            window = windows.computeIfAbsent(effectiveKey, k -> new ArrayDeque<>());
        }
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                // 最早一次请求滑出窗口后即可再放行
                long waitNanos = Math.max(0L, window.peekFirst() + WINDOW_NANOS - now);
                long seconds = (waitNanos + 999_999_999L) / 1_000_000_000L;
                return new Result(false, Math.max(1L, seconds));
            }
            window.addLast(now);
            return Result.ALLOW;
        }
    }

    /** 每分钟清一次过期 key，避免按 IP 限流留下长期占用的空窗口。 */
    private void sweepIfDue(long now) {
        long last = lastSweepNanos.get();
        if (now - last < WINDOW_NANOS || !lastSweepNanos.compareAndSet(last, now)) {
            return;
        }
        long cutoff = now - WINDOW_NANOS;
        for (Map.Entry<String, Deque<Long>> entry : windows.entrySet()) {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                while (!window.isEmpty() && window.peekFirst() < cutoff) {
                    window.pollFirst();
                }
                if (window.isEmpty()) {
                    windows.remove(entry.getKey(), window);
                }
            }
        }
    }
}
