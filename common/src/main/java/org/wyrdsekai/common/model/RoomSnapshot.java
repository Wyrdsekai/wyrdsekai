package org.wyrdsekai.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Full room state snapshot, sent on entry or reconnect.
 *
 * @param roomId      Room identifier (persistence key, zone-unique)
 * @param name        Room display name
 * @param description Current room description (may change based on state)
 * @param zone        Zone this room belongs to
 * @param aliases     Human-readable aliases for resolution (e.g. "docks", "port")
 * @param exits       Available exits
 * @param entities    Entities currently in the room
 * @param objects     Interactive objects
 * @param hints       Current contextual hints (§65.2)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoomSnapshot(
    @JsonProperty("roomId") String roomId,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("zone") String zone,
    @JsonProperty("aliases") List<String> aliases,
    @JsonProperty("exits") List<Exit> exits,
    @JsonProperty("entities") List<Entity> entities,
    @JsonProperty("objects") List<RoomObject> objects,
    @JsonProperty("hints") List<Hint> hints
) {
    /** Backward-compatible constructor — no aliases. */
    public RoomSnapshot(String roomId, String name, String description, String zone,
                        List<Exit> exits, List<Entity> entities,
                        List<RoomObject> objects, List<Hint> hints) {
        this(roomId, name, description, zone, List.of(), exits, entities, objects, hints);
    }

    /** Jackson deserialization — defaults aliases to empty list when absent. */
    @JsonCreator
    public static RoomSnapshot create(
            @JsonProperty("roomId") String roomId,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("zone") String zone,
            @JsonProperty("aliases") List<String> aliases,
            @JsonProperty("exits") List<Exit> exits,
            @JsonProperty("entities") List<Entity> entities,
            @JsonProperty("objects") List<RoomObject> objects,
            @JsonProperty("hints") List<Hint> hints) {
        return new RoomSnapshot(roomId, name, description, zone,
            aliases != null ? aliases : List.of(),
            exits != null ? exits : List.of(),
            entities != null ? entities : List.of(),
            objects != null ? objects : List.of(),
            hints != null ? hints : List.of());
    }
}
