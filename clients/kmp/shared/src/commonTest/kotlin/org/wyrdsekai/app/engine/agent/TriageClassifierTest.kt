package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.ChatResponse
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Tests for [TriageClassifier] — the heuristic + LLM-based input complexity
 * classifier that routes between fast local inference (0.6B SIMPLE) and
 * deeper household inference (7B+ COMPLEX).
 *
 * Tests 1-12: heuristicClassify() — pure functions, no inference.
 * Tests 13-15: classify() — with a FakeInferenceClient mock.
 */
class TriageClassifierTest {

    /** Fake inference client that returns a canned response. Never calls HTTP. */
    private class FakeInferenceClient(private val response: String = "SIMPLE") : InferenceClient() {
        var callCount = 0

        override suspend fun complete(
            baseUrl: String,
            messages: List<ChatMessage>,
            options: CompletionOptions,
        ): ChatResponse {
            callCount++
            return ChatResponse(
                content = response,
                promptTokens = 0,
                completionTokens = 0,
            )
        }
    }

    // ── Heuristic tests ──

    @Test
    fun greeting_hello_is_simple() {
        val result = TriageClassifier.heuristicClassify("hello", "hello")
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
    }

    @Test
    fun greeting_hi_is_simple() {
        val result = TriageClassifier.heuristicClassify("hi", "hi")
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
    }

    @Test
    fun greeting_good_morning_is_simple() {
        val result = TriageClassifier.heuristicClassify("good morning", "good morning")
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
    }

    @Test
    fun mud_command_look_returns_null() {
        val result = TriageClassifier.heuristicClassify("look", "look")
        assertNull(result, "MUD command 'look' should return null (handled as command, not classified)")
    }

    @Test
    fun mud_command_go_north_returns_null() {
        val result = TriageClassifier.heuristicClassify("go north", "go north")
        assertNull(result, "MUD command 'go north' should return null (first word 'go' is a command)")
    }

    @Test
    fun social_nod_returns_null() {
        val result = TriageClassifier.heuristicClassify("nod", "nod")
        assertNull(result, "Social command 'nod' should return null (it's in MUD_COMMANDS)")
    }

    @Test
    fun short_ack_is_routine() {
        val result = TriageClassifier.heuristicClassify("yes", "yes")
        assertEquals(TriageClassifier.Tier.ROUTINE, result,
            "Acknowledgment 'yes' should be ROUTINE")
    }

    @Test
    fun ack_with_question_mark_is_routine() {
        // "ok?" — stripped of punctuation → "ok" which is in ACK_PATTERNS → ROUTINE
        val result = TriageClassifier.heuristicClassify("ok?", "ok?")
        assertEquals(TriageClassifier.Tier.ROUTINE, result,
            "Acknowledgment 'ok?' should be ROUTINE after punctuation stripping")
    }

    @Test
    fun long_input_is_complex() {
        val longInput = "I have been thinking about this for a while and I believe that " +
            "we should reorganize the entire project structure from scratch because the " +
            "current layout is confusing and makes it hard to find anything when you need it"
        val lower = longInput.lowercase()
        val result = TriageClassifier.heuristicClassify(lower, longInput)
        assertEquals(TriageClassifier.Tier.COMPLEX, result,
            "Input with > 30 words should be COMPLEX")
    }

    @Test
    fun long_question_is_complex() {
        val question = "Can you help me organize all my photos from the last three years into albums?"
        val lower = question.lowercase()
        val result = TriageClassifier.heuristicClassify(lower, question)
        assertEquals(TriageClassifier.Tier.COMPLEX, result,
            "Question with > 8 words should be COMPLEX")
    }

    @Test
    fun medium_statement_returns_null() {
        // 7 words, no "?", not a greeting, not a MUD command, not > 30 words,
        // no complex keywords. Falls through all heuristic checks — needs LLM.
        val text = "I had a really interesting session earlier"
        val lower = text.lowercase()
        val result = TriageClassifier.heuristicClassify(lower, text)
        assertNull(result, "Medium-length statement should return null (needs LLM)")
    }

    @Test
    fun empty_input_is_routine() {
        val result = TriageClassifier.heuristicClassify("", "")
        assertEquals(TriageClassifier.Tier.ROUTINE, result,
            "Empty input should be ROUTINE (≤3 words, no question mark)")
    }

    // ── classify() with mock inference ──

    @Test
    fun classify_delegates_to_llm_when_heuristic_undecided() = runTest {
        // "I had a really interesting day today" — heuristic returns null
        val client = FakeInferenceClient(response = "COMPLEX")
        val result = TriageClassifier.classify(
            text = "I had a really interesting session earlier",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.COMPLEX, result,
            "Should delegate to LLM and return COMPLEX when LLM says COMPLEX")
        assertEquals(1, client.callCount,
            "LLM should be called exactly once for classification")
    }

    @Test
    fun classify_defaults_to_simple_on_llm_failure() = runTest {
        // Use an inference client that always throws
        val failingClient = object : InferenceClient() {
            override suspend fun complete(
                baseUrl: String,
                messages: List<ChatMessage>,
                options: CompletionOptions,
            ): ChatResponse {
                throw RuntimeException("Network error")
            }
        }
        val result = TriageClassifier.classify(
            text = "I had a really interesting session earlier",
            inferenceClient = failingClient,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.SIMPLE, result,
            "Should default to SIMPLE when LLM classification fails")
    }

    @Test
    fun classify_uses_heuristic_without_llm_call() = runTest {
        val client = FakeInferenceClient()
        val result = TriageClassifier.classify(
            text = "hello",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
        assertEquals(0, client.callCount,
            "LLM should NOT be called when heuristic decides (greeting 'hello' is SIMPLE)")
    }

    // ── Edge cases ──

    @Test
    fun greeting_with_whitespace_is_simple() = runTest {
        // classify() trims input before heuristic
        val client = FakeInferenceClient()
        val result = TriageClassifier.classify(
            text = "  hello  ",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
        assertEquals(0, client.callCount, "Trimmed greeting should use heuristic path")
    }

    @Test
    fun greeting_case_insensitive() = runTest {
        val client = FakeInferenceClient()
        val result = TriageClassifier.classify(
            text = "HELLO",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.ROUTINE, result)
        assertEquals(0, client.callCount, "Case-insensitive greeting should match heuristic")
    }

    @Test
    fun llm_response_containing_complex_anywhere_returns_complex() = runTest {
        // LLM might return "The answer is COMPLEX." — still counts
        val client = FakeInferenceClient(response = "I think this is COMPLEX.")
        val result = TriageClassifier.classify(
            text = "I had a really interesting session earlier",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.COMPLEX, result,
            "Any response containing 'COMPLEX' should return COMPLEX")
    }

    @Test
    fun llm_response_without_complex_returns_simple() = runTest {
        // LLM returns something that doesn't contain "COMPLEX"
        val client = FakeInferenceClient(response = "SIMPLE")
        val result = TriageClassifier.classify(
            text = "I had a really interesting session earlier",
            inferenceClient = client,
            inferenceBaseUrl = "http://test",
        )
        assertEquals(TriageClassifier.Tier.SIMPLE, result,
            "Response without 'COMPLEX' should default to SIMPLE")
    }
}
