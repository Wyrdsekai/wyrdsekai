package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.VitalityState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests against clients/rn/src/engine/soul/SleepCycle.ts. If you change
 * recovery math or thresholds here, change them there too — they must stay
 * numerically identical or KMP and RN companions will diverge on sleep.
 */
class SleepCycleTest {

    private val testProfile = AgentProfile(
        name = "Lain",
        entityId = "home-server-1",
        entityType = "agent",
        description = "Test agent",
        systemPrompt = "You are Lain.",
        contextWindowTokens = 4096,
        maxResponseTokens = 512,
        temperature = 0.7,
    )

    private fun freshManifest(): ClientSoulManifest = LocalForge.forge(
        did = "did:key:home-server",
        publicKey = "z6MkLain",
        version = 1,
        profile = testProfile,
        residentIdentity = "I am Lain.",
        vitality = VitalityState.initial(),
    )

    @Test fun initialSleepState_isAwakeAndEmpty() {
        val s = SleepCycle.initialSleepState()
        assertFalse(s.isSleeping)
        assertEquals(0, s.consecutiveSleeps)
        assertEquals(0, s.ticksSinceLastSleep)
        assertTrue(s.eventsSinceLastSleep.isEmpty())
    }

    @Test fun shouldSleep_requiresAllConditions() {
        val ready = SleepCycle.initialSleepState().copy(ticksSinceLastSleep = 31)
        // All conditions met
        assertTrue(SleepCycle.shouldSleep(ready, energy = 0.1, isIdle = true))
        // Energy too high
        assertFalse(SleepCycle.shouldSleep(ready, energy = 0.2, isIdle = true))
        // Not idle
        assertFalse(SleepCycle.shouldSleep(ready, energy = 0.1, isIdle = false))
        // Already sleeping
        assertFalse(SleepCycle.shouldSleep(ready.copy(isSleeping = true), 0.1, true))
        // Too soon after last sleep
        assertFalse(SleepCycle.shouldSleep(ready.copy(ticksSinceLastSleep = 30), 0.1, true))
    }

    @Test fun recoveryMultiplier_decaysWithConsecutiveSleeps() {
        assertEquals(10, SleepCycle.recoveryMultiplier(0))
        assertEquals(5, SleepCycle.recoveryMultiplier(1))
        assertEquals(2, SleepCycle.recoveryMultiplier(2))
        assertEquals(1, SleepCycle.recoveryMultiplier(3))
        assertEquals(1, SleepCycle.recoveryMultiplier(100))
    }

    @Test fun executeSleep_bumpsVersionAndAdjustsTanks() {
        val before = freshManifest()
        val result = SleepCycle.executeSleep(
            state = SleepCycle.initialSleepState(),
            manifest = before,
            energy = 0.1,
        )

        // Version always bumps
        assertEquals(before.manifestVersion + 1, result.newManifest.manifestVersion)
        // Recovery values are positive when energy is low
        assertTrue(result.energyRecovery > 0.0, "energyRecovery should be positive")
        assertTrue(result.focusRecovery > 0.0, "focusRecovery should be positive")
        assertTrue(result.sleepQuality in 0.0..1.0)
        // Tanks get adjusted
        val tanks = result.newManifest.vitalityTanks
        assertNotNull(tanks["energy"])
        assertNotNull(tanks["focus"])
        assertNotNull(tanks["errorPressure"])
        // No forge result attached by the pure cycle itself
        assertNull(result.forgeResult)
    }

    @Test fun executeSleep_qualityHigherWhenMoreDepleted() {
        val manifest = freshManifest()
        val state = SleepCycle.initialSleepState()
        val depleted = SleepCycle.executeSleep(state, manifest, energy = 0.05)
        val rested = SleepCycle.executeSleep(state, manifest, energy = 0.9)
        assertTrue(
            depleted.sleepQuality > rested.sleepQuality,
            "depleted=${depleted.sleepQuality} should beat rested=${rested.sleepQuality}",
        )
    }

    @Test fun executeSleep_firstSleepGivesMostEnergyBack() {
        val manifest = freshManifest()
        val first = SleepCycle.executeSleep(
            state = SleepCycle.initialSleepState(),
            manifest = manifest,
            energy = 0.1,
        )
        val fourth = SleepCycle.executeSleep(
            state = SleepCycle.initialSleepState().copy(consecutiveSleeps = 3),
            manifest = manifest,
            energy = 0.1,
        )
        assertTrue(
            first.energyRecovery > fourth.energyRecovery,
            "first=${first.energyRecovery} should beat fourth=${fourth.energyRecovery}",
        )
    }

    @Test fun completeSleep_clearsEventsAndBumpsConsecutive() {
        val s = SleepCycleState(
            eventsSinceLastSleep = listOf("a", "b", "c"),
            consecutiveSleeps = 2,
            isSleeping = true,
            ticksSinceLastSleep = 42,
        )
        val after = SleepCycle.completeSleep(s)
        assertTrue(after.eventsSinceLastSleep.isEmpty())
        assertEquals(3, after.consecutiveSleeps)
        assertFalse(after.isSleeping)
        assertEquals(0, after.ticksSinceLastSleep)
    }

    @Test fun tanksAreClampedInto01() {
        // Force a manifest with already-high focus to verify clamp
        val manifest = freshManifest().copy(
            vitalityTanks = mapOf("energy" to 0.99, "focus" to 0.95, "errorPressure" to 0.02),
        )
        val result = SleepCycle.executeSleep(
            state = SleepCycle.initialSleepState(),
            manifest = manifest,
            energy = 0.99,
        )
        val tanks = result.newManifest.vitalityTanks
        assertTrue((tanks["energy"] ?: 0.0) in 0.0..1.0)
        assertTrue((tanks["focus"] ?: 0.0) in 0.0..1.0)
        assertTrue((tanks["errorPressure"] ?: 0.0) in 0.0..1.0)
    }
}
