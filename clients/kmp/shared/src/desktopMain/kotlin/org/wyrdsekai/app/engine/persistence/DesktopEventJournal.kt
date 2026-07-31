package org.wyrdsekai.app.engine.persistence

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.wyrdsekai.app.engine.event.WorldEvent
import java.io.File

/**
 * Desktop file-backed EventJournal.
 *
 * Stores events as JSON Lines (one JSON object per line) in
 * `~/.wyrdsekai/journal/{roomId}.jsonl`. Snapshots are stored as
 * single JSON files in `~/.wyrdsekai/snapshots/`.
 *
 * Atomic writes via temp file + rename. Auto-compaction triggers
 * after [autoCompactThreshold] appends per room.
 *
 * Same pattern as [AndroidEventJournal] but targeting desktop
 * with a default data directory under the user's home.
 */
class DesktopEventJournal(
    baseDir: String = "${System.getProperty("user.home")}/.wyrdsekai",
    private val autoCompactThreshold: Int = 100,
) : EventJournal {
    private val journalDir = File(baseDir, "journal")
    private val snapshotDir = File(baseDir, "snapshots")
    private val json = Json { ignoreUnknownKeys = true }
    private val appendCounts = mutableMapOf<String, Int>()

    private fun ensureJournalDir() {
        if (!journalDir.exists()) journalDir.mkdirs()
    }

    private fun ensureSnapshotDir() {
        if (!snapshotDir.exists()) snapshotDir.mkdirs()
    }

    override suspend fun append(roomId: String, event: WorldEvent) {
        ensureJournalDir()
        val file = File(journalDir, "$roomId.jsonl")
        val line = json.encodeToString(event)
        file.appendText("$line\n", Charsets.UTF_8)

        // Track appends for auto-compaction (disabled when threshold <= 0)
        if (autoCompactThreshold > 0) {
            val count = (appendCounts[roomId] ?: 0) + 1
            appendCounts[roomId] = count
            if (count >= autoCompactThreshold) {
                compact(roomId)
                appendCounts[roomId] = 0
            }
        }
    }

    override suspend fun replay(roomId: String): List<WorldEvent> = loadEvents(roomId)

    override suspend fun saveSnapshot(roomId: String, snapshotJson: String) {
        ensureSnapshotDir()
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
        ensureJournalDir()
        val events = loadEvents(roomId)
        if (events.size > keepLast) {
            val kept = events.takeLast(keepLast)
            saveEventsAtomically(roomId, kept)
        }
    }

    private fun loadEvents(roomId: String): List<WorldEvent> {
        val file = File(journalDir, "$roomId.jsonl")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        json.decodeFromString<WorldEvent>(line)
                    } catch (_: Exception) {
                        null // Skip malformed lines
                    }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Write all events atomically using a temp file + rename.
     * Used by compact() to rewrite the journal with fewer events.
     */
    private fun saveEventsAtomically(roomId: String, events: List<WorldEvent>) {
        val file = File(journalDir, "$roomId.jsonl")
        val tmp = File(journalDir, "$roomId.jsonl.tmp")
        val content = events.joinToString("\n") { json.encodeToString(it) } + "\n"
        tmp.writeText(content, Charsets.UTF_8)
        tmp.renameTo(file)
    }
}
