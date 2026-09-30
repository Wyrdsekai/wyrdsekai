package org.wyrdsekai.app.engine.agent

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.between.BudDelegation
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.engine.study.InMemoryStudyStore
import org.wyrdsekai.app.engine.study.StudyItem
import org.wyrdsekai.app.i18n.fillTemplate
import org.wyrdsekai.app.i18n.uiStringsFor
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.NowLine

/**
 * Every request of hers opens its last user message with the date line
 * (`[Now: …]`, and on a replay `[Asked: …]` under it). A model that repeats it at
 * the start of a line of its reply has not said it: the server strips it (core
 * ActionParser NOW_LINE_ECHO), and the phone did not, on any path, so it was said
 * aloud, or written into her journal, as it came.
 *
 * Each path is driven end to end: quick, acknowledgement, the household's answer
 * over HTTP (raw remote), bud delegation, the offline replay, and study command
 * mode, where the line also hid the verb and reached her journal, notes and searches.
 *
 * Desktop-only: the household is a local HTTP endpoint and wyrdsekai.inference.url
 * is a JVM system property, set and cleared around each test; the queue persists
 * through java.io.File.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnEchoedDateLineIsNotHerWordsTest {

    private val profile = AgentProfile(
        name = "Wyrd",
        entityId = "companion-wyrd",
        entityType = "agent",
        description = "A test companion",
        systemPrompt = "You are Wyrd, a test companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    private val en = uiStringsFor("en")
    private val newYork = TimeZone.of("America/New_York")
    /** `[Now: Wednesday 23 September 2026, 10:05 UTC-4, morning]`, as the phone writes it. */
    private val nowLine = NowLine.dateTimeText(Instant.parse("2026-09-23T14:05:00Z"), newYork)
    /** `[Asked: Wednesday 23 September 2026, 09:40 UTC-4]` */
    private val askedLine = NowLine.askedText(Instant.parse("2026-09-23T13:40:00Z"), newYork)

    // > 8 words with a question mark: heuristic COMPLEX, no classifier call.
    private val complexQuestion = "Can you help me organize all my photos from the last three years into albums?"

    private lateinit var tmpDir: File
    private var server: HttpServer? = null

    @BeforeTest
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "wyrdsekai-echo-${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("wyrdsekai.inference.url")
        server?.stop(0)
        tmpDir.deleteRecursively()
    }

    /** The configured client: [reply] for every request; failing when there is none. */
    private class CannedClient(private val reply: String?) : InferenceClient() {
        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse = ChatResponse(reply ?: error("the model on the device is not loaded"), 1, 1)
    }

    /** A household that answers every chat completion with [content], and every delegation with [delegated]. */
    private fun household(content: String, delegated: String = content): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}}}],""" +
                """"usage":{"prompt_tokens":1,"completion_tokens":1}}""")
        }
        http.createContext("/api/companion/ask") { exchange ->
            exchange.requestBody.readBytes()
            respond(exchange, """{"requestId":"r1","text":${JsonPrimitive(delegated)},"actions":[]}""")
        }
        http.start()
        server = http
        val url = "http://127.0.0.1:${http.address.port}"
        System.setProperty("wyrdsekai.inference.url", url)
        return url
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private suspend fun room(scope: kotlinx.coroutines.CoroutineScope): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    /** What she said aloud, in order. */
    private fun said(journal: InMemoryEventJournal) =
        journal.allEvents("nexus").filterIsInstance<WorldEvent.Said>()
            .filter { it.entityId == profile.entityId }.map { it.text }

    /** Everything of hers in the room, said or emoted: none of it carries a date line. */
    private fun assertNoDateLine(journal: InMemoryEventJournal) {
        val hers = journal.allEvents("nexus").mapNotNull {
            when {
                it is WorldEvent.Said && it.entityId == profile.entityId -> it.text
                it is WorldEvent.Emoted && it.entityId == profile.entityId -> it.text
                else -> null
            }
        }
        assertTrue(hers.none { "[Now:" in it || "[Asked:" in it }, "a date line reached the room: $hers")
    }

    /** Alice says [text] and her turn runs on [client]: the first line she says, and the room. */
    private suspend fun TestScope.turn(
        client: InferenceClient,
        text: String,
        configure: (CompanionEngine) -> Unit = {},
    ): Pair<String, InMemoryEventJournal> {
        val (roomEngine, journal) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        configure(companion)
        companion.start()
        advanceTimeBy(200)
        // The household is real I/O, which advanceUntilIdle does not wait for.
        val first = backgroundScope.async { companion.companionSpeech.first() }
        runCurrent()
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", text))
        advanceTimeBy(5000)
        val line = first.await()
        advanceUntilIdle()
        companion.shutdown()
        roomEngine.shutdown()
        return line to journal
    }

    // ── her quick turn, her acknowledgement ────────────────────────────────

    @Test
    fun her_quick_answer_is_said_without_the_date_line() = runTest {
        val (line, journal) = turn(CannedClient("$nowLine\nMorning. The tea is on."), "hello")

        assertEquals("Morning. The tea is on.", line)
        assertEquals(listOf("Morning. The tea is on."), said(journal))
        assertNoDateLine(journal)
    }

    @Test
    fun her_own_line_that_starts_with_now_is_said_as_it_is() = runTest {
        val (line, _) = turn(CannedClient("Now: the kettle, then the letters."), "hello")

        assertEquals("Now: the kettle, then the letters.", line, "her own words are not a date line")
    }

    @Test
    fun her_acknowledgement_is_said_without_the_date_line() = runTest {
        // No delegation, no household URL: she acknowledges on the configured client.
        val (line, journal) = turn(CannedClient("${nowLine.dropLast(1)}\nI'll come back to that."), complexQuestion)

        assertEquals("I'll come back to that.", line, "the closing bracket dropped")
        assertNoDateLine(journal)
    }

    // ── the household: raw remote, delegation, replay ─────────────────────

    @Test
    fun the_households_answer_is_said_without_the_date_line() = runTest {
        household("$nowLine\nAt noon.")

        val (line, journal) = turn(CannedClient(null), complexQuestion)

        assertEquals("At noon.", line)
        assertNoDateLine(journal)
    }

    @Test
    fun a_delegated_answer_is_said_without_the_date_line() = runTest {
        val url = household(content = "unused", delegated = "$nowLine\nVamos.")

        val (line, journal) = turn(CannedClient(null), complexQuestion) {
            it.budDelegation = BudDelegation(
                between = null, nodeId = "phone", familyId = "family",
                serverUrl = url, deviceToken = "device-token",
            )
        }

        assertEquals("Vamos.", line)
        assertNoDateLine(journal)
    }

    private suspend fun TestScope.replay(answer: String): InMemoryEventJournal {
        household(answer)
        File(tmpDir, "offline-queue.json").writeText(
            """[{"triggerId":"t1","triggerText":"When does the tide turn?","triggerEntityName":"Alice",""" +
                """"roomId":"nexus","timestamp":${System.currentTimeMillis() - 25 * 60_000}}]""",
        )
        val (roomEngine, journal) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, CannedClient(null), "http://test", null, backgroundScope)
        companion.offlineQueue = OfflineQueue(tmpDir.absolutePath)

        companion.replayOfflineQueue()

        companion.shutdown()
        roomEngine.shutdown()
        return journal
    }

    @Test
    fun a_replayed_answer_is_said_without_its_now_and_asked_lines() = runTest {
        // The replay's answer was spoken as it came, with no parser in between.
        val journal = replay("$nowLine\n$askedLine\nAt noon.")

        assertEquals(listOf("About \"When does the tide turn?\" — At noon."), said(journal))
        assertNoDateLine(journal)
    }

    @Test
    fun a_replayed_answer_is_said_without_an_asked_line_that_lost_its_bracket() = runTest {
        val journal = replay("${askedLine.dropLast(1)}\nAt noon.")

        assertEquals(listOf("About \"When does the tide turn?\" — At noon."), said(journal))
    }

    @Test
    fun a_replayed_answer_that_was_only_the_date_lines_is_no_answer_and_the_request_stays_queued() = runTest {
        // It was said as the intro with nothing after it, and the request left the queue.
        val journal = replay("$nowLine\n$askedLine")

        val hers = journal.allEvents("nexus").filter {
            (it is WorldEvent.Said && it.entityId == profile.entityId) ||
                (it is WorldEvent.Emoted && it.entityId == profile.entityId)
        }
        assertEquals(emptyList(), hers, "no catch-up announced, nothing said")
        assertEquals(1, OfflineQueue(tmpDir.absolutePath).size(), "her question is still waiting")
    }

    // ── study command mode ────────────────────────────────────────────────

    /** One study-mode turn answered with [reply]: what she said, and her Study. */
    private suspend fun TestScope.studyTurn(
        reply: String,
        store: InMemoryStudyStore = InMemoryStudyStore(),
    ): Pair<List<String>, InMemoryStudyStore> {
        val (roomEngine, journal) = room(backgroundScope)
        val companion = CompanionEngine(profile, roomEngine, CannedClient(reply), "http://test", null, backgroundScope)
        companion.studyCommandMode = true
        companion.studyStore = store
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "I walked to the harbour today"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertNoDateLine(journal)
        companion.shutdown()
        roomEngine.shutdown()
        return said(journal) to store
    }

    private suspend fun InMemoryStudyStore.contents() =
        searchAll("local-user", "", limit = 50).sortedBy { it.timestamp }.map { it.itemType to it.content }

    @Test
    fun in_study_mode_a_date_line_before_the_verb_no_longer_hides_it() = runTest {
        // A client that does not honour the grammar can put the line on a line of its
        // own. The reply then matched no verb and was spoken whole, "say:" and all.
        val (said, _) = studyTurn("$nowLine\nsay:Morning.")

        assertEquals(listOf("Morning."), said)
    }

    @Test
    fun in_study_mode_a_date_line_right_after_say_is_not_said() = runTest {
        // Under the grammar her words follow the verb on the same line.
        val (said, _) = studyTurn("say:${nowLine.dropLast(1)}")

        assertEquals(emptyList(), said)
    }

    @Test
    fun her_journal_holds_her_entry_not_the_date_line_before_the_verb() = runTest {
        val (said, store) = studyTurn("$nowLine\njournal_write:Walked to the harbour.")

        assertEquals(listOf(StudyItem.TYPE_JOURNAL to "Walked to the harbour."), store.contents())
        assertEquals(listOf(fillTemplate(en.narrationJournalSaved, "Walked to the harbour.")), said)
    }

    @Test
    fun an_entry_that_was_only_the_date_line_is_not_written() = runTest {
        val (said, store) = studyTurn("journal_write:$nowLine")

        assertEquals(emptyList(), store.contents(), "nothing of hers to write")
        assertEquals(emptyList(), said)
    }

    @Test
    fun a_private_entry_that_was_only_the_date_line_is_not_written() = runTest {
        val (said, store) = studyTurn("journal_private:${nowLine.dropLast(1)}")

        assertEquals(emptyList(), store.contents())
        assertEquals(emptyList(), said)
    }

    @Test
    fun her_note_holds_her_words_not_the_asked_line_after_the_verb() = runTest {
        val (said, store) = studyTurn("note_add:$askedLine\nBuy thread.")

        assertEquals(listOf(StudyItem.TYPE_NOTE to "Buy thread."), store.contents())
        assertEquals(listOf(fillTemplate(en.narrationNoteSaved, "Buy thread.")), said)
    }

    @Test
    fun a_search_for_only_the_date_line_is_not_made() = runTest {
        // The line was the query, and it was narrated back: No journal entries found for "[Now: …]".
        val (said, _) = studyTurn("journal_search:$nowLine")

        assertEquals(emptyList(), said)
    }

    @Test
    fun her_search_is_for_her_words_not_the_date_line_after_the_verb() = runTest {
        val store = InMemoryStudyStore()
        store.writeJournal("local-user", "Walked to the harbour.")

        val (said, _) = studyTurn("journal_search:$nowLine\nharbour", store)

        assertEquals(listOf(fillTemplate(en.narrationJournalFound, 1, "- Walked to the harbour.")), said)
    }

    @Test
    fun a_verb_with_no_branch_and_only_the_date_line_after_it_says_nothing() = runTest {
        // look:, note_search:, pin: and remind: are said as they came, verb and all;
        // the date line after the verb was said with them.
        val (look, _) = studyTurn("look:$nowLine")
        val (remind, _) = studyTurn("remind:${nowLine.dropLast(1)}")

        assertEquals(emptyList(), look)
        assertEquals(emptyList(), remind, "the closing bracket dropped")
    }

    @Test
    fun a_verb_with_no_branch_keeps_her_words_and_loses_the_date_line() = runTest {
        // Still said verb and all, as before; only the date line is gone.
        val (said, _) = studyTurn("note_search:$askedLine\nthread")

        assertEquals(listOf("note_search:thread"), said)
    }

    @Test
    fun in_study_mode_her_own_line_that_starts_with_now_is_written_as_it_is() = runTest {
        val (_, store) = studyTurn("journal_write:Now: the kettle, then the letters.")

        assertEquals(listOf(StudyItem.TYPE_JOURNAL to "Now: the kettle, then the letters."), store.contents())
    }
}
