package org.wyrdsekai.app.engine.between

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresenceManagerTest {

    @Test
    fun announcePublishesToCorrectSubject() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = PresenceManager(between, "node-1", "household-1")

        manager.announce("online")

        assertEquals(1, between.published.size)
        assertEquals("between.household-1.presence.node-1", between.published[0].first)
    }

    @Test
    fun announceUpdatesLocalPresenceMap() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = PresenceManager(between, "node-1", "household-1")

        manager.announce("online")

        val presence = manager.getHouseholdPresence()
        assertEquals(1, presence.size)
        assertEquals("online", presence["node-1"]?.status)
    }

    @Test
    fun subscribeReceivesPresenceFromOtherAgents() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = PresenceManager(between, "node-1", "household-1")
        manager.startListening()

        // Simulate another agent announcing presence
        val otherState = PresenceState(
            nodeId = "node-2",
            status = "sleeping",
            timestamp = 1000L,
        )
        val data = kotlinx.serialization.json.Json.encodeToString(
            PresenceState.serializer(), otherState
        ).encodeToByteArray()
        between.publish("between.household-1.presence.node-2", data)

        val presence = manager.getHouseholdPresence()
        assertEquals(1, presence.size)
        assertEquals("sleeping", presence["node-2"]?.status)

        manager.stopListening()
    }

    @Test
    fun multipleAgentsTrackedSeparately() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = PresenceManager(between, "node-1", "household-1")
        manager.startListening()

        // Announce own presence
        manager.announce("online")

        // Simulate two other agents
        for (i in 2..3) {
            val state = PresenceState(nodeId = "node-$i", status = "online", timestamp = 1000L)
            val data = kotlinx.serialization.json.Json.encodeToString(
                PresenceState.serializer(), state
            ).encodeToByteArray()
            between.publish("between.household-1.presence.node-$i", data)
        }

        val presence = manager.getHouseholdPresence()
        assertEquals(3, presence.size)
        assertTrue(presence.containsKey("node-1"))
        assertTrue(presence.containsKey("node-2"))
        assertTrue(presence.containsKey("node-3"))

        manager.stopListening()
    }

    @Test
    fun gracefulWhenDisconnected() = runTest {
        val between = InMemoryBetweenClient()
        // NOT connected
        val manager = PresenceManager(between, "node-1", "household-1")

        // Should not throw
        manager.announce("online")

        // Still updates local map
        val presence = manager.getHouseholdPresence()
        assertEquals(1, presence.size)
        // But nothing published
        assertEquals(0, between.published.size)
    }

    @Test
    fun presenceStateUpdatedOnRepeatAnnounce() = runTest {
        val between = InMemoryBetweenClient()
        between.connect("ws://test")
        val manager = PresenceManager(between, "node-1", "household-1")

        manager.announce("online")
        assertEquals("online", manager.getHouseholdPresence()["node-1"]?.status)

        manager.announce("sleeping")
        assertEquals("sleeping", manager.getHouseholdPresence()["node-1"]?.status)
    }
}
