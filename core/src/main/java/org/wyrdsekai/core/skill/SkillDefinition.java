package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Definition of a skill — what it does, where it lives, what it needs.
 * Skills are the bridge between rooms and real-world capabilities.
 *
 * @param id          Dot-separated skill ID (e.g., "hearth.ha.set-light")
 * @param name        Human-readable name
 * @param description What this skill does
 * @param room        Target room (e.g., "hearth", "herald", "vault", "library")
 * @param tier        Execution tier: NATIVE, CLI, OPENCLAW
 * @param origin      Who built it (e.g., "wyrdsekai", "openclaw/openhue")
 * @param license     License (e.g., "Apache-2.0", "MIT")
 * @param params      Parameters this skill accepts
 * @param auth        What credentials are needed
 * @param locality    Where this skill can execute
 * @param schedulable Whether this skill can be used with SchedulerService
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillDefinition(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("room") String room,
    @JsonProperty("tier") SkillTier tier,
    @JsonProperty("origin") String origin,
    @JsonProperty("license") String license,
    @JsonProperty("params") List<SkillParam> params,
    @JsonProperty("auth") SkillAuth auth,
    @JsonProperty("locality") SkillLocality locality,
    @JsonProperty("schedulable") boolean schedulable
) {
    @JsonCreator
    public SkillDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Skill ID required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Skill name required");
        if (tier == null) tier = SkillTier.NATIVE;
        if (origin == null) origin = "wyrdsekai";
        if (params == null) params = List.of();
        if (locality == null) locality = SkillLocality.ANY;
    }

    /** Convenience: create a native skill definition with minimal fields. */
    public static SkillDefinition native_(String id, String name, String description,
                                           String room, List<SkillParam> params,
                                           SkillAuth auth) {
        return new SkillDefinition(id, name, description, room, SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", params, auth, SkillLocality.ANY, true);
    }

    /** Check if this skill requires authentication. */
    public boolean requiresAuth() {
        return auth != null && auth.credentialKey() != null;
    }

    /** Get the room prefix from the skill ID (e.g., "hearth" from "hearth.ha.set-light"). */
    public String roomPrefix() {
        int dot = id.indexOf('.');
        return dot > 0 ? id.substring(0, dot) : id;
    }
}
