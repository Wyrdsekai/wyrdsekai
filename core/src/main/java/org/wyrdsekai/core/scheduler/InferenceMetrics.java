package org.wyrdsekai.core.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Metrics recorded after each inference call.
 * Used for latency-aware routing and Engine Room observability.
 *
 * @param provider   provider name (e.g. "local", "ollama", "cloud")
 * @param model      model ID (e.g. "qwen2.5:7b")
 * @param latencyMs  round-trip latency in milliseconds
 * @param tokensIn   number of input tokens
 * @param tokensOut  number of output tokens
 * @param success    whether the call succeeded
 * @param timestamp  when the call completed
 */
public record InferenceMetrics(
    String provider,
    String model,
    long latencyMs,
    int tokensIn,
    int tokensOut,
    boolean success,
    Instant timestamp
) {

    /**
     * Rolling window aggregation of inference metrics for a single provider/model pair.
     * Supports percentile calculation and success rate tracking.
     */
    public static final class Aggregator {

        private final int windowSize;
        private final Duration maxAge;
        private final Deque<InferenceMetrics> window;

        public Aggregator() {
            this(1000, Duration.ofMinutes(5));
        }

        public Aggregator(int windowSize) {
            this(windowSize, Duration.ofMinutes(5));
        }

        /**
         * @param windowSize maximum number of samples to retain
         * @param maxAge     maximum age of samples — older samples are evicted
         */
        public Aggregator(int windowSize, Duration maxAge) {
            this.windowSize = windowSize;
            this.maxAge = maxAge;
            this.window = new ArrayDeque<>(windowSize);
        }

        /** Record a new metric observation. */
        public synchronized void record(InferenceMetrics metric) {
            evictStale();
            if (window.size() >= windowSize) {
                window.removeFirst();
            }
            window.addLast(metric);
        }

        /** Number of observations in the window. */
        public synchronized int size() {
            evictStale();
            return window.size();
        }

        /** Calculate a latency percentile (0-100) over the rolling window. */
        public synchronized long percentile(double p) {
            evictStale();
            if (window.isEmpty()) return 0;
            var latencies = window.stream()
                .mapToLong(InferenceMetrics::latencyMs)
                .sorted()
                .toArray();
            int index = (int) Math.ceil(p / 100.0 * latencies.length) - 1;
            return latencies[Math.max(0, Math.min(index, latencies.length - 1))];
        }

        /** P50 latency. */
        public long p50() { return percentile(50); }

        /** P95 latency. */
        public long p95() { return percentile(95); }

        /** P99 latency. */
        public long p99() { return percentile(99); }

        /** Success rate (0.0-1.0) over the rolling window. */
        public synchronized double successRate() {
            evictStale();
            if (window.isEmpty()) return 1.0;
            long total = window.size();
            long successes = window.stream().filter(InferenceMetrics::success).count();
            return (double) successes / total;
        }

        /** Average latency in milliseconds. */
        public synchronized double avgLatencyMs() {
            evictStale();
            return window.stream()
                .mapToLong(InferenceMetrics::latencyMs)
                .average()
                .orElse(0);
        }

        /** Total tokens processed (in + out) in the window. */
        public synchronized long totalTokens() {
            evictStale();
            return window.stream()
                .mapToLong(m -> m.tokensIn() + m.tokensOut())
                .sum();
        }

        /** Get a summary of the current aggregation state. */
        public synchronized Summary summary() {
            evictStale();
            return new Summary(
                window.size(), p50(), p95(), p99(),
                avgLatencyMs(), successRate(), totalTokens()
            );
        }

        /** Remove samples older than maxAge from the deque head. */
        private void evictStale() {
            Instant cutoff = Instant.now().minus(maxAge);
            while (!window.isEmpty() && window.peekFirst().timestamp().isBefore(cutoff)) {
                window.removeFirst();
            }
        }
    }

    /**
     * Aggregation summary for a provider/model pair.
     */
    public record Summary(
        int sampleCount,
        long p50Ms,
        long p95Ms,
        long p99Ms,
        double avgLatencyMs,
        double successRate,
        long totalTokens
    ) {
        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0.0, 1.0, 0);
        }
    }
}
