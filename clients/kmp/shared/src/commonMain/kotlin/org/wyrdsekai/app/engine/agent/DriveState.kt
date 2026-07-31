package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * Five motivational drives that create internal impulse for proactive behavior.
 * Each drive accumulates pressure (0.0-1.0) over time and spikes on relevant events.
 * When pressure crosses a threshold, the agent considers acting via ProactivityJudgment.
 *
 * Drives are separate from VitalityState to avoid breaking existing call sites.
 * VitalityState = how the agent FEELS. DriveState = what the agent WANTS TO DO.
 *
 * Port of core/agent/DriveState.java for the KMP phone client.
 */
@Serializable
data class DriveState(
    val curiosity: Double,
    val care: Double,
    val social: Double,
    val achievement: Double,
    val alertness: Double,
) {
    companion object {
        /** All drives at zero — freshly relieved or newly created agent. */
        fun initial() = DriveState(0.0, 0.0, 0.0, 0.0, 0.0)

        // Passive accumulation rates per second
        private const val CURIOSITY_RATE   = 0.0003  // ~18 min to 0.3 threshold
        private const val CARE_RATE        = 0.0002  // ~25 min to 0.3
        private const val SOCIAL_RATE      = 0.0004  // ~12 min to 0.3
        private const val ACHIEVEMENT_RATE = 0.0001  // ~50 min to 0.3 (slow burn)
        private const val ALERTNESS_RATE   = 0.0001  // mostly event-driven
    }

    // ── Passive accumulation (called every vitality tick) ─────────────────

    /**
     * Passive tick — drives accumulate slowly over time.
     * Called every vitality tick (1 second).
     */
    fun tick(): DriveState = copy(
        curiosity = clamp(curiosity + CURIOSITY_RATE),
        care = clamp(care + CARE_RATE),
        social = clamp(social + SOCIAL_RATE),
        achievement = clamp(achievement + ACHIEVEMENT_RATE),
        alertness = clamp(alertness + ALERTNESS_RATE),
    )

    // ── Event spikes ─────────────────────────────────────────────────────

    fun spikeCuriosity(amount: Double) = copy(curiosity = clamp(curiosity + amount))
    fun spikeCare(amount: Double) = copy(care = clamp(care + amount))
    fun spikeSocial(amount: Double) = copy(social = clamp(social + amount))
    fun spikeAchievement(amount: Double) = copy(achievement = clamp(achievement + amount))
    fun spikeAlertness(amount: Double) = copy(alertness = clamp(alertness + amount))

    // ── Relief (after acting on a drive) ─────────────────────────────────

    fun relieveCuriosity() = copy(curiosity = 0.0)
    fun relieveCare() = copy(care = 0.0)
    fun relieveSocial() = copy(social = 0.0)
    fun relieveAchievement() = copy(achievement = 0.0)
    fun relieveAlertness() = copy(alertness = 0.0)

    // ── Queries ──────────────────────────────────────────────────────────

    /** Returns the name and pressure of the highest drive. */
    fun peak(): DrivePeak {
        var name = "curiosity"
        var maxVal = curiosity
        if (care > maxVal) { name = "care"; maxVal = care }
        if (social > maxVal) { name = "social"; maxVal = social }
        if (achievement > maxVal) { name = "achievement"; maxVal = achievement }
        if (alertness > maxVal) { name = "alertness"; maxVal = alertness }
        return DrivePeak(name, maxVal)
    }

    /** Whether any drive exceeds the given threshold. */
    fun anyAbove(threshold: Double): Boolean =
        curiosity > threshold || care > threshold || social > threshold ||
            achievement > threshold || alertness > threshold

    @Serializable
    data class DrivePeak(val name: String, val pressure: Double)
}

private fun clamp(v: Double): Double = max(0.0, min(1.0, v))
