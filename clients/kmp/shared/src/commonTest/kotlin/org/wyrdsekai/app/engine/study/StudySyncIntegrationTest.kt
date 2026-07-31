package org.wyrdsekai.app.engine.study

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for Study sync between two nodes.
 * Uses InMemoryBetweenClient for deterministic, fast tests.
 *
 * Tier 3 equivalent — tests the full sync protocol:
 * advertisement → delta request → delta response → merge.
 */
class StudySyncIntegrationTest {

    /**
     * Two nodes share a Between network. Node A writes, broadcasts state.
     * Node B detects it's behind and requests a delta.
     */
    @Test
    fun nodeABroadcastsStateNodeBRequestsDelta() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val storeA = InMemoryStudyStoreWithClocks("phone-a")
        val storeB = InMemoryStudyStore()

        val layerA = StudySyncLayer(between, storeA, "phone-a", "family-1", "user1", this)
        val layerB = StudySyncLayer(between, storeB, "phone-b", "family-1", "user1", this)

        layerA.startListening()
        layerB.startListening()

        // Node A writes a journal entry (with vector clock set by the store)
        storeA.writeJournal("user1", "Entry from phone A")

        // Broadcast state from A — clock summary will show phone-a:1
        layerA.broadcastState()
        testScheduler.advanceUntilIdle()

        // B should have sent a delta request (it has no items, A has clock > 0)
        val syncMessages = between.published.filter { it.first.contains("study.sync") }
        assertTrue(syncMessages.isNotEmpty(), "Node B should have sent a delta request after seeing A's state")
    }

    /**
     * Verify that concurrent edits on two devices are detected as conflicts.
     */
    @Test
    fun concurrentEditsDetectedAsConflict() {
        // Two items with the same ID but different clocks where neither dominates
        val itemA = StudyItem(
            id = "shared-item",
            userDid = "user1",
            itemType = StudyItem.TYPE_JOURNAL,
            content = "Edited on phone A",
            timestamp = 1000L,
            vectorClock = mapOf("phone-a" to 2L, "phone-b" to 1L),
            lastModifiedBy = "phone-a",
        )

        val itemB = StudyItem(
            id = "shared-item",
            userDid = "user1",
            itemType = StudyItem.TYPE_JOURNAL,
            content = "Edited on phone B",
            timestamp = 1001L,
            vectorClock = mapOf("phone-a" to 1L, "phone-b" to 2L),
            lastModifiedBy = "phone-b",
        )

        // These clocks are concurrent: A has (a:2, b:1), B has (a:1, b:2)
        val relation = VectorClock.compare(itemA.vectorClock, itemB.vectorClock)
        assertEquals(VectorClock.Relation.CONCURRENT, relation)
    }

    /**
     * Verify that a strictly newer edit fast-forwards without conflict.
     */
    @Test
    fun newerEditDominatesAndFastForwards() {
        val older = StudyItem(
            id = "shared-item",
            userDid = "user1",
            itemType = StudyItem.TYPE_JOURNAL,
            content = "Original",
            timestamp = 1000L,
            vectorClock = mapOf("phone-a" to 1L),
            lastModifiedBy = "phone-a",
        )

        val newer = StudyItem(
            id = "shared-item",
            userDid = "user1",
            itemType = StudyItem.TYPE_JOURNAL,
            content = "Updated on A then synced to B",
            timestamp = 2000L,
            vectorClock = mapOf("phone-a" to 2L),
            lastModifiedBy = "phone-a",
        )

        val relation = VectorClock.compare(newer.vectorClock, older.vectorClock)
        assertEquals(VectorClock.Relation.DOMINATES, relation)
    }

    /**
     * Verify that sync events are emitted on merge.
     */
    @Test
    fun syncLayerEmitsEventsOnMerge() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val storeB = InMemoryStudyStore()
        val events = mutableListOf<SyncEvent>()

        val layerB = StudySyncLayer(between, storeB, "phone-b", "family-1", "user1", this)
        layerB.onSyncEvent { events.add(it) }
        layerB.startListening()

        // Simulate A sending a delta with items
        val deltaMsg = """{"type":"study_delta","deviceId":"phone-a","items":[{"id":"si-1","userDid":"user1","itemType":"journal","title":"From A","content":"From A","collection":"","timestamp":1000,"version":1,"vectorClock":{"phone-a":1},"lastModifiedBy":"phone-a","conflictVersions":[],"deleted":false}],"conflicts":0}"""
        between.publish(
            "between.family-1.phone-a.phone-b.study.sync",
            deltaMsg.encodeToByteArray(),
        )
        testScheduler.advanceUntilIdle()

        // Should have emitted an items_merged event
        assertTrue(events.any { it is SyncEvent.ItemsMerged }, "Should emit ItemsMerged event")
    }

    /**
     * DeviceCapabilityService: full invocation round-trip.
     */
    @Test
    fun deviceCapabilityRoundTrip() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        // Phone registers camera capability
        val phoneCaps = org.wyrdsekai.app.engine.between.DeviceCapabilityService(
            between, "phone-1", "family-1",
        )
        phoneCaps.register("camera") { mapOf("photo" to "base64data") }
        phoneCaps.grant("desktop-1", "camera")
        phoneCaps.startListening()

        // Desktop discovers phone capabilities
        phoneCaps.advertise()
        val adverts = between.published.filter { it.first.contains("device.capabilities") }
        assertEquals(1, adverts.size)
        assertTrue(adverts[0].second.decodeToString().contains("camera"))

        // Desktop invokes camera
        val invokeEvents = mutableListOf<org.wyrdsekai.app.engine.between.CapabilityEvent>()
        phoneCaps.onEvent { invokeEvents.add(it) }

        val request = """{"requesterId":"desktop-1","capabilityName":"camera","params":{},"requestId":"req-1"}"""
        between.publish(
            "between.family-1.desktop-1.phone-1.device.invoke",
            request.encodeToByteArray(),
        )

        // Phone should have executed the capability
        assertEquals(1, invokeEvents.size)
        assertTrue(invokeEvents[0] is org.wyrdsekai.app.engine.between.CapabilityEvent.Invoked)

        // Result should have been published back
        val results = between.published.filter { it.first.contains("device.result") }
        assertEquals(1, results.size)
        assertTrue(results[0].second.decodeToString().contains("base64data"))
    }
}

/**
 * InMemoryStudyStore that auto-populates vector clocks on write.
 * Stores items with vector clocks set so sync tests see non-empty clock summaries.
 */
class InMemoryStudyStoreWithClocks(private val deviceId: String) : StudyStore {
    private val items = mutableMapOf<String, StudyItem>()
    private var idCounter = 0
    private var tsCounter = 1000L
    private var clockVersion = 0L

    override suspend fun putItem(item: StudyItem) { items[item.id] = item }

    override suspend fun writeJournal(userDid: String, content: String, isPrivate: Boolean): StudyItem {
        val item = StudyItem(
            id = "si-test-${++idCounter}",
            userDid = userDid,
            itemType = if (isPrivate) StudyItem.TYPE_JOURNAL_PRIVATE else StudyItem.TYPE_JOURNAL,
            title = content.lineSequence().firstOrNull()?.take(120) ?: "",
            content = content,
            timestamp = ++tsCounter,
            vectorClock = mapOf(deviceId to ++clockVersion),
            lastModifiedBy = deviceId,
        )
        items[item.id] = item
        return item
    }

    override suspend fun editItem(id: String, newContent: String): StudyItem? {
        val existing = items[id] ?: return null
        val updated = existing.copy(content = newContent, version = existing.version + 1, timestamp = ++tsCounter)
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
        val item = StudyItem(id = "si-test-${++idCounter}", userDid = userDid, itemType = StudyItem.TYPE_NOTE,
            title = content.take(120), content = content, timestamp = ++tsCounter,
            vectorClock = mapOf(deviceId to ++clockVersion), lastModifiedBy = deviceId)
        items[item.id] = item
        return item
    }

    override suspend fun pin(userDid: String, title: String, snippet: String, sourceUrl: String): StudyItem {
        val item = StudyItem(id = "si-test-${++idCounter}", userDid = userDid, itemType = StudyItem.TYPE_PINBOARD,
            title = title, content = snippet, timestamp = ++tsCounter,
            vectorClock = mapOf(deviceId to ++clockVersion), lastModifiedBy = deviceId)
        items[item.id] = item
        return item
    }

    override suspend fun searchAll(userDid: String, query: String, limit: Int): List<StudyItem> {
        val lower = query.lowercase()
        return items.values.filter {
            it.userDid == userDid && (it.content.lowercase().contains(lower) || it.title.lowercase().contains(lower))
        }.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun getItem(id: String): StudyItem? = items[id]
    override suspend fun deleteItem(id: String): Boolean = items.remove(id) != null
    override suspend fun count(userDid: String): Int = items.values.count { it.userDid == userDid }
}
