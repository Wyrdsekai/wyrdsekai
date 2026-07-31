package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F15: type-level contract — wrong-backend pairing is detectable before
 * dispatch instead of silently truncating the prompt at the backend.
 */
class AssembledPromptTest {

    private static final List<ChatMessage> SAMPLE = List.of(
        new ChatMessage("system", "you are a companion"),
        new ChatMessage("user", "hi"));

    @Test
    void rejectsBlankBackendId() {
        assertThrows(IllegalArgumentException.class,
            () -> new AssembledPrompt("", SAMPLE, 100));
        assertThrows(IllegalArgumentException.class,
            () -> new AssembledPrompt(null, SAMPLE, 100));
    }

    @Test
    void exactBackendMatch() {
        var voice = new AssembledPrompt(AssembledPrompt.BACKEND_VOICE, SAMPLE, 50);
        assertTrue(voice.matches(AssembledPrompt.BACKEND_VOICE));
        assertFalse(voice.matches(AssembledPrompt.BACKEND_FULL));
    }

    @Test
    void nullResolvedBackendDefaultsToFullCompat() {
        // CompanionActor's old call sites pass null model — historical
        // routing default = heavy. So a cap:full-tagged prompt with null
        // resolved backend is still compatible.
        var full = new AssembledPrompt(AssembledPrompt.BACKEND_FULL, SAMPLE, 100);
        assertTrue(full.matches(null));
        // But a voice-tagged prompt with null hint is a routing accident —
        // the dispatcher would send it to the heavy default and the prompt
        // is too slim to use the heavy capabilities.
        var voice = new AssembledPrompt(AssembledPrompt.BACKEND_VOICE, SAMPLE, 50);
        assertFalse(voice.matches(null),
            "voice prompts must always carry an explicit cap:quick hint");
    }

    @Test
    void tokenEstimateFromCharCount() {
        var msgs = List.of(
            new ChatMessage("system", "x".repeat(400)),  // ~100 tokens
            new ChatMessage("user", "y".repeat(80)));     // ~20 tokens
        assertEquals(120, AssembledPrompt.estimateTokens(msgs));
    }
}
