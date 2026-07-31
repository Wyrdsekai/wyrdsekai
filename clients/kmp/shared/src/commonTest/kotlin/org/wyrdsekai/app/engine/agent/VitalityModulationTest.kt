package org.wyrdsekai.app.engine.agent

import kotlin.test.Test
import kotlin.test.assertTrue

class VitalityModulationTest {

    private val profile = Companions.NEXUS_COMPANION

    @Test
    fun defaultModulation() {
        val mod = VitalityModulation.compute(VitalityState.initial(), profile)
        assertTrue(mod.maxResponseTokens > 64)
        assertTrue(mod.maxResponseTokens <= profile.maxResponseTokens)
        assertTrue(mod.temperature > 0.0)
        assertTrue(mod.temperature <= 1.5)
        assertTrue(mod.debounceDelayMs > 0)
        assertTrue(mod.conversationHistorySize >= 5)
    }

    @Test
    fun lowEnergyReducesTokens() {
        val low = VitalityState.initial().withEnergy(0.1)
        val high = VitalityState.initial().withEnergy(0.9)
        val modLow = VitalityModulation.compute(low, profile)
        val modHigh = VitalityModulation.compute(high, profile)
        assertTrue(modLow.maxResponseTokens < modHigh.maxResponseTokens)
    }

    @Test
    fun highConfidenceLowersTemperature() {
        val confident = VitalityState.initial().withConfidence(0.9)
        val uncertain = VitalityState.initial().withConfidence(0.1)
        val modConfident = VitalityModulation.compute(confident, profile)
        val modUncertain = VitalityModulation.compute(uncertain, profile)
        assertTrue(modConfident.temperature < modUncertain.temperature)
    }

    @Test
    fun highMomentumShortensDebounce() {
        val fast = VitalityState.initial().withMomentum(0.9)
        val slow = VitalityState.initial().withMomentum(0.1)
        val modFast = VitalityModulation.compute(fast, profile)
        val modSlow = VitalityModulation.compute(slow, profile)
        assertTrue(modFast.debounceDelayMs <= modSlow.debounceDelayMs)
    }

    @Test
    fun highFocusIncreasesHistory() {
        val focused = VitalityState.initial().withFocus(0.9)
        val distracted = VitalityState.initial().withFocus(0.1)
        val modFocused = VitalityModulation.compute(focused, profile)
        val modDistracted = VitalityModulation.compute(distracted, profile)
        assertTrue(modFocused.conversationHistorySize > modDistracted.conversationHistorySize)
    }

    @Test
    fun errorPressureReducesTemperature() {
        val pressured = VitalityState.initial().withErrorPressure(0.7)
        val calm = VitalityState.initial().withErrorPressure(0.0)
        val modPressured = VitalityModulation.compute(pressured, profile)
        val modCalm = VitalityModulation.compute(calm, profile)
        assertTrue(modPressured.temperature < modCalm.temperature)
    }
}
