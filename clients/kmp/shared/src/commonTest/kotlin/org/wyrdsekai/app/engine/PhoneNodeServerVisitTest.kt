package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.tier.*
import org.wyrdsekai.app.engine.transit.InMemoryServerConnection
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PhoneNode server room visiting via WebSocket.
 *
 * Verifies:
 * - Home room has "out" exit pointing to server:nexus
 * - go("out") with no server connection emits an error
 * - go("out") with a connected server connection enters server-visiting mode
 * - say() in server-visiting mode routes to the server
 * - go("back") returns to local home room
 * - Server room_state messages are forwarded as RoomChanged events
 * - Server prose messages are forwarded as Prose events
 * - Server room_state messages include injected "back" exit
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeServerVisitTest {

    /** T2 probe — Home room (with server:nexus exit) requires T2+. */
    private fun makeT2Probe() = object : ResourceProbe {
        override fun snapshot() = ResourceSnapshot(
            availableMemoryMb = 2500,
            totalMemoryMb = 4000,
            batteryPercent = 80,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
    }

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        serverConnection: InMemoryServerConnection? = null,
    ): PhoneNode {
        val tierManager = TierManager(makeT2Probe(), scope = scope)
        return PhoneNode(
            journal = InMemoryEventJournal(),
            vitalityStore = null,
            inferenceClient = InferenceClient(),
            inferenceBaseUrl = "http://test",
            scope = scope,
            tierManager = tierManager,
            serverConnection = serverConnection,
        )
    }

    @Test
    fun homeRoomHasOutExit() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // At T2, player starts in Study. Navigate to Home first.
        node.go("player", "You", "out")
        advanceUntilIdle()

        val snapshot = node.look()
        assertNotNull(snapshot)
        val outExit = snapshot.exits.find { it.direction == "out" }
        assertNotNull(outExit, "Home room should have an 'out' exit")
        assertEquals("server:nexus", outExit.targetRoom)
        assertEquals("Step outside to the household", outExit.label)

        node.stop()
    }

    @Test
    fun goOutWithNoServerConnectionEmitsError() = runTest {
        val node = makeNode(scope = backgroundScope)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate from Study to Home first
        node.go("player", "You", "out")
        advanceUntilIdle()

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        advanceUntilIdle() // Ensure collector is active before emitting

        // Now go "out" from Home → server:nexus (no server connection)
        node.go("player", "You", "out")
        advanceUntilIdle()

        val error = events.filterIsInstance<PhoneNodeEvent.Error>().firstOrNull()
        assertNotNull(error, "Should emit error when no server connection")
        assertEquals("no_server", error.code)

        job.cancel()
        node.stop()
    }

    @Test
    fun goOutWithServerConnectionEntersServerMode() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true
        conn.addRemoteRooms("nexus")

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate from Study to Home first
        node.go("player", "You", "out")
        advanceUntilIdle()

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }

        // Now go "out" from Home → server:nexus
        node.go("player", "You", "out")
        advanceUntilIdle()

        // Should be visiting server room
        assertEquals("nexus", node.visitingServerRoom)

        // Should have sent a Look C2S message to the server
        val lookMsg = conn.sent.find { it is C2SMessage.Look }
        assertNotNull(lookMsg, "Should have sent Look to server")
        assertEquals("nexus", (lookMsg as C2SMessage.Look).roomId)

        // Should have emitted ServerRoomEntered event
        val entered = events.filterIsInstance<PhoneNodeEvent.ServerRoomEntered>().firstOrNull()
        assertNotNull(entered, "Should emit ServerRoomEntered event")
        assertEquals("nexus", entered.roomId)

        // Should have emitted narrator prose
        val prose = events.filterIsInstance<PhoneNodeEvent.Prose>()
            .find { it.text.contains("step outside") }
        assertNotNull(prose, "Should emit narrator prose about stepping outside")

        job.cancel()
        node.stop()
    }

    @Test
    fun sayInServerModeRoutesToServer() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        conn.sent.clear()

        node.say("player", "You", "Hello world!")
        advanceUntilIdle()

        val sayMsg = conn.sent.find { it is C2SMessage.Say }
        assertNotNull(sayMsg, "Say should be routed to server")
        assertEquals("Hello world!", (sayMsg as C2SMessage.Say).text)
        assertEquals("nexus", sayMsg.roomId)

        node.stop()
    }

    @Test
    fun goBackReturnsToLocalHome() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        assertEquals("nexus", node.visitingServerRoom)

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        advanceUntilIdle() // Ensure collector is active before emitting

        node.go("player", "You", "back")
        advanceUntilIdle()

        // Should no longer be visiting
        assertNull(node.visitingServerRoom)

        // Should have emitted RoomChanged for Home (returnFromServerRoom sets currentRoomId = "home")
        val roomChanged = events.filterIsInstance<PhoneNodeEvent.RoomChanged>().firstOrNull()
        assertNotNull(roomChanged, "Should emit RoomChanged back to Home")
        assertEquals("home", roomChanged.snapshot.roomId)

        // Should have emitted ServerRoomLeft
        val left = events.filterIsInstance<PhoneNodeEvent.ServerRoomLeft>().firstOrNull()
        assertNotNull(left, "Should emit ServerRoomLeft")
        assertEquals("nexus", left.roomId)

        job.cancel()
        node.stop()
    }

    @Test
    fun serverRoomStateForwardedAsRoomChanged() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        advanceUntilIdle() // Ensure collector is active before emitting

        // Simulate server sending room_state
        conn.receive(S2CMessage.RoomState(
            seq = 1,
            room = RoomSnapshot(
                roomId = "nexus",
                name = "The Nexus",
                description = "The central hub of the household.",
                zone = "foundation",
                exits = listOf(
                    Exit("north", "terminal", "A corridor leads to The Terminal"),
                    Exit("south", "garden", "A path leads to the garden"),
                ),
                entities = emptyList(),
                objects = emptyList(),
            ),
            inventory = null,
        ))
        advanceUntilIdle()

        val roomChanged = events.filterIsInstance<PhoneNodeEvent.RoomChanged>().firstOrNull()
        assertNotNull(roomChanged, "Server room_state should emit RoomChanged")
        assertEquals("The Nexus", roomChanged.snapshot.name)
        assertEquals("The central hub of the household.", roomChanged.snapshot.description)

        // Should have injected "back" exit
        val backExit = roomChanged.snapshot.exits.find { it.direction == "back" }
        assertNotNull(backExit, "Should inject 'back' exit in server room snapshot")
        assertEquals("home", backExit.targetRoom)

        job.cancel()
        node.stop()
    }

    @Test
    fun serverProseForwardedAsProse() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        advanceUntilIdle() // Ensure collector is active before emitting

        // Simulate server sending prose
        conn.receive(S2CMessage.Prose(
            seq = 2,
            speaker = "Guide",
            text = "Welcome to the Nexus!",
        ))
        advanceUntilIdle()

        val prose = events.filterIsInstance<PhoneNodeEvent.Prose>()
            .find { it.text == "Welcome to the Nexus!" }
        assertNotNull(prose, "Server prose should be forwarded")
        assertEquals("Guide", prose.speaker)

        job.cancel()
        node.stop()
    }

    @Test
    fun goInServerModeForwardsToServer() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        conn.sent.clear()

        // Navigate within server rooms
        node.go("player", "You", "north")
        advanceUntilIdle()

        val goMsg = conn.sent.find { it is C2SMessage.Go }
        assertNotNull(goMsg, "Go should be forwarded to server")
        assertEquals("north", (goMsg as C2SMessage.Go).direction)
        assertEquals("nexus", goMsg.roomId)

        node.stop()
    }

    @Test
    fun stopCleansUpServerVisit() = runTest {
        val conn = InMemoryServerConnection()
        conn.isConnected = true

        val node = makeNode(scope = backgroundScope, serverConnection = conn)
        node.start()
        advanceTimeBy(1000) // T2 boots 5 rooms (5 × 100ms delay each)

        // Navigate Study → Home → server:nexus
        node.go("player", "You", "out") // study → home
        advanceUntilIdle()
        node.go("player", "You", "out") // home → server:nexus
        advanceUntilIdle()

        assertEquals("nexus", node.visitingServerRoom)

        node.stop()

        assertNull(node.visitingServerRoom)
        assertEquals(PhoneNode.State.STOPPED, node.state.value)
    }
}
