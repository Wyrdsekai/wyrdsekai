@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.event.WorldEvent
import platform.Foundation.*

/**
 * File-backed event journal for iOS using JSON files in NSDocumentDirectory.
 * Phase 4a uses simple JSON files; Phase 4b can add SQLite via cinterop.
 */
class IosEventJournal(
    dataDir: String,
    private val autoCompactThreshold: Int = 100,
) : EventJournal {
    private val journalDir = "$dataDir/journal"
    private val snapshotDir = "$dataDir/snapshots"
    private val json = Json { ignoreUnknownKeys = true }
    private val appendCounts = mutableMapOf<String, Int>()

    init {
        val fm = NSFileManager.defaultManager
        fm.createDirectoryAtPath(journalDir, withIntermediateDirectories = true, attributes = null, error = null)
        fm.createDirectoryAtPath(snapshotDir, withIntermediateDirectories = true, attributes = null, error = null)
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
        val path = "$snapshotDir/$roomId.json"
        (snapshotJson as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun loadSnapshot(roomId: String): String? {
        val path = "$snapshotDir/$roomId.json"
        return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
    }

    override suspend fun compact(roomId: String, keepLast: Int) {
        val events = loadEvents(roomId)
        if (events.size > keepLast) {
            saveEvents(roomId, events.takeLast(keepLast))
        }
    }

    private fun loadEvents(roomId: String): List<WorldEvent> {
        val path = "$journalDir/$roomId.json"
        val content = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
            ?: return emptyList()
        return try {
            json.decodeFromString<List<WorldEvent>>(content)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveEvents(roomId: String, events: List<WorldEvent>) {
        val path = "$journalDir/$roomId.json"
        val content = json.encodeToString(events)
        (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }
}
