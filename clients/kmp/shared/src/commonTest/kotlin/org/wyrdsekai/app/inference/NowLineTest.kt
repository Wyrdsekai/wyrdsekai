package org.wyrdsekai.app.inference

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A companion always knows what day it is, on every path a model speaks, thinks
 * or acts as her (the steward's rule, decided 2026-03-28, said again 2026-09-23).
 * The phone's port of core's NowLine: the line itself, where it goes, and what
 * actually leaves the send points.
 */
class NowLineTest {

    private val at = Instant.parse("2026-09-23T14:05:00Z")
    private val newYork = TimeZone.of("America/New_York")

    private fun msg(role: String, content: String) = ChatMessage(role, content)

    // ── The line ───────────────────────────────────────────────────────────

    @Test
    fun the_line_says_the_day_the_date_the_time_the_zone_and_the_part_of_the_day() {
        // kotlinx-datetime has no short zone names: the phone prints the offset
        // alone where the server prints "EDT (UTC-4)".
        assertEquals("[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]",
            NowLine.dateTimeText(at, newYork))
        assertEquals("[Now: Wednesday 23 September 2026, 23:05 UTC+9, night]",
            NowLine.dateTimeText(at, TimeZone.of("Asia/Tokyo")))
        assertEquals("[Now: Wednesday 23 September 2026, 19:35 UTC+5:30, evening]",
            NowLine.dateTimeText(at, TimeZone.of("Asia/Kolkata")))
        assertEquals("[Now: Wednesday 23 December 2026, 09:05 UTC-5, morning]",
            NowLine.dateTimeText(Instant.parse("2026-12-23T14:05:00Z"), newYork),
            "the offset moves with daylight saving")

        // Where the server has no short name either, the lines are the same bytes.
        assertEquals("[Now: Wednesday 23 September 2026, 14:05 UTC, afternoon]",
            NowLine.dateTimeText(at, TimeZone.UTC))
        assertEquals("[Now: Wednesday 23 September 2026, 14:05 UTC, afternoon]",
            NowLine.dateTimeText(at, TimeZone.of("UTC")))
        assertEquals("[Now: Wednesday 23 September 2026, 11:05 UTC-3, morning]",
            NowLine.dateTimeText(at, TimeZone.of("America/Sao_Paulo")))
        assertEquals("[Now: Wednesday 23 September 2026, 19:50 UTC+5:45, evening]",
            NowLine.dateTimeText(at, TimeZone.of("Asia/Kathmandu")))

        assertEquals("[Now: Sunday 4 October 2026, 08:05 UTC-4, morning]",
            NowLine.dateTimeText(Instant.parse("2026-10-04T12:05:00Z"), newYork),
            "the day of the month is not padded; the hour is")
        assertEquals("Today is Wednesday, 23 September 2026.", NowLine.dateText(at, newYork))
        assertEquals("[Asked: Wednesday 23 September 2026, 09:40 UTC-4]",
            NowLine.askedText(Instant.parse("2026-09-23T13:40:00Z"), newYork))
    }

    @Test
    fun parts_of_the_day() {
        val expected = mapOf(
            0 to "night", 1 to "night", 2 to "late night", 4 to "late night",
            5 to "morning", 11 to "morning", 12 to "afternoon", 16 to "afternoon",
            17 to "evening", 20 to "evening", 21 to "night", 23 to "night",
        )
        for ((hour, part) in expected) assertEquals(part, NowLine.partOfDay(hour), "hour $hour")
    }

    // ── Where it goes ──────────────────────────────────────────────────────

    @Test
    fun her_turn_the_line_opens_the_last_user_message_and_nothing_before_it_changes() {
        val input = listOf(
            msg("system", "You are Mia."), msg("user", "operator says: hi"),
            msg("assistant", "hello"), msg("user", "operator says: what day is it?"),
        )
        val out = NowLine.dateTime(at).stamp(input, newYork)

        assertEquals(input.subList(0, 3), out.subList(0, 3))
        assertEquals(
            "[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]\nmasumi says: what day is it?",
            out[3].content,
        )
        assertEquals("operator says: what day is it?", input[3].content,
            "the caller's list is never changed")
        assertEquals(out, NowLine.dateTime(at).stamp(input, newYork),
            "same asOf, same bytes: the model server's prompt cache holds")
    }

    @Test
    fun work_for_her_the_date_opens_the_system_message_and_the_rest_is_untouched() {
        val input = listOf(msg("system", "Summarize: X"), msg("user", "text"))
        val out = NowLine.date(at).stamp(input, newYork)
        assertEquals("Today is Wednesday, 23 September 2026.\nSummarize: X", out[0].content)
        assertEquals(input[1], out[1])
        assertEquals("Summarize: X", input[0].content)

        val noSystem = NowLine.date(at).stamp(listOf(msg("user", "think about Y")), newYork)
        assertEquals(msg("system", "Today is Wednesday, 23 September 2026."), noSystem[0])
        assertEquals(msg("user", "think about Y"), noSystem[1])
    }

    @Test
    fun a_classifier_carries_nothing_and_nothing_is_stamped_twice() {
        val input = listOf(msg("system", "Classify."), msg("user", "Message: \"hi\""))
        assertSame(input, NowLine.NONE.stamp(input, newYork))

        val once = NowLine.dateTime(at).stamp(input, newYork)
        assertEquals(once, NowLine.dateTime(at + 10.minutes).stamp(once, newYork))
        val dated = NowLine.date(at).stamp(input, newYork)
        assertEquals(dated, NowLine.date(at).stamp(dated, newYork))
    }

    @Test
    fun no_user_turn_the_line_becomes_one_after_the_system_message() {
        val out = NowLine.dateTime(at).stamp(listOf(msg("system", "s"), msg("assistant", "a")), newYork)
        assertEquals(3, out.size)
        assertEquals(msg("user", "[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]"), out[1])

        val bare = NowLine.dateTime(at).stamp(listOf(msg("assistant", "a")), newYork)
        assertEquals("user", bare[0].role)
    }

    @Test
    fun a_replayed_request_says_when_it_was_asked_under_the_now_line() {
        val input = listOf(msg("system", "You are Mia."), msg("user", "operator says: the tide?"))

        val waited = NowLine.dateTime(at, asked = at - 25.minutes).stamp(input, newYork)
        assertEquals(
            "[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]\n" +
                "[Asked: Wednesday 23 September 2026, 09:40 UTC-4]\n" +
                "operator says: the tide?",
            waited[1].content,
        )

        val barely = NowLine.dateTime(at, asked = at - 30.seconds).stamp(input, newYork)
        assertEquals(
            "[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]\nmasumi says: the tide?",
            barely[1].content,
            "under a minute it did not wait",
        )
    }

    // ── What actually leaves ──────────────────────────────────────────────

    /** Sees what [InferenceClient.complete] hands to the wire. */
    private class WireClient : InferenceClient() {
        val sent = mutableListOf<List<ChatMessage>>()
        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            sent += messages
            return ChatResponse("ok", 1, 1)
        }
    }

    private class WireLocal : LocalInferenceProvider {
        override val state: StateFlow<String> = MutableStateFlow("running")
        val sent = mutableListOf<List<ChatMessage>>()
        override suspend fun completeLocal(
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            sent += messages
            return ChatResponse("ok", 1, 1)
        }
    }

    private val turn = listOf(
        ChatMessage("system", "You are Mia."), ChatMessage("user", "operator says: a"),
        ChatMessage("assistant", "b"), ChatMessage("user", "operator says: c"),
    )

    @Test
    fun the_client_stamps_what_it_sends_per_declaration() = runTest {
        val client = WireClient()
        val zone = NowLine.zone()

        client.complete("http://test", turn, CompletionOptions(now = NowLine.dateTime(at)))
        assertEquals(NowLine.dateTimeText(at, zone) + "\nmasumi says: c", client.sent[0].last().content)
        assertEquals(turn.subList(0, 3), client.sent[0].subList(0, 3))

        client.complete("http://test", turn, CompletionOptions(now = NowLine.NONE))
        assertEquals(turn, client.sent[1])

        client.complete("http://test", turn, CompletionOptions())
        assertTrue(client.sent[2].last().content.startsWith("[Now: "),
            "a forgotten path still knows the day")

        client.complete("http://test", turn, CompletionOptions(now = NowLine.date(at)))
        assertEquals(NowLine.dateText(at, zone) + "\nYou are Mia.", client.sent[3][0].content)
        assertEquals(turn.subList(1, 4), client.sent[3].subList(1, 4))

        assertEquals("operator says: c", turn.last().content, "the caller's list is never changed")
    }

    @Test
    fun the_router_stamps_the_local_path_too() = runTest {
        val local = WireLocal()
        val router = InferenceRouter(local)

        router.complete(turn, CompletionOptions(now = NowLine.dateTime(at)))
        assertEquals(NowLine.dateTimeText(at, NowLine.zone()) + "\nmasumi says: c",
            local.sent[0].last().content)

        router.complete(turn, CompletionOptions(now = NowLine.NONE))
        assertEquals(turn, local.sent[1])

        router.complete(turn)
        assertTrue(local.sent[2].last().content.startsWith("[Now: "),
            "a forgotten path still knows the day")
    }

    @Test
    fun the_router_and_the_client_do_not_stamp_twice() = runTest {
        val remote = WireClient()
        val router = InferenceRouter(WireLocal(), remoteClient = remote, remoteBaseUrl = "http://remote")

        router.complete(turn, CompletionOptions(now = NowLine.dateTime(at)), role = ModelRole.DRIVE)
        val last = remote.sent.single().last().content
        assertEquals(NowLine.dateTimeText(at, NowLine.zone()) + "\nmasumi says: c", last)
    }
}
