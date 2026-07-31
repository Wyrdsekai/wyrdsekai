package org.wyrdsekai.app.engine.tier

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class TierConfigTest {

    @Test
    fun tierConfigsAreConsistentWithSpec() {
        // T0: soul + nexus (companion needs a room), remote inference
        val t0 = TierConfig.forTier(Tier.T0)
        assertEquals(0, t0.maxRooms) // maxRooms is for non-essential rooms; nexus is always-on
        assertEquals(InferenceMode.REMOTE, t0.inferenceMode)
        assertEquals(false, t0.betweenEnabled)

        // T1: one room, local inference
        val t1 = TierConfig.forTier(Tier.T1)
        assertEquals(1, t1.maxRooms)
        assertEquals(InferenceMode.LOCAL, t1.inferenceMode)
        assertEquals(false, t1.betweenEnabled)

        // T2: multi-room, Between enabled
        val t2 = TierConfig.forTier(Tier.T2)
        assertEquals(4, t2.maxRooms)
        assertEquals(true, t2.betweenEnabled)

        // T3: full peer
        val t3 = TierConfig.forTier(Tier.T3)
        assertEquals(16, t3.maxRooms)
        assertEquals(2, t3.maxConcurrentInference)
        assertEquals(true, t3.betweenEnabled)
    }

    @Test
    fun contextTokensScaleWithTier() {
        val tiers = Tier.entries.map { TierConfig.forTier(it).maxContextTokens }
        // Each tier should have >= previous tier's tokens
        for (i in 1 until tiers.size) {
            assertTrue(tiers[i] >= tiers[i - 1], "T${i} context tokens should be >= T${i - 1}")
        }
    }

    // ── ResourceSnapshot tier recommendation ────────────────────────────

    @Test
    fun criticalThermalForcesT0() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 100,
            isCharging = true,
            thermalState = ThermalState.CRITICAL,
            hasWifi = true,
        )
        assertEquals(Tier.T0, snap.recommendTier())
    }

    @Test
    fun lowBatteryNotChargingForcesT0() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 5,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
        assertEquals(Tier.T0, snap.recommendTier())
    }

    @Test
    fun chargingWithWifiAndHighMemoryGivesT3() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 80,
            isCharging = true,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
        assertEquals(Tier.T3, snap.recommendTier())
    }

    @Test
    fun wifiWithGoodResourcesGivesT2() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 2500,
            totalMemoryMb = 4000,
            batteryPercent = 60,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
        assertEquals(Tier.T2, snap.recommendTier())
    }

    @Test
    fun moderateResourcesGivesT1() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 1500,
            totalMemoryMb = 3000,
            batteryPercent = 40,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = false,
        )
        assertEquals(Tier.T1, snap.recommendTier())
    }

    @Test
    fun lowMemoryGivesT0() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 800,
            totalMemoryMb = 2000,
            batteryPercent = 40,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
        assertEquals(Tier.T0, snap.recommendTier())
    }

    @Test
    fun lowBatteryWhileChargingStillAllowsT1() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 2000,
            totalMemoryMb = 4000,
            batteryPercent = 5,
            isCharging = true,
            thermalState = ThermalState.NOMINAL,
            hasWifi = false,
        )
        assertEquals(Tier.T1, snap.recommendTier())
    }
}
