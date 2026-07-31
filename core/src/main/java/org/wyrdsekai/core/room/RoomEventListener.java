package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;

/**
 * Callback interface for room event publication.
 *
 * <p>The core module cannot depend on the between module, so this interface
 * bridges room event persistence in {@link RoomActor} to the Between replication
 * layer. The between module provides the implementation (e.g., publishing events
 * to NATS for event-sourced rooms, or triggering snapshot publication for
 * write-through rooms).</p>
 *
 * <p>The listener is invoked <em>after</em> an event is persisted, inside the
 * {@code thenRun} callback. It must not block.</p>
 */
@FunctionalInterface
public interface RoomEventListener {

    /**
     * Called after a room event is persisted.
     *
     * @param roomId the room where the event occurred
     * @param event  the persisted world event
     */
    void onRoomEvent(String roomId, WorldEvent event);
}
