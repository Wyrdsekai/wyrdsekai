package org.wyrdsekai.app.engine.room

import kotlin.time.Clock
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.protocol.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomStateTest {
    private val now = Clock.System.now()
    private val roomId = "test-room"

    @Test
    fun emptyState() {
        val state = RoomState.empty(roomId)
        assertEquals(roomId, state.roomId)
        assertEquals("", state.name)
        assertTrue(state.exits.isEmpty())
        assertTrue(state.entities.isEmpty())
        assertTrue(state.objects.isEmpty())
    }

    @Test
    fun applyRoomCreated() {
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.RoomCreated(roomId, now, "The Nexus", "A hub.", "foundation"))
        assertEquals("The Nexus", state.name)
        assertEquals("A hub.", state.description)
        assertEquals("foundation", state.zone)
    }

    @Test
    fun applyEntityEnteredAndLeft() {
        var state = RoomState.empty(roomId)
            .apply(WorldEvent.EntityEntered(roomId, now, "player-1", "Alice", "player", "north"))
        assertEquals(1, state.entities.size)
        assertEquals("Alice", state.entities["player-1"]!!.name)

        state = state.apply(WorldEvent.EntityLeft(roomId, now, "player-1", "Alice", "south"))
        assertTrue(state.entities.isEmpty())
    }

    @Test
    fun applyObjectTakenAndDropped() {
        var state = RoomState.empty(roomId)
            .apply(WorldEvent.ObjectAdded(roomId, now, "obj-1", "sword", "A sharp sword", true))
        assertEquals(1, state.objects.size)
        assertEquals("sword", state.objects["obj-1"]!!.name)

        state = state.apply(WorldEvent.ObjectTaken(roomId, now, "player-1", "obj-1", "sword"))
        assertTrue(state.objects.isEmpty())

        state = state.apply(WorldEvent.ObjectDropped(roomId, now, "player-1", "obj-1", "sword", "A sharp sword", true))
        assertEquals(1, state.objects.size)
    }

    @Test
    fun applyExitOpenedAndClosed() {
        var state = RoomState.empty(roomId)
            .apply(WorldEvent.ExitOpened(roomId, now, "north", "room-2", "A door"))
        assertEquals(1, state.exits.size)
        assertEquals("room-2", state.exits["north"]!!.targetRoom)

        state = state.apply(WorldEvent.ExitClosed(roomId, now, "north"))
        assertTrue(state.exits.isEmpty())
    }

    @Test
    fun applyDescriptionChanged() {
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.RoomCreated(roomId, now, "Room", "Old desc", "zone"))
            .apply(WorldEvent.DescriptionChanged(roomId, now, "New desc", "script"))
        assertEquals("New desc", state.description)
    }

    @Test
    fun applyHintsUpdated() {
        val hints = listOf(Hint("Try this", "hint1", "say", null))
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.HintsUpdated(roomId, now, hints))
        assertEquals(1, state.hints.size)
        assertEquals("Try this", state.hints[0].label)
    }

    @Test
    fun applyPropertyChanged() {
        var state = RoomState.empty(roomId)
            .apply(WorldEvent.PropertyChanged(roomId, now, "key1", null, "value1"))
        assertEquals("value1", state.properties["key1"])

        state = state.apply(WorldEvent.PropertyChanged(roomId, now, "key1", "value1", null))
        assertNull(state.properties["key1"])
    }

    @Test
    fun saidDoesNotChangeState() {
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.RoomCreated(roomId, now, "Room", "Desc", "zone"))
        val after = state.apply(WorldEvent.Said(roomId, now, "p1", "Alice", "Hello"))
        assertEquals(state, after)
    }

    @Test
    fun toSnapshot() {
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.RoomCreated(roomId, now, "The Nexus", "A hub.", "foundation"))
            .apply(WorldEvent.ExitOpened(roomId, now, "north", "terminal", "North"))
            .apply(WorldEvent.EntityEntered(roomId, now, "p1", "Alice", "player", "south"))
        val snapshot = state.toSnapshot()
        assertEquals("The Nexus", snapshot.name)
        assertEquals(1, snapshot.exits.size)
        assertEquals(1, snapshot.entities.size)
    }

    @Test
    fun applyObjectUsed() {
        val state = RoomState.empty(roomId)
            .apply(WorldEvent.ObjectAdded(roomId, now, "obj-1", "crystal", "A crystal", false))
        val after = state.apply(WorldEvent.ObjectUsed(roomId, now, "p1", "obj-1", "crystal", null, null))
        assertEquals(state, after) // ObjectUsed doesn't change state
    }

    @Test
    fun applyWhispered() {
        val state = RoomState.empty(roomId)
        val after = state.apply(WorldEvent.Whispered(roomId, now, "p1", "Alice", "p2", "psst"))
        assertEquals(state, after) // Whispered doesn't change state
    }

    @Test
    fun applyScriptTriggered() {
        val state = RoomState.empty(roomId)
        val after = state.apply(WorldEvent.ScriptTriggered(roomId, now, "nexus", "onEnter", emptyMap()))
        assertEquals(state, after) // ScriptTriggered doesn't change state
    }
}
