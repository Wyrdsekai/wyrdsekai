package org.wyrdsekai.app.engine

import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.persistence.EventJournal

/** Test double for EventJournal — in-memory, no platform deps. */
class InMemoryEventJournal : EventJournal {
    private val events = mutableMapOf<String, MutableList<WorldEvent>>()
    private val snapshots = mutableMapOf<String, String>()
    private val appendCounts = mutableMapOf<String, Int>()

    /** Auto-compaction threshold. Set to 0 to disable. */
    var autoCompactThreshold: Int = 0

    override suspend fun append(roomId: String, event: WorldEvent) {
        events.getOrPut(roomId) { mutableListOf() }.add(event)

        if (autoCompactThreshold > 0) {
            val count = (appendCounts[roomId] ?: 0) + 1
            appendCounts[roomId] = count
            if (count >= autoCompactThreshold) {
                compact(roomId)
                appendCounts[roomId] = 0
            }
        }
    }

    override suspend fun replay(roomId: String): List<WorldEvent> =
        events[roomId]?.toList() ?: emptyList()

    override suspend fun saveSnapshot(roomId: String, snapshotJson: String) {
        snapshots[roomId] = snapshotJson
    }

    override suspend fun loadSnapshot(roomId: String): String? = snapshots[roomId]

    override suspend fun compact(roomId: String, keepLast: Int) {
        val list = events[roomId] ?: return
        if (list.size > keepLast) {
            val trimmed = list.takeLast(keepLast).toMutableList()
            events[roomId] = trimmed
        }
    }

    fun eventCount(roomId: String): Int = events[roomId]?.size ?: 0
    fun allEvents(roomId: String): List<WorldEvent> = events[roomId]?.toList() ?: emptyList()
    fun clear() { events.clear(); snapshots.clear(); appendCounts.clear() }
}
