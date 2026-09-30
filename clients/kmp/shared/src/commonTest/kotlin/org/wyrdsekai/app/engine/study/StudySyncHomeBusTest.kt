package org.wyrdsekai.app.engine.study

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.between.InMemoryBetweenClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On the home's bus a phone logs in as its own pairing account (USER) and the
 * home lets it hear only Study frames addressed to USER: other devices' frames
 * carry their session tokens. So there the layer subscribes to its directed
 * sync subject alone, and publishes only as USER.
 */
class StudySyncHomeBusTest {

    private class Recording(private val inner: InMemoryBetweenClient = InMemoryBetweenClient()) : BetweenClient by inner {
        val subscribed = mutableListOf<String>()
        val published get() = inner.published
        override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
            subscribed += subject
            return inner.subscribe(subject, handler)
        }
    }

    @Test
    fun onTheHomeBusItHearsOnlyFramesAddressedToIt() = runTest {
        val bus = Recording().apply { connect("wss://home") }
        val store = InMemoryStudyStore()
        store.writeJournal("user1", "a note")
        val user = "phone-6f1c"
        val layer = StudySyncLayer(bus, store, user, "rehearsal", "user1", this, authToken = "tok", directedOnly = true)
        layer.startListening()
        layer.broadcastState()
        layer.requestDelta("server")

        assertEquals(listOf("between.rehearsal.*.$user.study.sync"), bus.subscribed)
        val subjects = bus.published.map { it.first }
        assertEquals(listOf("between.rehearsal.$user.*.study.state", "between.rehearsal.$user.server.study.sync"), subjects)
    }

    @Test
    fun theHomesDirectedAnswerStillArrives() = runTest {
        val bus = Recording().apply { connect("wss://home") }
        val store = InMemoryStudyStore()
        val user = "phone-6f1c"
        val layer = StudySyncLayer(bus, store, user, "rehearsal", "user1", this, directedOnly = true)
        layer.startListening()
        val item = StudyItem(id = "n1", userDid = "user1", itemType = StudyItem.TYPE_JOURNAL, content = "from home",
            timestamp = 1L, vectorClock = mapOf("server" to 1L), lastModifiedBy = "server")
        val delta = StudySyncMessage(type = "study_delta", deviceId = "server", userDid = "user1", items = listOf(item))
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        bus.publish("between.rehearsal.server.$user.study.sync",
            json.encodeToString(StudySyncMessage.serializer(), delta).encodeToByteArray())
        testScheduler.advanceUntilIdle()
        assertTrue(store.getItem("n1") != null, "the home's delta addressed to this phone is merged")
    }

    @Test
    fun elsewhereItStillHearsPeerStates() = runTest {
        val bus = Recording().apply { connect("ws://test") }
        val layer = StudySyncLayer(bus, InMemoryStudyStore(), "phone-1", "family-1", "user1", this)
        layer.startListening()
        assertEquals(listOf("between.family-1.*.*.study.state", "between.family-1.*.phone-1.study.sync"), bus.subscribed)
    }
}
