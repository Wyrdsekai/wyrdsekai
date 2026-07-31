package org.wyrdsekai.app.engine.agent

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Builds time-awareness context for agent prompts.
 * Kotlin port for KMP — mirrors core/agent/TimeContext.java.
 *
 * Gives the agent a sense of current time, time-of-day,
 * and elapsed time since the human last spoke.
 */
object TimeContext {

    fun build(lastHumanSaid: Instant? = null): String {
        val now = Clock.System.now()
        val sb = StringBuilder()

        // Current local time via kotlinx-datetime (all targets)
        val local = now.toLocalDateTime(TimeZone.currentSystemDefault())
        val hour = local.hour
        val minute = local.minute
        val dayOfWeek = local.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val months = arrayOf("January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val month = months[local.month.ordinal]
        val day = local.day
        val year = local.year

        sb.append("Current time: ")
        sb.append("${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}")
        sb.append(", $dayOfWeek $month $day, $year")
        sb.append(" (${timeOfDay(hour)}).")

        // Time since human last spoke
        if (lastHumanSaid != null) {
            val elapsed = now - lastHumanSaid
            if (elapsed.inWholeMinutes >= 1) {
                sb.append(" Last heard from you: ${formatDuration(elapsed)} ago.")
            }
        }

        return sb.toString()
    }

    private fun timeOfDay(hour: Int): String = when {
        hour in 5..11 -> "morning"
        hour in 12..16 -> "afternoon"
        hour in 17..20 -> "evening"
        hour >= 21 || hour < 2 -> "night"
        else -> "late night"
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
