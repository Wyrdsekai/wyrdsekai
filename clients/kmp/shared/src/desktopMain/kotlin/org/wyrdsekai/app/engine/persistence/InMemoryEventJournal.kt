package org.wyrdsekai.app.engine.persistence

import org.wyrdsekai.app.engine.event.WorldEvent

/**
 * In-memory event journal for desktop.
 * Desktop runs the full server subprocess — this is only used if
 * the extracted engine is run for testing purposes.
 */
class InMemoryEventJournal : EventJournal {
    private val events = mutableMapOf<String, MutableList<WorldEvent>>()
    private val snapshots = mutableMapOf<String, String>()

    override suspend fun append(roomId: String, event: WorldEvent) {
        events.getOrPut(roomId) { mutableListOf() }.add(event)
    }

    override suspend fun replay(roomId: String): List<WorldEvent> {
        return events[roomId]?.toList() ?: emptyList()
    }

    override suspend fun saveSnapshot(roomId: String, snapshotJson: String) {
        snapshots[roomId] = snapshotJson
    }

    override suspend fun loadSnapshot(roomId: String): String? {
        return snapshots[roomId]
    }

    override suspend fun compact(roomId: String, keepLast: Int) {
        val list = events[roomId] ?: return
        if (list.size > keepLast) {
            val trimmed = list.takeLast(keepLast).toMutableList()
            events[roomId] = trimmed
        }
    }
}
