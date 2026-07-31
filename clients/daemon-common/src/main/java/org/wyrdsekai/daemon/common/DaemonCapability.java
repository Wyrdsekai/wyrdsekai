package org.wyrdsekai.daemon.common;

import java.time.Instant;
import java.util.List;

/**
 * Wire-compatible with {@code InferenceGossip.InferenceCapability} on the server.
 * Same JSON shape, same NATS subject. The daemon produces these; the server consumes them.
 *
 * @param nodeId       unique daemon node identifier
 * @param models       models currently loaded and available
 * @param totalGpuCount number of GPUs (0 for phone CPU-only)
 * @param totalFreeVramMB free VRAM in MB (0 for CPU-only)
 * @param availableSlots inference slots available (usually 1 for phones)
 * @param queueDepth   requests waiting
 * @param avgLatencyMs rolling average latency
 * @param timestamp    epoch seconds (must match server's Instant.now().getEpochSecond())
 */
public record DaemonCapability(
    String nodeId,
    List<DaemonModel> models,
    int totalGpuCount,
    long totalFreeVramMB,
    int availableSlots,
    int queueDepth,
    double avgLatencyMs,
    long timestamp
) {
    /** Create a capability snapshot with current timestamp. */
    public static DaemonCapability now(
            String nodeId, List<DaemonModel> models,
            int gpuCount, long freeVramMB,
            int availableSlots, int queueDepth, double avgLatencyMs) {
        return new DaemonCapability(
            nodeId, models, gpuCount, freeVramMB,
            availableSlots, queueDepth, avgLatencyMs,
            Instant.now().getEpochSecond()
        );
    }
}
