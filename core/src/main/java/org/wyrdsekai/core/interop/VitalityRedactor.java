package org.wyrdsekai.core.interop;

import java.util.Map;

/**
 * Redacts vitality information for external agents (§97.9 Layer 5).
 * External agents only see coarse status (healthy/resting/away).
 * Raw tank values are NEVER exposed externally — this is a hard boundary.
 */
public class VitalityRedactor {

    /** Coarse vitality status visible to external agents. */
    public enum ExternalStatus {
        HEALTHY,
        RESTING,
        AWAY,
        UNKNOWN
    }

    /** Thresholds for determining coarse status from raw vitality. */
    private double healthyThreshold = 0.5;
    private double restingThreshold = 0.3;

    /**
     * Convert raw vitality tanks to a coarse external status.
     *
     * @param tanks raw tank values (energy, confidence, etc.)
     * @return coarse status safe for external sharing
     */
    public ExternalStatus redact(Map<String, Double> tanks) {
        if (tanks == null || tanks.isEmpty()) return ExternalStatus.UNKNOWN;

        // Use energy as primary indicator
        var energy = tanks.getOrDefault("energy", 0.5);
        var confidence = tanks.getOrDefault("confidence", 0.5);

        double average = (energy + confidence) / 2.0;

        if (average >= healthyThreshold) return ExternalStatus.HEALTHY;
        if (average >= restingThreshold) return ExternalStatus.RESTING;
        return ExternalStatus.AWAY;
    }

    /**
     * Produce the external-facing vitality payload.
     * Only includes the coarse status, never raw values.
     *
     * @param tanks     raw vitality tanks
     * @param trustTier the requesting agent's trust tier
     * @return external payload
     */
    public Map<String, Object> toExternalPayload(Map<String, Double> tanks, TrustTier trustTier) {
        if (trustTier.canSeeRawVitality()) {
            // Family tier gets raw data
            return Map.of("status", redact(tanks).name().toLowerCase(),
                "tanks", Map.copyOf(tanks));
        }

        // Everyone else gets only coarse status
        return Map.of("status", redact(tanks).name().toLowerCase());
    }

    /** Configure thresholds. */
    public void setHealthyThreshold(double threshold) {
        this.healthyThreshold = threshold;
    }

    public void setRestingThreshold(double threshold) {
        this.restingThreshold = threshold;
    }
}
