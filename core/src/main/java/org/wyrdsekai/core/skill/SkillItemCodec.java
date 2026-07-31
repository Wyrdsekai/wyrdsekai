package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.soul.SoulItem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Codec for the skill item JSON format stored in SoulItem.text.
 * A skill SoulItem's text field contains a JSON document describing
 * a companion-created capability (code, params, tests, metadata).
 */
public final class SkillItemCodec {

    private SkillItemCodec() {}

    /**
     * Decoded skill definition from a SoulItem's text field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillDefinition(
        @JsonProperty("version") int version,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("code") String code,
        @JsonProperty("params") List<Param> params,
        @JsonProperty("description") String description,
        @JsonProperty("testCases") List<TestCase> testCases,
        @JsonProperty("dependencies") List<String> dependencies,
        @JsonProperty("usageCount") int usageCount,
        @JsonProperty("lastUsed") Instant lastUsed
    ) {
        @JsonCreator
        public SkillDefinition {}

        /** Create with incremented usage count and updated timestamp. */
        public SkillDefinition withUsage() {
            return new SkillDefinition(version, runtime, code, params, description,
                testCases, dependencies, usageCount + 1, Instant.now());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Param(
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("description") String description,
        @JsonProperty("required") boolean required
    ) {
        @JsonCreator
        public Param {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TestCase(
        @JsonProperty("params") Map<String, Object> params,
        @JsonProperty("expectSuccess") boolean expectSuccess,
        @JsonProperty("expectContains") String expectContains
    ) {
        @JsonCreator
        public TestCase {}
    }

    /**
     * Decode a SoulItem's text field into a SkillDefinition.
     *
     * @return decoded definition, or null if the text is not valid skill JSON
     */
    public static SkillDefinition decode(SoulItem item) {
        if (item == null || item.text() == null || item.text().isBlank()) return null;
        if (!"skill".equals(item.category())) return null;
        return decode(item.text());
    }

    /**
     * Decode a JSON string into a SkillDefinition.
     */
    public static SkillDefinition decode(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.mapper().readValue(json, SkillDefinition.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Encode a SkillDefinition to JSON string (for SoulItem.text).
     */
    public static String encode(SkillDefinition def) {
        try {
            return Json.mapper().writeValueAsString(def);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode skill definition", e);
        }
    }

    /**
     * Create a new SkillDefinition for initial storage.
     */
    public static SkillDefinition create(String runtime, String code,
                                           List<Param> params, String description,
                                           List<TestCase> testCases,
                                           List<String> dependencies) {
        return new SkillDefinition(1, runtime, code,
            params != null ? params : List.of(),
            description,
            testCases != null ? testCases : List.of(),
            dependencies != null ? dependencies : List.of(),
            0, null);
    }

    /**
     * Build a SoulItem for a validated skill.
     */
    public static SoulItem toSoulItem(String skillName, SkillDefinition def,
                                        String creatorDid) {
        String json = encode(def);
        var tags = new ArrayList<String>();
        tags.add(skillName);
        if (def.description() != null) {
            for (var word : def.description().toLowerCase().split("\\s+")) {
                if (word.length() > 3 && tags.size() < 10) tags.add(word);
            }
        }
        return SoulItem.create("skill", skillName, json, creatorDid, 0.6,
            tags.toArray(new String[0]));
    }
}
