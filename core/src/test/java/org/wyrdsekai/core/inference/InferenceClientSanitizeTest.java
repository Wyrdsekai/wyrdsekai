package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link InferenceClient#sanitizeMessageRoles} — the guard that stops the
 * ReAct loop / scripted-tool-result follow-ups from shipping "2+ assistant messages at the end",
 * which llama-server's jinja template rejects with HTTP 400 (task-battery 2026-07-08).
 */
class InferenceClientSanitizeTest {

    @Test
    void merges_two_trailing_assistant_messages() {
        var in = List.of(
            new ChatMessage("system", "s"),
            new ChatMessage("user", "u"),
            new ChatMessage("assistant", "first"),
            new ChatMessage("assistant", "second"));
        var out = InferenceClient.sanitizeMessageRoles(in);
        assertThat(out).hasSize(3);
        assertThat(out.get(2).role()).isEqualTo("assistant");
        assertThat(out.get(2).content()).contains("first").contains("second");
    }

    @Test
    void collapses_a_run_of_three_same_role() {
        var in = List.of(
            new ChatMessage("user", "a"),
            new ChatMessage("assistant", "1"),
            new ChatMessage("assistant", "2"),
            new ChatMessage("assistant", "3"));
        var out = InferenceClient.sanitizeMessageRoles(in);
        assertThat(out).hasSize(2);
        assertThat(out.get(1).content()).contains("1").contains("2").contains("3");
    }

    @Test
    void leaves_valid_alternating_sequence_untouched() {
        var in = List.of(
            new ChatMessage("system", "s"),
            new ChatMessage("user", "u"),
            new ChatMessage("assistant", "a"),
            new ChatMessage("tool", "t"),
            new ChatMessage("assistant", "b"));
        var out = InferenceClient.sanitizeMessageRoles(in);
        assertThat(out).isSameAs(in);
    }

    @Test
    void does_not_merge_structured_tool_messages() {
        // tool-result messages (tool_call_id set) must never be merged.
        var in = List.of(
            new ChatMessage("tool", "r1", null, "call-1"),
            new ChatMessage("tool", "r2", null, "call-2"));
        var out = InferenceClient.sanitizeMessageRoles(in);
        assertThat(out).hasSize(2);
    }

    @Test
    void handles_null_and_singleton() {
        assertThat(InferenceClient.sanitizeMessageRoles(null)).isNull();
        var one = List.of(new ChatMessage("user", "u"));
        assertThat(InferenceClient.sanitizeMessageRoles(one)).isSameAs(one);
    }
}
