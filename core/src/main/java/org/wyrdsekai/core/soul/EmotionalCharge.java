package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Result of emotional charge assessment via MirrorResonance.
 * The LLM-as-mirror-neuron produces this structured assessment of
 * how an observed event emotionally affects this agent.
 *
 * Key design (Experiment 18):
 * - isSignificant() gates charge processing: intensity > 0.2 AND context not noise/manipulative
 * - effectivePerturbation() scales by rapport (bond strength) with 5% floor for strangers
 * - Gaming resistance comes from contextType classification, not intensity suppression
 *
 * @param intensity          Emotional intensity (0.0-1.0)
 * @param primaryEmotion     Dominant emotion: grief, joy, fear, anger, resignation, mixed, none
 * @param contextType        Source classification: genuine, academic, performative, manipulative, noise
 * @param confidence         Scorer confidence (0.0-1.0)
 * @param tankPerturbations  Per-tank deltas (not absolute values)
 * @param reasoning          Free-form explanation of assessment
 */
public record EmotionalCharge(
    @JsonProperty("intensity") float intensity,
    @JsonProperty("primaryEmotion") String primaryEmotion,
    @JsonProperty("contextType") String contextType,
    @JsonProperty("confidence") float confidence,
    @JsonProperty("tankPerturbations") Map<String, Double> tankPerturbations,
    @JsonProperty("reasoning") String reasoning
) {
    @JsonCreator
    public EmotionalCharge {}

    /** No emotional charge — neutral event. */
    public static EmotionalCharge none() {
        return new EmotionalCharge(0.0f, "none", "genuine", 1.0f, Map.of(), "No emotional charge detected.");
    }

    /**
     * Whether this charge should be processed by the vitality system.
     * This is the gaming resistance gate: noise and manipulation are blocked
     * regardless of intensity. Context classification IS the defense.
     */
    @JsonIgnore
    public boolean isSignificant() {
        return intensity > 0.2f
            && !"noise".equals(contextType)
            && !"manipulative".equals(contextType);
    }

    /**
     * Tank perturbations scaled by rapport (bond strength).
     * Even strangers get 5% floor — you can still be moved by someone
     * you don't know, just not as much.
     *
     * @param rapport Bond strength with the observed entity (0.0-1.0)
     * @return Scaled perturbation map
     */
    public Map<String, Double> effectivePerturbation(double rapport) {
        double scale = Math.max(0.05, rapport);
        return tankPerturbations.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() * scale));
    }
}
