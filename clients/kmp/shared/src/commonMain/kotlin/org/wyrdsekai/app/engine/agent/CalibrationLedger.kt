package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Fast feedback profile for proactivity calibration.
 * Lives alongside a Bond (keyed by bond ID) — each human-agent relationship
 * has its own calibration.
 *
 * Immediate updates on calibration_feedback action.
 * Forge extraction distills feedback log into soul fragments during sleep.
 *
 * Port of core/agent/CalibrationLedger.java for the KMP phone client.
 * Uses kotlinx.serialization for persistence.
 *
 * @see ProactivityJudgment — reads timing bias and salience weights
 */
@Serializable
class CalibrationLedger(
    private val timingBias: MutableMap<String, Double> = mutableMapOf(),
    private val salienceWeights: MutableMap<String, Double> = mutableMapOf(),
    private var intrusionTolerance: Double = 0.5,
    private var positiveFeedbackCount: Int = 0,
    private val feedbackLog: MutableList<Feedback> = mutableListOf(),
) {
    /** A single calibration feedback event. */
    @Serializable
    data class Feedback(
        val whenMs: Long,         // epoch millis (KMP-safe, no java.time.Instant)
        val type: String,         // timing, salience, intrusion, positive
        val direction: String,    // sooner, later, higher, lower, good
        val category: String?,    // e.g. "anomaly", "pattern"
        val trigger: String,      // human's original words
    )

    companion object {
        private const val MAX_FEEDBACK_LOG = 20
        private val ALL_CATEGORIES = listOf("anomaly", "pattern", "forecast", "topic", "correlation")
    }

    // ── Immediate feedback application ───────────────────────────────────

    /**
     * Apply a calibration feedback immediately.
     *
     * @param type      timing | salience | intrusion | positive
     * @param direction sooner | later | higher | lower | good
     * @param category  prediction category (nullable — applies globally if null)
     * @param trigger   human's original words (for Forge extraction)
     */
    fun applyFeedback(type: String, direction: String, category: String?, trigger: String) {
        val feedback = Feedback(
            whenMs = Clock.System.now().toEpochMilliseconds(),
            type = type,
            direction = direction,
            category = category,
            trigger = trigger,
        )
        feedbackLog.add(feedback)
        while (feedbackLog.size > MAX_FEEDBACK_LOG) {
            feedbackLog.removeAt(0)
        }

        val delta = 0.15 // adjustment per feedback
        when (type) {
            "timing" -> {
                val adjustment = if (direction == "sooner") -delta else delta
                if (category != null) {
                    timingBias[category] = (timingBias[category] ?: 0.0) + adjustment
                } else {
                    for (key in ALL_CATEGORIES) {
                        timingBias[key] = (timingBias[key] ?: 0.0) + adjustment
                    }
                }
                // Clamp all timing biases
                for (key in timingBias.keys.toList()) {
                    timingBias[key] = max(-1.0, min(1.0, timingBias[key]!!))
                }
            }
            "salience" -> {
                val adjustment = if (direction == "higher") 0.2 else -0.2
                if (category != null) {
                    salienceWeights[category] = (salienceWeights[category] ?: 1.0) + adjustment
                }
                for (key in salienceWeights.keys.toList()) {
                    salienceWeights[key] = max(0.0, min(2.0, salienceWeights[key]!!))
                }
            }
            "intrusion" -> {
                if (direction == "higher" || direction == "good") {
                    intrusionTolerance = min(1.0, intrusionTolerance + 0.1)
                } else {
                    intrusionTolerance = max(0.0, intrusionTolerance - 0.1)
                }
            }
            "positive" -> {
                positiveFeedbackCount++
                // Positive feedback slightly increases intrusion tolerance
                intrusionTolerance = min(1.0, intrusionTolerance + 0.02)
            }
        }
    }

    // ── Queries (used by ProactivityJudgment) ────────────────────────────

    /** Get timing bias for a category (-1 = tell sooner, +1 = wait). Default: 0. */
    fun getTimingBias(category: String): Double = timingBias[category] ?: 0.0

    /** Get salience weight for a category (0 = ignore, 2 = amplify). Default: 1. */
    fun getSalienceWeight(category: String): Double = salienceWeights[category] ?: 1.0

    fun getIntrusionTolerance(): Double = intrusionTolerance

    fun getPositiveFeedbackCount(): Int = positiveFeedbackCount

    /** Get recent feedback events for Forge extraction. */
    fun getRecentFeedback(): List<Feedback> = feedbackLog.toList()

    /** Clear the feedback log after Forge extraction. */
    fun clearFeedbackLog() { feedbackLog.clear() }

    /** Human-readable summary for bond context injection. */
    fun describe(): String {
        val sb = StringBuilder()
        if (intrusionTolerance > 0.7) sb.append("Prefers proactive communication. ")
        else if (intrusionTolerance < 0.3) sb.append("Prefers minimal unsolicited input. ")

        val biased = timingBias.filter { kotlin.math.abs(it.value) > 0.3 }
        for ((cat, bias) in biased) {
            if (bias < -0.3) sb.append("Wants earlier alerts about $cat. ")
            else if (bias > 0.3) sb.append("Prefers delayed $cat notifications. ")
        }

        val weighted = salienceWeights.filter { kotlin.math.abs(it.value - 1.0) > 0.3 }
        for ((cat, weight) in weighted) {
            if (weight > 1.3) sb.append("High interest in $cat. ")
            else if (weight < 0.7) sb.append("Low interest in $cat. ")
        }

        if (positiveFeedbackCount > 5) sb.append("Strong positive calibration history. ")
        return sb.toString().trim()
    }
}
