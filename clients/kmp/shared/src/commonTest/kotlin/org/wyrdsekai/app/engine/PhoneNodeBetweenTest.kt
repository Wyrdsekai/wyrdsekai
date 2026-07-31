package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import org.wyrdsekai.app.engine.soul.Headline
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeBetweenTest {

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        betweenClient: org.wyrdsekai.app.engine.between.BetweenClient? = null,
        nodeId: String = "test-node",
        familyId: String = "test-family",
    ) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = null,
        betweenClient = betweenClient,
        nodeId = nodeId,
        familyId = familyId,
    )

    // ── Null Between (backwards compatibility) ──────────────────────────

    @Test
    fun startWithoutBetweenClient() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertNull(node.headlineSyncClient)
        assertNotNull(node.currentRoom(), "Study room should be active at T1")

        node.stop()
    }

    @Test
    fun stopWithoutBetweenDoesNotThrow() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)
        node.stop()

        assertEquals(PhoneNode.State.STOPPED, node.state.value)
        assertNull(node.headlineSyncClient)
    }

    // ── InMemoryBetweenClient integration ───────────────────────────────

    @Test
    fun startWithConnectedBetweenCreatesHeadlineSync() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertNotNull(node.headlineSyncClient, "HeadlineSyncClient should be created when Between is connected")

        node.stop()
    }

    @Test
    fun startWithDisconnectedBetweenDoesNotCreateSync() = runTest {
        val between = InMemoryBetweenClient()
        // NOT connected

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertNull(node.headlineSyncClient, "HeadlineSyncClient should not be created when Between is disconnected")

        node.stop()
    }

    @Test
    fun stopCleansUpHeadlineSync() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)
        assertNotNull(node.headlineSyncClient)

        node.stop()
        assertNull(node.headlineSyncClient, "HeadlineSyncClient should be null after stop")
    }

    // ── Headline publishing ─────────────────────────────────────────────

    @Test
    fun postHeadlinePublishesToBetween() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between, nodeId = "my-node", familyId = "my-family")

        node.start()
        advanceTimeBy(200)

        val headline = Headline(
            budDid = "my-node",
            summary = "All is well",
            vitalitySnapshot = mapOf("energy" to 0.8f),
            itemCount = 3,
            timestamp = 1000L,
        )
        node.postHeadline(headline)

        assertTrue(between.published.isNotEmpty(), "Headline should be published to Between")
        val publishedSubject = between.published.last().first
        assertTrue(publishedSubject.contains("soul.headlines"), "Subject should contain soul.headlines")
        assertTrue(publishedSubject.contains("my-family"), "Subject should contain family ID")
    }

    @Test
    fun postHeadlineWithNullSyncIsNoOp() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        // Should not throw
        val headline = Headline(
            budDid = "node-1",
            summary = "Test",
            vitalitySnapshot = emptyMap(),
            itemCount = 0,
            timestamp = 1000L,
        )
        node.postHeadline(headline)

        // No crash = success
        assertEquals(PhoneNode.State.RUNNING, node.state.value)

        node.stop()
    }

    @Test
    fun headlineReceivedFromSibling() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between, nodeId = "node-A", familyId = "fam-1")

        node.start()
        advanceTimeBy(200)

        val sync = node.headlineSyncClient
        assertNotNull(sync)

        // Track received headlines
        val received = mutableListOf<Headline>()
        sync.onHeadlineReceived { received.add(it) }

        // Simulate sibling publishing a headline
        val siblingHeadline = Headline(
            budDid = "node-B",
            summary = "Exploring dream chamber",
            vitalitySnapshot = mapOf("energy" to 0.5f),
            itemCount = 2,
            timestamp = 2000L,
        )
        val data = kotlinx.serialization.json.Json.encodeToString(
            Headline.serializer(), siblingHeadline
        ).encodeToByteArray()
        between.publish("between.household.fam-1.node-B.soul.headlines", data)

        assertEquals(1, received.size)
        assertEquals("node-B", received[0].budDid)
        assertEquals("Exploring dream chamber", received[0].summary)

        node.stop()
    }

    @Test
    fun automaticHeadlinePublishAfterInterval() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")

        val node = makeNode(scope = backgroundScope, betweenClient = between)

        node.start()
        advanceTimeBy(200)

        // Clear any initial publishes
        val initialCount = between.published.size

        // Advance past one headline publish interval (30s)
        advanceTimeBy(PhoneNode.HEADLINE_PUBLISH_INTERVAL_MS + 100)

        assertTrue(
            between.published.size > initialCount,
            "Automatic headline should be published after interval"
        )

        node.stop()
    }
}
