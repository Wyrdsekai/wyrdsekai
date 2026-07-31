package org.wyrdsekai.core.room;

import java.util.List;
import java.util.Map;

/**
 * A room template from the standard library.
 *
 * <p>Room templates define the structure and behavior of a room type.
 * Like item templates, they use {@code inherit("std/room/library")} to load
 * base behavior. The creator configures via setters and overrides hooks.</p>
 *
 * @param name          Template name (e.g., "library", "hub", "garden")
 * @param displayName   Human-readable name (e.g., "Library", "Central Hub")
 * @param description   What this room type is for
 * @param baseScript    Path in scripts/std/room/ (e.g., "std/room/library")
 * @param defaultObjects Default room objects (id, name, description, takeable)
 * @param defaultImprint Default trait weights for the room's personality
 * @param defaultConfig  Default configuration key-value pairs
 */
public record RoomTemplate(
    String name,
    String displayName,
    String description,
    String baseScript,
    List<DefaultObject> defaultObjects,
    Map<String, Double> defaultImprint,
    Map<String, String> defaultConfig
) {
    /**
     * A default object in a room template.
     */
    public record DefaultObject(
        String id,
        String name,
        String description,
        boolean takeable
    ) {}
}
