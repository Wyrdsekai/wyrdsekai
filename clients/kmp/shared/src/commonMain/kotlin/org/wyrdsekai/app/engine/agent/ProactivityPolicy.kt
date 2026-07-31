package org.wyrdsekai.app.engine.agent

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Controls when and how a companion may proactively use skills
 * without explicit human request.
 *
 * Port of core/agent/ProactivityPolicy.java for the KMP phone client.
 *
 * The policy defines which skill patterns are proactive-eligible,
 * vitality thresholds, and a windowed rate limit. The companion's
 * LLM decides whether to act; this policy gates whether the
 * proactivity context is injected into Layer 2.7.
 */
data class ProactivityPolicy(
    val proactivePatterns: List<String> = emptyList(),
    val minEnergy: Double = 0.6,
    val minConfidence: Double = 0.5,
    val maxPerWindow: Int = 2,
    val windowSize: Duration = 10.minutes,
) {
    // Window tracking (mutable, single-threaded on phone)
    private var actionsInWindow: Int = 0
    private var windowStart: Instant = Clock.System.now()

    companion object {
        /** Phone default: higher thresholds, less autonomy. */
        fun phoneDefault(patterns: List<String> = emptyList()) = ProactivityPolicy(
            proactivePatterns = patterns,
            minEnergy = 0.6,
            minConfidence = 0.5,
            maxPerWindow = 2,
            windowSize = 10.minutes,
        )

        /** Disabled policy — no proactive skills. */
        fun disabled() = ProactivityPolicy(
            proactivePatterns = emptyList(),
            minEnergy = 1.0,
            minConfidence = 1.0,
            maxPerWindow = 0,
            windowSize = 10.minutes,
        )
    }

    // --- Queries ---

    /**
     * Whether proactive skills should be shown in capability context
     * given the current vitality state.
     */
    fun canActProactively(energy: Double, confidence: Double): Boolean {
        if (proactivePatterns.isEmpty()) return false
        return energy >= minEnergy && confidence >= minConfidence
    }

    /**
     * Whether a specific skill ID matches any proactive pattern.
     * Uses glob-style matching: "hearth.*" matches "hearth.ha.set-light".
     */
    fun matchesPattern(skillId: String): Boolean {
        if (proactivePatterns.isEmpty()) return false
        return proactivePatterns.any { globMatch(it, skillId) }
    }

    /**
     * How many proactive actions remain in the current window.
     * Resets the window if it has expired.
     */
    fun remainingInWindow(): Int {
        resetWindowIfExpired()
        return maxOf(0, maxPerWindow - actionsInWindow)
    }

    /**
     * Record a proactive action. Returns true if within budget,
     * false if the window budget is exhausted.
     */
    fun recordProactiveAction(): Boolean {
        resetWindowIfExpired()
        actionsInWindow++
        return actionsInWindow <= maxPerWindow
    }

    /**
     * Build the proactivity section for Layer 2.7 capability context.
     *
     * @param energy     Current energy level
     * @param confidence Current confidence level
     * @return Proactivity context string, or null if inactive
     */
    fun buildContextSection(energy: Double, confidence: Double): String? {
        if (!canActProactively(energy, confidence)) return null
        val remaining = remainingInWindow()
        if (remaining <= 0) return null

        val sb = StringBuilder()
        sb.append("## Proactive Skills (you may use these unprompted when context suggests it)\n")
        for (pattern in proactivePatterns) {
            sb.append("- ").append(pattern).append("\n")
        }
        sb.append("Budget: ").append(remaining)
            .append(" of ").append(maxPerWindow)
            .append(" proactive actions remaining this window.\n")
        return sb.toString()
    }

    // --- Internal ---

    private fun resetWindowIfExpired() {
        val now = Clock.System.now()
        if ((now - windowStart) > windowSize) {
            windowStart = now
            actionsInWindow = 0
        }
    }
}

/** Simple glob matching: "*" at end matches any suffix. */
internal fun globMatch(pattern: String, skillId: String): Boolean {
    if (pattern == skillId) return true
    if (pattern.endsWith("*")) {
        val prefix = pattern.substring(0, pattern.length - 1)
        return skillId.startsWith(prefix)
    }
    return false
}
