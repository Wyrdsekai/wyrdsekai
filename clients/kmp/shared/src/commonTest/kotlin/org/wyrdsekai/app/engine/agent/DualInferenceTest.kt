package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.wyrdsekai.app.engine.InMemoryEventJournal
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Tests for dual inference routing in [CompanionEngine].
 *
 * The CompanionEngine triages user input via [TriageClassifier]:
 *   - SIMPLE: minimal 2-message prompt, maxTokens=64, local 0.6B model
 *   - COMPLEX: full 8-layer prompt, household 7B+ model (or offline queue)
 *
 * These tests verify the routing decisions, prompt shapes, and thinking indicators
 * using a [CapturingInferenceClient] that records all calls.
 *
 * Tests requiring file I/O (OfflineQueue) are in desktopTest:
 * [DualInferenceOfflineTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualInferenceTest {

    private val testProfile = AgentProfile(
        name = "Wyrd",
        entityId = "companion-wyrd",
        entityType = "agent",
        description = "A test companion",
        systemPrompt = "You are Wyrd, a test companion.",
        contextWindowTokens = 4096,
        maxResponseTokens = 128,
        temperature = 0.7,
    )

    /**
     * InferenceClient that captures all calls for assertion.
     * Returns sequential canned responses (for classification then response).
     */
    private class CapturingInferenceClient(
        private val responses: List<String> = listOf("Hello there!"),
    ) : InferenceClient() {
        val calls = mutableListOf<CapturedCall>()
        private var callIndex = 0

        data class CapturedCall(
            val baseUrl: String,
            val messages: List<ChatMessage>,
            val options: CompletionOptions,
        )

        override suspend fun complete(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            calls.add(CapturedCall(baseUrl, messages.toList(), options))
            val response = responses.getOrElse(callIndex) { responses.last() }
            callIndex++
            return ChatResponse(
                content = response,
                promptTokens = 50,
                completionTokens = 10,
            )
        }
    }

    private suspend fun bootRoom(
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<RoomEngine, InMemoryEventJournal> {
        val journal = InMemoryEventJournal()
        val engine = RoomEngine("nexus", journal, null, null, scope)
        engine.send(RoomEngineCommand.CreateRoom("The Nexus", "A shimmering hub.", "foundation"))
        return engine to journal
    }

    // ── Test 1: SIMPLE input uses minimal 2-message prompt ──

    @Test
    fun simple_input_uses_minimal_prompt() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)

        // "hello" is caught by heuristic as SIMPLE — no classification call.
        // The response call uses the minimal 2-message prompt.
        val client = CapturingInferenceClient(listOf("Hi there!"))

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Should have exactly 1 call (the response, not classification — "hello" is heuristic SIMPLE)
        assertTrue(client.calls.isNotEmpty(), "Expected at least one inference call")

        // Find the response call (the one with only 2 messages: system + user)
        val responseCall = client.calls.find { it.messages.size == 2 }
        assertTrue(responseCall != null,
            "SIMPLE path should use a minimal 2-message prompt (system + user). " +
                "Actual calls: ${client.calls.map { "messages=${it.messages.size}" }}")

        // Verify the minimal prompt structure
        assertEquals("system", responseCall.messages[0].role)
        assertEquals("user", responseCall.messages[1].role)
        assertTrue(responseCall.messages[0].content.contains("Respond briefly"),
            "SIMPLE system prompt should contain 'Respond briefly'")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 2: SIMPLE input uses small maxTokens ──

    @Test
    fun simple_input_uses_small_max_tokens() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)

        val client = CapturingInferenceClient(listOf("Sure thing!"))

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Find the response call (2-message minimal prompt)
        val responseCall = client.calls.find { it.messages.size == 2 }
        assertTrue(responseCall != null, "Expected a SIMPLE-path response call")
        assertEquals(64, responseCall.options.maxTokens,
            "SIMPLE path should use maxTokens=64")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 3: Greeting skips LLM classification ──

    @Test
    fun greeting_skips_llm_classification() = runTest {
        val (roomEngine, _) = bootRoom(backgroundScope)

        val client = CapturingInferenceClient(listOf("Hello, nice to meet you!"))

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        // "hi" is a greeting — heuristic catches it as SIMPLE
        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hi"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        // Should be called exactly ONCE (for the response), NOT twice (classification + response).
        // The heuristic catches "hi" as SIMPLE without needing LLM classification.
        assertEquals(1, client.calls.size,
            "Greeting 'hi' should skip LLM classification — only 1 call for the response. " +
                "Got ${client.calls.size} calls.")

        companion.shutdown()
        roomEngine.shutdown()
    }

    // ── Test 4: SIMPLE path emits "considers..." indicator ──

    @Test
    fun simple_path_emits_considers_indicator() = runTest {
        val (roomEngine, journal) = bootRoom(backgroundScope)

        val client = CapturingInferenceClient(listOf("Hey!"))

        val companion = CompanionEngine(
            profile = testProfile,
            roomEngine = roomEngine,
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
            vitalityStore = null,
            scope = backgroundScope,
        )
        companion.start()
        advanceTimeBy(200)

        roomEngine.send(RoomEngineCommand.SayInRoom("player-1", "Alice", "hello"))
        advanceTimeBy(5000)
        advanceUntilIdle()

        val events = journal.allEvents("nexus")
        val emoted = events.filterIsInstance<WorldEvent.Emoted>()
        assertTrue(emoted.any { it.text == "considers..." && it.entityId == testProfile.entityId },
            "SIMPLE path should emit 'considers...' indicator. Emoted: $emoted")

        companion.shutdown()
        roomEngine.shutdown()
    }
}
