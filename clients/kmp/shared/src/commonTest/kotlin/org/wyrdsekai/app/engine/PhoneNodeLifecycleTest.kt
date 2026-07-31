package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.wyrdsekai.app.engine.soul.BootstrapSoulManifest
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeLifecycleTest {

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
    ) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = null,
    )

    @Test
    fun startBootsStudyRoom() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        val studyRoom = node.currentRoom()
        assertNotNull(studyRoom)
        assertEquals("The Study", studyRoom.state.value.name)

        node.stop()
    }

    @Test
    fun sayDispatchesToCurrentRoom() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        node.say("player-1", "Alice", "Hello, Wyrd!")

        // The Said event should be persisted in the journal via the study room
        val studyRoom = node.currentRoom()
        assertNotNull(studyRoom)
        // Verify the room state has been updated — Said doesn't change RoomState,
        // but we can check the journal was used by checking notifications
        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        yield()

        // Say again so we can observe the notification
        node.say("player-1", "Alice", "Testing say")
        yield()

        assertTrue(events.any { it is PhoneNodeEvent.Prose && (it as PhoneNodeEvent.Prose).text == "Testing say" })
        job.cancel()

        node.stop()
    }

    @Test
    fun lookReturnsSnapshot() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        val snapshot = node.look()

        assertNotNull(snapshot)
        assertEquals("The Study", snapshot.name)

        node.stop()
    }

    @Test
    fun goInvalidDirectionEmitsError() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.startRoomsOnly()

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        yield()

        node.go("player-1", "Alice", "down")
        yield()

        assertTrue(events.any {
            it is PhoneNodeEvent.Error && (it as PhoneNodeEvent.Error).code == "no_exit"
        })

        job.cancel()
        node.stop()
    }

    @Test
    fun stopCleansUp() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.startRoomsOnly()
        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertTrue(node.activeRoomIds().isNotEmpty())

        node.stop()

        assertEquals(PhoneNode.State.STOPPED, node.state.value)
        assertTrue(node.activeRoomIds().isEmpty())
    }

    @Test
    fun companionLoadsBootstrapSoul() = runTest {
        val node = makeNode(scope = backgroundScope)

        node.start()
        advanceTimeBy(200)

        assertNotNull(node.companion)
        assertNotNull(node.companion!!.soulManifest)
        assertEquals(BootstrapSoulManifest.BOOTSTRAP_DID, node.companion!!.soulManifest!!.did)

        node.stop()
    }
}
