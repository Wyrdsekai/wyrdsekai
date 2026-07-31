package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A parameter that a skill accepts.
 *
 * @param name        Parameter name (e.g., "light", "query", "entity_id")
 * @param type        Type: "string", "number", "boolean", "enum"
 * @param description Human-readable description
 * @param required    Whether this parameter must be provided
 * @param enumValues  Valid values for type "enum"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillParam(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("description") String description,
    @JsonProperty("required") boolean required,
    @JsonProperty("enumValues") List<String> enumValues
) {
    @JsonCreator
    public SkillParam {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Param name required");
        if (type == null) type = "string";
        if (enumValues == null) enumValues = List.of();
    }

    public static SkillParam required(String name, String type, String description) {
        return new SkillParam(name, type, description, true, List.of());
    }

    public static SkillParam optional(String name, String type, String description) {
        return new SkillParam(name, type, description, false, List.of());
    }

    public static SkillParam enum_(String name, String description, boolean required,
                                    List<String> values) {
        return new SkillParam(name, "enum", description, required, values);
    }
}
