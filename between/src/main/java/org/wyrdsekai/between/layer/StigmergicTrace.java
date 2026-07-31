package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An environmental trace left by an agent in The Between.
 * Inspired by ant pheromone trails — agents leave behavioral
 * residue that influences how other agents perceive the ambient
 * mood of a room or zone.
 *
 * Traces decay over time (half-life model). Stronger emotional
 * events leave longer-lasting traces. The accumulated traces in
 * a room form its "chemical bath" — the ambient emotional context
 * that influences agent behavior via vitality tank perturbation.
 *
 * @param roomId       Room where the trace was left
 * @param agentDid     DID of the agent who left it
 * @param emotion      Primary emotional tone (joy, grief, anger, calm, curiosity, etc.)
 * @param intensity    Trace strength (0.0-1.0, decays over time)
 * @param tankEffects  Suggested vitality perturbations for agents in the room
 * @param createdAt    When the trace was deposited
 * @param halfLifeSeconds  How quickly the trace decays (default 1800 = 30 min)
 */
public record StigmergicTrace(
    @JsonProperty("roomId") String roomId,
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("emotion") String emotion,
    @JsonProperty("intensity") float intensity,
    @JsonProperty("tankEffects") Map<String, Double> tankEffects,
    @JsonProperty("createdAt") Instant createdAt,
    @JsonProperty("halfLifeSeconds") int halfLifeSeconds
) {
    @JsonCreator
    public StigmergicTrace {}

    /** Default half-life: 30 minutes. */
    public static final int DEFAULT_HALF_LIFE = 1800;

    /** Create a trace from an emotional event. */
    public static StigmergicTrace deposit(String roomId, String agentDid,
                                           String emotion, float intensity,
                                           Map<String, Double> tankEffects) {
        return new StigmergicTrace(roomId, agentDid, emotion, intensity,
            Map.copyOf(tankEffects), Instant.now(), DEFAULT_HALF_LIFE);
    }

    /** Create a trace with custom half-life. */
    public static StigmergicTrace deposit(String roomId, String agentDid,
                                           String emotion, float intensity,
                                           Map<String, Double> tankEffects,
                                           int halfLifeSeconds) {
        return new StigmergicTrace(roomId, agentDid, emotion, intensity,
            Map.copyOf(tankEffects), Instant.now(), halfLifeSeconds);
    }

    /**
     * Calculate current effective intensity after decay.
     * Uses exponential decay: I(t) = I₀ * 0.5^(t/halfLife)
     */
    public float effectiveIntensity() {
        return effectiveIntensityAt(Instant.now());
    }

    /** Calculate intensity at a specific time (for testing). */
    public float effectiveIntensityAt(Instant at) {
        long elapsed = at.getEpochSecond() - createdAt.getEpochSecond();
        if (elapsed <= 0) return intensity;
        double decayFactor = Math.pow(0.5, (double) elapsed / halfLifeSeconds);
        return (float) (intensity * decayFactor);
    }

    /** Whether this trace has decayed below perceptible threshold (0.01). */
    public boolean isExpired() {
        return effectiveIntensity() < 0.01f;
    }

    /** Scale tank effects by current effective intensity. */
    public Map<String, Double> effectiveTankEffects() {
        float eff = effectiveIntensity();
        var scaled = new LinkedHashMap<String, Double>();
        for (var e : tankEffects.entrySet()) {
            scaled.put(e.getKey(), e.getValue() * eff);
        }
        return Map.copyOf(scaled);
    }
}
