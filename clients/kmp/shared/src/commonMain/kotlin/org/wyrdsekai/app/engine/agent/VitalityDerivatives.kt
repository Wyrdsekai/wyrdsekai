package org.wyrdsekai.app.engine.agent

import kotlin.math.abs

/**
 * Derivative tracking for VitalityState.
 * Port of core/agent/VitalityDerivatives.java.
 * Tracks velocity (rate of change) and acceleration (rate of change of velocity)
 * using exponential moving average (alpha=0.3) for smoothing.
 */
data class VitalityDerivatives(
    val energyVelocity: Double,
    val confidenceVelocity: Double,
    val focusVelocity: Double,
    val errorPressureVelocity: Double,
    val rapportVelocity: Double,
    val momentumVelocity: Double,
    val energyAcceleration: Double,
    val confidenceAcceleration: Double,
    val focusAcceleration: Double,
    val errorPressureAcceleration: Double,
) {
    companion object {
        private const val ALPHA = 0.3

        fun zero() = VitalityDerivatives(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        fun compute(
            prev: VitalityState,
            current: VitalityState,
            prevDerivatives: VitalityDerivatives,
        ): VitalityDerivatives {
            val eV = smooth(prevDerivatives.energyVelocity, current.energy - prev.energy)
            val cV = smooth(prevDerivatives.confidenceVelocity, current.confidence - prev.confidence)
            val fV = smooth(prevDerivatives.focusVelocity, current.focus - prev.focus)
            val epV = smooth(prevDerivatives.errorPressureVelocity, current.errorPressure - prev.errorPressure)
            val rV = smooth(prevDerivatives.rapportVelocity, current.rapport - prev.rapport)
            val mV = smooth(prevDerivatives.momentumVelocity, current.momentum - prev.momentum)

            val eA = smooth(prevDerivatives.energyAcceleration, eV - prevDerivatives.energyVelocity)
            val cA = smooth(prevDerivatives.confidenceAcceleration, cV - prevDerivatives.confidenceVelocity)
            val fA = smooth(prevDerivatives.focusAcceleration, fV - prevDerivatives.focusVelocity)
            val epA = smooth(prevDerivatives.errorPressureAcceleration, epV - prevDerivatives.errorPressureVelocity)

            return VitalityDerivatives(eV, cV, fV, epV, rV, mV, eA, cA, fA, epA)
        }

        private fun smooth(prev: Double, current: Double): Double =
            prev * (1 - ALPHA) + current * ALPHA
    }

    /** Describe notable trends for the LLM prompt. */
    fun describeTrends(): String {
        val sb = StringBuilder()
        val threshold = 0.01

        describeTrend(sb, "energy", energyVelocity, energyAcceleration, threshold)
        describeTrend(sb, "confidence", confidenceVelocity, confidenceAcceleration, threshold)
        describeTrend(sb, "focus", focusVelocity, focusAcceleration, threshold)
        describeTrend(sb, "error pressure", errorPressureVelocity, errorPressureAcceleration, threshold)

        return if (sb.isEmpty()) "" else "Trends: ${sb.toString().trimEnd()}"
    }

    private fun describeTrend(
        sb: StringBuilder,
        name: String,
        velocity: Double,
        acceleration: Double,
        threshold: Double,
    ) {
        if (abs(velocity) < threshold) return

        val direction = if (velocity > 0) "rising" else "falling"
        val rate = if (abs(velocity) > 0.03) "rapidly " else ""
        sb.append("$name $rate$direction")

        if (abs(acceleration) > threshold) {
            sb.append(if (acceleration > 0) " (accelerating)" else " (decelerating)")
        }
        sb.append(". ")
    }
}
