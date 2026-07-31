package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WarmHandoffTest {

    @Test
    fun sendHandoffPublishesToBetween() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = WarmHandoffManager(between, "node-1", "family-1")

        val context = WarmHandoffContext(
            fromDid = "bud-1",
            toDid = "bud-2",
            activeRoomId = "nexus",
            openConversationDids = listOf("bud-2"),
            recentTurns = listOf(
                ConversationTurn("user", "Hello", 1000),
                ConversationTurn("assistant", "Hi there!", 1001),
            ),
            vitalitySnapshot = mapOf("energy" to 0.8f, "confidence" to 0.7f),
            currentTask = "exploring",
            timestamp = 2000,
        )

        manager.sendHandoff(context, "node-2")

        assertEquals(1, between.published.size)
        val (subject, _) = between.published[0]
        assertTrue(subject.contains("family-1"))
        assertTrue(subject.contains("node-1"))
        assertTrue(subject.contains("node-2"))
        assertTrue(subject.contains("soul.handoff"))
    }

    @Test
    fun receiveHandoffTriggersCallback() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = WarmHandoffManager(between, "node-2", "family-1")

        var received: WarmHandoffContext? = null
        manager.onHandoffReceived { received = it }
        manager.startListening()

        // Simulate handoff from node-1
        val context = WarmHandoffContext(
            fromDid = "bud-1",
            toDid = "bud-2",
            activeRoomId = "terminal",
            timestamp = 3000,
        )
        val data = kotlinx.serialization.json.Json.encodeToString(
            WarmHandoffContext.serializer(), context
        ).encodeToByteArray()
        between.publish("between.household.family-1.node-1.node-2.soul.handoff", data)

        assertNotNull(received)
        assertEquals("terminal", received!!.activeRoomId)
        assertEquals("bud-1", received!!.fromDid)

        manager.stopListening()
    }

    @Test
    fun contextSerializesWithConversationTurns() {
        val context = WarmHandoffContext(
            fromDid = "bud-1",
            toDid = "bud-2",
            activeRoomId = "nexus",
            recentTurns = listOf(
                ConversationTurn("user", "How are you?", 100),
                ConversationTurn("assistant", "I'm well, thank you!", 101),
            ),
            vitalitySnapshot = mapOf("energy" to 0.5f),
            timestamp = 200,
        )

        val json = kotlinx.serialization.json.Json.encodeToString(
            WarmHandoffContext.serializer(), context
        )
        val restored = kotlinx.serialization.json.Json.decodeFromString(
            WarmHandoffContext.serializer(), json
        )
        assertEquals(2, restored.recentTurns.size)
        assertEquals("How are you?", restored.recentTurns[0].content)
    }

    @Test
    fun handoffSubjectMatchesPattern() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = WarmHandoffManager(between, "node-1", "family-abc")

        val context = WarmHandoffContext(
            fromDid = "bud-1", toDid = "bud-2",
            activeRoomId = "nexus", timestamp = 100,
        )
        manager.sendHandoff(context, "node-2")

        val subject = between.published[0].first
        assertEquals("between.household.family-abc.node-1.node-2.soul.handoff", subject)
    }
}
