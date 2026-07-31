package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.ClaudeCliInference;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.ClaudeCliFixture;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live test for Claude CLI as inference backend.
 * Verifies the full chain: auto-detect → fixture → backend → completion.
 * Skips if claude CLI is not installed or not authenticated.
 */
@Tag("claude-cli")
class ClaudeCliInferenceTest {

    private static ClaudeCliFixture fixture;

    @BeforeAll
    static void setUp() throws Exception {
        ClaudeCliFixture.assumeAvailable();
        fixture = new ClaudeCliFixture();
        fixture.start();
    }

    @Test
    void auto_detect_finds_authenticated_cli() {
        var cli = ClaudeCliInference.autoDetect();
        assertTrue(cli.isPresent(), "autoDetect should find authenticated claude CLI");
        assertFalse(cli.get().availableModels().isEmpty(),
            "Should have at least one model available");
    }

    @Test
    void fixture_creates_backend() {
        var backend = fixture.createBackend("test-claude", 10);
        assertEquals("claude-cli", backend.type());
        assertEquals("claude-cli://oauth", backend.url());
        assertTrue(backend.healthCheck().join(), "Claude CLI backend health check should pass");
    }

    @Test
    void simple_completion_returns_response() throws Exception {
        var backend = fixture.createBackend("test-claude", 10);
        var request = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(
                new InferenceClient.ChatMessage("system", "You are a helpful assistant. Reply in one sentence."),
                new InferenceClient.ChatMessage("user", "What is 2+2?")
            ),
            50,   // max tokens
            0.0,  // temperature
            null, // tools
            null  // tool_choice
        );

        var response = backend.chatCompletion(request).get(120, TimeUnit.SECONDS);

        assertNotNull(response, "Should get a response from Claude CLI");
        assertFalse(response.choices().isEmpty(), "Response should have choices");
        var text = response.choices().getFirst().message().content();
        assertNotNull(text, "Response text should not be null");
        assertFalse(text.isBlank(), "Response text should not be blank");
        assertTrue(text.toLowerCase().contains("4"),
            "Response should mention 4: " + text);

        System.out.println("[ClaudeCliInference] Response: " + text);
        System.out.println("[ClaudeCliInference] Usage: input=" + response.usage().promptTokens() +
            " output=" + response.usage().completionTokens());
    }

    @Test
    void completion_with_system_prompt_works() throws Exception {
        var backend = fixture.createBackend("test-claude-sys", 10);
        var request = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(
                new InferenceClient.ChatMessage("system",
                    "You are a companion in a MUD world called Wyrdsekai. Reply in character, one sentence."),
                new InferenceClient.ChatMessage("user", "Where am I?")
            ),
            80,
            0.7,
            null,
            null
        );

        var response = backend.chatCompletion(request).get(120, TimeUnit.SECONDS);

        assertNotNull(response);
        var text = response.choices().getFirst().message().content();
        assertFalse(text.isBlank(), "Should get in-character response");
        System.out.println("[ClaudeCliInference] In-character: " + text);
    }
}
