package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * A growth event in the Crucible lifecycle (§85.16.8).
 *
 * Records what happened during agent self-modification.
 * Everything is logged — growth is transparent, never hidden.
 * The agent signs every event to prove sovereignty over their growth.
 *
 * Types:
 * - crucible_start:    Agent begins a growth cycle
 * - variant_generated: A behavioral variant was created
 * - variant_evaluated: A variant was tested (fitness score)
 * - crucible_adopt:    Agent chose to adopt a variant
 * - crucible_discard:  Agent chose to discard a variant
 * - crucible_modify:   Agent modified a variant before adopting
 * - level_change:      Modification level changed (1→2, 2→3)
 * - milestone:         Diagnostic growth stage transition
 *
 * @param timestamp    When the event occurred
 * @param type         Event type (from list above)
 * @param agentDid     The agent undergoing growth
 * @param description  Human-readable description
 * @param experimentId Link to CodeZaiku experiment (null for Level 1)
 * @param variantId    Variant involved (null for lifecycle events)
 * @param metrics      Arbitrary metrics (fitness, divergence, etc.)
 * @param signature    Agent's Ed25519 signature over this event
 */
public record GrowthEvent(
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("type") String type,
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("description") String description,
    @JsonProperty("experimentId") String experimentId,
    @JsonProperty("variantId") String variantId,
    @JsonProperty("metrics") Map<String, Double> metrics,
    @JsonProperty("signature") byte[] signature
) {
    @JsonCreator
    public GrowthEvent {}

    public static GrowthEvent crucibleStart(String agentDid, String description) {
        return new GrowthEvent(Instant.now(), "crucible_start", agentDid,
            description, null, null, Map.of(), null);
    }

    public static GrowthEvent variantGenerated(String agentDid, String variantId,
                                                 String experimentId, int level) {
        return new GrowthEvent(Instant.now(), "variant_generated", agentDid,
            "Level " + level + " variant generated", experimentId, variantId,
            Map.of("level", (double) level), null);
    }

    public static GrowthEvent variantEvaluated(String agentDid, String variantId,
                                                 double fitness, double regression) {
        return new GrowthEvent(Instant.now(), "variant_evaluated", agentDid,
            "Variant evaluated: fitness=" + String.format("%.3f", fitness),
            null, variantId,
            Map.of("fitness", fitness, "regression", regression), null);
    }

    public static GrowthEvent adopted(String agentDid, String variantId,
                                        String experimentId) {
        return new GrowthEvent(Instant.now(), "crucible_adopt", agentDid,
            "Agent adopted variant " + variantId, experimentId, variantId,
            Map.of(), null);
    }

    public static GrowthEvent discarded(String agentDid, String variantId, String reason) {
        return new GrowthEvent(Instant.now(), "crucible_discard", agentDid,
            "Agent discarded variant: " + reason, null, variantId,
            Map.of(), null);
    }

    public static GrowthEvent modified(String agentDid, String variantId,
                                         String whatChanged) {
        return new GrowthEvent(Instant.now(), "crucible_modify", agentDid,
            "Agent modified variant before adopting: " + whatChanged,
            null, variantId, Map.of(), null);
    }

    public static GrowthEvent milestone(String agentDid, String stage,
                                          double confidence) {
        return new GrowthEvent(Instant.now(), "milestone", agentDid,
            "Growth milestone: " + stage, null, null,
            Map.of("confidence", confidence), null);
    }

    /** Attach an Ed25519 signature. */
    public GrowthEvent signed(byte[] sig) {
        return new GrowthEvent(timestamp, type, agentDid, description,
            experimentId, variantId, metrics, sig);
    }

    /** Whether this is an adoption event. */
    public boolean isAdoption() {
        return "crucible_adopt".equals(type) || "crucible_modify".equals(type);
    }

    /** Whether this event has been signed by the agent. */
    public boolean isSigned() {
        return signature != null && signature.length > 0;
    }
}
