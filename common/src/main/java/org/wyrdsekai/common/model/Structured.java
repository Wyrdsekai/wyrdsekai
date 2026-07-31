package org.wyrdsekai.common.model;

import java.util.List;
import java.util.Map;

/**
 * Machine-parseable room data for accessibility (§66.2).
 * Allows screen readers and alternative UIs to present room state structurally.
 *
 * @param name        Room name
 * @param description Room description
 * @param exits       Available exits
 * @param entities    Entities (agents/players) present
 * @param objects     Interactive objects in the room
 * @param hints       Current contextual hints
 * @param properties  Room properties (key-value metadata)
 * @param zone        Zone this room belongs to
 */
public record Structured(
    String name,
    String description,
    List<Exit> exits,
    List<Entity> entities,
    List<RoomObject> objects,
    List<Hint> hints,
    Map<String, String> properties,
    String zone
) {
    /** Backward-compatible 5-param constructor. */
    public Structured(String name, String description, List<Exit> exits,
                      List<Entity> entities, List<RoomObject> objects) {
        this(name, description, exits, entities, objects, List.of(), Map.of(), "");
    }

    /** Create from a RoomSnapshot. */
    public static Structured fromSnapshot(RoomSnapshot snapshot) {
        return new Structured(
            snapshot.name(),
            snapshot.description(),
            snapshot.exits(),
            snapshot.entities(),
            snapshot.objects(),
            snapshot.hints(),
            Map.of(),
            snapshot.zone()
        );
    }
}
