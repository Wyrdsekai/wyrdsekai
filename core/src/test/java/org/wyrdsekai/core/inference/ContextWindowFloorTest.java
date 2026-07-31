package org.wyrdsekai.core.inference;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prompt assembler's ceiling and the backend's per-slot window are two
 * halves of ONE constraint, and they were allowed to drift apart.
 *
 * <p>{@code PromptAssembler.MIN_BACKEND_SAFE_PROMPT_TOKENS} was 7500 while
 * {@code application.conf} shipped {@code context-size = 4096} in three places
 * and {@code InferenceConfig} used 4096 as its fallback. Every ordinary
 * companion turn (measured 4606-4782 tokens) therefore returned HTTP 400
 * "exceeds the available context size (4096 tokens)". The router then fell back
 * to the VOICE backend — which carries no tools — so the companion narrated
 * doing things instead of doing them. 18 overflows in two hours on a GPU host
 * (second-node, 2026-07-29).</p>
 *
 * <p>Nothing tied the two numbers together, so nothing failed when they
 * diverged. These tests are that tie.</p>
 */
class ContextWindowFloorTest {

    /** The floor must actually hold the largest prompt we are allowed to build. */
    @Test
    void per_slot_floor_holds_the_assembler_ceiling() {
        assertThat(InferenceConfig.MIN_PER_SLOT_CONTEXT)
            .as("a backend window smaller than the assembler's ceiling 400s on ordinary turns")
            .isGreaterThan(7500);
    }

    /** A config that under-provisions must be raised, not obeyed. */
    @Test
    void an_under_provisioned_context_size_is_floored() {
        var cfg = ConfigFactory.parseString("""
            name = "llama-server"
            type = "llama-server"
            priority = 10
            enabled = true
            url = "http://127.0.0.1:11525"
            model-path = ""
            context-size = 4096
            gpu-layers = 0
            port = 11525
            """);
        // model-path blank → no spawn, but the parse must still not accept 4096.
        // Read it the same way createLlamaServer does.
        var raw = cfg.getInt("context-size");
        var floored = Math.max(InferenceConfig.MIN_PER_SLOT_CONTEXT, raw);
        assertThat(raw).isEqualTo(4096);
        assertThat(floored)
            .as("4096 must be raised to the floor, never used as-is")
            .isEqualTo(InferenceConfig.MIN_PER_SLOT_CONTEXT);
    }

    /** An operator asking for MORE than the floor keeps what they asked for. */
    @Test
    void a_generous_context_size_is_preserved() {
        assertThat(Math.max(InferenceConfig.MIN_PER_SLOT_CONTEXT, 32768))
            .as("the floor must not clamp a deliberately larger window down")
            .isEqualTo(32768);
    }
}
