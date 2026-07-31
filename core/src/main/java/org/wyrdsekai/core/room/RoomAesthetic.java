package org.wyrdsekai.core.room;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Room-level aesthetic override — the "holodeck effect."
 * When a companion enters a room with a RoomAesthetic, it replaces the zone aesthetic.
 * When they leave, the zone aesthetic reverts.
 *
 * A room aesthetic is a partial override: only non-null fields replace the zone defaults.
 */
public record RoomAesthetic(
    @JsonProperty("roomId") String roomId,
    @JsonProperty("aesthetic") ZoneAesthetic aesthetic
) {
    @JsonCreator
    public RoomAesthetic {}

    /**
     * Resolve the effective aesthetic for a room.
     * Room aesthetic overrides zone aesthetic if present.
     *
     * @param zoneAesthetic the zone's default aesthetic
     * @param roomAesthetic the room's override (nullable)
     * @return the effective aesthetic for this room
     */
    public static ZoneAesthetic resolve(ZoneAesthetic zoneAesthetic, RoomAesthetic roomAesthetic) {
        if (roomAesthetic == null || roomAesthetic.aesthetic() == null) {
            return zoneAesthetic != null ? zoneAesthetic : ZoneAesthetic.none();
        }
        return roomAesthetic.aesthetic();
    }
}
