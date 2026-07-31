package org.wyrdsekai.app.engine.tier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for tier detection wiring: ResourceSnapshot.recommendTier() under
 * various device conditions. Complements TierConfigTest by focusing on
 * realistic device profiles rather than boundary conditions.
 */
class TierDetectionTest {

    // ── High-resource scenarios ──────────────────────────────────────────

    @Test
    fun highMemoryWifiChargingRecommendsT3() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 8000,
            totalMemoryMb = 16000,
            batteryPercent = 80,
            isCharging = true,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T3, snap.recommendTier())
    }

    @Test
    fun highMemoryWifiBatteryRecommendsT2() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 50,
            isCharging = false,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T2, snap.recommendTier())
    }

    // ── Low-resource scenarios ───────────────────────────────────────────

    @Test
    fun lowMemoryRecommendsT1() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 1500,
            totalMemoryMb = 4000,
            batteryPercent = 30,
            isCharging = false,
            hasWifi = false,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T1, snap.recommendTier())
    }

    @Test
    fun criticalThermalRecommendsT0() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 8000,
            totalMemoryMb = 16000,
            batteryPercent = 100,
            isCharging = true,
            hasWifi = true,
            thermalState = ThermalState.CRITICAL,
        )
        assertEquals(Tier.T0, snap.recommendTier(),
            "CRITICAL thermal state should always force T0 regardless of other resources")
    }

    @Test
    fun lowBatteryNotChargingRecommendsT0() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 5,
            isCharging = false,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T0, snap.recommendTier(),
            "Battery < 10% and not charging should force T0")
    }

    // ── Realistic device profiles ────────────────────────────────────────

    @Test
    fun razrProfileGetsT2OrHigher() {
        // Simulate Motorola Razr Ultra 2025: 16GB RAM, comfortable state
        val snap = ResourceSnapshot(
            availableMemoryMb = 6000,  // ~6GB free of 16GB
            totalMemoryMb = 16000,
            batteryPercent = 80,
            isCharging = false,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        val tier = snap.recommendTier()
        assertTrue(
            tier >= Tier.T2,
            "Razr Ultra profile (6GB free, 80% battery, wifi) should get T2 or T3, got $tier"
        )
    }

    @Test
    fun razrChargingGetsT3() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 6000,
            totalMemoryMb = 16000,
            batteryPercent = 80,
            isCharging = true,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T3, snap.recommendTier(),
            "Razr charging on wifi with 6GB free should get T3")
    }

    // ── Edge cases ───────────────────────────────────────────────────────

    @Test
    fun lowBatteryChargingStillAllowsT1() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 1500,
            totalMemoryMb = 4000,
            batteryPercent = 5,
            isCharging = true,
            hasWifi = false,
            thermalState = ThermalState.NOMINAL,
        )
        // Battery < 10% but IS charging, so not forced to T0
        // Memory 1500 >= 1200 threshold, charging bypasses battery threshold for T1
        assertEquals(Tier.T1, snap.recommendTier(),
            "Low battery but charging with adequate memory should allow T1")
    }

    @Test
    fun seriousThermalDoesNotForceT0() {
        // Only CRITICAL forces T0; SERIOUS should not
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 80,
            isCharging = true,
            hasWifi = true,
            thermalState = ThermalState.SERIOUS,
        )
        val tier = snap.recommendTier()
        assertTrue(tier > Tier.T0,
            "SERIOUS thermal should not force T0 (only CRITICAL does), got $tier")
    }

    @Test
    fun noWifiPreventsT2() {
        // Good resources but no wifi -- T2 requires wifi
        val snap = ResourceSnapshot(
            availableMemoryMb = 4000,
            totalMemoryMb = 8000,
            batteryPercent = 80,
            isCharging = false,
            hasWifi = false,
            thermalState = ThermalState.NOMINAL,
        )
        val tier = snap.recommendTier()
        assertTrue(tier < Tier.T2,
            "Without wifi, tier should be below T2, got $tier")
    }

    @Test
    fun exactT2Threshold() {
        // Exactly at T2 thresholds: 2000 MB, 30% battery, wifi
        val snap = ResourceSnapshot(
            availableMemoryMb = 2000,
            totalMemoryMb = 4000,
            batteryPercent = 30,
            isCharging = false,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T2, snap.recommendTier(),
            "Exact T2 thresholds (2000 MB, 30% battery, wifi) should yield T2")
    }

    @Test
    fun justBelowT2BatteryGetsT1() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 2000,
            totalMemoryMb = 4000,
            batteryPercent = 29, // Below 30% threshold for T2
            isCharging = false,
            hasWifi = true,
            thermalState = ThermalState.NOMINAL,
        )
        assertEquals(Tier.T1, snap.recommendTier(),
            "Battery 29% (below T2's 30% threshold) should drop to T1")
    }
}
