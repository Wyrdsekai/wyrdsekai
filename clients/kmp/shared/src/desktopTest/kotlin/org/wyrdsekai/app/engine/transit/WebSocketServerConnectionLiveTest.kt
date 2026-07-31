package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Live test for WebSocketServerConnection against the running Wyrdsekai server.
 * Requires: wyrdsekai running on localhost:7070 with a valid paired device token.
 *
 * Run: ./gradlew :shared:desktopTest --tests '*WebSocketServerConnectionLiveTest*'
 */
class WebSocketServerConnectionLiveTest {

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

    @Test
    fun connectAndReceiveRoomState() = runBlocking {
        if (skip()) return@runBlocking

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wsUrl = "$serverUrl?device_token=$deviceToken"
        val conn = WebSocketServerConnection(wsUrl, scope)

        val received = mutableListOf<String>()
        conn.onMessage { msg ->
            val type = msg::class.simpleName ?: "?"
            println("RECEIVED: $type")
            received.add(type)
        }

        println("Connecting to $wsUrl")
        conn.connect()
        assertTrue(conn.isConnected, "Should be connected")
        println("Connected!")

        // Wait for initial room state
        delay(3000)

        println("Received ${received.size} messages: $received")
        assertTrue(received.isNotEmpty(), "Should receive at least one message")
        assertTrue(received.any { it == "Prose" || it == "RoomState" },
            "Should receive Prose or RoomState")

        conn.disconnect()
        scope.cancel()
    }

    @Test
    fun sendSayAndReceiveEcho() = runBlocking {
        if (skip()) return@runBlocking

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wsUrl = "$serverUrl?device_token=$deviceToken"
        val conn = WebSocketServerConnection(wsUrl, scope)

        val proseMessages = mutableListOf<String>()
        conn.onMessage { msg ->
            if (msg is org.wyrdsekai.app.protocol.S2CMessage.Prose) {
                println("PROSE: ${msg.speaker}: ${msg.text}")
                proseMessages.add("${msg.speaker}: ${msg.text}")
            }
        }

        conn.connect()
        assertTrue(conn.isConnected)

        // Wait for initial messages
        delay(2000)

        // Send a say
        val testText = "hello from desktop test ${System.currentTimeMillis()}"
        println("Sending: $testText")
        conn.send(org.wyrdsekai.app.protocol.C2SMessage.Say(
            id = "test-1",
            roomId = "nexus",
            text = testText,
        ))

        // Wait for echo
        delay(3000)

        println("All prose: $proseMessages")
        val echoed = proseMessages.any { it.contains(testText) }
        assertTrue(echoed, "Should see our own say echoed back")

        conn.disconnect()
        scope.cancel()
    }

    @Test
    fun sessionStaysAlive() = runBlocking {
        if (skip()) return@runBlocking

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val wsUrl = "$serverUrl?device_token=$deviceToken"
        val conn = WebSocketServerConnection(wsUrl, scope)

        conn.connect()
        assertTrue(conn.isConnected)

        // Wait 10 seconds — session should still be alive
        delay(10_000)
        assertTrue(conn.isConnected, "Session should still be connected after 10s")

        conn.disconnect()
        scope.cancel()
    }
}
