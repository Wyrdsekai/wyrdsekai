package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;

/**
 * Persistence wrapper for room events.
 * This wraps WorldEvent for Pekko Persistence event sourcing.
 * The roomId is used as the persistence entity ID.
 */
public record RoomEvent(WorldEvent event) {}
