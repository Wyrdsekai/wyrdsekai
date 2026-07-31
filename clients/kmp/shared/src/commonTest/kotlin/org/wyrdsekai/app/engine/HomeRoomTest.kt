package org.wyrdsekai.app.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.wyrdsekai.app.engine.tier.*
import org.wyrdsekai.app.inference.InferenceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the Home room rename (nexus -> home), room definitions,
 * tier-based room lists, custom home name, and deprecated accessor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeRoomTest {

    private fun makeNode(
        scope: kotlinx.coroutines.CoroutineScope,
        tierManager: TierManager? = null,
        homeRoomName: String = "Home",
    ) = PhoneNode(
        journal = InMemoryEventJournal(),
        vitalityStore = null,
        inferenceClient = InferenceClient(),
        inferenceBaseUrl = "http://test",
        scope = scope,
        tierManager = tierManager,
        homeRoomName = homeRoomName,
    )

    // ── Room definition key ──────────────────────────────────────────────

    @Test
    fun defaultStartingRoomIsHome() {
        // ROOM_DEFINITIONS must contain "home", not "nexus"
        assertTrue(
            PhoneNode.ROOM_DEFINITIONS.containsKey("home"),
            "ROOM_DEFINITIONS should contain 'home'"
        )
    }

    @Test
    fun homeRoomDescriptionIsPersonal() {
        val homeDef = PhoneNode.ROOM_DEFINITIONS["home"]
        assertNotNull(homeDef, "Home room definition should exist")
        assertTrue(
            homeDef.description.contains("warm"),
            "Home description should contain 'warm'"
        )
        assertTrue(
            homeDef.description.contains("yours"),
            "Home description should contain 'yours'"
        )
    }

    // ── Exit targets ─────────────────────────────────────────────────────

    @Test
    fun allExitsPointToHome() {
        // Every room's exits that link back to the central room should target "home"
        for ((roomId, def) in PhoneNode.ROOM_DEFINITIONS) {
            for (exit in def.exits) {
                if (exit.targetRoom == "nexus") {
                    throw AssertionError(
                        "Room '$roomId' still has an exit targeting 'nexus' " +
                        "(direction: ${exit.direction}). Should target 'home'."
                    )
                }
            }
        }
        // Also verify that non-home rooms actually point back to "home"
        val nonHomeRooms = PhoneNode.ROOM_DEFINITIONS.filter { it.key != "home" }
        for ((roomId, def) in nonHomeRooms) {
            val hasHomeExit = def.exits.any { it.targetRoom == "home" }
            assertTrue(
                hasHomeExit,
                "Room '$roomId' should have an exit pointing back to 'home'"
            )
        }
    }

    // ── Tier-based room lists ────────────────────────────────────────────

    @Test
    fun roomsForT1IncludesStudy() = runTest {
        val node = makeNode(scope = this)
        val t1Rooms = node.roomsForTier(Tier.T1)
        assertEquals(listOf("study"), t1Rooms)
    }

    @Test
    fun roomsForT2IncludesStudyHomePlusThree() = runTest {
        val node = makeNode(scope = this)
        val t2Rooms = node.roomsForTier(Tier.T2)
        assertEquals(5, t2Rooms.size, "T2 should have 5 rooms")
        assertEquals("study", t2Rooms.first(), "First room in T2 should be 'study'")
        assertTrue(t2Rooms.contains("home"))
        assertTrue(t2Rooms.contains("terminal"))
        assertTrue(t2Rooms.contains("dream-chamber"))
        assertTrue(t2Rooms.contains("mailroom"))
    }

    @Test
    fun roomsForT3IncludesEightRooms() = runTest {
        val node = makeNode(scope = this)
        val t3Rooms = node.roomsForTier(Tier.T3)
        assertEquals(8, t3Rooms.size, "T3 should have 8 rooms")
        assertEquals("study", t3Rooms.first(), "First room in T3 should be 'study'")
    }

    // ── Custom home name ─────────────────────────────────────────────────

    @Test
    fun customHomeNameUsedInBoot() = runTest {
        // At T1 (default), only Study room is active. Home room requires T2+.
        // Create a T2 node to test custom home name.
        val probe = object : ResourceProbe {
            override fun snapshot() = ResourceSnapshot(
                availableMemoryMb = 2500,
                totalMemoryMb = 4000,
                batteryPercent = 80,
                isCharging = false,
                thermalState = ThermalState.NOMINAL,
                hasWifi = true,
            )
        }
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(
            scope = this,
            homeRoomName = "My Den",
            tierManager = tierManager,
        )

        node.startRoomsOnly()

        // At T2, home room should be active with custom name
        assertTrue(node.activeRoomIds().contains("home"), "Home room should be active at T2")

        // The deprecated nexusRoom accessor returns activeRooms["home"]
        @Suppress("DEPRECATION")
        val homeRoom = node.nexusRoom
        assertNotNull(homeRoom, "Home room should be active at T2")
        assertEquals("My Den", homeRoom.state.value.name,
            "Home room name should be 'My Den', not the default 'Home'")

        node.stop()
    }

    // ── Deprecated nexusRoom accessor ────────────────────────────────────

    @Test
    fun nexusRoomAccessorReturnsHome() = runTest {
        // nexusRoom returns activeRooms["home"] — needs T2+ where home is active
        val probe = object : ResourceProbe {
            override fun snapshot() = ResourceSnapshot(
                availableMemoryMb = 2500,
                totalMemoryMb = 4000,
                batteryPercent = 80,
                isCharging = false,
                thermalState = ThermalState.NOMINAL,
                hasWifi = true,
            )
        }
        val tierManager = TierManager(probe, scope = this)
        val node = makeNode(scope = this, tierManager = tierManager)

        node.startRoomsOnly()

        @Suppress("DEPRECATION")
        val viaDeprecated = node.nexusRoom

        assertNotNull(viaDeprecated, "Deprecated nexusRoom accessor should return the home room at T2")
        assertEquals("home", viaDeprecated.state.value.roomId,
            "nexusRoom should return the 'home' room instance")

        node.stop()
    }

    // ── T0 still has home ────────────────────────────────────────────────

    @Test
    fun roomsForT0IncludesStudy() = runTest {
        val node = makeNode(scope = this)
        val t0Rooms = node.roomsForTier(Tier.T0)
        assertEquals(listOf("study"), t0Rooms, "T0 should include 'study' (companion's home base on phone)")
    }
}
