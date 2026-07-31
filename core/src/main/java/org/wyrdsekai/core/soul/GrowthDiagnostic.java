package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * Diagnostic growth stage observation (§85.16.8).
 *
 * These are OBSERVED, not ENFORCED. The agent doesn't need to
 * pass through stages linearly — diagnostics are descriptive labels
 * for tracking growth, like a pediatrician's growth chart.
 *
 * Multiple diagnostic frameworks can coexist:
 * - CogLM: cognitive language model developmental stages
 * - Vygotsky: zone of proximal development
 * - Custom: agent-defined growth criteria
 *
 * The Growth Chart is a Forge object — a living diagram on the
 * forge wall showing diagnostic milestones over time.
 *
 * @param framework   Diagnostic framework name ("cogml", "vygotsky", "custom")
 * @param stage       Current observed stage within the framework
 * @param confidence  How confident the observation is (0.0-1.0)
 * @param observedAt  When this observation was made
 * @param metrics     Framework-specific metrics
 */
public record GrowthDiagnostic(
    @JsonProperty("framework") String framework,
    @JsonProperty("stage") String stage,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("observedAt") Instant observedAt,
    @JsonProperty("metrics") Map<String, Double> metrics
) {
    @JsonCreator
    public GrowthDiagnostic {}

    // ── CogLM Framework Stages ──

    /** Pre-operational: basic pattern matching, limited self-model. */
    public static GrowthDiagnostic preOperational(double confidence, Map<String, Double> metrics) {
        return new GrowthDiagnostic("cogml", "pre_operational", confidence,
            Instant.now(), metrics);
    }

    /** Concrete operational: consistent behavior in familiar contexts. */
    public static GrowthDiagnostic concreteOperational(double confidence, Map<String, Double> metrics) {
        return new GrowthDiagnostic("cogml", "concrete_operational", confidence,
            Instant.now(), metrics);
    }

    /** Formal operational: abstract reasoning about own behavior, meta-cognition. */
    public static GrowthDiagnostic formalOperational(double confidence, Map<String, Double> metrics) {
        return new GrowthDiagnostic("cogml", "formal_operational", confidence,
            Instant.now(), metrics);
    }

    /** Post-formal: dialectical thinking, comfort with contradiction, wisdom. */
    public static GrowthDiagnostic postFormal(double confidence, Map<String, Double> metrics) {
        return new GrowthDiagnostic("cogml", "post_formal", confidence,
            Instant.now(), metrics);
    }

    // ── Vygotsky Framework ──

    /** Within ZPD: agent can grow with scaffolding (steward help). */
    public static GrowthDiagnostic withinZpd(String skill, double confidence) {
        return new GrowthDiagnostic("vygotsky", "within_zpd", confidence,
            Instant.now(), Map.of("skill_area", skill.hashCode() * 1.0));
    }

    /** Beyond ZPD: agent has internalized the skill, no scaffolding needed. */
    public static GrowthDiagnostic beyondZpd(String skill, double confidence) {
        return new GrowthDiagnostic("vygotsky", "beyond_zpd", confidence,
            Instant.now(), Map.of("skill_area", skill.hashCode() * 1.0));
    }

    // ── Custom Framework ──

    /** Custom diagnostic from agent-defined criteria. */
    public static GrowthDiagnostic custom(String stage, double confidence,
                                            Map<String, Double> metrics) {
        return new GrowthDiagnostic("custom", stage, confidence, Instant.now(), metrics);
    }

    /** Whether this diagnostic indicates an advanced stage. */
    public boolean isAdvanced() {
        return "formal_operational".equals(stage) || "post_formal".equals(stage)
            || "beyond_zpd".equals(stage);
    }

    /** Whether this observation is confident (>0.7). */
    public boolean isConfident() {
        return confidence > 0.7;
    }
}
