package org.wyrdsekai.app.engine.study

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for StudySyncLayer using InMemoryBetweenClient.
 * Verifies the advertisement → delta → merge protocol.
 */
class StudySyncLayerTest {

    @Test
    fun `broadcast state publishes to correct subject`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val store = InMemoryStudyStore()

        store.writeJournal("user1", "test entry")

        val layer = StudySyncLayer(between, store, "phone-1", "family-1", "user1", TestScope())
        layer.startListening()
        layer.broadcastState()

        assertEquals(1, between.published.size)
        assertTrue(between.published[0].first.contains("study.state"))
    }

    @Test
    fun `peer state triggers delta request when behind`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val store = InMemoryStudyStore()

        val layer = StudySyncLayer(between, store, "phone-1", "family-1", "user1", this)
        layer.startListening()

        // Simulate a peer broadcasting a state with a clock we don't have
        val peerMsg = """{"type":"study_state","deviceId":"phone-2","itemCount":5,"latestModified":9999,"clockSummary":{"phone-2":10}}"""
        between.publish(
            "between.family-1.phone-2.*.study.state",
            peerMsg.encodeToByteArray(),
        )

        // Let launched coroutines complete
        testScheduler.advanceUntilIdle()

        // Should have published a delta request
        val requests = between.published.filter { it.first.contains("study.sync") }
        assertEquals(1, requests.size, "Should have sent a delta request")
    }

    @Test
    fun `delta with a brand-new item persists it, no data loss`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val store = InMemoryStudyStore()

        val layer = StudySyncLayer(between, store, "phone-b", "family-1", "user1", this)
        layer.startListening()

        val remote = StudyItem(
            id = "si-remote-1",
            userDid = "user1",
            itemType = StudyItem.TYPE_JOURNAL,
            content = "Entry from phone A",
            timestamp = 1234L,
            vectorClock = mapOf("phone-a" to 1L),
            lastModifiedBy = "phone-a",
        )
        val delta = StudySyncMessage(type = "study_delta", deviceId = "phone-a", items = listOf(remote))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        between.publish(
            "between.family-1.phone-a.phone-b.study.sync",
            json.encodeToString(StudySyncMessage.serializer(), delta).encodeToByteArray(),
        )
        testScheduler.advanceUntilIdle()

        // #5 regression guard — before the fix, mergeIncoming's local==null
        // branch counted merged++ but never stored the item, so this was 0.
        assertEquals(1, store.count("user1"))
        val stored = store.getItem("si-remote-1")
        assertNotNull(stored)
        assertEquals("Entry from phone A", stored.content)
    }

    @Test
    fun `device capability service registers and advertises`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val capService = org.wyrdsekai.app.engine.between.DeviceCapabilityService(
            between, "phone-1", "family-1",
        )
        capService.register("camera") { mapOf("photo" to "base64data") }
        capService.register("gps") { mapOf("lat" to "35.6762", "lon" to "139.6503") }
        capService.advertise()

        assertEquals(1, between.published.size)
        val msg = between.published[0].second.decodeToString()
        assertTrue(msg.contains("camera"))
        assertTrue(msg.contains("gps"))
    }

    @Test
    fun `capability invocation with grant succeeds`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val events = mutableListOf<org.wyrdsekai.app.engine.between.CapabilityEvent>()
        val capService = org.wyrdsekai.app.engine.between.DeviceCapabilityService(
            between, "phone-1", "family-1",
        )
        capService.register("camera") { mapOf("photo" to "captured") }
        capService.grant("desktop-1", "camera")
        capService.onEvent { events.add(it) }
        capService.startListening()

        // Simulate desktop invoking camera
        val request = """{"requesterId":"desktop-1","capabilityName":"camera","params":{},"requestId":"req-1"}"""
        between.publish(
            "between.family-1.desktop-1.phone-1.device.invoke",
            request.encodeToByteArray(),
        )

        assertEquals(1, events.size)
        assertTrue(events[0] is org.wyrdsekai.app.engine.between.CapabilityEvent.Invoked)
    }

    @Test
    fun `capability invocation without grant requires authorization`() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val events = mutableListOf<org.wyrdsekai.app.engine.between.CapabilityEvent>()
        val capService = org.wyrdsekai.app.engine.between.DeviceCapabilityService(
            between, "phone-1", "family-1",
        )
        capService.register("camera") { mapOf("photo" to "captured") }
        // NO grant for desktop-1
        capService.onEvent { events.add(it) }
        capService.startListening()

        val request = """{"requesterId":"desktop-1","capabilityName":"camera","params":{},"requestId":"req-1"}"""
        between.publish(
            "between.family-1.desktop-1.phone-1.device.invoke",
            request.encodeToByteArray(),
        )

        assertEquals(1, events.size)
        assertTrue(events[0] is org.wyrdsekai.app.engine.between.CapabilityEvent.AuthorizationRequired)
    }
}

/** In-memory StudyStore for testing (no SQLite dependency). */
class InMemoryStudyStore : StudyStore {
    private val items = mutableMapOf<String, StudyItem>()
    private var idCounter = 0
    private var tsCounter = 1000L  // monotonic timestamp for deterministic ordering

    override suspend fun putItem(item: StudyItem) { items[item.id] = item }

    override suspend fun writeJournal(userDid: String, content: String, isPrivate: Boolean): StudyItem {
        val item = StudyItem(
            id = "si-test-${++idCounter}",
            userDid = userDid,
            itemType = if (isPrivate) StudyItem.TYPE_JOURNAL_PRIVATE else StudyItem.TYPE_JOURNAL,
            title = content.lineSequence().firstOrNull()?.take(120) ?: "",
            content = content,
            timestamp = ++tsCounter,
        )
        items[item.id] = item
        return item
    }

    override suspend fun editItem(id: String, newContent: String): StudyItem? {
        val existing = items[id] ?: return null
        val updated = existing.copy(
            content = newContent,
            title = newContent.lineSequence().firstOrNull()?.take(120) ?: existing.title,
            version = existing.version + 1,
            timestamp = ++tsCounter,
        )
        items[id] = updated
        return updated
    }

    override suspend fun searchJournal(userDid: String, query: String, limit: Int): List<StudyItem> {
        val lower = query.lowercase()
        return items.values.filter {
            it.userDid == userDid &&
            (it.itemType == StudyItem.TYPE_JOURNAL || it.itemType == StudyItem.TYPE_JOURNAL_PRIVATE) &&
            (it.content.lowercase().contains(lower) || it.title.lowercase().contains(lower))
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun recentJournal(userDid: String, limit: Int): List<StudyItem> {
        return items.values.filter {
            it.userDid == userDid &&
            (it.itemType == StudyItem.TYPE_JOURNAL || it.itemType == StudyItem.TYPE_JOURNAL_PRIVATE)
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun addNote(userDid: String, content: String): StudyItem {
        val item = StudyItem(
            id = "si-test-${++idCounter}",
            userDid = userDid,
            itemType = StudyItem.TYPE_NOTE,
            title = content.take(120),
            content = content,
            timestamp = ++tsCounter,
        )
        items[item.id] = item
        return item
    }

    override suspend fun pin(userDid: String, title: String, snippet: String, sourceUrl: String): StudyItem {
        val fullContent = if (sourceUrl.isNotEmpty()) "$snippet\n\nSource: $sourceUrl" else snippet
        val item = StudyItem(
            id = "si-test-${++idCounter}",
            userDid = userDid,
            itemType = StudyItem.TYPE_PINBOARD,
            title = title,
            content = fullContent,
            timestamp = ++tsCounter,
        )
        items[item.id] = item
        return item
    }

    override suspend fun searchAll(userDid: String, query: String, limit: Int): List<StudyItem> {
        val lower = query.lowercase()
        return items.values.filter {
            it.userDid == userDid &&
            (it.content.lowercase().contains(lower) || it.title.lowercase().contains(lower))
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun getItem(id: String): StudyItem? = items[id]

    override suspend fun deleteItem(id: String): Boolean = items.remove(id) != null

    override suspend fun count(userDid: String): Int = items.values.count { it.userDid == userDid }
}
