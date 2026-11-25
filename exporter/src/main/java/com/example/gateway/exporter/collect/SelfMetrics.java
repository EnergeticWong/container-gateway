package com.example.gateway.exporter.collect;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** exporter 自监控状态：只保存自身计数，不涉及领域数据。 */
public final class SelfMetrics {

    public static final double[] SCRAPE_BUCKETS = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 8};

    private final LongAdder ok = new LongAdder();
    private final LongAdder error = new LongAdder();
    private final LongAdder count = new LongAdder();
    private final DoubleAdder sum = new DoubleAdder();
    private final LongAdder[] buckets = new LongAdder[SCRAPE_BUCKETS.length + 1];

    private volatile double lastScrapeSeconds;
    private volatile double snapshotAgeSeconds = -1;
    private volatile boolean gatewayUp;

    public SelfMetrics() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void record(boolean upstreamOk, long durationMs) {
        if (upstreamOk) {
            ok.increment();
        } else {
            error.increment();
        }
        double seconds = Math.max(0L, durationMs) / 1000.0;
        count.increment();
        sum.add(seconds);
        int i = 0;
        while (i < SCRAPE_BUCKETS.length && seconds > SCRAPE_BUCKETS[i]) {
            i++;
        }
        buckets[i].increment();
        lastScrapeSeconds = seconds;
    }

    public void gateway(boolean up, double ageSeconds) {
        this.gatewayUp = up;
        this.snapshotAgeSeconds = ageSeconds;
    }

    public long ok() { return ok.sum(); }
    public long error() { return error.sum(); }
    public long count() { return count.sum(); }
    public double sum() { return sum.sum(); }
    public long bucket(int i) { return buckets[i].sum(); }
    public double lastScrapeSeconds() { return lastScrapeSeconds; }
    public boolean gatewayUp() { return gatewayUp; }
    public double snapshotAgeSeconds() { return snapshotAgeSeconds; }
}
