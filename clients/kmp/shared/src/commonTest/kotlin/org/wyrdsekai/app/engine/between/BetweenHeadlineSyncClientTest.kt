package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.soul.Headline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BetweenHeadlineSyncClientTest {

    private fun makeHeadline(
        budDid: String = "bud-1",
        summary: String = "All is well",
    ) = Headline(
        budDid = budDid,
        summary = summary,
        vitalitySnapshot = mapOf("energy" to 0.8f, "confidence" to 0.7f),
        itemCount = 5,
        timestamp = 1000L,
    )

    @Test
    fun postHeadlineCachesLocally() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        client.postHeadline(makeHeadline())

        assertEquals(1, client.latestHeadlines().size)
        assertEquals("All is well", client.latestHeadlines()[0].summary)
    }

    @Test
    fun postHeadlinePublishesToBetween() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        client.postHeadline(makeHeadline())

        assertEquals(1, between.published.size)
        assertTrue(between.published[0].first.contains("soul.headlines"))
        assertTrue(between.published[0].first.contains("family-1"))
    }

    @Test
    fun receiveHeadlineFromSibling() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        val received = mutableListOf<Headline>()
        client.onHeadlineReceived { received.add(it) }
        client.startListening()

        // Simulate sibling posting a headline
        val siblingHeadline = makeHeadline(budDid = "bud-2", summary = "Exploring terminal")
        val data = kotlinx.serialization.json.Json.encodeToString(
            Headline.serializer(), siblingHeadline
        ).encodeToByteArray()
        between.publish("between.household.family-1.node-2.soul.headlines", data)

        assertEquals(1, received.size)
        assertEquals("bud-2", received[0].budDid)
        assertEquals("Exploring terminal", received[0].summary)

        client.stopListening()
    }

    @Test
    fun ignoresOwnHeadlines() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        val received = mutableListOf<Headline>()
        client.onHeadlineReceived { received.add(it) }
        client.startListening()

        // Simulate own headline echoing back
        val ownHeadline = makeHeadline(budDid = "node-1", summary = "My own headline")
        val data = kotlinx.serialization.json.Json.encodeToString(
            Headline.serializer(), ownHeadline
        ).encodeToByteArray()
        between.publish("between.household.family-1.node-1.soul.headlines", data)

        // Should NOT trigger callback (own node ID)
        assertEquals(0, received.size)
    }

    @Test
    fun latestHeadlinesSortedByTimestamp() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        client.postHeadline(makeHeadline(budDid = "a").copy(timestamp = 100))
        client.postHeadline(makeHeadline(budDid = "b").copy(timestamp = 300))
        client.postHeadline(makeHeadline(budDid = "c").copy(timestamp = 200))

        val headlines = client.latestHeadlines()
        assertEquals("b", headlines[0].budDid)
        assertEquals("c", headlines[1].budDid)
        assertEquals("a", headlines[2].budDid)
    }

    @Test
    fun gracefulWhenDisconnected() = runTest {
        val between = InMemoryBetweenClient()
        // NOT connected
        val client = BetweenHeadlineSyncClient(between, "node-1", "family-1")

        // Should still cache locally without throwing
        client.postHeadline(makeHeadline())
        assertEquals(1, client.latestHeadlines().size)
        assertEquals(0, between.published.size) // Not published
    }
}
