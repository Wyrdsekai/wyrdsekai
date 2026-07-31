package org.wyrdsekai.app.engine.event

import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldEventTest {

    private val now = Clock.System.now()

    @Test
    fun roomCreatedConstruction() {
        val event = WorldEvent.RoomCreated(
            roomId = "room-1",
            timestamp = now,
            name = "The Nexus",
            description = "A vast crystalline chamber.",
            zone = "foundation",
        )
        assertEquals("room-1", event.roomId)
        assertEquals(now, event.timestamp)
        assertEquals("The Nexus", event.name)
        assertEquals("A vast crystalline chamber.", event.description)
        assertEquals("foundation", event.zone)
    }

    @Test
    fun saidConstruction() {
        val event = WorldEvent.Said(
            roomId = "room-1",
            timestamp = now,
            entityId = "player-1",
            entityName = "Alice",
            text = "Hello, world!",
        )
        assertEquals("room-1", event.roomId)
        assertEquals(now, event.timestamp)
        assertEquals("player-1", event.entityId)
        assertEquals("Alice", event.entityName)
        assertEquals("Hello, world!", event.text)
    }

    @Test
    fun entityEnteredConstruction() {
        val event = WorldEvent.EntityEntered(
            roomId = "room-1",
            timestamp = now,
            entityId = "player-1",
            entityName = "Alice",
            entityType = "player",
            fromDirection = "north",
        )
        assertEquals("room-1", event.roomId)
        assertEquals(now, event.timestamp)
        assertEquals("player-1", event.entityId)
        assertEquals("Alice", event.entityName)
        assertEquals("player", event.entityType)
        assertEquals("north", event.fromDirection)
    }

    @Test
    fun typeDiscrimination() {
        val events: List<WorldEvent> = listOf(
            WorldEvent.RoomCreated("r", now, "Room", "Desc", "zone"),
            WorldEvent.Said("r", now, "p1", "Alice", "Hi"),
            WorldEvent.EntityEntered("r", now, "p1", "Alice", "player", "north"),
            WorldEvent.EntityLeft("r", now, "p1", "Alice", "south"),
        )

        var roomCreated = false
        var said = false
        var entered = false
        var left = false

        for (event in events) {
            when (event) {
                is WorldEvent.RoomCreated -> roomCreated = true
                is WorldEvent.Said -> said = true
                is WorldEvent.EntityEntered -> entered = true
                is WorldEvent.EntityLeft -> left = true
                else -> {} // other event types
            }
        }

        assertTrue(roomCreated, "RoomCreated should be matched")
        assertTrue(said, "Said should be matched")
        assertTrue(entered, "EntityEntered should be matched")
        assertTrue(left, "EntityLeft should be matched")
    }
}
