package org.wyrdsekai.app.engine.agent

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Evaluates whether an agent should act proactively based on drive pressure.
 *
 * Filters: timing, salience, confidence, personality, cost, calibration.
 * Returns Act / Hold / Discard.
 *
 * Port of core/agent/ProactivityJudgment.java for the KMP phone client.
 * Simplified: no AgentCapabilityRegistry, no ProactivityCoordinator (phone is single-agent).
 *
 * Called from CompanionEngine's vitality tick when any drive exceeds threshold.
 */
object ProactivityJudgment {

    /** Default drive threshold — drives below this don't trigger evaluation. */
    const val DEFAULT_THRESHOLD = 0.3

    /** Maximum proactivity budget per hour (replenished linearly). */
    const val MAX_BUDGET_PER_HOUR = 3.0

    /** Minimum seconds between proactive actions. */
    const val MIN_INTERVAL_SECONDS = 30L

    // ── Result types ─────────────────────────────────────────────────────

    sealed class JudgmentResult {
        data class Act(val action: ProactiveAction) : JudgmentResult()
        /**
         * Hold — defer the action for later.
         * @param reason            Why the action was held (cooldown, human recently active, budget exhausted)
         * @param driveName         Which drive was trying to act
         * @param pressure          Drive pressure at time of hold
         * @param secondsSinceHuman Seconds since last human speech (timing context for later reasoning)
         * @param secondsSinceLast  Seconds since last proactive action (cooldown context)
         */
        data class Hold(
            val reason: String,
            val driveName: String,
            val pressure: Double,
            val secondsSinceHuman: Long = -1,
            val secondsSinceLast: Long = -1,
        ) : JudgmentResult()
        data class Discard(val reason: String) : JudgmentResult()
    }

    // ── Evaluation context ───────────────────────────────────────────────

    data class Context(
        val drives: DriveState,
        val vitality: VitalityState,
        val remainingBudget: Double,          // proactivity budget remaining this hour
        val lastProactiveAction: Instant?,    // nullable — never acted proactively
        val lastHumanSpeech: Instant?,        // nullable — no human has spoken yet
        val agentEntityId: String,
        val tier: Int,                        // computed agent tier (0-3)
        val calibration: CalibrationLedger? = null,
    )

    // ── Main evaluation ──────────────────────────────────────────────────

    /**
     * Evaluate whether the agent should act on its current drive state.
     *
     * @param ctx evaluation context
     * @return Act, Hold, or Discard
     */
    fun evaluate(ctx: Context): JudgmentResult {
        val peak = ctx.drives.peak()
        if (peak.pressure < thresholdForTier(ctx.tier)) {
            return JudgmentResult.Discard("drive pressure below threshold")
        }

        // Compute timing context once for Hold results
        val sinceHuman = ctx.lastHumanSpeech?.let { (Clock.System.now() - it).inWholeSeconds } ?: -1L
        val sinceLast = ctx.lastProactiveAction?.let { (Clock.System.now() - it).inWholeSeconds } ?: -1L

        // 1. Timing filter — don't act too soon after last proactive action
        if (ctx.lastProactiveAction != null) {
            if (sinceLast < MIN_INTERVAL_SECONDS) {
                return JudgmentResult.Hold("cooldown", peak.name, peak.pressure, sinceHuman, sinceLast)
            }
        }

        // 2. Human activity filter — if human spoke recently, prefer reactive over proactive
        if (ctx.lastHumanSpeech != null) {
            if (sinceHuman < 10) {
                // Human is active — only alertness (urgent) can interrupt
                if (peak.name != "alertness" || peak.pressure < 0.7) {
                    return JudgmentResult.Hold("human recently active", peak.name, peak.pressure, sinceHuman, sinceLast)
                }
            }
        }

        // 3. Energy filter — don't be proactive when exhausted
        if (ctx.vitality.energy < 0.2) {
            return JudgmentResult.Discard("energy too low for proactive behavior")
        }

        // 4. Budget filter — don't exceed proactivity budget
        var action = selectAction(peak, ctx)
        if (action.budgetCost > ctx.remainingBudget) {
            return JudgmentResult.Hold("budget exhausted", peak.name, peak.pressure, sinceHuman, sinceLast)
        }

        // 5. Confidence filter — need sufficient confidence for initiative actions
        if (action is ProactiveAction.Initiative) {
            if (ctx.vitality.confidence < 0.3) {
                // Downgrade to observation instead
                action = ProactiveAction.Observation(
                    speechText = buildObservationText(peak),
                    driveName = peak.name,
                    category = peak.name,
                )
            }
        }

        return JudgmentResult.Act(action)
    }

    // ── Action selection ─────────────────────────────────────────────────

    private fun selectAction(peak: DriveState.DrivePeak, ctx: Context): ProactiveAction =
        when (peak.name) {
            "curiosity" -> {
                if (peak.pressure > 0.7 && ctx.tier >= 2) {
                    ProactiveAction.Initiative(
                        actionJson = """{"action": "library_search", "query": "recent interests"}""",
                        driveName = "curiosity",
                        description = "Exploring something that caught attention",
                    )
                } else {
                    ProactiveAction.Observation(
                        speechText = buildObservationText(peak),
                        driveName = "curiosity",
                        category = "curiosity",
                    )
                }
            }
            "care" -> {
                if (peak.pressure > 0.8) {
                    ProactiveAction.Observation(
                        speechText = "Is everything alright? It's been quiet.",
                        driveName = "care",
                        category = "care",
                    )
                } else {
                    ProactiveAction.Ambient(
                        emoteText = "*glances up with a concerned expression*",
                        driveName = "care",
                    )
                }
            }
            "social" -> {
                if (peak.pressure > 0.6) {
                    ProactiveAction.Observation(
                        speechText = buildSocialText(ctx),
                        driveName = "social",
                        category = "social",
                    )
                } else {
                    ProactiveAction.Ambient(
                        emoteText = "*shifts thoughtfully*",
                        driveName = "social",
                    )
                }
            }
            "achievement" -> {
                if (peak.pressure > 0.7 && ctx.tier >= 1) {
                    ProactiveAction.Initiative(
                        actionJson = """{"action": "make_commitment", "description": "follow up on pending task"}""",
                        driveName = "achievement",
                        description = "Acting on pending commitment",
                    )
                } else {
                    ProactiveAction.Observation(
                        speechText = "I've been meaning to follow up on something...",
                        driveName = "achievement",
                        category = "achievement",
                    )
                }
            }
            "alertness" -> {
                // Oracle predictions — always at least observation
                ProactiveAction.Observation(
                    speechText = buildAlertnessText(ctx),
                    driveName = "alertness",
                    category = "oracle",
                )
            }
            else -> ProactiveAction.Ambient(
                emoteText = "*pauses thoughtfully*",
                driveName = peak.name,
            )
        }

    // ── Text builders ────────────────────────────────────────────────────

    private fun buildObservationText(peak: DriveState.DrivePeak): String =
        "I noticed something worth mentioning..."

    private fun buildSocialText(ctx: Context): String {
        val lastSpeech = ctx.lastHumanSpeech
        val idleMinutes = if (lastSpeech != null) {
            (Clock.System.now() - lastSpeech).inWholeMinutes
        } else 0L
        return if (idleMinutes > 30) "It's been a while — hope you're doing well."
        else "Anything on your mind?"
    }

    private fun buildAlertnessText(ctx: Context): String =
        "Something shifted in the patterns..."

    // ── Tier-based threshold scaling ─────────────────────────────────────

    /**
     * Drive threshold decreases as agent tier increases (more trust = lower bar to act).
     */
    internal fun thresholdForTier(tier: Int): Double = when (tier) {
        0 -> 0.7   // Nascent: very cautious
        1 -> 0.5   // Observant: moderate
        2 -> 0.35  // Trusted: responsive
        3 -> 0.2   // Senior: proactive
        else -> DEFAULT_THRESHOLD
    }

    // ── Budget management ────────────────────────────────────────────────

    /**
     * Compute remaining budget given time elapsed and actions taken.
     * Budget replenishes linearly over 1 hour.
     */
    fun computeBudget(spent: Double, elapsedMs: Long): Double {
        val replenished = (elapsedMs / 3_600_000.0) * MAX_BUDGET_PER_HOUR
        return min(MAX_BUDGET_PER_HOUR, replenished - spent)
    }

    private fun min(a: Double, b: Double): Double = if (a < b) a else b
}
