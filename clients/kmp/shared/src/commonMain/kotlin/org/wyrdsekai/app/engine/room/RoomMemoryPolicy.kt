package org.wyrdsekai.app.engine.room

import org.wyrdsekai.app.engine.event.WorldEvent

/**
 * Three-tier conversation buffer for room memory management.
 * Port of core/room/RoomMemoryPolicy.java.
 *
 * Hot: last N messages (full detail, high priority for prompts)
 * Warm: next M messages (available but lower priority)
 * Compacted: oldest messages, summarized or dropped
 */
class RoomMemoryPolicy(
    private val hotSize: Int = 10,
    private val warmSize: Int = 20,
) {
    private val _hotEvents = mutableListOf<WorldEvent.Said>()
    private val _warmEvents = mutableListOf<WorldEvent.Said>()

    fun add(event: WorldEvent.Said) {
        _hotEvents.add(event)
        // Cascade: hot overflows to warm, warm overflows to compacted (dropped)
        while (_hotEvents.size > hotSize) {
            val overflow = _hotEvents.removeFirst()
            _warmEvents.add(overflow)
        }
        while (_warmEvents.size > warmSize) {
            _warmEvents.removeFirst() // compacted = dropped for phone node
        }
    }

    /** Recent messages for prompt assembly. */
    fun hotEvents(): List<WorldEvent.Said> = _hotEvents.toList()

    /** Older messages available for expanded context. */
    fun warmEvents(): List<WorldEvent.Said> = _warmEvents.toList()

    /** All retained messages (hot + warm). */
    fun allEvents(): List<WorldEvent.Said> = _warmEvents + _hotEvents

    fun clear() {
        _hotEvents.clear()
        _warmEvents.clear()
    }

    companion object {
        fun default() = RoomMemoryPolicy()
    }
}
