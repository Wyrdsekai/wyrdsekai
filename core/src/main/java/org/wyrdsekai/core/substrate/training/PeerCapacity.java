package org.wyrdsekai.core.substrate.training;

/**
 * Capacity snapshot for a reachable peer node, as relevant to training delegation.
 *
 * <p>Sourced from {@code ResourceRegistry} / {@code ResourceSnapshot} (#117) —
 * the cross-node resource model already in production. The training selector
 * uses the {@link #score()} as a coarse rank: higher score = better
 * delegation target.</p>
 *
 * @param nodeId          the peer's node identifier (used for routing)
 * @param zone            the peer's zone (cross-zone routing key)
 * @param freeGpuVramGb   estimated free GPU VRAM on the peer (largest GPU)
 * @param totalGpuVramGb  total GPU VRAM on the peer
 * @param hasGpu          true if the peer has any GPU at all
 * @param networkLatencyMs round-trip latency budget for shipping corpus + adapter
 * @param trustTier       reputation/trust score (0.0 - 1.0); below 0.5 = avoid
 */
public record PeerCapacity(
    String nodeId,
    String zone,
    double freeGpuVramGb,
    double totalGpuVramGb,
    boolean hasGpu,
    long networkLatencyMs,
    double trustTier
) {

    /** True if this peer can probably handle a training task this size. */
    public boolean canTrain(double trainingEstimateGb) {
        return hasGpu && freeGpuVramGb >= trainingEstimateGb && trustTier >= 0.5;
    }

    /**
     * Coarse rank for selecting between multiple peers — bigger is better.
     * Combines spare VRAM, latency budget, and trust.
     */
    public double score() {
        if (!hasGpu) return 0;
        var vramScore = Math.min(freeGpuVramGb / 24.0, 1.0);  // 24GB caps the boost
        var latencyScore = Math.max(0, 1.0 - networkLatencyMs / 5000.0);  // 5s = 0
        return (vramScore * 0.6 + latencyScore * 0.4) * trustTier;
    }
}
