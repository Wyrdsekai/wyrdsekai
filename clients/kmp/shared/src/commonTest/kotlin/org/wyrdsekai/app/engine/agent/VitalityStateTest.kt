package org.wyrdsekai.app.engine.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VitalityStateTest {

    @Test
    fun initialState() {
        val s = VitalityState.initial()
        assertEquals(0.5, s.contextBudget)
        assertEquals(0.5, s.confidence)
        assertEquals(1.0, s.energy)
        assertEquals(0.3, s.alignment)
        assertEquals(0.0, s.errorPressure)
        assertEquals(0.0, s.momentum)
        assertEquals(0.3, s.rapport)
        assertEquals(0.5, s.focus)
    }

    @Test
    fun tickAppliesRecoveryAndDecay() {
        val s = VitalityState.initial().tick()
        assertTrue(s.contextBudget > 0.5) // +0.003
        assertTrue(s.energy > 1.0 - 0.001) // +0.005 but clamped to 1.0
        assertTrue(s.alignment < 0.3) // -0.001
        assertTrue(s.momentum < 0.0 + 0.001) // -0.003 but clamped to 0.0
    }

    @Test
    fun clampedKeepsBounds() {
        val s = VitalityState(
            contextBudget = 1.5,
            confidence = -0.5,
            energy = 2.0,
            alignment = -1.0,
            errorPressure = 0.5,
            momentum = 0.5,
            rapport = 0.5,
            focus = 0.5,
        ).clamped()
        assertEquals(1.0, s.contextBudget)
        assertEquals(0.0, s.confidence)
        assertEquals(1.0, s.energy)
        assertEquals(0.0, s.alignment)
    }

    @Test
    fun withMethodsClamp() {
        val s = VitalityState.initial()
        assertEquals(1.0, s.withEnergy(1.5).energy)
        assertEquals(0.0, s.withEnergy(-0.5).energy)
        assertEquals(0.8, s.withRapport(0.8).rapport)
    }

    @Test
    fun describeProducesNonEmptyString() {
        val desc = VitalityState.initial().describe()
        assertTrue(desc.isNotBlank())
        assertTrue(desc.startsWith("Current state:"))
    }

    @Test
    fun describeExhausted() {
        val s = VitalityState.initial().withEnergy(0.1)
        assertTrue(s.describe().contains("exhausted"))
    }

    @Test
    fun appearanceRadiant() {
        val s = VitalityState.initial().withEnergy(0.9).withFocus(0.8)
        assertEquals("radiant and focused", s.appearance())
    }

    @Test
    fun appearanceDim() {
        val s = VitalityState.initial().withEnergy(0.1)
        assertEquals("dim and fading", s.appearance())
    }

    @Test
    fun tickManyTimesConverges() {
        var s = VitalityState.initial()
        repeat(1000) { s = s.tick() }
        // After many ticks, energy should be maxed, error pressure zeroed
        assertEquals(1.0, s.energy)
        assertEquals(0.0, s.errorPressure)
        assertEquals(1.0, s.contextBudget)
    }
}
