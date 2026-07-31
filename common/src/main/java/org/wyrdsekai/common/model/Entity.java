package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * An entity (agent or player) present in a room.
 *
 * @param id          Entity ID
 * @param name        Display name
 * @param type        "player", "agent", "npc"
 * @param description Brief description of the entity
 * @param did         DID:key identifier (nullable — agents with AgentIdentity, §85.1)
 * @param aliases     Human-readable aliases for resolution (e.g. "wyrd", "companion").
 *                    Same MUD ordinal resolution: "tell 2.guard".
 * @param posture — optional body state (verb, atObject, descriptor
 *                    setAt, innerImprint). null means the entity has no explicit posture
 *                    set; renderers treat null as the default (standing, no decorator).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Entity(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("description") String description,
    @JsonProperty("did") String did,
    @JsonProperty("aliases") List<String> aliases,
    @JsonProperty("posture") Posture posture
) {

    /** Backward-compatible constructor without DID, aliases, or posture. */
    public Entity(String id, String name, String type, String description) {
        this(id, name, type, description, null, List.of(), null);
    }

    /** Backward-compatible constructor without aliases or posture. */
    public Entity(String id, String name, String type, String description, String did) {
        this(id, name, type, description, did, List.of(), null);
    }

    /** Backward-compatible constructor without posture. */
    public Entity(String id, String name, String type, String description,
                  String did, List<String> aliases) {
        this(id, name, type, description, did, aliases, null);
    }

    /** Jackson deserialization — defaults did, aliases, and posture when absent. */
    @JsonCreator
    public static Entity create(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("description") String description,
            @JsonProperty("did") String did,
            @JsonProperty("aliases") List<String> aliases,
            @JsonProperty("posture") Posture posture) {
        return new Entity(id, name, type, description, did,
            aliases != null ? aliases : List.of(),
            posture);
    }

    /** Return a copy of this entity with the given posture (or null to clear). */
    public Entity withPosture(Posture newPosture) {
        return new Entity(id, name, type, description, did, aliases, newPosture);
    }
}
