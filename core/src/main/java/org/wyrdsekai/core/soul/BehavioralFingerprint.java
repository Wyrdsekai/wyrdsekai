package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The behavioral fingerprint: what defines the agent by what it does and
 * doesn't do. The ma (negative space) of the agent's identity.
 *
 * Expanded from original 8-tank to 12-tank system (Experiment 18).
 * Added observedSensitivity and emotionalResponseProfile for genome
 * drift detection and MirrorResonance integration.
 *
 * @param baselineVitality         Resting tank state average (12 tanks)
 * @param baselineDerivatives      How tanks typically move (velocity per tank)
 * @param observedSensitivity      Measured per-tank responsiveness vs genome prediction
 * @param actionDistribution       Action type frequencies ("say" 0.6, "move" 0.1, etc.)
 * @param topicAffinities          Topics the agent gravitates toward
 * @param avoidancePatterns        Topics/actions the agent avoids
 * @param averageResponseLength    In tokens
 * @param responseLatencyProfile   Tendency to respond quickly vs slowly
 * @param stylisticMarkers         Characteristic phrases and patterns
 * @param emotionalResponseProfile How agent responds to charge types (grief->resonance, etc.)
 */
public record BehavioralFingerprint(
    @JsonProperty("baselineVitality") Map<String, Float> baselineVitality,
    @JsonProperty("baselineDerivatives") Map<String, Float> baselineDerivatives,
    @JsonProperty("observedSensitivity") Map<String, Float> observedSensitivity,
    @JsonProperty("actionDistribution") Map<String, Float> actionDistribution,
    @JsonProperty("topicAffinities") Map<String, Float> topicAffinities,
    @JsonProperty("avoidancePatterns") Map<String, Float> avoidancePatterns,
    @JsonProperty("averageResponseLength") float averageResponseLength,
    @JsonProperty("responseLatencyProfile") float responseLatencyProfile,
    @JsonProperty("stylisticMarkers") List<String> stylisticMarkers,
    @JsonProperty("emotionalResponseProfile") Map<String, Float> emotionalResponseProfile
) {
    @JsonCreator
    public BehavioralFingerprint {}

    /** Empty fingerprint for a new agent with no behavioral history. */
    public static BehavioralFingerprint empty() {
        return new BehavioralFingerprint(
            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
            0.0f, 0.0f, List.of(), Map.of()
        );
    }

    /**
     * Merge two fingerprints with weighting (for sleep cycle consolidation).
     * New observations weighted by alpha, existing by (1-alpha).
     *
     * @param existing The historical fingerprint
     * @param fresh    The newly extracted fingerprint
     * @param alpha    Weight for fresh data (0.3 = 30% new, 70% historical)
     * @return Merged fingerprint
     */
    public static BehavioralFingerprint merge(BehavioralFingerprint existing,
                                               BehavioralFingerprint fresh,
                                               float alpha) {
        return new BehavioralFingerprint(
            mergeMaps(existing.baselineVitality, fresh.baselineVitality, alpha),
            mergeMaps(existing.baselineDerivatives, fresh.baselineDerivatives, alpha),
            mergeMaps(existing.observedSensitivity, fresh.observedSensitivity, alpha),
            mergeMaps(existing.actionDistribution, fresh.actionDistribution, alpha),
            mergeMaps(existing.topicAffinities, fresh.topicAffinities, alpha),
            mergeMaps(existing.avoidancePatterns, fresh.avoidancePatterns, alpha),
            existing.averageResponseLength * (1 - alpha) + fresh.averageResponseLength * alpha,
            existing.responseLatencyProfile * (1 - alpha) + fresh.responseLatencyProfile * alpha,
            fresh.stylisticMarkers.isEmpty() ? existing.stylisticMarkers : fresh.stylisticMarkers,
            mergeMaps(existing.emotionalResponseProfile, fresh.emotionalResponseProfile, alpha)
        );
    }

    private static Map<String, Float> mergeMaps(Map<String, Float> a, Map<String, Float> b,
                                                  float alpha) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        var result = new LinkedHashMap<>(a);
        for (var entry : b.entrySet()) {
            result.merge(entry.getKey(), entry.getValue(),
                (old, fresh) -> old * (1 - alpha) + fresh * alpha);
        }
        return Map.copyOf(result);
    }
}
