package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A prompt assembled for the 9B drive (16K window) that lands on the 4B voice model
 * (8K window) takes an HTTP 400. Before the compaction path this was treated as a
 * flatly permanent error: the turn dead-ended ("the threads of thought are tangled"),
 * and on a node whose 9B was down — a phone borrowing a household GPU that went away —
 * NO turn could complete at all. These cover the shrink-and-retry that replaces it.
 */
class ContextOverflowCompactionTest {

    /** The exact body llama.cpp returns; measured on second-node 2026-07-13. */
    private static final String LLAMA_400 =
        "Chat completion failed: HTTP 400 — {\"error\":{\"code\":400,\"message\":"
        + "\"request (11458 tokens) exceeds the available context size (8192 tokens)\"}}";

    private static InferenceClient.ChatMessage msg(String role, String content) {
        return new InferenceClient.ChatMessage(role, content);
    }

    private static InferenceClient.ChatRequest req(List<InferenceClient.ChatMessage> msgs,
                                                    int maxTokens) {
        return new InferenceClient.ChatRequest("m", msgs, maxTokens, 0.3, null, null,
            null, null, null, null, null, null, null);
    }

    private static String words(int chars) {
        return "x".repeat(chars);
    }

    @Test
    @DisplayName("context overflow is recognised, and distinguished from other permanent errors")
    void recognisesContextOverflow() {
        assertTrue(InferenceRouter.isContextOverflowError(LLAMA_400));
        assertTrue(InferenceRouter.isContextOverflowError(
            "This model's maximum context length is 8192 tokens"));
        // A payload-size reject is permanent but NOT fixable by compaction — a smaller
        // prompt is not the remedy for a malformed body, so it must not enter the retry.
        assertFalse(InferenceRouter.isContextOverflowError(
            "Message payload size exceeded max_payload"));
        assertFalse(InferenceRouter.isContextOverflowError("connection refused"));
        assertFalse(InferenceRouter.isContextOverflowError(null));
    }

    @Test
    @DisplayName("the window we must fit inside is parsed from the error, not guessed")
    void parsesAvailableWindow() {
        assertEquals(8192, InferenceRouter.parseAvailableContext(LLAMA_400));
        assertEquals(8192, InferenceRouter.parseAvailableContext(
            "This model's maximum context length is 8192 tokens, however you requested 9000"));
        // No number to read → 0, so the caller fails honestly rather than truncating
        // the prompt to some invented window.
        assertEquals(0, InferenceRouter.parseAvailableContext("context size problem"));
        assertEquals(0, InferenceRouter.parseAvailableContext(null));
    }

    @Test
    @DisplayName("compaction drops the OLDEST history and keeps system + the newest turn")
    void compactionKeepsSystemAndNewest() {
        var system = msg("system", "You are Mia.");
        var old1 = msg("user", words(15000));      // ~5000 tok each — together they
        var old2 = msg("assistant", words(15000)); // cannot both fit an 8K window
        var newest = msg("user", "what is the weather tomorrow?");
        var compacted = InferenceRouter.compactToFit(
            req(List.of(system, old1, old2, newest), 256), 8192);

        assertNotNull(compacted, "an over-long prompt must be compactable, not fatal");
        assertTrue(compacted.messages().size() < 4, "history must actually be dropped");
        assertEquals("system", compacted.messages().getFirst().role(),
            "the companion's identity survives compaction");
        assertEquals(newest.content(), compacted.messages().getLast().content(),
            "the question being answered is never the thing we drop");
    }

    @Test
    @DisplayName("compacted prompt fits the window it was given")
    void compactedPromptFits() {
        var msgs = new ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", words(3000)));
        for (int i = 0; i < 20; i++) msgs.add(msg("user", words(3000)));
        var compacted = InferenceRouter.compactToFit(req(msgs, 512), 8192);
        assertNotNull(compacted);

        var total = compacted.messages().stream()
            .mapToInt(m -> InferenceRouter.estimateTokens(m.content()))
            .sum();
        assertTrue(total + 512 <= 8192,
            "compacted prompt (" + total + " tok + 512 completion) must fit 8192");
    }

    @Test
    @DisplayName("when system + newest alone overflow, the system prompt yields — not the question")
    void truncatesSystemRatherThanTheQuestion() {
        var system = msg("system", words(60000));            // 20k tok — alone over budget
        var newest = msg("user", "what is 17 times 3?");
        var compacted = InferenceRouter.compactToFit(req(List.of(system, newest), 256), 8192);

        assertNotNull(compacted);
        assertEquals(2, compacted.messages().size());
        assertEquals(newest.content(), compacted.messages().getLast().content(),
            "the user's actual question must survive intact");
        assertTrue(compacted.messages().getFirst().content().length()
                < system.content().length(),
            "the system prompt is what gets clipped");
    }

    @Test
    @DisplayName("non-content is preserved through compaction (tools, sampling, model)")
    void preservesRequestShape() {
        var msgs = List.of(msg("system", words(9000)), msg("user", words(21000)),
                           msg("user", "hi"));
        var original = new InferenceClient.ChatRequest("mia-model", msgs, 300, 0.42,
            0.9, null, null, null, null, "auto", 0.5, 1.1, null);
        var compacted = InferenceRouter.compactToFit(original, 8192);

        assertNotNull(compacted);
        assertEquals("mia-model", compacted.model());
        assertEquals(300, compacted.maxTokens());
        assertEquals(0.42, compacted.temperature());
        assertEquals("auto", compacted.toolChoice());
        assertEquals(1.1, compacted.repeatPenalty());
    }

    @Test
    @DisplayName("nothing to drop → null, so the caller fails honestly instead of looping")
    void unshrinkableReturnsNull() {
        // A single already-small exchange: compaction has nothing to remove. Returning
        // null (rather than an identical request) is what stops a retry loop.
        var same = List.of(msg("system", "s"), msg("user", "hi"));
        assertNull(InferenceRouter.compactToFit(req(same, 256), 8192));

        // System prompt only — no turn to preserve.
        assertNull(InferenceRouter.compactToFit(
            req(List.of(msg("system", words(90000))), 256), 8192));

        // The completion reservation alone exceeds the window.
        assertNull(InferenceRouter.compactToFit(req(same, 99000), 8192));
        assertNull(InferenceRouter.compactToFit(null, 8192));
    }

    @Test
    @DisplayName("token estimate is pessimistic — it must never under-count into another 400")
    void estimateIsPessimistic() {
        // ~4 chars/token is the usual rule; we deliberately assume 3 so compaction
        // overshoots into safety. A prompt that still overflows costs a dead turn.
        assertTrue(InferenceRouter.estimateTokens("x".repeat(400)) >= 100);
        assertEquals(1, InferenceRouter.estimateTokens(""));
        assertEquals(0, InferenceRouter.estimateTokens(null));
    }

    @Test
    @DisplayName("a request that already fits is left alone")
    void fittingRequestIsNotTouched() {
        var msgs = List.of(msg("system", "You are Mia."), msg("user", "hello"));
        assertNull(InferenceRouter.compactToFit(req(msgs, 256), 8192),
            "nothing dropped → null → primary dispatch path unaffected");
    }

    @Test
    @DisplayName("Japanese is not under-counted — a JA household must compact too")
    void estimateIsScriptAware() {
        // ~1 token per CJK char vs ~3 chars/token for latin. An English-only estimate
        // ("chars / 3") under-counts Japanese ~3x, so a JA prompt would sail past the
        // check and overflow anyway.
        var ja = "こんにちは".repeat(200);           // 1000 CJK chars
        var en = "hello ".repeat(200);             // 1200 latin chars
        assertTrue(InferenceRouter.estimateTokens(ja) >= 1000,
            "1000 CJK chars must be counted as >= 1000 tokens, was "
                + InferenceRouter.estimateTokens(ja));
        assertTrue(InferenceRouter.estimateTokens(ja) > InferenceRouter.estimateTokens(en),
            "CJK must cost more tokens per char than latin");
    }

    @Test
    @DisplayName("a Japanese prompt compacts to fit, rather than overflowing a second time")
    void compactsJapanese() {
        var msgs = new ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", "あなたはミアです。"));
        for (int i = 0; i < 12; i++) msgs.add(msg("user", "今日は".repeat(500)));  // 1500 CJK each
        var compacted = InferenceRouter.compactToFit(req(msgs, 256), 8192);
        assertNotNull(compacted);
        var total = compacted.messages().stream()
            .mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        assertTrue(total + 256 <= 8192,
            "compacted JA prompt (" + total + " tok) must fit 8192");
    }
}
