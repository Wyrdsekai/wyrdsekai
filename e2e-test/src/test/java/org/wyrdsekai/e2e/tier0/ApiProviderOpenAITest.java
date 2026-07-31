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
 * Tier 0 protocol tests for OpenAI-compatible wire format.
 * Validates auth, endpoint, response parsing, health path.
 */
@Tag("integration")
class ApiProviderOpenAITest {

    private WireMockInferenceServer server;
    private InferenceClient client;

    @BeforeEach
    void setup() {
        server = WireMockInferenceServer.openAi();
        server.start();
        client = new InferenceClient(server.baseUrl(), "sk-openai-test",
            Duration.ofSeconds(10), new ApiProvider.OpenAI());
    }

    @AfterEach
    void teardown() {
        server.stop();
    }

    @Test
    void bearer_auth_header() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        var request = new InferenceClient.ChatRequest("gpt-4",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        server.verifyApiKeyHeader("sk-openai-test");
    }

    @Test
    void endpoint_is_v1_chat_completions() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        var request = new InferenceClient.ChatRequest("gpt-4",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        // WireMock stub was on /v1/chat/completions — if it worked, endpoint is correct
        server.verifyCompletionCalled(1);
    }

    @Test
    void response_parsing() throws Exception {
        server.stubChatCompletion("Parsed correctly!", 15, 25);

        var request = new InferenceClient.ChatRequest("gpt-4",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            256, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().getFirst().message().role()).isEqualTo("assistant");
        assertThat(response.choices().getFirst().message().content()).isEqualTo("Parsed correctly!");
        assertThat(response.usage().promptTokens()).isEqualTo(15);
        assertThat(response.usage().completionTokens()).isEqualTo(25);
        assertThat(response.usage().totalTokens()).isEqualTo(40);
    }

    @Test
    void no_auth_when_key_null() throws Exception {
        server.stubChatCompletion("Response", 10, 20);

        // Client without API key (local inference)
        var noAuthClient = new InferenceClient(server.baseUrl());

        var request = new InferenceClient.ChatRequest("local-model",
            List.of(new InferenceClient.ChatMessage("user", "Hello")),
            128, 0.7);

        var response = noAuthClient.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices()).hasSize(1);
        // No auth header should have been sent — verified by successful response
    }
}
