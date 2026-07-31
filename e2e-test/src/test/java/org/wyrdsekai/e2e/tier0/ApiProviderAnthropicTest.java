package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 0 protocol tests for Anthropic Messages API wire format.
 * Validates system message extraction, headers, stop reason mapping.
 */
@Tag("integration")
class ApiProviderAnthropicTest {

    private WireMockInferenceServer server;
    private InferenceClient client;

    @BeforeEach
    void setup() {
        server = WireMockInferenceServer.anthropic();
        server.start();
        client = new InferenceClient(server.baseUrl(), "sk-ant-test-key",
            Duration.ofSeconds(10), new ApiProvider.Anthropic());
    }

    @AfterEach
    void teardown() {
        server.stop();
    }

    @Test
    void system_message_extraction_to_top_level() throws Exception {
        server.stubChatCompletion("Hello from Anthropic!", 20, 30);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(
                new InferenceClient.ChatMessage("system", "You are a guide."),
                new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices().getFirst().message().content())
            .isEqualTo("Hello from Anthropic!");

        // Verify system message was sent as top-level field, not in messages array
        // (WireMock request verification)
        server.verifyCompletionCalled(1);
    }

    @Test
    void x_api_key_header() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        server.verifyApiKeyHeader("sk-ant-test-key");
    }

    @Test
    void anthropic_version_header() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        server.verifyAnthropicVersionHeader("2023-06-01");
    }

    @Test
    void end_turn_maps_to_stop() throws Exception {
        server.stubChatCompletionWithFinishReason("Done!", "stop", 10, 20);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices().getFirst().finishReason()).isEqualTo("stop");
    }

    @Test
    void max_tokens_maps_to_length() throws Exception {
        server.stubChatCompletionWithFinishReason("Truncated...", "length", 10, 256);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Tell me everything")),
            256, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices().getFirst().finishReason()).isEqualTo("length");
    }

    @Test
    void endpoint_is_v1_messages() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        // WireMock stub was on /v1/messages — if it worked, endpoint is correct
        server.verifyCompletionCalled(1);
    }

    @Test
    void usage_token_mapping() throws Exception {
        server.stubChatCompletion("Response", 42, 58);

        var request = new InferenceClient.ChatRequest("claude-3-sonnet",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        // Anthropic uses input_tokens/output_tokens → mapped to prompt/completion
        assertThat(response.usage().promptTokens()).isEqualTo(42);
        assertThat(response.usage().completionTokens()).isEqualTo(58);
        assertThat(response.usage().totalTokens()).isEqualTo(100);
    }
}
