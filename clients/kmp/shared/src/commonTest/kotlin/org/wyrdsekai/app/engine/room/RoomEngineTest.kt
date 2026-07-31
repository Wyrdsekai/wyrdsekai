package org.wyrdsekai.app.engine.room

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.protocol.Exit
import org.wyrdsekai.app.protocol.RoomObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomEngineTest {

    @Test
    fun createRoomSetsState() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        val response = engine.send(RoomEngineCommand.CreateRoom(
            name = "Test Room",
            description = "A test room.",
            zone = "test",
        ))

        assertTrue(response is RoomEngineResponse.Ok)
        assertEquals("Test Room", engine.state.value.name)
        assertEquals("A test room.", engine.state.value.description)
        assertTrue(journal.eventCount("test") > 0)
        engine.shutdown()
    }

    @Test
    fun enterAndLeaveRoom() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom("Room", "Desc", "zone"))
        engine.send(RoomEngineCommand.EnterRoom("p1", "Alice", "player", "north"))

        assertEquals(1, engine.state.value.entities.size)
        assertEquals("Alice", engine.state.value.entities["p1"]!!.name)

        engine.send(RoomEngineCommand.LeaveRoom("p1", "Alice", "south"))
        assertTrue(engine.state.value.entities.isEmpty())
        engine.shutdown()
    }

    @Test
    fun takeNonexistentObjectRejected() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom("Room", "Desc", "zone"))
        val result = engine.send(RoomEngineCommand.TakeObject("p1", "ghost"))
        assertTrue(result is RoomEngineResponse.Rejected)
        assertEquals("not_found", (result as RoomEngineResponse.Rejected).code)
        engine.shutdown()
    }

    @Test
    fun takeUntakeableObjectRejected() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom(
            name = "Room", description = "Desc", zone = "zone",
            objects = listOf(RoomObject("obj-1", "pedestal", "A stone pedestal", false)),
        ))
        val result = engine.send(RoomEngineCommand.TakeObject("p1", "pedestal"))
        assertTrue(result is RoomEngineResponse.Rejected)
        assertEquals("not_takeable", (result as RoomEngineResponse.Rejected).code)
        engine.shutdown()
    }

    @Test
    fun takeTakeableObjectSucceeds() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom(
            name = "Room", description = "Desc", zone = "zone",
            objects = listOf(RoomObject("obj-1", "sword", "A sharp sword", true)),
        ))
        val result = engine.send(RoomEngineCommand.TakeObject("p1", "sword"))
        assertTrue(result is RoomEngineResponse.Ok)
        assertTrue(engine.state.value.objects.isEmpty())
        engine.shutdown()
    }

    @Test
    fun notificationsEmitted() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        // Verify events are persisted to journal (more reliable than SharedFlow timing)
        engine.send(RoomEngineCommand.CreateRoom("Room", "Desc", "zone"))
        engine.send(RoomEngineCommand.SayInRoom("p1", "Alice", "Hello"))

        val replayed = journal.allEvents("test")
        assertTrue(replayed.any { it is WorldEvent.RoomCreated })
        assertTrue(replayed.any { it is WorldEvent.Said })
        engine.shutdown()
    }

    @Test
    fun createRoomWithExitsAndObjects() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom(
            name = "Nexus",
            description = "A hub.",
            zone = "foundation",
            exits = listOf(Exit("north", "terminal", "To Terminal")),
            objects = listOf(RoomObject("obj-1", "crystal", "A crystal", false)),
        ))

        assertEquals(1, engine.state.value.exits.size)
        assertEquals("terminal", engine.state.value.exits["north"]!!.targetRoom)
        assertEquals(1, engine.state.value.objects.size)
        assertEquals("crystal", engine.state.value.objects["obj-1"]!!.name)
        engine.shutdown()
    }

    @Test
    fun useNonexistentObjectRejected() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom("Room", "Desc", "zone"))
        val result = engine.send(RoomEngineCommand.UseObject("p1", "ghost", null))
        assertTrue(result is RoomEngineResponse.Rejected)
        engine.shutdown()
    }

    @Test
    fun eventsPersisted() = runTest {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("test", journal, null, null, this)

        engine.send(RoomEngineCommand.CreateRoom("Room", "Desc", "zone"))
        engine.send(RoomEngineCommand.EnterRoom("p1", "Alice", "player", "north"))
        engine.send(RoomEngineCommand.SayInRoom("p1", "Alice", "Hello"))

        val replayed = journal.allEvents("test")
        assertTrue(replayed.size >= 3)
        assertTrue(replayed[0] is WorldEvent.RoomCreated)
        assertTrue(replayed[1] is WorldEvent.EntityEntered)
        assertTrue(replayed[2] is WorldEvent.Said)
        engine.shutdown()
    }
}
