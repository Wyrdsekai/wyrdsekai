package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SleepSyncTest {

    @Test
    fun buildRequestIncludesAllFields() {
        val between = InMemoryBetweenClient()
        val manager = SleepSyncManager(between, "node-1", "family-1")

        val request = manager.buildRequest(
            budDid = "bud-1",
            manifestVersion = 3,
            localItemHashes = listOf("hash-a", "hash-b"),
            localTombstones = listOf(
                Tombstone("hash-c", "superseded", "bud-1", 100),
            ),
            lastSyncTimestamp = 5000,
        )

        assertEquals("bud-1", request.budDid)
        assertEquals("node-1", request.nodeId)
        assertEquals(3, request.manifestVersion)
        assertEquals(2, request.localItemHashes.size)
        assertEquals(1, request.localTombstones.size)
        assertEquals(5000, request.lastSyncTimestamp)
        assertTrue(request.timestamp > 0)
    }

    @Test
    fun requestSyncPublishesToBetween() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = SleepSyncManager(between, "node-1", "family-1")

        val request = SleepSyncRequest(
            budDid = "bud-1",
            nodeId = "node-1",
            manifestVersion = 1,
            localItemHashes = listOf("h1"),
            lastSyncTimestamp = 0,
            timestamp = 1000,
        )
        manager.requestSync(request)

        assertEquals(1, between.published.size)
        val subject = between.published[0].first
        assertTrue(subject.contains("soul.sync.request"))
        assertTrue(subject.contains("family-1"))
        assertTrue(subject.contains("node-1"))
    }

    @Test
    fun receiveSyncResponseTriggersCallback() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = SleepSyncManager(between, "node-1", "family-1")

        var received: SleepSyncResponse? = null
        manager.onSyncResponse { received = it }
        manager.startListening()

        // Simulate server response
        val response = SleepSyncResponse(
            budDid = "bud-1",
            newItems = listOf(
                SoulItemRef("hash-x", "memory", 0.9f, "bud-2", 500),
            ),
            newTombstones = listOf(
                Tombstone("hash-old", "expired", "system", 600),
            ),
            manifestUpdated = true,
            itemsMerged = 3,
            tombstonesApplied = 1,
            timestamp = 2000,
        )
        val data = kotlinx.serialization.json.Json.encodeToString(
            SleepSyncResponse.serializer(), response
        ).encodeToByteArray()
        between.publish("between.household.family-1.server.node-1.soul.sync.response", data)

        assertNotNull(received)
        assertEquals(1, received!!.newItems.size)
        assertEquals("hash-x", received!!.newItems[0].hash)
        assertEquals(true, received!!.manifestUpdated)
        assertEquals(3, received!!.itemsMerged)

        manager.stopListening()
    }

    @Test
    fun syncRequestSubjectMatchesPattern() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = SleepSyncManager(between, "node-1", "family-abc")

        val request = SleepSyncRequest(
            budDid = "bud-1", nodeId = "node-1",
            manifestVersion = 1, localItemHashes = emptyList(),
            lastSyncTimestamp = 0, timestamp = 100,
        )
        manager.requestSync(request)

        assertEquals(
            "between.household.family-abc.node-1.server.soul.sync.request",
            between.published[0].first,
        )
    }

    @Test
    fun tombstoneRoundTrip() {
        val json = kotlinx.serialization.json.Json
        val tombstone = Tombstone("hash-1", "expired", "bud-1", 1000)
        val encoded = json.encodeToString(Tombstone.serializer(), tombstone)
        val decoded = json.decodeFromString(Tombstone.serializer(), encoded)
        assertEquals(tombstone, decoded)
    }

    @Test
    fun soulItemRefRoundTrip() {
        val json = kotlinx.serialization.json.Json
        val ref = SoulItemRef("abc123", "personality", 0.95f, "bud-1", 500)
        val encoded = json.encodeToString(SoulItemRef.serializer(), ref)
        val decoded = json.decodeFromString(SoulItemRef.serializer(), encoded)
        assertEquals(ref, decoded)
    }
}
