package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;

/**
 * Notifications sent to subscribers when room events occur.
 * Session actors subscribe to receive these and forward as S2C messages.
 */
public record RoomNotification(WorldEvent event) {}
