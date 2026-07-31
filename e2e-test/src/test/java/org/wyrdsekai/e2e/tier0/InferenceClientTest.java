package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.inference.ApiProvider;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceClient.InferenceException;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * Tier 0 protocol tests for InferenceClient against WireMock.
 * Validates HTTP behavior, error handling, and timeout logic.
 */
@Tag("integration")
class InferenceClientTest {

    private WireMockInferenceServer server;
    private InferenceClient client;

    @BeforeEach
    void setup() {
        server = WireMockInferenceServer.openAi();
        server.start();
        client = new InferenceClient(server.baseUrl(), null, Duration.ofSeconds(10));
    }

    @AfterEach
    void teardown() {
        server.stop();
    }

    @Test
    void chat_completion_success() throws Exception {
        server.stubChatCompletion("Hello, world!", 10, 15);

        var request = new InferenceClient.ChatRequest("test-model",
            List.of(new InferenceClient.ChatMessage("user", "Hi")),
            128, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);

        assertThat(response.choices()).hasSize(1);
        assertThat(response.choices().getFirst().message().content()).isEqualTo("Hello, world!");
        assertThat(response.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(10);
        assertThat(response.usage().completionTokens()).isEqualTo(15);
    }

    @Test
    void chat_completion_with_system_message() throws Exception {
        server.stubChatCompletion("I am a helpful guide.", 20, 25);

        var request = new InferenceClient.ChatRequest("test-model",
            List.of(
                new InferenceClient.ChatMessage("system", "You are a guide."),
                new InferenceClient.ChatMessage("user", "Who are you?")),
            128, 0.7);

        var response = client.chatCompletion(request).get(10, TimeUnit.SECONDS);
        assertThat(response.choices().getFirst().message().content()).isEqualTo("I am a helpful guide.");
    }

    @Test
    void health_check_unhealthy() throws Exception {
        server.stubHealthUnhealthy();
        var healthy = client.healthCheck("/v1/models").get(5, TimeUnit.SECONDS);
        assertThat(healthy).isFalse();
    }

    @Test
    void health_check_unreachable() throws Exception {
        server.stop();
        var healthy = client.healthCheck("/v1/models").get(10, TimeUnit.SECONDS);
        assertThat(healthy).isFalse();
    }

    @Test
    void http_error_throws_inference_exception() {
        server.stubChatCompletionError(500, "Internal error");

        var request = new InferenceClient.ChatRequest("test-model",
            List.of(new InferenceClient.ChatMessage("user", "Hi")),
            128, 0.7);

        assertThatThrownBy(() -> client.chatCompletion(request).get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(InferenceException.class)
            .hasMessageContaining("500");
    }

    @Test
    void timeout_throws_exception() {
        server.stubChatCompletionTimeout(15000);

        var shortTimeoutClient = new InferenceClient(
            server.baseUrl(), null, Duration.ofSeconds(2));

        var request = new InferenceClient.ChatRequest("test-model",
            List.of(new InferenceClient.ChatMessage("user", "Hi")),
            128, 0.7);

        assertThatThrownBy(() ->
            shortTimeoutClient.chatCompletion(request).get(5, TimeUnit.SECONDS))
            .isInstanceOf(Exception.class);
    }

    @Test
    void api_key_header_sent() throws Exception {
        server.stubChatCompletion("Hello!", 5, 10);

        var authedClient = new InferenceClient(
            server.baseUrl(), "sk-test-key-123", Duration.ofSeconds(10));

        var request = new InferenceClient.ChatRequest("test-model",
            List.of(new InferenceClient.ChatMessage("user", "Hi")),
            128, 0.7);

        authedClient.chatCompletion(request).get(10, TimeUnit.SECONDS);
        server.verifyApiKeyHeader("sk-test-key-123");
    }

    @Test
    void convenience_complete_method() throws Exception {
        server.stubChatCompletion("The Nexus is a central hub.", 15, 30);

        var result = client.complete("test-model", "You are a guide.",
            "What is the Nexus?", 128, 0.7).get(10, TimeUnit.SECONDS);

        assertThat(result).isEqualTo("The Nexus is a central hub.");
    }
}
