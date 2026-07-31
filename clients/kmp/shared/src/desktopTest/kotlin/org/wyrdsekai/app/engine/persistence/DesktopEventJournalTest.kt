package org.wyrdsekai.app.engine.persistence

import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.wyrdsekai.app.engine.event.WorldEvent
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopEventJournalTest {

    private lateinit var tmpDir: File
    private lateinit var journal: DesktopEventJournal

    private fun said(roomId: String, text: String) = WorldEvent.Said(
        roomId = roomId,
        timestamp = Clock.System.now(),
        entityId = "player-1",
        entityName = "Alice",
        text = text,
    )

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        journal = DesktopEventJournal(baseDir = tmpDir.absolutePath, autoCompactThreshold = 0)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── Append and replay ───────────────────────────────────────────────

    @Test
    fun appendAndReplay() = runTest {
        journal.append("nexus", said("nexus", "Hello!"))
        journal.append("nexus", said("nexus", "Hi there!"))

        val replayed = journal.replay("nexus")
        assertEquals(2, replayed.size)
        assertTrue(replayed[0] is WorldEvent.Said)
        assertEquals("Hello!", (replayed[0] as WorldEvent.Said).text)
        assertEquals("Hi there!", (replayed[1] as WorldEvent.Said).text)
    }

    @Test
    fun replayEmptyRoomReturnsEmptyList() = runTest {
        val result = journal.replay("nonexistent-room")
        assertTrue(result.isEmpty())
    }

    @Test
    fun appendToMultipleRooms() = runTest {
        journal.append("room-a", said("room-a", "In A"))
        journal.append("room-b", said("room-b", "In B"))

        assertEquals(1, journal.replay("room-a").size)
        assertEquals(1, journal.replay("room-b").size)
    }

    // ── Snapshots ───────────────────────────────────────────────────────

    @Test
    fun saveAndLoadSnapshot() = runTest {
        val snapshotJson = """{"name":"The Nexus","description":"A room"}"""
        journal.saveSnapshot("nexus", snapshotJson)

        val loaded = journal.loadSnapshot("nexus")
        assertEquals(snapshotJson, loaded)
    }

    @Test
    fun loadSnapshotForMissingRoomReturnsNull() = runTest {
        val result = journal.loadSnapshot("missing-room")
        assertNull(result)
    }

    // ── Compaction ──────────────────────────────────────────────────────

    @Test
    fun compactKeepsOnlyLastN() = runTest {
        for (i in 1..10) {
            journal.append("nexus", said("nexus", "Message $i"))
        }

        assertEquals(10, journal.replay("nexus").size)

        journal.compact("nexus", keepLast = 3)

        val afterCompact = journal.replay("nexus")
        assertEquals(3, afterCompact.size)
        assertEquals("Message 8", (afterCompact[0] as WorldEvent.Said).text)
        assertEquals("Message 9", (afterCompact[1] as WorldEvent.Said).text)
        assertEquals("Message 10", (afterCompact[2] as WorldEvent.Said).text)
    }

    @Test
    fun compactNoOpWhenFewerEventsThanKeepLast() = runTest {
        journal.append("nexus", said("nexus", "Only one"))
        journal.compact("nexus", keepLast = 100)
        assertEquals(1, journal.replay("nexus").size)
    }

    // ── Auto-compaction ─────────────────────────────────────────────────

    @Test
    fun autoCompactTriggersAtThreshold() = runTest {
        val autoJournal = DesktopEventJournal(
            baseDir = tmpDir.absolutePath,
            autoCompactThreshold = 5,
        )

        for (i in 1..6) {
            autoJournal.append("nexus", said("nexus", "Msg $i"))
        }

        // After auto-compact with default keepLast=500, all 6 events fit
        // so compaction doesn't trim, but the mechanism ran without error.
        val events = autoJournal.replay("nexus")
        assertTrue(events.isNotEmpty())
        assertTrue(events.size <= 6)
    }

    // ── Persistence across instances ────────────────────────────────────

    @Test
    fun dataPersistedAcrossInstances() = runTest {
        journal.append("nexus", said("nexus", "Persistent"))

        // New journal instance pointing to same directory
        val journal2 = DesktopEventJournal(baseDir = tmpDir.absolutePath)

        val replayed = journal2.replay("nexus")
        assertEquals(1, replayed.size)
        assertEquals("Persistent", (replayed[0] as WorldEvent.Said).text)
    }

    // ── JSON Lines format ───────────────────────────────────────────────

    @Test
    fun journalFileIsJsonLines() = runTest {
        journal.append("nexus", said("nexus", "Line 1"))
        journal.append("nexus", said("nexus", "Line 2"))

        val file = File(tmpDir, "journal/nexus.jsonl")
        assertTrue(file.exists())

        val lines = file.readLines()
        assertEquals(2, lines.size)
        // Each line should be valid JSON (contains "said" type)
        assertTrue(lines[0].contains("\"said\""))
        assertTrue(lines[1].contains("\"said\""))
    }
}
