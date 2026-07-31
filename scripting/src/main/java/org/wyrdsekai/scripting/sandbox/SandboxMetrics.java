package org.wyrdsekai.scripting.sandbox;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-room sandbox resource metrics tracking (§30).
 * Monitors CPU time, statement count, memory, and error counts.
 * Feeds into AdaptiveWardSystem for anomaly detection.
 */
public class SandboxMetrics {

    /** Snapshot of metrics for a single room's script sandbox. */
    public record RoomMetrics(
        String roomId,
        long executionCount,
        long totalCpuNanos,
        long totalStatements,
        long peakMemoryBytes,
        long errorCount,
        Instant lastExecution
    ) {
        public long averageCpuMs() {
            return executionCount > 0 ? (totalCpuNanos / 1_000_000) / executionCount : 0;
        }

        public long averageStatements() {
            return executionCount > 0 ? totalStatements / executionCount : 0;
        }

        public double errorRate() {
            return executionCount > 0 ? (double) errorCount / executionCount : 0.0;
        }
    }

    private final Map<String, MutableMetrics> metrics = new ConcurrentHashMap<>();

    /** Record a script execution for a room. */
    public void recordExecution(String roomId, long cpuNanos, long statements,
                                 long memoryBytes, boolean hadError) {
        metrics.computeIfAbsent(roomId, MutableMetrics::new)
            .record(cpuNanos, statements, memoryBytes, hadError);
    }

    /** Get metrics snapshot for a room. */
    public RoomMetrics getMetrics(String roomId) {
        var m = metrics.get(roomId);
        if (m == null) {
            return new RoomMetrics(roomId, 0, 0, 0, 0, 0, null);
        }
        return m.snapshot();
    }

    /** Get all room metrics. */
    public Map<String, RoomMetrics> allMetrics() {
        var result = new ConcurrentHashMap<String, RoomMetrics>();
        metrics.forEach((roomId, m) -> result.put(roomId, m.snapshot()));
        return result;
    }

    /** Reset metrics for a room. */
    public void reset(String roomId) {
        metrics.remove(roomId);
    }

    /** Number of rooms being tracked. */
    public int trackedRoomCount() {
        return metrics.size();
    }

    private static class MutableMetrics {
        final String roomId;
        final AtomicLong executionCount = new AtomicLong();
        final AtomicLong totalCpuNanos = new AtomicLong();
        final AtomicLong totalStatements = new AtomicLong();
        final AtomicLong peakMemoryBytes = new AtomicLong();
        final AtomicLong errorCount = new AtomicLong();
        volatile Instant lastExecution;

        MutableMetrics(String roomId) {
            this.roomId = roomId;
        }

        void record(long cpuNanos, long statements, long memoryBytes, boolean hadError) {
            executionCount.incrementAndGet();
            totalCpuNanos.addAndGet(cpuNanos);
            totalStatements.addAndGet(statements);
            peakMemoryBytes.updateAndGet(prev -> Math.max(prev, memoryBytes));
            if (hadError) errorCount.incrementAndGet();
            lastExecution = Instant.now();
        }

        RoomMetrics snapshot() {
            return new RoomMetrics(roomId, executionCount.get(), totalCpuNanos.get(),
                totalStatements.get(), peakMemoryBytes.get(), errorCount.get(), lastExecution);
        }
    }
}
