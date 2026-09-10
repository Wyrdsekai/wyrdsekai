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

        // System prompt only — no turn to preserve, so it is sheared to fit (the llama-server
        // retry adds the synthetic user turn afterwards, as the own-time prompts need).
        var systemOnly = InferenceRouter.compactToFit(
            req(List.of(msg("system", words(90000))), 256), 8192);
        assertNotNull(systemOnly);
        assertTrue(InferenceRouter.estimateTokens(systemOnly.messages().getFirst().content())
            <= 8192 - 256 - InferenceRouter.ctxSafetyMargin(8192));

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
    /**
     * Her own-time prompt is twenty system layers and no user turn. The first version
     * returned null for that shape and every such overflow fell through to a canned line.
     */
    @Test
    @DisplayName("an own-time prompt with no user turn is compacted, keeping its first layer and its last")
    void ownTimePromptWithNoUserTurnIsCompacted() {
        var msgs = new java.util.ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", "You speak English. Every reply you write is in English."));
        for (int i = 0; i < 18; i++) msgs.add(msg("system", "LAYER" + i + " " + words(2700)));
        msgs.add(msg("system", "Output constraints: speak as yourself."));
        var err = "Chat completion failed: HTTP 400 — {\"error\":{\"code\":400,\"message\":"
            + "\"request (16869 tokens) exceeds the available context size (16384 tokens)\"}}";
        assertEquals(16869, InferenceRouter.parseRequestTokens(err));
        int before = msgs.stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();

        var compacted = InferenceRouter.compactToFit(req(msgs, 1024), 16384,
            InferenceRouter.parseRequestTokens(err));

        assertNotNull(compacted, "no user turn is not a reason to give up");
        assertEquals(msgs.getFirst().content(), compacted.messages().getFirst().content(), "language pin intact");
        assertEquals(msgs.getLast().content(), compacted.messages().getLast().content(), "last layer intact");
        int after = compacted.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        int margin = InferenceRouter.ctxSafetyMargin(16384);
        assertTrue(before - after >= 16869 - 16384 + margin,
            "removed at least the reported overshoot plus the margin: " + (before - after));
        assertTrue(compacted.messages().stream().allMatch(m -> m.content().length() > 0), "no empty layers");
    }

    @Test
    @DisplayName("history goes before identity: with a user turn present, layers survive and the old turns go")
    void historyGoesBeforeIdentity() {
        var msgs = new java.util.ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", "pin"));
        for (int i = 0; i < 6; i++) msgs.add(msg("system", "LAYER" + i + " " + words(1500)));
        msgs.add(msg("user", "old " + words(6000)));
        msgs.add(msg("assistant", "older " + words(6000)));
        msgs.add(msg("user", "the question"));
        var compacted = InferenceRouter.compactToFit(req(msgs, 512), 8192, 9000);
        assertNotNull(compacted);
        assertEquals("the question", compacted.messages().getLast().content());
        assertEquals(7, compacted.messages().stream().filter(m -> "system".equals(m.role())).count(),
            "every system layer kept; the history paid");
        assertTrue(compacted.messages().stream().noneMatch(m -> m.content().startsWith("old ")));
    }

    @Test
    @DisplayName("a prompt the estimate liked but the server refused still shrinks by the margin")
    void serverVerdictBeatsTheEstimate() {
        var msgs = List.of(msg("system", "pin"), msg("system", "LAYER " + words(3000)),
            msg("system", "LAYER2 " + words(3000)), msg("user", "q"));
        // estimate: ~2000 tokens, far under 8192 — yet the server said 8300.
        var compacted = InferenceRouter.compactToFit(req(msgs, 256), 8192, 8300);
        assertNotNull(compacted, "the server's 400 is the truth");
        int after = compacted.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        assertTrue(after < 2002 - InferenceRouter.ctxSafetyMargin(8192) + 8, "shrank by at least the margin: " + after);
    }
    /**
     * llama-server shaping consolidates every system layer into one message and inserts a
     * synthetic user turn at position 1 (ensureUserTurn). Live 2026-09-06: the first
     * compaction dropped that turn as "oldest history" and the retry came back
     * "No user query found in messages" — seven of eight own-time overflows.
     */
    @Test
    @DisplayName("the only user turn survives compaction of a shaped own-time prompt")
    void theOnlyUserTurnSurvivesCompaction() {
        var msgs = new java.util.ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", "pin " + words(9000)));
        msgs.add(msg("user", "Given what you just found, answer in your own words."));
        msgs.add(msg("assistant", "step one " + words(2000)));
        msgs.add(msg("assistant", "step two " + words(2000)));
        var compacted = InferenceRouter.compactToFit(req(msgs, 1024), 16384, 17621);
        assertNotNull(compacted);
        assertEquals(1, compacted.messages().stream().filter(m -> "user".equals(m.role())).count(),
            "the template raises without a user turn; compaction must never remove the last one");
        assertEquals("step two " + words(2000), compacted.messages().getLast().content(), "last message intact");
        int after = compacted.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        int before = msgs.stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        // step 3 trims by token estimate and re-estimates the head: allow rounding slack
        assertTrue(before - after >= 17621 - 16384 + InferenceRouter.ctxSafetyMargin(16384) - 16,
            "still removed the overshoot plus the margin: " + (before - after));
    }

    @Test
    @DisplayName("a user turn ahead of an assistant prefill is kept and older history goes first")
    void userTurnBeforePrefillIsKept() {
        var msgs = List.of(msg("system", "pin"), msg("user", "old " + words(6000)),
            msg("assistant", "older " + words(6000)), msg("user", "the question"),
            msg("assistant", "prefill:"));
        var compacted = InferenceRouter.compactToFit(req(msgs, 512), 8192, 9000);
        assertNotNull(compacted);
        assertEquals("prefill:", compacted.messages().getLast().content());
        assertTrue(compacted.messages().stream().anyMatch(m -> "the question".equals(m.content())),
            "the newest user turn is protected");
        assertTrue(compacted.messages().stream().noneMatch(m -> m.content().startsWith("old ")));
    }
    /**
     * Live 2026-09-07 18:34: a 16931-token prompt compacted by an estimated 2083 tokens came back
     * at 16420 — 36 over — and the single-shot rule failed it fast. The server's overshoot is
     * exact; our estimate of a removed layer's worth is not. A later round doubles the margin.
     */
    @Test
    @DisplayName("a retry that still overflows compacts again with a doubled margin, three rounds at most")
    void aSecondRoundRemovesMore() {
        assertEquals(1, InferenceRouter.compactMarginMultiplier(1));
        assertEquals(2, InferenceRouter.compactMarginMultiplier(2));
        assertEquals(4, InferenceRouter.compactMarginMultiplier(3));
        assertEquals(3, InferenceRouter.MAX_COMPACT_ROUNDS);
        var msgs = new java.util.ArrayList<InferenceClient.ChatMessage>();
        msgs.add(msg("system", "pin " + words(9000)));
        msgs.add(msg("user", "Given what you just found, answer in your own words."));
        msgs.add(msg("assistant", "step one " + words(3000)));
        msgs.add(msg("assistant", "step two " + words(3000)));
        int before = msgs.stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        int margin = InferenceRouter.ctxSafetyMargin(16384);
        var first = InferenceRouter.compactToFit(req(msgs, 1024), 16384, 16420, 1);
        var second = InferenceRouter.compactToFit(req(msgs, 1024), 16384, 16420, 2);
        assertNotNull(first); assertNotNull(second);
        int afterFirst = first.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        int afterSecond = second.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        assertTrue(before - afterFirst >= 36 + margin - 16, "round 1 removes the overshoot plus one margin: " + (before - afterFirst));
        assertTrue(before - afterSecond >= 36 + 2 * margin - 16, "round 2 removes the overshoot plus two margins: " + (before - afterSecond));
        assertTrue(afterSecond < afterFirst, "the second round is strictly more aggressive");
        assertEquals(1, second.messages().stream().filter(m -> "user".equals(m.role())).count(), "the user turn still survives");
    }

    @Test
    @DisplayName("when the fat is in the protected turn itself, the middle of it is sheared and the question survives")
    void theOversizedTurnIsShearedRatherThanFailedFast() {
        // Two turns after a restart: no history, a small system layer, and one user message that
        // carries everything. Rounds 1 and 2 freed 153 and then 0 tokens and she failed fast.
        var msgs = List.of(
            msg("system", "You are here. " + words(3000)),
            msg("user", "ROOM AND MEMORY " + words(48000) + " So: what do you make of the pinboard?"));
        int window = 16384;
        var r1 = InferenceRouter.compactToFit(req(msgs, 1024), window, 17133, 1);
        assertNotNull(r1, "a prompt that can be sheared is never unshrinkable");
        int after = r1.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        assertTrue(after <= window - 1024 - InferenceRouter.ctxSafetyMargin(window),
            "the sheared prompt fits the budget: " + after);
        var user = r1.messages().get(1);
        assertEquals("user", user.role());
        assertTrue(user.content().startsWith("ROOM AND MEMORY "), "the head of the turn is kept");
        assertTrue(user.content().endsWith("what do you make of the pinboard?"), "the question at the tail is kept");
        assertTrue(user.content().contains(InferenceRouter.COMPACT_ELISION.strip()), "the cut is marked");
        // round 2 is stricter still and also succeeds
        var r2 = InferenceRouter.compactToFit(req(msgs, 1024), window, 17133, 2);
        assertNotNull(r2);
        int after2 = r2.messages().stream().mapToInt(m -> InferenceRouter.estimateTokens(m.content())).sum();
        assertTrue(after2 < after, "round 2 removes more");
        // a tiny exchange still comes back null: nothing to shear below the floor
        assertNull(InferenceRouter.compactToFit(req(List.of(msg("system", "s"), msg("user", "hi")), 256), 8192));
        // the shape line names where the tokens live
        var shape = InferenceRouter.describeShape(req(msgs, 1024));
        assertTrue(shape.startsWith("2 message(s) [system:"), shape);
        assertTrue(shape.contains(", user:") && shape.endsWith("tools=0"), shape);
    }
}
