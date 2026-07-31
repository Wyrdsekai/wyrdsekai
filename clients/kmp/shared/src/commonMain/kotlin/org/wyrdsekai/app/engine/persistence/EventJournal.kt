package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.event.WorldEvent

/**
 * Event journal for room event sourcing.
 * Platform-specific implementations: SQLite on iOS, stubs on desktop/android
 * (those platforms run the full server subprocess instead).
 *
 * Snapshot methods use serialized JSON strings to avoid circular dependency
 * with RoomState (which depends on WorldEvent from this package's sibling).
 */
interface EventJournal {
    suspend fun append(roomId: String, event: WorldEvent)
    suspend fun replay(roomId: String): List<WorldEvent>
    suspend fun saveSnapshot(roomId: String, snapshotJson: String)
    suspend fun loadSnapshot(roomId: String): String?

    /**
     * Compacts the journal for a room, keeping only the most recent [keepLast] events.
     * Default implementation is a no-op for backward compatibility.
     */
    suspend fun compact(roomId: String, keepLast: Int = 500) { /* no-op default */ }
}
