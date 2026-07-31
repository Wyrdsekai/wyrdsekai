package org.wyrdsekai.app.engine.tier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceProbeTest {

    // ── DefaultResourceProbe ──────────────────────────────────────────────

    @Test
    fun defaultProbeReturnsValidSnapshot() {
        val probe = DefaultResourceProbe()
        val snap = probe.snapshot()

        assertTrue(snap.availableMemoryMb > 0, "Available memory should be > 0")
        assertTrue(snap.totalMemoryMb > 0, "Total memory should be > 0")
        assertTrue(snap.availableMemoryMb <= snap.totalMemoryMb, "Available <= total")
        assertTrue(snap.batteryPercent in 0..100, "Battery should be 0-100")
        assertEquals(ThermalState.NOMINAL, snap.thermalState)
        assertEquals(true, snap.hasWifi)
    }

    @Test
    fun defaultProbeRecommendsTier() {
        val probe = DefaultResourceProbe()
        val snap = probe.snapshot()
        val tier = snap.recommendTier()
        // DefaultResourceProbe: 2000 MB avail, 80% battery, not charging, NOMINAL, wifi
        // => T2 (wifi + good resources + battery >= 30)
        assertEquals(Tier.T2, tier)
    }

    // ── ResourceSnapshot field validation ──────────────────────────────────

    @Test
    fun snapshotWithValidFieldsAccepted() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 1024,
            totalMemoryMb = 2048,
            batteryPercent = 50,
            isCharging = false,
            thermalState = ThermalState.FAIR,
            hasWifi = true,
        )
        assertTrue(snap.availableMemoryMb > 0)
        assertTrue(snap.batteryPercent in 0..100)
        assertTrue(snap.thermalState in ThermalState.entries)
    }

    @Test
    fun snapshotThermalStateCoversAllValues() {
        val states = ThermalState.entries
        assertEquals(4, states.size)
        assertTrue(states.contains(ThermalState.NOMINAL))
        assertTrue(states.contains(ThermalState.FAIR))
        assertTrue(states.contains(ThermalState.SERIOUS))
        assertTrue(states.contains(ThermalState.CRITICAL))
    }

    @Test
    fun snapshotWithZeroBatteryIsValid() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 2000,
            totalMemoryMb = 4000,
            batteryPercent = 0,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = true,
        )
        assertEquals(0, snap.batteryPercent)
        // 0% battery + not charging => T0
        assertEquals(Tier.T0, snap.recommendTier())
    }

    @Test
    fun snapshotWithFullBatteryIsValid() {
        val snap = ResourceSnapshot(
            availableMemoryMb = 2000,
            totalMemoryMb = 4000,
            batteryPercent = 100,
            isCharging = true,
            thermalState = ThermalState.NOMINAL,
            hasWifi = false,
        )
        assertEquals(100, snap.batteryPercent)
    }

    // ── Custom probe ──────────────────────────────────────────────────────

    @Test
    fun customProbeSnapshotFieldsInRange() {
        val probe = object : ResourceProbe {
            override fun snapshot() = ResourceSnapshot(
                availableMemoryMb = 512,
                totalMemoryMb = 1024,
                batteryPercent = 25,
                isCharging = true,
                thermalState = ThermalState.SERIOUS,
                hasWifi = false,
            )
        }
        val snap = probe.snapshot()
        assertTrue(snap.availableMemoryMb > 0)
        assertTrue(snap.totalMemoryMb >= snap.availableMemoryMb)
        assertTrue(snap.batteryPercent in 0..100)
        assertEquals(ThermalState.SERIOUS, snap.thermalState)
    }

    @Test
    fun probeSnapshotIsConsistentAcrossMultipleCalls() {
        val probe = DefaultResourceProbe()
        val snap1 = probe.snapshot()
        val snap2 = probe.snapshot()
        // DefaultResourceProbe is deterministic — same values each time
        assertEquals(snap1, snap2)
    }

    @Test
    fun thermalStateOrderingMatchesSeverity() {
        // Verify ordinal ordering: NOMINAL < FAIR < SERIOUS < CRITICAL
        assertTrue(ThermalState.NOMINAL.ordinal < ThermalState.FAIR.ordinal)
        assertTrue(ThermalState.FAIR.ordinal < ThermalState.SERIOUS.ordinal)
        assertTrue(ThermalState.SERIOUS.ordinal < ThermalState.CRITICAL.ordinal)
    }

    @Test
    fun snapshotWithMinimalMemoryRecommendation() {
        // Exactly at T1 threshold: 1200 MB, 20% battery
        val snap = ResourceSnapshot(
            availableMemoryMb = 1200,
            totalMemoryMb = 2000,
            batteryPercent = 20,
            isCharging = false,
            thermalState = ThermalState.NOMINAL,
            hasWifi = false,
        )
        assertEquals(Tier.T1, snap.recommendTier())
    }
}
