package org.wyrdsekai.app.engine.tier

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TierManagerTest {

    private fun probe(
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

    @Test
    fun initializeSetsInitialTier() = runTest {
        val manager = TierManager(probe(memoryMb = 1500, wifi = false), scope = this)
        manager.initialize()
        assertEquals(Tier.T1, manager.currentTier.value)
    }

    @Test
    fun initializeWithLowResourcesSetsT0() = runTest {
        val manager = TierManager(probe(memoryMb = 500), scope = this)
        manager.initialize()
        assertEquals(Tier.T0, manager.currentTier.value)
    }

    @Test
    fun initializeWithHighResourcesSetsT3() = runTest {
        val manager = TierManager(probe(memoryMb = 4000, charging = true, wifi = true), scope = this)
        manager.initialize()
        assertEquals(Tier.T3, manager.currentTier.value)
    }

    @Test
    fun forceTierOverridesProbe() = runTest {
        val manager = TierManager(probe(memoryMb = 500), scope = this)
        manager.initialize()
        assertEquals(Tier.T0, manager.currentTier.value)

        manager.forceTier(Tier.T2)
        assertEquals(Tier.T2, manager.currentTier.value)
    }

    @Test
    fun tierTransitionsEmitted() = runTest {
        val manager = TierManager(probe(memoryMb = 1500, wifi = false), scope = this)
        val transitions = mutableListOf<TierTransition>()
        val job = launch { manager.transitions.collect { transitions.add(it) } }
        yield() // Let collector start

        manager.initialize()
        yield() // Let emission propagate
        // Initial transition T0 → T1
        assertEquals(1, transitions.size)
        assertEquals(Tier.T0, transitions[0].from)
        assertEquals(Tier.T1, transitions[0].to)
        assertEquals("initial", transitions[0].reason)

        manager.forceTier(Tier.T3)
        yield()
        assertEquals(2, transitions.size)
        assertTrue(transitions[1].isPromotion)

        manager.forceTier(Tier.T0)
        yield()
        assertEquals(3, transitions.size)
        assertTrue(transitions[2].isDemotion)

        job.cancel()
    }

    @Test
    fun configUpdatesWithTier() = runTest {
        val manager = TierManager(probe(), scope = this)
        manager.initialize()

        manager.forceTier(Tier.T0)
        assertEquals(InferenceMode.REMOTE, manager.config.value.inferenceMode)
        assertEquals(0, manager.config.value.maxRooms)

        manager.forceTier(Tier.T2)
        assertEquals(InferenceMode.LOCAL, manager.config.value.inferenceMode)
        assertEquals(4, manager.config.value.maxRooms)
        assertTrue(manager.config.value.betweenEnabled)
    }

    @Test
    fun monitoringDetectsChanges() = runTest {
        var currentMemory = 2000L
        val dynamicProbe = object : ResourceProbe {
            override fun snapshot() = ResourceSnapshot(
                availableMemoryMb = currentMemory,
                totalMemoryMb = 4000,
                batteryPercent = 80,
                isCharging = false,
                thermalState = ThermalState.NOMINAL,
                hasWifi = true,
            )
        }

        val manager = TierManager(dynamicProbe, probeIntervalMs = 1000, scope = this)
        val transitions = mutableListOf<TierTransition>()
        val job = launch { manager.transitions.collect { transitions.add(it) } }
        yield()

        manager.initialize()
        manager.startMonitoring()

        // Simulate memory drop
        currentMemory = 500
        advanceTimeBy(1500)
        yield()
        assertEquals(2, transitions.size)
        assertEquals(Tier.T0, transitions[1].to)

        // Simulate memory recovery
        currentMemory = 3000
        advanceTimeBy(1500)
        yield()
        assertEquals(3, transitions.size)
        assertEquals(Tier.T2, transitions[2].to)

        manager.stopMonitoring()
        job.cancel()
    }

    @Test
    fun noTransitionEmittedWhenTierUnchanged() = runTest {
        val manager = TierManager(probe(memoryMb = 1500, wifi = false), probeIntervalMs = 1000, scope = this)
        val transitions = mutableListOf<TierTransition>()
        val job = launch { manager.transitions.collect { transitions.add(it) } }
        yield()

        manager.initialize()
        manager.startMonitoring()

        // Advance several intervals — probe returns same resources, no new transitions
        advanceTimeBy(5000)
        yield()
        assertEquals(1, transitions.size) // Only the initial transition

        manager.stopMonitoring()
        job.cancel()
    }
}
