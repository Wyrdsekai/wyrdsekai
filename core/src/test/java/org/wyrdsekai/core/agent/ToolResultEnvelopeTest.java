package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed boundary for script→actor results (second-node 2026-07-10). The dispatch
 * site's identity is authoritative; failure is an error key OR {ok:false};
 * the raw map survives as payload for shape-specific renderers.
 */
class ToolResultEnvelopeTest {

    @Test
    void dispatch_identity_wins_and_error_key_means_failure() {
        var env = ToolResultEnvelope.normalize("morning_briefing",
            Map.of("ok", false, "error", "address is required"));
        assertThat(env.toolId()).isEqualTo("morning_briefing");
        assertThat(env.ok()).isFalse();
        assertThat(env.error()).isEqualTo("address is required");
    }

    @Test
    void ok_false_without_error_message_is_still_a_failure() {
        // The shape that slipped through every consumer as a "success" before.
        var env = ToolResultEnvelope.normalize("web_clipper", Map.of("ok", false));
        assertThat(env.ok()).isFalse();
        assertThat(env.error()).contains("without a message");
    }

    @Test
    void separate_message_key_is_folded_into_error() {
        // trip_planner shape: the explanation lived in `message` and never reached
        // the model while `error` carried only the bare code.
        var env = ToolResultEnvelope.normalize("trip_planner", Map.of(
            "ok", false, "error", "missing_args",
            "message", "origin, destination, and date are required"));
        assertThat(env.error()).isEqualTo(
            "missing_args — origin, destination, and date are required");
    }

    @Test
    void success_shapes_pass_through_as_payload() {
        var env = ToolResultEnvelope.normalize("library_card",
            Map.of("findings", "three shelves matched", "sources", "shelf-1"));
        assertThat(env.ok()).isTrue();
        assertThat(env.error()).isNull();
        assertThat(env.has("findings")).isTrue();
        assertThat(String.valueOf(env.get("findings"))).contains("three shelves");
    }

    @Test
    void tool_key_is_fallback_only_when_dispatch_has_no_identity() {
        var selfReported = ToolResultEnvelope.normalize(null,
            Map.of("tool", "run_script", "summary", "done"));
        assertThat(selfReported.toolId()).isEqualTo("run_script");

        var dispatchWins = ToolResultEnvelope.normalize("morning_briefing",
            Map.of("tool", "something_else", "summary", "done"));
        assertThat(dispatchWins.toolId()).isEqualTo("morning_briefing");
    }

    @Test
    void null_raw_map_is_a_safe_empty_success() {
        var env = ToolResultEnvelope.normalize("zone-narrative", null);
        assertThat(env.ok()).isTrue();
        assertThat(env.payload()).isEmpty();
        assertThat(env.toolId()).isEqualTo("zone-narrative");
    }
}
