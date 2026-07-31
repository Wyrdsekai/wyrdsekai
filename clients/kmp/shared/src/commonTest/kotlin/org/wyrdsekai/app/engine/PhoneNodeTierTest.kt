package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.wyrdsekai.app.engine.tier.*
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNodeTierTest {

    private fun makeProbe(
        memoryMb: Long = 2000,
        battery: Int = 80,
        charging: Boolean = false,
        thermal: ThermalState = ThermalState.NOMINAL,
        wifi: Boolean = true,
    ) = object : ResourceProbe {
        override fun snapshot() = ResourceSnapshot(
            availableMemoryMb = memoryMb,
            totalMemoryMb = 4000,
            batteryPercent = battery,
            isCharging = charging,
            thermalState = thermal,
            hasWifi = wifi,
        )
    }

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        tierManager: TierManager? = null,
    ) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = tierManager,
    )

    // ── Room definitions ────────────────────────────────────────────────

    @Test
    fun roomsForTierAreCumulative() {
        val node = makeNode(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )

        // T0 and T1 both have study (companion's home base on phone)
        assertEquals(listOf("study"), node.roomsForTier(Tier.T0))
        assertEquals(listOf("study"), node.roomsForTier(Tier.T1))
        // T2 adds home + more rooms
        assertTrue(node.roomsForTier(Tier.T2).contains("study"))
        assertTrue(node.roomsForTier(Tier.T2).contains("home"))
        assertTrue(node.roomsForTier(Tier.T2).contains("terminal"))
        assertTrue(node.roomsForTier(Tier.T2).size > node.roomsForTier(Tier.T1).size)
        // T3 adds even more
        assertTrue(node.roomsForTier(Tier.T3).size > node.roomsForTier(Tier.T2).size)
    }

    @Test
    fun allRoomDefinitionsExist() {
        val node = makeNode(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        val allRoomIds = Tier.entries.flatMap { node.roomsForTier(it) }.toSet()

        for (roomId in allRoomIds) {
            assertNotNull(
                PhoneNode.ROOM_DEFINITIONS[roomId],
                "Room definition missing for '$roomId'"
            )
        }
    }

    @Test
    fun roomDefinitionsHaveExitsAndDescriptions() {
        for ((roomId, def) in PhoneNode.ROOM_DEFINITIONS) {
            assertTrue(def.name.isNotBlank(), "Room '$roomId' has empty name")
            assertTrue(def.description.isNotBlank(), "Room '$roomId' has empty description")
            assertTrue(def.zone.isNotBlank(), "Room '$roomId' has empty zone")
            // All rooms should have at least one exit (back to home, or home has exits to others)
            assertTrue(def.exits.isNotEmpty(), "Room '$roomId' has no exits")
        }
    }

    // ── Tier-aware boot (rooms only, no companion/inference) ────────────

    @Test
    fun bootAtT1CreatesStudyOnly() = runTest {
        val probe = makeProbe(memoryMb = 1500, wifi = false)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertEquals(Tier.T1, node.currentTier)
        assertTrue(node.activeRoomIds().contains("study"))
        assertFalse(node.activeRoomIds().contains("home"))
        assertFalse(node.activeRoomIds().contains("terminal"))

        node.stop()
    }

    @Test
    fun bootAtT2CreatesMultipleRooms() = runTest {
        val probe = makeProbe(memoryMb = 2500, wifi = true)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()

        assertEquals(Tier.T2, node.currentTier)
        assertTrue(node.activeRoomIds().contains("study"))
        assertTrue(node.activeRoomIds().contains("home"))
        assertTrue(node.activeRoomIds().contains("terminal"))
        assertTrue(node.activeRoomIds().contains("dream-chamber"))
        assertTrue(node.activeRoomIds().contains("mailroom"))

        node.stop()
    }

    @Test
    fun bootWithoutTierManagerDefaultsToT1() = runTest {
        val node = makeNode(scope = this, tierManager = null)

        node.startRoomsOnly()

        assertEquals(PhoneNode.State.RUNNING, node.state.value)
        assertEquals(Tier.T1, node.currentTier)
        assertTrue(node.activeRoomIds().contains("study"))
        assertFalse(node.activeRoomIds().contains("home"))

        node.stop()
    }

    // ── Tier transitions ────────────────────────────────────────────────

    @Test
    fun promotionActivatesNewRooms() = runTest {
        val probe = makeProbe(memoryMb = 1500, wifi = false)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()
        yield() // Let tier listener collector start
        assertEquals(Tier.T1, node.currentTier)
        assertEquals(1, node.activeRoomIds().size)

        // Promote to T2 — advanceTimeBy lets bootRoom's delay(100) complete for each room
        tierManager.forceTier(Tier.T2)
        advanceTimeBy(1000); yield()

        assertEquals(Tier.T2, node.currentTier)
        assertTrue(node.activeRoomIds().contains("terminal"))
        assertTrue(node.activeRoomIds().contains("dream-chamber"))

        node.stop()
    }

    @Test
    fun demotionPassivatesRooms() = runTest {
        val probe = makeProbe(memoryMb = 2500, wifi = true)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()
        yield() // Let tier listener collector start
        assertEquals(Tier.T2, node.currentTier)
        assertTrue(node.activeRoomIds().size >= 5)

        // Demote to T1
        tierManager.forceTier(Tier.T1)
        advanceTimeBy(1000); yield()

        assertEquals(Tier.T1, node.currentTier)
        assertEquals(setOf("study"), node.activeRoomIds())
        assertTrue(node.passivatedRoomIds().contains("home"))
        assertTrue(node.passivatedRoomIds().contains("terminal"))
        assertTrue(node.passivatedRoomIds().contains("dream-chamber"))

        node.stop()
    }

    @Test
    fun reactivationAfterPromotion() = runTest {
        val probe = makeProbe(memoryMb = 2500, wifi = true)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()
        yield() // Let tier listener collector start

        // Demote then re-promote
        tierManager.forceTier(Tier.T1)
        advanceTimeBy(1000); yield()
        assertTrue(node.passivatedRoomIds().contains("terminal"))

        tierManager.forceTier(Tier.T2)
        advanceTimeBy(1000); yield()
        assertTrue(node.activeRoomIds().contains("terminal"))
        assertFalse(node.passivatedRoomIds().contains("terminal"))

        node.stop()
    }

    @Test
    fun tierChangedEventEmitted() = runTest {
        val probe = makeProbe(memoryMb = 1500, wifi = false)
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        val events = mutableListOf<PhoneNodeEvent>()
        val job = launch { node.notifications.collect { events.add(it) } }
        yield() // Let collector start

        node.startRoomsOnly()
        yield() // Let tier listener collector start

        tierManager.forceTier(Tier.T3)
        advanceTimeBy(1000); yield()

        assertTrue(events.any { it is PhoneNodeEvent.TierChanged })
        val tierEvent = events.filterIsInstance<PhoneNodeEvent.TierChanged>().first()
        assertEquals(Tier.T1, tierEvent.from)
        assertEquals(Tier.T3, tierEvent.to)

        job.cancel()
        node.stop()
    }
}
