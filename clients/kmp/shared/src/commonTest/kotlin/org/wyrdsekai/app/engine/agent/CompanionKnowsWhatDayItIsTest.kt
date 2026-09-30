package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.engine.soul.IdentityEvolver
import org.wyrdsekai.app.engine.soul.SoulAuthoring
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * What the phone's companion paths actually send: her turns carry the date and
 * the time on the last user message; a classifier and her identity authoring
 * carry nothing. The captures are taken at [InferenceClient.send], after the
 * send point has stamped the outgoing copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionKnowsWhatDayItIsTest {

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

    private class WireClient(private val responses: List<String>) : InferenceClient() {
        val sent = mutableListOf<Pair<List<ChatMessage>, CompletionOptions>>()
        override suspend fun send(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            sent += messages to options
            return ChatResponse(responses.getOrElse(sent.size - 1) { responses.last() }, 1, 1)
        }
    }

    private val nowLine = Regex(
        """^\[Now: [A-Z][a-z]+day \d{1,2} [A-Z][a-z]+ \d{4}, \d{2}:\d{2} UTC([+-]\d{1,2}(:\d{2})?)?, (morning|afternoon|evening|night|late night)]$""",
    )

    private fun carriesNowLine(content: String, rest: String) {
        val head = content.substringBefore("\n")
        assertTrue(nowLine.matches(head), "expected the Now line, got: $content")
        assertEquals(rest, content.substringAfter("\n"))
    }

    private fun carriesNoDate(messages: List<ChatMessage>) {
        for (m in messages) {
            assertFalse(m.content.contains("[Now: "), "unexpected Now line in: ${m.content}")
            assertFalse(m.content.startsWith("Today is "), "unexpected date in: ${m.content}")
        }
    }

    private suspend fun room(scope: kotlinx.coroutines.CoroutineScope): RoomEngine {
        val engine = RoomEngine("nexus", InMemoryEventJournal(), null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine
    }

    @Test
    fun her_quick_turn_leaves_with_the_date_and_time() = runTest {
        val roomEngine = room(backgroundScope)
        val client = WireClient(listOf("Hi there!"))
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val (messages, _) = client.sent.single()
        assertEquals(ChatMessage("system", "You are Wyrd. Respond briefly in 1-2 sentences."), messages[0],
            "the system prompt stays byte-stable for the prompt cache")
        carriesNowLine(messages[1].content, "Alice says: hello")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun the_classifier_leaves_without_a_date_and_her_answer_after_it_carries_one() = runTest {
        val roomEngine = room(backgroundScope)
        // Eight words, no question mark, no keyword: the heuristic cannot decide.
        val text = "I wonder whether the garden will bloom soon"
        val client = WireClient(listOf("SIMPLE", "It will."))
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", text))
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertEquals(2, client.sent.size, "classification, then her answer")
        val (classify, classifyOptions) = client.sent[0]
        assertEquals(8, classifyOptions.maxTokens)
        carriesNoDate(classify)
        assertEquals("Message: \"$text\"", classify[1].content)

        carriesNowLine(client.sent[1].first.last().content, "Alice says: $text")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun her_offline_acknowledgement_leaves_with_the_date_and_time() = runTest {
        val roomEngine = room(backgroundScope)
        val client = WireClient(listOf("I'll come back to that."))
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        companion.start()
        advanceTimeBy(200)

        // > 8 words with a question mark: heuristic COMPLEX; no delegation, no
        // remote URL, so she acknowledges locally.
        val text = "Can you help me organize all my photos from the last three years into albums?"
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", text))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val (messages, _) = client.sent.last()
        assertTrue(messages[0].content.startsWith("You are Wyrd. Acknowledge briefly."), messages[0].content)
        carriesNowLine(messages.last().content, "Alice says: $text")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun a_queued_request_remembers_when_it_was_asked_not_when_it_was_queued() = runTest {
        val journal = InMemoryEventJournal()
        val roomEngine = RoomEngine("nexus", journal, null, null, backgroundScope)
        roomEngine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        // The classifier takes real time to answer COMPLEX, so the question is
        // queued measurably later than it was asked (on a phone: 90 s of HTTP
        // delegation, 60 s of NATS, then the remote attempt).
        val client = object : InferenceClient() {
            val sent = mutableListOf<CompletionOptions>()
            override suspend fun send(
                baseUrl: String,
                messages: List<ChatMessage>,
                options: CompletionOptions,
            ): ChatResponse {
                sent += options
                if (options.maxTokens != 8) return ChatResponse("I'll come back to that.", 1, 1)
                val until = Clock.System.now() + 20.milliseconds
                while (Clock.System.now() < until) { /* a slow model, in wall-clock time */ }
                return ChatResponse("COMPLEX", 1, 1)
            }
        }
        val companion = CompanionEngine(profile, roomEngine, client, "http://test", null, backgroundScope)
        // No such directory: nothing is written, the queue keeps its copy in memory.
        val queue = OfflineQueue("/nonexistent/wyrdsekai-queue-test")
        companion.offlineQueue = queue
        companion.start()
        advanceTimeBy(200)

        // Eight words, no question mark, no keyword: the classifier decides.
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "I wonder whether the garden will bloom soon"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        assertEquals(listOf(8, 64), client.sent.map { it.maxTokens }, "classification, then her acknowledgement")
        val asked = journal.replay("nexus").filterIsInstance<WorldEvent.Said>()
            .single { it.entityId == "player-1" }.timestamp
        assertEquals(asked.toEpochMilliseconds(), queue.pending().single().timestamp,
            "the replay's [Asked: …] line reads this")

        companion.shutdown()
        roomEngine.shutdown()
    }

    @Test
    fun authoring_her_identity_leaves_without_a_date() = runTest {
        val client = WireClient(listOf("not json"))
        val answers = mapOf("personality" to "Curious and warm.")
        SoulAuthoring.authorSeed(client, "http://test", answers, "Mia")
        carriesNoDate(client.sent.single().first)

        val evolver = WireClient(listOf("I am Mia, and I notice small things first."))
        IdentityEvolver.regenerateIdentity(evolver, "http://test", SoulAuthoring.fallbackSeed("Mia", answers))
        carriesNoDate(evolver.sent.single().first)
    }

    @Test
    fun the_full_prompt_has_no_trimmable_clock_layer() {
        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = null,
            recentSaid = emptyList(),
            triggerEvent = null,
        )
        assertNotNull(messages.firstOrNull())
        for (m in messages) assertFalse(m.content.contains("Current time"), m.content)
        assertEquals("", TimeContext.build(null), "nothing to say, no layer")
    }
}
