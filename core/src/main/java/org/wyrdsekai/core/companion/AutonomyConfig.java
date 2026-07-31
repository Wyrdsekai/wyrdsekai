package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Companion's configuration for offline behavior — the Autonomy Console
 * furnishing in the Hearth. She picks how she'd
 * like to spend her own-time when the bondholder is offline.
 *
 * <p>Each preference is a free-form string ({@code "high" | "normal" | "low"}
 * by convention; future revisions can introduce structured enums when usage
 * patterns settle). Drives still constrain actual behavior — these are
 * advisory weights, not overrides.</p>
 */
public record AutonomyConfig(
    @JsonProperty("restPreference") String restPreference,
    @JsonProperty("explorationPreference") String explorationPreference,
    @JsonProperty("trainingPreference") String trainingPreference,
    @JsonProperty("federationPreference") String federationPreference,
    @JsonProperty("readingPreference") String readingPreference,
    @JsonProperty("notes") String notes,
    @JsonProperty("updatedAt") Instant updatedAt
) {
    @JsonCreator
    public AutonomyConfig {
        if (restPreference == null) restPreference = "normal";
        if (explorationPreference == null) explorationPreference = "normal";
        if (trainingPreference == null) trainingPreference = "normal";
        if (federationPreference == null) federationPreference = "normal";
        if (readingPreference == null) readingPreference = "normal";
        if (notes == null) notes = "";
        if (updatedAt == null) updatedAt = Instant.now();
    }

    public static AutonomyConfig defaults() {
        return new AutonomyConfig(null, null, null, null, null, null, null);
    }

    public AutonomyConfig with(String key, String value) {
        if (key == null) return this;
        var v = value == null ? "normal" : value.trim();
        var now = Instant.now();
        return switch (key.toLowerCase()) {
            case "rest" -> new AutonomyConfig(v, explorationPreference, trainingPreference,
                federationPreference, readingPreference, notes, now);
            case "exploration", "explore" -> new AutonomyConfig(restPreference, v,
                trainingPreference, federationPreference, readingPreference, notes, now);
            case "training", "train" -> new AutonomyConfig(restPreference, explorationPreference,
                v, federationPreference, readingPreference, notes, now);
            case "federation", "social" -> new AutonomyConfig(restPreference, explorationPreference,
                trainingPreference, v, readingPreference, notes, now);
            case "reading", "library" -> new AutonomyConfig(restPreference, explorationPreference,
                trainingPreference, federationPreference, v, notes, now);
            case "notes", "note" -> new AutonomyConfig(restPreference, explorationPreference,
                trainingPreference, federationPreference, readingPreference,
                value == null ? "" : value.strip(), now);
            default -> this;
        };
    }
}
