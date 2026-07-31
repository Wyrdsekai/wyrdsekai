package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisitingRoomProxyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun subscribesToCorrectRoomEventsSubject() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val proxy = VisitingRoomProxy("terminal", between, "household-1", this)
        proxy.startListening()

        // Simulate a remote room event
        val event = WorldEvent.Said(
            roomId = "terminal",
            timestamp = Clock.System.now(),
            entityId = "npc-1",
            entityName = "Guide",
            text = "Welcome to the Terminal.",
        )
        val data = json.encodeToString<WorldEvent>(event).encodeToByteArray()
        between.publish("between.household-1.room.terminal.events", data)

        // State should have been updated (Said doesn't change state, but verify no crash)
        assertEquals("terminal", proxy.state.value.roomId)

        proxy.shutdown()
    }

    @Test
    fun receivedEventsUpdateState() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val proxy = VisitingRoomProxy("terminal", between, "household-1", this)
        proxy.startListening()

        // Send a RoomCreated event
        val event = WorldEvent.RoomCreated(
            roomId = "terminal",
            timestamp = Clock.System.now(),
            name = "The Terminal",
            description = "Banks of crystalline screens line the walls.",
            zone = "foundation",
        )
        val data = json.encodeToString<WorldEvent>(event).encodeToByteArray()
        between.publish("between.household-1.room.terminal.events", data)

        assertEquals("The Terminal", proxy.state.value.name)
        assertEquals("foundation", proxy.state.value.zone)

        proxy.shutdown()
    }

    @Test
    fun receivedEventsEmitNotifications() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val proxy = VisitingRoomProxy("terminal", between, "household-1", this)
        proxy.startListening()

        val received = mutableListOf<WorldEvent>()
        val job = launch {
            proxy.notifications.collect { received.add(it) }
        }

        val event = WorldEvent.Said(
            roomId = "terminal",
            timestamp = Clock.System.now(),
            entityId = "npc-1",
            entityName = "Guide",
            text = "Welcome!",
        )
        val data = json.encodeToString<WorldEvent>(event).encodeToByteArray()
        between.publish("between.household-1.room.terminal.events", data)

        // Allow coroutine to process — testScheduler drives virtual time
        testScheduler.advanceUntilIdle()

        assertTrue(received.isNotEmpty())
        assertTrue(received[0] is WorldEvent.Said)
        assertEquals("Welcome!", (received[0] as WorldEvent.Said).text)

        job.cancel()
        proxy.shutdown()
    }

    @Test
    fun sendCommandPublishesToCorrectSubject() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val proxy = VisitingRoomProxy("terminal", between, "household-1", this)

        val command = RoomEngineCommand.SayInRoom(
            entityId = "player-1",
            entityName = "Traveler",
            text = "Hello, Terminal!",
        )
        proxy.send(command)

        assertEquals(1, between.published.size)
        assertEquals(
            "between.household-1.room.terminal.commands",
            between.published[0].first,
        )

        // Verify the command can be deserialized
        val payload = between.published[0].second.decodeToString()
        assertTrue(payload.contains("Traveler"))
        assertTrue(payload.contains("Hello, Terminal!"))

        proxy.shutdown()
    }

    @Test
    fun entityEnteredUpdatesState() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val proxy = VisitingRoomProxy("nexus", between, "household-1", this)
        proxy.startListening()

        val event = WorldEvent.EntityEntered(
            roomId = "nexus",
            timestamp = Clock.System.now(),
            entityId = "companion-1",
            entityName = "Wyrd",
            entityType = "companion",
            fromDirection = "north",
        )
        val data = json.encodeToString<WorldEvent>(event).encodeToByteArray()
        between.publish("between.household-1.room.nexus.events", data)

        assertTrue(proxy.state.value.entities.containsKey("companion-1"))
        assertEquals("Wyrd", proxy.state.value.entities["companion-1"]?.name)

        proxy.shutdown()
    }
}
