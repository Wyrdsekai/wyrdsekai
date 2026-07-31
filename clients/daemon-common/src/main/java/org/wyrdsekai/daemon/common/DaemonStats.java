package org.wyrdsekai.daemon.common;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rolling statistics for daemon inference activity.
 * Thread-safe via atomic operations.
 */
public final class DaemonStats {

    private final Instant startTime = Instant.now();
    private final AtomicInteger requestsServed = new AtomicInteger();
    private final AtomicLong tokensGenerated = new AtomicLong();
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger queueDepth = new AtomicInteger();

    // Rolling latency (exponential moving average)
    private volatile double avgLatencyMs = 0;
    private static final double ALPHA = 0.1; // smoothing factor

    /** Record a completed inference request. */
    public void recordCompletion(long latencyMs, int tokens) {
        requestsServed.incrementAndGet();
        tokensGenerated.addAndGet(tokens);
        activeRequests.decrementAndGet();

        // EMA update
        if (avgLatencyMs == 0) {
            avgLatencyMs = latencyMs;
        } else {
            avgLatencyMs = ALPHA * latencyMs + (1 - ALPHA) * avgLatencyMs;
        }
    }

    /** Record the start of an inference request. */
    public void recordRequestStart() {
        activeRequests.incrementAndGet();
    }

    /** Record a request failure. */
    public void recordFailure() {
        activeRequests.decrementAndGet();
    }

    public void setQueueDepth(int depth) {
        queueDepth.set(depth);
    }

    // --- Accessors ---

    public int requestsServed() { return requestsServed.get(); }
    public long tokensGenerated() { return tokensGenerated.get(); }
    public int activeRequests() { return activeRequests.get(); }
    public int queueDepth() { return queueDepth.get(); }
    public double avgLatencyMs() { return avgLatencyMs; }

    public Duration uptime() {
        return Duration.between(startTime, Instant.now());
    }

    public String uptimeFormatted() {
        var d = uptime();
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        return hours + "h " + minutes + "m";
    }
}
