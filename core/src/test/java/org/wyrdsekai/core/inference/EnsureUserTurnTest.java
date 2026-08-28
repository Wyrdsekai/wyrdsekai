package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live failure, 2026-08-07: every tool-result follow-up returned HTTP 400
 * from BOTH backends —
 *
 * <pre>Jinja Exception: No user query found in messages.</pre>
 *
 * <p>The Qwen chat template scans backwards for the last user turn and raises if
 * there is none. A tool-result follow-up is system + assistant + tool, which has
 * none. So whenever the companion used a tool she could not speak about what it
 * returned; the never-silent guard read out the raw digest instead — "it
 * returned raw data I couldn't read as an answer". It looked like a retrieval
 * bug and was a message-shape bug.</p>
 */
class EnsureUserTurnTest {

    private static InferenceClient.ChatMessage msg(String role, String content) {
        return new InferenceClient.ChatMessage(role, content);
    }

    private static boolean hasUser(List<InferenceClient.ChatMessage> ms) {
        return ms.stream().anyMatch(m -> "user".equals(m.role()));
    }

    /** THE case: system + assistant + tool must gain a user turn. */
    @Test
    void tool_result_followup_gains_a_user_turn() {
        var in = List.of(
            msg("system", "You are mia."),
            msg("assistant", "calling library_search"),
            msg("tool", "1. Glass Tide: vel-shara of Adrun…"));

        var out = InferenceRouter.ensureUserTurn(in);

        assertTrue(hasUser(out), "the template raises without a user turn");
        assertEquals(4, out.size());
    }

    /** The system prompt must stay at position 0 — the other template constraint. */
    @Test
    void system_message_stays_first() {
        var out = InferenceRouter.ensureUserTurn(List.of(
            msg("system", "You are mia."),
            msg("assistant", "…"),
            msg("tool", "…")));

        assertEquals("system", out.get(0).role(),
            "consolidateSystemMessages requires system at position 0");
        assertEquals("user", out.get(1).role(),
            "the user turn must precede the assistant/tool exchange");
    }

    /** A list that already has a user turn must be returned untouched. */
    @Test
    void leaves_a_wellformed_conversation_alone() {
        var in = List.of(
            msg("system", "You are mia."),
            msg("user", "what did the Librarian say?"),
            msg("assistant", "…"),
            msg("tool", "…"));

        assertSame(in, InferenceRouter.ensureUserTurn(in),
            "must not rebuild a list that is already valid");
    }

    /** No system message — the user turn goes first. */
    @Test
    void handles_a_list_with_no_system_message() {
        var out = InferenceRouter.ensureUserTurn(List.of(
            msg("assistant", "…"),
            msg("tool", "…")));

        assertEquals("user", out.get(0).role());
        assertTrue(hasUser(out));
    }

    /** Order of the original messages must be preserved. */
    @Test
    void preserves_original_order() {
        var out = InferenceRouter.ensureUserTurn(List.of(
            msg("system", "S"),
            msg("assistant", "A"),
            msg("tool", "T")));

        assertEquals("S", out.get(0).content());
        assertEquals("A", out.get(2).content());
        assertEquals("T", out.get(3).content());
    }

    /** Degenerate input must not throw. */
    @Test
    void handles_null_and_empty() {
        assertEquals(null, InferenceRouter.ensureUserTurn(null));
        assertTrue(InferenceRouter.ensureUserTurn(List.of()).isEmpty());
    }

    /** The synthetic turn must invite an answer, not put words in her mouth. */
    @Test
    void synthetic_turn_is_neutral() {
        var out = InferenceRouter.ensureUserTurn(List.of(
            msg("system", "S"), msg("tool", "T")));
        var user = out.stream().filter(m -> "user".equals(m.role())).findFirst().orElseThrow();

        assertTrue(user.content() != null && !user.content().isBlank());
        assertTrue(user.content().toLowerCase().contains("your own words"),
            "should invite her own phrasing rather than dictate content: " + user.content());
    }
}
