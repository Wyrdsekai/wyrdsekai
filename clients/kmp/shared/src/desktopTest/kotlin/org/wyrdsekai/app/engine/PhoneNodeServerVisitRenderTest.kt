package org.wyrdsekai.app.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import org.wyrdsekai.app.engine.transit.WebSocketServerConnection
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live test for PhoneNode server room visiting against the running Wyrdsekai server.
 * Verifies that messages from OTHER players arrive as PhoneNodeEvent.Prose events
 * through the wireServerMessages handler.
 *
 * Requires:
 *   - Wyrdsekai server running on localhost:7070
 *   - Valid paired device token in WYRDSEKAI_TEST_TOKEN env
 *
 * Run:
 *   WYRDSEKAI_TEST_TOKEN=<token> ./gradlew :shared:desktopTest \
 *       --tests '*PhoneNodeServerVisitRenderTest*'
 */
class PhoneNodeServerVisitRenderTest {

    private val serverUrl = "ws://localhost:7070/ws"
    private val deviceToken = System.getProperty("wyrdsekai.test.token")
        ?: System.getenv("WYRDSEKAI_TEST_TOKEN")

    private fun skip(): Boolean {
        if (deviceToken.isNullOrBlank()) {
            println("SKIP: No device token. Set WYRDSEKAI_TEST_TOKEN env")
            return true
        }
        return false
    }

    /**
     * Test that messages from a second WebSocket connection arrive on the first
     * connection's message handler — validates the raw WebSocket plumbing that
     * wireServerMessages relies on.
     *
     * Flow:
     * 1. Open connection A (phone's connection) with device_token auth
     * 2. Register a handler on connection A that collects Prose events
     * 3. Open connection B (simulating another player) on the same server
     * 4. Connection B sends a Say in the nexus room
     * 5. Verify connection A receives a Prose event containing the message
     */
    @Test
    fun receivesMessagesFromOtherPlayers() = runBlocking {
        if (skip()) return@runBlocking

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wsUrlA = "$serverUrl?device_token=$deviceToken"

        // ── Connection A: the phone's connection ───────────────────────
        val connA = WebSocketServerConnection(wsUrlA, scope)
        val proseFromA = mutableListOf<S2CMessage.Prose>()

        connA.onMessage { msg ->
            if (msg is S2CMessage.Prose) {
                println("CONN-A PROSE: ${msg.speaker}: ${msg.text}")
                proseFromA.add(msg)
            }
        }

        println("Connecting A to $wsUrlA")
        connA.connect()
        assertTrue(connA.isConnected, "Connection A should be connected")
        println("Connection A connected")

        // Wait for initial room state / welcome messages
        delay(2000)
        val initialCount = proseFromA.size
        println("Connection A has $initialCount initial prose messages")

        // ── Connection B: another player ───────────────────────────────
        val wsUrlB = "$serverUrl?device_token=$deviceToken"
        val connB = WebSocketServerConnection(wsUrlB, scope)

        connB.connect()
        assertTrue(connB.isConnected, "Connection B should be connected")
        println("Connection B connected")

        // Wait for connection B to settle into the room
        delay(2000)

        // ── Send a message from connection B ───────────────────────────
        val testMessage = "hello from connection B ${System.currentTimeMillis()}"
        println("Connection B sending: $testMessage")
        connB.send(C2SMessage.Say(
            id = "test-b-1",
            roomId = "nexus",
            text = testMessage,
        ))

        // Wait for the message to propagate
        delay(3000)

        println("Connection A prose after B's message: ${proseFromA.size} total")
        for (p in proseFromA) {
            println("  - ${p.speaker}: ${p.text}")
        }

        // Verify connection A received the message from B
        val received = proseFromA.any { it.text.contains(testMessage) }
        assertTrue(received,
            "Connection A should receive prose from connection B containing: $testMessage")

        // ── Cleanup ────────────────────────────────────────────────────
        connB.disconnect()
        connA.disconnect()
        scope.cancel()
    }

    /**
     * Test the full PhoneNode pipeline: PhoneNode visits a server room,
     * a second connection sends a message, and PhoneNode emits a
     * PhoneNodeEvent.Prose notification.
     *
     * This tests the wireServerMessages -> tryEmit -> notifications pipeline
     * end-to-end against a real server.
     */
    @Test
    fun phoneNodeReceivesServerProseNotification() = runBlocking {
        if (skip()) return@runBlocking

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val httpServerUrl = "http://localhost:7070"

        // ── Create PhoneNode with server credentials ───────────────────
        val node = PhoneNode(
            journal = InMemoryEventJournal(),
            vitalityStore = null,
            inferenceClient = org.wyrdsekai.app.inference.InferenceClient(),
            inferenceBaseUrl = "http://localhost:8080",
            scope = scope,
            serverUrl = httpServerUrl,
            deviceToken = deviceToken,
        )
        node.start()

        // Wait for node to boot
        withTimeout(5000) {
            node.state.first { it == PhoneNode.State.RUNNING }
        }
        println("PhoneNode started, state=${node.state.value}")

        // ── Collect notifications ──────────────────────────────────────
        val events = mutableListOf<PhoneNodeEvent>()
        val collectJob = launch {
            node.notifications.collect { event ->
                println("EVENT: ${event::class.simpleName} -> $event")
                events.add(event)
            }
        }
        // Give the collector a moment to start
        delay(100)

        // ── Navigate to server room ────────────────────────────────────
        println("Navigating to server room (out)...")
        node.go("player", "You", "out")

        // Wait for server connection + room state
        delay(5000)

        val serverRoom = node.visitingServerRoom
        println("Visiting server room: $serverRoom")
        assertNotNull(serverRoom, "Should be visiting a server room after go('out')")

        // Verify we got a ServerRoomEntered event
        val entered = events.filterIsInstance<PhoneNodeEvent.ServerRoomEntered>()
        assertTrue(entered.isNotEmpty(), "Should have received ServerRoomEntered event")
        println("ServerRoomEntered events: ${entered.map { it.roomId }}")

        // ── Open a second connection and send a message ────────────────
        val wsUrl2 = "ws://localhost:7070/ws?device_token=$deviceToken"
        val conn2 = WebSocketServerConnection(wsUrl2, scope)
        conn2.connect()
        assertTrue(conn2.isConnected, "Second connection should be connected")
        println("Second connection connected")

        delay(2000)
        events.clear()

        val testMessage = "live test from second player ${System.currentTimeMillis()}"
        println("Sending from second connection: $testMessage")
        conn2.send(C2SMessage.Say(
            id = "test-2",
            roomId = serverRoom!!,
            text = testMessage,
        ))

        // Wait for the message to propagate through the pipeline
        delay(5000)

        println("Events after second player's message:")
        for (e in events) {
            println("  - ${e::class.simpleName}: $e")
        }

        val proseEvents = events.filterIsInstance<PhoneNodeEvent.Prose>()
        println("Prose events: ${proseEvents.map { "${it.speaker}: ${it.text}" }}")

        val received = proseEvents.any { it.text.contains(testMessage) }
        assertTrue(received,
            "PhoneNode should emit Prose notification for message from second player: $testMessage")

        // ── Cleanup ────────────────────────────────────────────────────
        collectJob.cancel()
        conn2.disconnect()
        node.stop()
        scope.cancel()
    }
}
