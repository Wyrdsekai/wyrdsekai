package org.wyrdsekai.app.engine.agent

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Builds the elapsed-time context for agent prompts: how long since the human
 * last spoke. Kotlin port for KMP — mirrors core/agent/TimeContext.java.
 *
 * The date and the time are not here any more. Layer 3 is trimmable and sits in
 * the middle of the prompt, and every path built outside FullPromptAssembler
 * never had them. Every request now says what it knows about today and the send
 * point stamps it ([org.wyrdsekai.app.inference.NowLine]).
 */
object TimeContext {

    /** A compact context string, or empty when there is nothing to say. */
    fun build(lastHumanSaid: Instant? = null): String {
        if (lastHumanSaid == null) return ""
        val elapsed = Clock.System.now() - lastHumanSaid
        if (elapsed.inWholeMinutes < 1) return ""
        return "Last heard from you: ${formatDuration(elapsed)} ago."
    }

    private fun formatDuration(d: Duration): String {
        val totalMinutes = d.inWholeMinutes
        if (totalMinutes < 2) return "a moment"
        if (totalMinutes < 60) return "$totalMinutes minutes"
        val hours = d.inWholeHours
        val remainingMinutes = totalMinutes - (hours * 60)
        if (hours < 24) {
            if (remainingMinutes == 0L) return "$hours ${if (hours == 1L) "hour" else "hours"}"
            return "${hours}h ${remainingMinutes}m"
        }
        val days = d.inWholeDays
        val remainingHours = hours - (days * 24)
        if (remainingHours == 0L) return "$days ${if (days == 1L) "day" else "days"}"
        return "$days ${if (days == 1L) "day" else "days"} ${remainingHours}h"
    }
}
