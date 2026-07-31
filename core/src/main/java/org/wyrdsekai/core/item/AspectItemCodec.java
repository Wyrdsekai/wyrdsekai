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
 * Codec for aspect item JSON stored in SoulItem.text.
 * An aspect SoulItem's text field contains a JSON document describing
 * an equippable persona overlay (prompt text, vitality shifts, appearance).
 *
 * Follows the same pattern as {@link org.wyrdsekai.core.skill.SkillItemCodec}.
 */
public final class AspectItemCodec {

    private AspectItemCodec() {}

    /**
     * Decoded aspect definition from a SoulItem's text field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AspectDefinition(
        @JsonProperty("version") int version,
        @JsonProperty("promptOverlay") String promptOverlay,
        @JsonProperty("vitalityShifts") Map<String, Double> vitalityShifts,
        @JsonProperty("selfDescription") String selfDescription,
        @JsonProperty("slotHint") String slotHint,
        @JsonProperty("tokenEstimate") int tokenEstimate
    ) {
        @JsonCreator
        public AspectDefinition {
            if (vitalityShifts == null) vitalityShifts = Map.of();
            if (slotHint == null) slotHint = "garment";
            if (tokenEstimate <= 0) tokenEstimate = 20;
        }

        /** Whether this aspect injects prompt text. */
        public boolean hasPromptOverlay() {
            return promptOverlay != null && !promptOverlay.isBlank();
        }

        /** Whether this aspect modifies vitality baselines. */
        public boolean hasVitalityShifts() {
            return !vitalityShifts.isEmpty();
        }
    }

    /**
     * Decode a SoulItem's text field into an AspectDefinition.
     *
     * @return decoded definition, or null if invalid
     */
    public static AspectDefinition decode(SoulItem item) {
        if (item == null || item.text() == null || item.text().isBlank()) return null;
        if (!"aspect".equals(item.category())) return null;
        return decode(item.text());
    }

    /**
     * Decode a JSON string into an AspectDefinition.
     */
    public static AspectDefinition decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.mapper().readValue(json, AspectDefinition.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Encode an AspectDefinition to JSON string (for SoulItem.text).
     */
    public static String encode(AspectDefinition def) {
        try {
            return Json.mapper().writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode aspect definition", e);
        }
    }

    /**
     * Create a new AspectDefinition.
     */
    public static AspectDefinition create(String promptOverlay,
                                           Map<String, Double> vitalityShifts,
                                           String selfDescription,
                                           String slotHint,
                                           int tokenEstimate) {
        return new AspectDefinition(1, promptOverlay, vitalityShifts,
            selfDescription, slotHint, tokenEstimate);
    }

    /**
     * Build a SoulItem for a validated aspect.
     */
    public static SoulItem toSoulItem(String name, AspectDefinition def,
                                        String creatorDid, double significance) {
        String json = encode(def);
        var tags = new ArrayList<String>();
        tags.add(name.toLowerCase().replace(' ', '-'));
        if (def.slotHint() != null) tags.add(def.slotHint());
        if (def.selfDescription() != null) {
            for (var word : def.selfDescription().toLowerCase().split("\\s+")) {
                if (word.length() > 3 && tags.size() < 8) tags.add(word);
            }
        }
        return SoulItem.create("aspect", name, json, creatorDid, significance,
            tags.toArray(new String[0]));
    }
}
