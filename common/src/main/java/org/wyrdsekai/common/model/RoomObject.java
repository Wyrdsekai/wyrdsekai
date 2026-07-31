package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An interactive object in a room.
 *
 * @param id          Object ID (zone-unique)
 * @param name        Display name
 * @param description Brief description
 * @param takeable    Whether the object can be picked up
 * @param visible     Whether the object is listed in room descriptions (default true).
 *                    Invisible objects still respond to 'use' and speech trigger words.
 *                    Used for ambient interfaces ("computer"), hidden mechanisms, etc.
 * @param cloneable   Whether taking creates a copy (true) or transfers ownership (false).
 *                    Cloneable items are blueprints — everyone can take a copy,
 *                    the original stays in the room. Unique items (false) require
 *                    primary-node coordination for ownership transfer.
 *                    Default: true for takeable items (most items are tools/utilities).
 * @param aliases     Human-readable aliases for resolution (e.g. "sword", "iron sword").
 *                    NOT enforced unique — items move between rooms.
 *                    Collision resolved via MUD-style ordinals: "take 2.sword".
 * @param state — free-form object-side state, set by scripts and
 *                    consumed by scripts/renderers. Engine does not interpret it. Common keys:
 *                    {@code sittable, leanable, liedown} (boolean affordances surfaced as hints),
 *                    {@code lit, open, hot, occupied} (transient world state).
 *                    Defaults to empty map for backward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomObject(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("takeable") boolean takeable,
    @JsonProperty("visible") boolean visible,
    @JsonProperty("cloneable") boolean cloneable,
    @JsonProperty("aliases") List<String> aliases,
    @JsonProperty("state") Map<String, String> state
) {

    /** Compact constructor — defensively copies the state map so callers can't mutate it after construction. */
    public RoomObject {
        state = state == null ? Map.of() : Map.copyOf(state);
    }

    /** Backward-compatible constructor — visible=true, cloneable=true, no aliases, no state. */
    public RoomObject(String id, String name, String description, boolean takeable) {
        this(id, name, description, takeable, true, true, List.of(), Map.of());
    }

    /** Backward-compatible constructor — cloneable defaults to true, no aliases, no state. */
    public RoomObject(String id, String name, String description, boolean takeable, boolean visible) {
        this(id, name, description, takeable, visible, true, List.of(), Map.of());
    }

    /** Backward-compatible constructor — no aliases, no state. */
    public RoomObject(String id, String name, String description, boolean takeable,
                      boolean visible, boolean cloneable) {
        this(id, name, description, takeable, visible, cloneable, List.of(), Map.of());
    }

    /** Backward-compatible constructor — no state. */
    public RoomObject(String id, String name, String description, boolean takeable,
                      boolean visible, boolean cloneable, List<String> aliases) {
        this(id, name, description, takeable, visible, cloneable, aliases, Map.of());
    }

    /**
     * Jackson deserialization — defaults visible, cloneable, aliases, and state when not present.
     */
    @JsonCreator
    public static RoomObject create(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("takeable") boolean takeable,
        @JsonProperty("visible") Boolean visible,
        @JsonProperty("cloneable") Boolean cloneable,
        @JsonProperty("aliases") List<String> aliases,
        @JsonProperty("state") Map<String, String> state
    ) {
        return new RoomObject(id, name, description, takeable,
            visible != null ? visible : true,
            cloneable != null ? cloneable : true,
            aliases != null ? aliases : List.of(),
            state != null ? state : Map.of());
    }

    /** Create a unique (non-cloneable) item. */
    public static RoomObject unique(String id, String name, String description, boolean takeable) {
        return new RoomObject(id, name, description, takeable, true, false, List.of(), Map.of());
    }

    /** Return a copy of this object with the given state map (replacing any existing state). */
    public RoomObject withState(Map<String, String> newState) {
        return new RoomObject(id, name, description, takeable, visible, cloneable, aliases, newState);
    }

    /** Return a copy of this object with one state key set (additive). */
    public RoomObject withStateKey(String key, String value) {
        var merged = new HashMap<>(state);
        merged.put(key, value);
        return new RoomObject(id, name, description, takeable, visible, cloneable, aliases, merged);
    }

    /** True if this object's state flag matches "true" (case-insensitive). */
    public boolean isFlag(String key) {
        var v = state.get(key);
        return v != null && Boolean.parseBoolean(v);
    }
}
