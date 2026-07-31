package org.wyrdsekai.app.engine.agent

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VitalityDerivativesTest {

    @Test
    fun zeroDerivativesAllZero() {
        val d = VitalityDerivatives.zero()
        assertEquals(0.0, d.energyVelocity)
        assertEquals(0.0, d.confidenceVelocity)
        assertEquals(0.0, d.focusVelocity)
        assertEquals(0.0, d.errorPressureVelocity)
        assertEquals(0.0, d.rapportVelocity)
        assertEquals(0.0, d.momentumVelocity)
        assertEquals(0.0, d.energyAcceleration)
        assertEquals(0.0, d.confidenceAcceleration)
        assertEquals(0.0, d.focusAcceleration)
        assertEquals(0.0, d.errorPressureAcceleration)
    }

    @Test
    fun computeVelocityFromStateChanges() {
        val prev = VitalityState(
            contextBudget = 0.5,
            confidence = 0.5,
            energy = 0.5,
            alignment = 0.5,
            errorPressure = 0.0,
            momentum = 0.0,
            rapport = 0.3,
            focus = 0.5,
        )
        val current = VitalityState(
            contextBudget = 0.5,
            confidence = 0.5,
            energy = 0.7,
            alignment = 0.5,
            errorPressure = 0.0,
            momentum = 0.0,
            rapport = 0.3,
            focus = 0.5,
        )
        val d = VitalityDerivatives.compute(prev, current, VitalityDerivatives.zero())

        // Energy changed from 0.5 to 0.7, so velocity should be positive
        // smoothed: 0.0 * 0.7 + 0.2 * 0.3 = 0.06
        assertTrue(d.energyVelocity > 0.0, "energyVelocity should be positive, was ${d.energyVelocity}")
        assertEquals(0.06, d.energyVelocity, 0.001)
    }

    @Test
    fun computeAccelerationFromVelocityChanges() {
        val state1 = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.5, alignment = 0.5,
            errorPressure = 0.0, momentum = 0.0, rapport = 0.3, focus = 0.5,
        )
        val state2 = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.6, alignment = 0.5,
            errorPressure = 0.0, momentum = 0.0, rapport = 0.3, focus = 0.5,
        )
        val state3 = VitalityState(
            contextBudget = 0.5, confidence = 0.5, energy = 0.8, alignment = 0.5,
            errorPressure = 0.0, momentum = 0.0, rapport = 0.3, focus = 0.5,
        )

        // First computation: velocity emerges
        val d1 = VitalityDerivatives.compute(state1, state2, VitalityDerivatives.zero())
        // Second computation: velocity increases (0.1 then 0.2), so acceleration should be non-zero
        val d2 = VitalityDerivatives.compute(state2, state3, d1)

        assertTrue(
            abs(d2.energyAcceleration) > 0.0,
            "energyAcceleration should be non-zero, was ${d2.energyAcceleration}"
        )
    }

    @Test
    fun describeTrendsEmptyWhenStable() {
        val d = VitalityDerivatives.zero()
        assertEquals("", d.describeTrends())
    }

    @Test
    fun describeTrendsShowsRising() {
        // energyVelocity > 0.01 threshold triggers a trend description
        val d = VitalityDerivatives(
            energyVelocity = 0.02,
            confidenceVelocity = 0.0,
            focusVelocity = 0.0,
            errorPressureVelocity = 0.0,
            rapportVelocity = 0.0,
            momentumVelocity = 0.0,
            energyAcceleration = 0.0,
            confidenceAcceleration = 0.0,
            focusAcceleration = 0.0,
            errorPressureAcceleration = 0.0,
        )
        val trends = d.describeTrends()
        assertTrue(trends.contains("energy"), "trends should mention 'energy', was: $trends")
        assertTrue(trends.contains("rising"), "trends should mention 'rising', was: $trends")
    }
}
