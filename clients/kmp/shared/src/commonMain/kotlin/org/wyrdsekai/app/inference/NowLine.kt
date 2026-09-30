package org.wyrdsekai.app.inference

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What a model request knows about today: every request declares one.
 * Kotlin port of core/inference/NowLine.java.
 *
 * A companion always knows the date and the time (decided 2026-03-28). On the
 * phone that was true in one place, FullPromptAssembler's Layer 3, which was
 * trimmable and sat in the middle of the prompt; the quick, acknowledgement,
 * study and replay paths never carried it. With no date the model believes it
 * is 2024.
 *
 * So every request says which of three it is ([CompletionOptions.now]) and the
 * send point ([InferenceClient.complete], [InferenceRouter.complete]) stamps the
 * outgoing copy:
 *  - [Mode.DATE_TIME] — she speaks, thinks or acts: the line
 *    `[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]` opens the LAST
 *    user message, close to generation and after everything the model server
 *    can reuse from its prompt cache. The stored history never holds it.
 *  - [Mode.DATE] — a single-shot request made for her:
 *    `Today is Wednesday, 23 September 2026.` opens the leading system message.
 *  - [Mode.NONE] — a rewrite, a classifier, identity authoring, a pass-through:
 *    nothing is added.
 *
 * A request that declares nothing gets DATE_TIME as of the moment it is sent.
 *
 * The zone is the device's own. kotlinx-datetime has no short zone names, so
 * the phone prints the offset alone (`UTC-4` where the server prints
 * `EDT (UTC-4)`); the offset is the part the model needs.
 *
 * @param asOf the moment the line states; null = when sent.
 * @param asked when a replayed request was first asked (DATE_TIME only). When it
 *   waited a minute or more, `[Asked: …]` follows the Now line so she knows.
 */
data class NowLine(
    val mode: Mode,
    val asOf: Instant? = null,
    val asked: Instant? = null,
) {
    enum class Mode { DATE_TIME, DATE, NONE }

    /**
     * The outgoing copy of [messages] with this line in place. The input list is
     * never changed. A list that already carries the line (a retry, a request
     * stamped upstream) is returned as it is.
     */
    fun stamp(messages: List<ChatMessage>, zone: TimeZone): List<ChatMessage> {
        if (mode == Mode.NONE) return messages
        val at = asOf ?: Clock.System.now()
        if (mode == Mode.DATE) {
            val line = dateText(at, zone)
            val first = messages.firstOrNull()
            if (first == null || first.role != "system") return listOf(ChatMessage("system", line)) + messages
            if (first.content.startsWith(TODAY)) return messages
            val body = if (first.content.isEmpty()) line else line + "\n" + first.content
            return listOf(first.copy(content = body)) + messages.drop(1)
        }
        var line = dateTimeText(at, zone)
        if (asked != null && at - asked >= 1.minutes) line += "\n" + askedText(asked, zone)
        val out = messages.toMutableList()
        val last = out.indexOfLast { it.role == "user" }
        if (last >= 0) {
            if (out[last].content.startsWith(OPEN)) return messages
            out[last] = out[last].copy(content = line + "\n" + out[last].content)
            return out
        }
        // No user turn at all: the line is one, after a leading system message.
        out.add(if (out.firstOrNull()?.role == "system") 1 else 0, ChatMessage("user", line))
        return out
    }

    companion object {
        val NONE = NowLine(Mode.NONE)

        internal const val OPEN = "[Now: "
        internal const val TODAY = "Today is "

        private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December")

        /** She speaks, thinks or acts: date and time as of [asOf] (null = when sent). */
        fun dateTime(asOf: Instant? = null, asked: Instant? = null) =
            NowLine(Mode.DATE_TIME, asOf, asked)

        /** A single-shot request made for her: the date as of [asOf] (null = when sent). */
        fun date(asOf: Instant? = null) = NowLine(Mode.DATE, asOf)

        /** The zone every stamp is read in: the device's own. */
        fun zone(): TimeZone = TimeZone.currentSystemDefault()

        /** The outgoing messages, saying what the request knows about today (null = date and time as sent). */
        fun stampToday(now: NowLine?, messages: List<ChatMessage>): List<ChatMessage> =
            (now ?: dateTime()).stamp(messages, zone())

        /** `[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]` */
        fun dateTimeText(at: Instant, zone: TimeZone): String {
            val t = at.toLocalDateTime(zone)
            return OPEN + weekday(t) + " " + dayMonthYear(t) + ", " + hourMinute(t) + " " +
                zoneText(at, zone) + ", " + partOfDay(t.hour) + "]"
        }

        /** `[Asked: Wednesday 23 September 2026, 09:40 UTC-4]` */
        fun askedText(at: Instant, zone: TimeZone): String {
            val t = at.toLocalDateTime(zone)
            return "[Asked: " + weekday(t) + " " + dayMonthYear(t) + ", " + hourMinute(t) + " " +
                zoneText(at, zone) + "]"
        }

        /** `Today is Wednesday, 23 September 2026.` */
        fun dateText(at: Instant, zone: TimeZone): String {
            val t = at.toLocalDateTime(zone)
            return TODAY + weekday(t) + ", " + dayMonthYear(t) + "."
        }

        fun partOfDay(hour: Int): String = when {
            hour in 5..11 -> "morning"
            hour in 12..16 -> "afternoon"
            hour in 17..20 -> "evening"
            hour >= 21 || hour < 2 -> "night"
            else -> "late night"
        }

        /** `UTC-4`, `UTC+5:30`, `UTC` — the offset, which moves with daylight saving. */
        internal fun zoneText(at: Instant, zone: TimeZone): String {
            val total = zone.offsetAt(at).totalSeconds
            if (total == 0) return "UTC"
            val hours = abs(total) / 3600
            val minutes = abs(total) % 3600 / 60
            return "UTC" + (if (total < 0) "-" else "+") + hours +
                (if (minutes == 0) "" else ":" + minutes.toString().padStart(2, '0'))
        }

        private fun weekday(t: LocalDateTime) =
            t.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }

        private fun dayMonthYear(t: LocalDateTime) = "${t.day} ${MONTHS[t.month.ordinal]} ${t.year}"

        private fun hourMinute(t: LocalDateTime) =
            t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
    }
}
