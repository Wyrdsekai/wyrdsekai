package org.wyrdsekai.core.item;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.ArrayList;
import java.util.Map;

/**
 * Codec for reagent item JSON stored in SoulItem.text.
 * A reagent SoulItem's text field contains a JSON document describing
 * a consumable temporary effect (vitality boost, duration, prompt overlay).
 *
 * Follows the same pattern as {@link org.wyrdsekai.core.skill.SkillItemCodec}.
 */
public final class ReagentItemCodec {

    private ReagentItemCodec() {}

    /**
     * Decoded reagent definition from a SoulItem's text field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReagentDefinition(
        @JsonProperty("version") int version,
        @JsonProperty("vitalityEffects") Map<String, Double> vitalityEffects,
        @JsonProperty("durationTicks") int durationTicks,
        @JsonProperty("promptOverlay") String promptOverlay,
        @JsonProperty("consumable") boolean consumable,
        @JsonProperty("tokenEstimate") int tokenEstimate
    ) {
        @JsonCreator
        public ReagentDefinition {
            if (vitalityEffects == null) vitalityEffects = Map.of();
            if (durationTicks <= 0) durationTicks = 300; // ~5 minutes default
            if (tokenEstimate <= 0) tokenEstimate = 10;
        }

        /** Maximum allowed duration: 1800 ticks (~30 minutes). */
        public static final int MAX_DURATION = 1800;

        /** Whether this reagent injects prompt text while active. */
        public boolean hasPromptOverlay() {
            return promptOverlay != null && !promptOverlay.isBlank();
        }

        /** Clamped duration (respects MAX_DURATION). */
        public int effectiveDuration() {
            return Math.min(durationTicks, MAX_DURATION);
        }
    }

    /**
     * Decode a SoulItem's text field into a ReagentDefinition.
     *
     * @return decoded definition, or null if invalid
     */
    public static ReagentDefinition decode(SoulItem item) {
        if (item == null || item.text() == null || item.text().isBlank()) return null;
        if (!"reagent".equals(item.category())) return null;
        return decode(item.text());
    }

    /**
     * Decode a JSON string into a ReagentDefinition.
     */
    public static ReagentDefinition decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.mapper().readValue(json, ReagentDefinition.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Encode a ReagentDefinition to JSON string (for SoulItem.text).
     */
    public static String encode(ReagentDefinition def) {
        try {
            return Json.mapper().writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode reagent definition", e);
        }
    }

    /**
     * Create a new ReagentDefinition.
     */
    public static ReagentDefinition create(Map<String, Double> vitalityEffects,
                                             int durationTicks,
                                             String promptOverlay,
                                             boolean consumable,
                                             int tokenEstimate) {
        return new ReagentDefinition(1, vitalityEffects,
            Math.min(durationTicks, ReagentDefinition.MAX_DURATION),
            promptOverlay, consumable, tokenEstimate);
    }

    /**
     * Build a SoulItem for a reagent.
     */
    public static SoulItem toSoulItem(String name, ReagentDefinition def,
                                        String creatorDid, double significance) {
        String json = encode(def);
        var tags = new ArrayList<String>();
        tags.add(name.toLowerCase().replace(' ', '-'));
        tags.add("reagent");
        if (def.consumable()) tags.add("consumable");
        return SoulItem.create("reagent", name, json, creatorDid, significance,
            tags.toArray(new String[0]));
    }
}
