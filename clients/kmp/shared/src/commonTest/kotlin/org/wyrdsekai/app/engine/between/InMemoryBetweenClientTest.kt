package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryBetweenClientTest {

    @Test
    fun startsDisconnected() {
        val client = InMemoryBetweenClient()
        assertFalse(client.isConnected)
    }

    @Test
    fun connectSetsConnected() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")
        assertTrue(client.isConnected)
    }

    @Test
    fun publishDeliversToSubscriber() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")

        val received = mutableListOf<Pair<String, ByteArray>>()
        client.subscribe("between.household.node1.soul.headlines") { subject, data ->
            received.add(subject to data)
        }

        val payload = "hello".encodeToByteArray()
        client.publish("between.household.node1.soul.headlines", payload)

        assertEquals(1, received.size)
        assertEquals("between.household.node1.soul.headlines", received[0].first)
        assertEquals("hello", received[0].second.decodeToString())
    }

    @Test
    fun wildcardStarMatchesSingleToken() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")

        val received = mutableListOf<String>()
        client.subscribe("a.*.c") { subject, _ ->
            received.add(subject)
        }

        // Should match: * matches single token "b"
        client.publish("a.b.c", "match".encodeToByteArray())
        assertEquals(1, received.size)
        assertEquals("a.b.c", received[0])

        // Should NOT match: "d" != "c"
        client.publish("a.b.d", "no-match".encodeToByteArray())
        assertEquals(1, received.size, "a.b.d should not match pattern a.*.c")
    }

    @Test
    fun wildcardGtMatchesRemaining() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")

        val received = mutableListOf<String>()
        client.subscribe("a.>") { subject, _ ->
            received.add(subject)
        }

        // > matches any remaining tokens
        client.publish("a.b.c.d", "match".encodeToByteArray())
        assertEquals(1, received.size)
        assertEquals("a.b.c.d", received[0])

        // Also matches fewer trailing tokens
        client.publish("a.b", "match2".encodeToByteArray())
        assertEquals(2, received.size)
    }

    @Test
    fun unsubscribeStopsDelivery() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")

        val received = mutableListOf<String>()
        val unsub = client.subscribe("test.subject") { _, data ->
            received.add(data.decodeToString())
        }

        client.publish("test.subject", "first".encodeToByteArray())
        assertEquals(1, received.size)

        // Unsubscribe
        unsub()

        // This should NOT be delivered
        client.publish("test.subject", "second".encodeToByteArray())
        assertEquals(1, received.size, "Should not receive after unsubscribe")
    }

    @Test
    fun publishRecordsForAssertions() = runTest {
        val client = InMemoryBetweenClient()
        client.connect("nats://localhost:4222")

        client.publish("topic.one", "payload-1".encodeToByteArray())
        client.publish("topic.two", "payload-2".encodeToByteArray())

        assertEquals(2, client.published.size)
        assertEquals("topic.one", client.published[0].first)
        assertEquals("payload-1", client.published[0].second.decodeToString())
        assertEquals("topic.two", client.published[1].first)
        assertEquals("payload-2", client.published[1].second.decodeToString())
    }
}
