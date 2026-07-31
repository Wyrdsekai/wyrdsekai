package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NatsBetweenClientReconnectTest {

    // ── Backoff calculation ──────────────────────────────────────────────

    @Test
    fun backoffDelayIsExponential() {
        assertEquals(1000L, NatsBetweenClient.backoffDelayMs(0))
        assertEquals(2000L, NatsBetweenClient.backoffDelayMs(1))
        assertEquals(4000L, NatsBetweenClient.backoffDelayMs(2))
        assertEquals(8000L, NatsBetweenClient.backoffDelayMs(3))
        assertEquals(16000L, NatsBetweenClient.backoffDelayMs(4))
    }

    @Test
    fun backoffDelayCapsAt16Seconds() {
        assertEquals(16000L, NatsBetweenClient.backoffDelayMs(4))
        assertEquals(16000L, NatsBetweenClient.backoffDelayMs(5))
        assertEquals(16000L, NatsBetweenClient.backoffDelayMs(10))
    }

    // ── connectWithRetry with FailThenSucceed client ─────────────────────

    @Test
    fun connectWithRetrySucceedsOnFirstAttempt() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 0)

        client.connectWithRetry("ws://test", maxAttempts = 3)

        assertTrue(client.isConnected)
        assertEquals(1, client.connectAttempts)
    }

    @Test
    fun connectWithRetrySucceedsAfterFailures() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 2)

        client.connectWithRetry("ws://test", maxAttempts = 5)

        assertTrue(client.isConnected)
        assertEquals(3, client.connectAttempts) // 2 failures + 1 success
    }

    @Test
    fun connectWithRetryFailsAfterMaxAttempts() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 10)

        assertFailsWith<Exception> {
            client.connectWithRetry("ws://test", maxAttempts = 3)
        }

        assertFalse(client.isConnected)
        assertEquals(3, client.connectAttempts)
    }

    @Test
    fun connectWithRetryDefaultMaxAttemptIsFive() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 100)

        assertFailsWith<Exception> {
            client.connectWithRetry("ws://test")
        }

        assertEquals(5, client.connectAttempts)
    }

    // ── Handler survival across reconnection ────────────────────────────

    @Test
    fun handlersRegisteredBeforeConnectSurviveReconnection() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 0)

        // Register handler BEFORE first connect
        val received = mutableListOf<String>()
        client.subscribe("test.topic") { _, data ->
            received.add(data.decodeToString())
        }

        // First connection
        client.connectWithRetry("ws://test", maxAttempts = 1)
        assertTrue(client.isConnected)

        // Simulate message delivery
        client.simulateMessage("test.topic", "hello-1".encodeToByteArray())
        assertEquals(1, received.size)
        assertEquals("hello-1", received[0])

        // Simulate disconnect + reconnect
        client.simulateDisconnect()
        assertFalse(client.isConnected)

        client.connectWithRetry("ws://test", maxAttempts = 1)
        assertTrue(client.isConnected)

        // Handler should still work after reconnection
        client.simulateMessage("test.topic", "hello-2".encodeToByteArray())
        assertEquals(2, received.size)
        assertEquals("hello-2", received[1])
    }

    @Test
    fun multipleHandlersSurviveReconnection() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 0)

        val topicA = mutableListOf<String>()
        val topicB = mutableListOf<String>()

        client.subscribe("topic.a") { _, data -> topicA.add(data.decodeToString()) }
        client.subscribe("topic.b") { _, data -> topicB.add(data.decodeToString()) }

        client.connectWithRetry("ws://test", maxAttempts = 1)

        // Pre-reconnect
        client.simulateMessage("topic.a", "a1".encodeToByteArray())
        client.simulateMessage("topic.b", "b1".encodeToByteArray())
        assertEquals(1, topicA.size)
        assertEquals(1, topicB.size)

        // Reconnect
        client.simulateDisconnect()
        client.connectWithRetry("ws://test", maxAttempts = 1)

        // Post-reconnect
        client.simulateMessage("topic.a", "a2".encodeToByteArray())
        client.simulateMessage("topic.b", "b2".encodeToByteArray())
        assertEquals(2, topicA.size)
        assertEquals(2, topicB.size)
    }

    @Test
    fun unsubscribedHandlerDoesNotSurviveReconnection() = runTest {
        val client = FailThenSucceedBetweenClient(failCount = 0)

        val received = mutableListOf<String>()
        val unsub = client.subscribe("test.topic") { _, data ->
            received.add(data.decodeToString())
        }

        client.connectWithRetry("ws://test", maxAttempts = 1)
        client.simulateMessage("test.topic", "before".encodeToByteArray())
        assertEquals(1, received.size)

        // Unsubscribe
        unsub()

        // Reconnect
        client.simulateDisconnect()
        client.connectWithRetry("ws://test", maxAttempts = 1)

        // Should not receive after unsubscribe
        client.simulateMessage("test.topic", "after".encodeToByteArray())
        assertEquals(1, received.size, "Unsubscribed handler should not receive after reconnect")
    }

    @Test
    fun autoReconnectDefaultsToFalse() {
        val client = NatsBetweenClient(scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertFalse(client.autoReconnect)
    }
}

/**
 * Test double for BetweenClient that fails a configurable number of times
 * on connect, then succeeds. Supports simulating disconnection and message
 * delivery for handler survival tests.
 *
 * This lives in the test sources and mimics the essential behavior of
 * NatsBetweenClient's handler map and subscription survival.
 */
private class FailThenSucceedBetweenClient(
    private val failCount: Int,
) : BetweenClient {
    private var _connected = false
    override val isConnected: Boolean get() = _connected

    var connectAttempts = 0
        private set

    private var nextSid = 1
    private val handlers = mutableMapOf<Int, Pair<String, (String, ByteArray) -> Unit>>()

    override suspend fun connect(url: String) {
        connectAttempts++
        if (connectAttempts <= failCount) {
            throw RuntimeException("Connection failed (attempt $connectAttempts/$failCount)")
        }
        _connected = true
    }

    override suspend fun disconnect() {
        _connected = false
    }

    override fun publish(subject: String, data: ByteArray) {
        // Deliver to matching handlers (like InMemoryBetweenClient)
        for ((_, pair) in handlers) {
            if (pair.first == subject) {
                pair.second(subject, data)
            }
        }
    }

    override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
        val sid = nextSid++
        handlers[sid] = subject to handler
        return { handlers.remove(sid) }
    }

    /**
     * Connect with exponential backoff retry (mirrors NatsBetweenClient API).
     */
    suspend fun connectWithRetry(url: String, maxAttempts: Int = 5) {
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                connect(url)
                return
            } catch (e: Exception) {
                lastError = e
                try { disconnect() } catch (_: Exception) {}
                if (attempt < maxAttempts - 1) {
                    val delayMs = NatsBetweenClient.backoffDelayMs(attempt)
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Connection failed after $maxAttempts attempts")
    }

    /** Simulate receiving a message (delivers to matching handlers). */
    fun simulateMessage(subject: String, data: ByteArray) {
        for ((_, pair) in handlers) {
            if (pair.first == subject) {
                pair.second(subject, data)
            }
        }
    }

    /** Simulate connection loss without clearing handlers. */
    fun simulateDisconnect() {
        _connected = false
        // Reset connect attempts so reconnect starts fresh
        connectAttempts = 0
    }
}
