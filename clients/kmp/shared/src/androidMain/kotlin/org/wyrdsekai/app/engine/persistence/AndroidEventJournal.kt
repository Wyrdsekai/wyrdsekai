package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.wyrdsekai.app.engine.event.WorldEvent
import java.io.File

/**
 * Android file-backed EventJournal.
 *
 * Stores events as JSON files in a journal/ subdirectory, one file per room.
 * Snapshots stored in snapshots/ subdirectory. Atomic writes via temp file + rename.
 *
 * Same pattern as IosEventJournal but using java.io.File instead of NSFileManager.
 */
class AndroidEventJournal(
    dataDir: String,
    private val autoCompactThreshold: Int = 100,
) : EventJournal {
    private val journalDir = File(dataDir, "journal")
    private val snapshotDir = File(dataDir, "snapshots")
    private val json = Json { ignoreUnknownKeys = true }
    private val appendCounts = mutableMapOf<String, Int>()

    init {
        journalDir.mkdirs()
        snapshotDir.mkdirs()
    }

    override suspend fun append(roomId: String, event: WorldEvent) {
        val events = loadEvents(roomId).toMutableList()
        events.add(event)
        saveEvents(roomId, events)

        // Track appends for auto-compaction
        val count = (appendCounts[roomId] ?: 0) + 1
        appendCounts[roomId] = count
        if (count >= autoCompactThreshold) {
            compact(roomId)
            appendCounts[roomId] = 0
        }
    }

    override suspend fun replay(roomId: String): List<WorldEvent> = loadEvents(roomId)

    override suspend fun saveSnapshot(roomId: String, snapshotJson: String) {
        val file = File(snapshotDir, "$roomId.json")
        val tmp = File(snapshotDir, "$roomId.json.tmp")
        tmp.writeText(snapshotJson, Charsets.UTF_8)
        tmp.renameTo(file)
    }

    override suspend fun loadSnapshot(roomId: String): String? {
        val file = File(snapshotDir, "$roomId.json")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    override suspend fun compact(roomId: String, keepLast: Int) {
        val events = loadEvents(roomId)
        if (events.size > keepLast) {
            saveEvents(roomId, events.takeLast(keepLast))
        }
    }

    private fun loadEvents(roomId: String): List<WorldEvent> {
        val file = File(journalDir, "$roomId.json")
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<WorldEvent>>(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveEvents(roomId: String, events: List<WorldEvent>) {
        val file = File(journalDir, "$roomId.json")
        val tmp = File(journalDir, "$roomId.json.tmp")
        tmp.writeText(json.encodeToString(events), Charsets.UTF_8)
        tmp.renameTo(file)
    }
}
