package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression pin for the fold-site dedup: foldedContent is the ONE
 * place a chat response becomes parser-facing text, so it must strip
 * <think> interiors on every path — the first dedup pass kept the fold
 * but silently dropped the strip on the primary dispatch path.
 */
class TheFoldNeverLeaksTheInteriorTest {

    private static InferenceClient.ChatResponse response(String content,
                                                         InferenceClient.ToolCall... calls) {
        var msg = new InferenceClient.ChatMessage("assistant", content,
            calls.length == 0 ? null : List.of(calls), null);
        return new InferenceClient.ChatResponse("id", "chat.completion", 0L, "m",
            List.of(new InferenceClient.Choice(0, msg, "stop")), null);
    }

    @Test
    void thinkBlocksAreStrippedFromProse() {
        var folded = InferenceRouter.foldedContent(
            response("<think>she seems tired, keep it gentle</think>Rest first. The forge keeps."));
        assertFalse(folded.contains("<think>"), folded);
        assertFalse(folded.contains("keep it gentle"), folded);
        assertEquals("Rest first. The forge keeps.", folded.strip());
    }

    @Test
    void thinkBlocksAreStrippedEvenWhenAToolCallRides() {
        var folded = InferenceRouter.foldedContent(response(
            "<think>check the shelf</think>Looking now.",
            new InferenceClient.ToolCall("c1", "function",
                new InferenceClient.ToolCallFunction("examine", Map.of("target", "shelf")))));
        assertFalse(folded.contains("<think>"), folded);
        assertTrue(folded.contains("Looking now."), folded);
        assertTrue(folded.contains("\"action\":\"examine\""), folded);
        assertTrue(folded.contains("\"target\":\"shelf\""), folded);
    }

    @Test
    void allThinkingNoProseStillFoldsTheToolCall() {
        // Content that is ONLY interior: the strip empties it; the fold
        // must still surface the action alone, not resurrect the interior
        // alongside it.
        var folded = InferenceRouter.foldedContent(response(
            "<think>just act</think>",
            new InferenceClient.ToolCall("c1", "function",
                new InferenceClient.ToolCallFunction("emote", Map.of("style", "nod")))));
        assertTrue(folded.contains("\"action\":\"emote\""), folded);
    }
}
