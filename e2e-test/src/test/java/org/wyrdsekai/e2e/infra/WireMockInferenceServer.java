package org.wyrdsekai.e2e.infra;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * WireMock-based inference server for deterministic E2E tests.
 * Provides canned responses for OpenAI and Anthropic wire formats,
 * error injection, and request verification.
 *
 * <p>Usage:
 * <pre>{@code
 * var server = WireMockInferenceServer.openAi(port);
 * server.start();
 * server.stubChatCompletion("Hello! I am Wyrd.", 10, 25);
 * // ... run tests ...
 * server.verifyCompletionCalled(1);
 * server.stop();
 * }</pre>
 */
public final class WireMockInferenceServer {

    private final WireMockServer server;
    private final Protocol protocol;
    private int requestCounter = 0;

    public enum Protocol { OPENAI, ANTHROPIC }

    private WireMockInferenceServer(int port, Protocol protocol) {
        this.server = new WireMockServer(WireMockConfiguration.options()
            .port(port));
        this.protocol = protocol;
    }

    /** Create an OpenAI-compatible mock server. */
    public static WireMockInferenceServer openAi(int port) {
        return new WireMockInferenceServer(port, Protocol.OPENAI);
    }

    /** Create an Anthropic-compatible mock server. */
    public static WireMockInferenceServer anthropic(int port) {
        return new WireMockInferenceServer(port, Protocol.ANTHROPIC);
    }

    /** Create an OpenAI-compatible mock on an auto-allocated port. */
    public static WireMockInferenceServer openAi() {
        return openAi(PortAllocator.allocate());
    }

    /** Create an Anthropic-compatible mock on an auto-allocated port. */
    public static WireMockInferenceServer anthropic() {
        return anthropic(PortAllocator.allocate());
    }

    public void start() {
        server.start();
        WireMock.configureFor("localhost", server.port());
        stubHealthEndpoint();
        // Warmup: ensure Jetty 12 is fully accepting connections before tests use the server.
        // Force HTTP/1.1 to avoid Jetty 12 H2C upgrade bug (NoSuchMethodError in HTTP2Connection).
        var httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();
        for (var path : List.of("/health", "/v1/models")) {
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.port() + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {}
        }
    }

    public void stop() {
        server.stop();
    }

    public void resetAll() {
        server.resetAll();
        requestCounter = 0;
        stubHealthEndpoint();
    }

    public int port() {
        return server.port();
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public WireMockServer wireMock() {
        return server;
    }

    // --- Health stubs ---

    private void stubHealthEndpoint() {
        if (protocol == Protocol.OPENAI) {
            server.stubFor(get(urlEqualTo("/v1/models"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {"data":[{"id":"test-model","object":"model"}]}
                        """)));
            server.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("{\"status\":\"ok\"}")));
        }
    }

    /** Stub health endpoint to return unhealthy (503). */
    public void stubHealthUnhealthy() {
        if (protocol == Protocol.OPENAI) {
            server.stubFor(get(urlEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(503)));
            server.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(503)));
        }
    }

    // --- Chat completion stubs ---

    /**
     * Stub a successful chat completion with the given response text and token usage.
     */
    public void stubChatCompletion(String responseText, int promptTokens, int completionTokens) {
        var reqId = "req-" + (++requestCounter);
        if (protocol == Protocol.OPENAI) {
            stubOpenAiCompletion(responseText, promptTokens, completionTokens, reqId, "stop");
        } else {
            stubAnthropicCompletion(responseText, promptTokens, completionTokens, reqId, "end_turn");
        }
    }

    /**
     * Stub a chat completion that returns a specific finish reason.
     */
    public void stubChatCompletionWithFinishReason(String responseText, String finishReason,
                                                     int promptTokens, int completionTokens) {
        var reqId = "req-" + (++requestCounter);
        if (protocol == Protocol.OPENAI) {
            stubOpenAiCompletion(responseText, promptTokens, completionTokens, reqId, finishReason);
        } else {
            String anthropicReason = switch (finishReason) {
                case "stop" -> "end_turn";
                case "length" -> "max_tokens";
                case "tool_calls" -> "tool_use";
                default -> finishReason;
            };
            stubAnthropicCompletion(responseText, promptTokens, completionTokens, reqId, anthropicReason);
        }
    }

    /**
     * Stub a chat completion that returns an HTTP error.
     */
    public void stubChatCompletionError(int statusCode, String errorMessage) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        server.stubFor(post(urlEqualTo(path))
            .willReturn(aResponse()
                .withStatus(statusCode)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"" + errorMessage + "\"}}")));
    }

    /**
     * Stub a chat completion that times out (fixed delay).
     */
    public void stubChatCompletionTimeout(int delayMs) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        server.stubFor(post(urlEqualTo(path))
            .willReturn(aResponse()
                .withFixedDelay(delayMs)
                .withStatus(200)
                .withBody("{}")));
    }

    /**
     * Stub a sequence of responses (first call returns first response, etc.).
     * Useful for testing fallback or degradation behavior.
     */
    public void stubChatCompletionSequence(String... responseTexts) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        // Remove existing stubs for this path
        server.removeStubsByMetadata(matchingJsonPath("$.path", equalTo(path)));

        for (int i = 0; i < responseTexts.length; i++) {
            var scenarioState = i == 0 ? "Started" : "call-" + i;
            var nextState = "call-" + (i + 1);
            var reqId = "req-seq-" + i;
            var body = protocol == Protocol.OPENAI
                ? openAiResponseBody(responseTexts[i], 10, 20, reqId, "stop")
                : anthropicResponseBody(responseTexts[i], 10, 20, reqId, "end_turn");

            server.stubFor(post(urlEqualTo(path))
                .inScenario("completion-sequence")
                .whenScenarioStateIs(scenarioState)
                .willSetStateTo(nextState)
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
        }
    }

    /**
     * Stub a response for any completion request whose BODY contains the given
     * substring — matched at higher priority than the sequence/default stubs.
     * Use this for one-shot prompts with distinctive markers (e.g. the §10
     * felt/inner voice passes) so tests don't depend on brittle call ORDER:
     * always-on interleaved calls (voice polish, cultural appraisal) otherwise
     * shift a sequence stub off by N.
     */
    public void stubChatCompletionContaining(String bodySubstring, String responseText) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        var reqId = "req-match-" + Math.abs(bodySubstring.hashCode());
        var body = protocol == Protocol.OPENAI
            ? openAiResponseBody(responseText, 10, 20, reqId, "stop")
            : anthropicResponseBody(responseText, 10, 20, reqId, "end_turn");
        server.stubFor(post(urlEqualTo(path))
            .atPriority(1)
            .withRequestBody(containing(bodySubstring))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    // --- Verification ---

    /** Verify the completion endpoint was called exactly N times. */
    public void verifyCompletionCalled(int times) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        server.verify(times, postRequestedFor(urlEqualTo(path)));
    }

    /** Verify the completion endpoint was called at least N times. */
    public void verifyCompletionCalledAtLeast(int times) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        server.verify(WireMock.moreThanOrExactly(times),
            postRequestedFor(urlEqualTo(path)));
    }

    /** Verify the health endpoint was called. */
    public void verifyHealthChecked() {
        if (protocol == Protocol.OPENAI) {
            server.verify(WireMock.moreThanOrExactly(1),
                getRequestedFor(urlEqualTo("/v1/models")));
        }
    }

    /** Verify that the API key header was sent correctly. */
    public void verifyApiKeyHeader(String expectedKey) {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        if (protocol == Protocol.OPENAI) {
            server.verify(postRequestedFor(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer " + expectedKey)));
        } else {
            server.verify(postRequestedFor(urlEqualTo(path))
                .withHeader("x-api-key", equalTo(expectedKey)));
        }
    }

    /** Verify the Anthropic version header was sent. */
    public void verifyAnthropicVersionHeader(String expectedVersion) {
        if (protocol == Protocol.ANTHROPIC) {
            server.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo(expectedVersion)));
        }
    }

    /** Get all recorded request bodies to the completion endpoint. */
    public List<String> getCompletionRequestBodies() {
        String path = protocol == Protocol.OPENAI ? "/v1/chat/completions" : "/v1/messages";
        return server.findAll(postRequestedFor(urlEqualTo(path))).stream()
            .map(r -> r.getBodyAsString())
            .toList();
    }

    // --- Internal response builders ---

    private void stubOpenAiCompletion(String text, int promptTokens, int completionTokens,
                                       String reqId, String finishReason) {
        server.stubFor(post(urlEqualTo("/v1/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(openAiResponseBody(text, promptTokens, completionTokens, reqId, finishReason))));
    }

    private void stubAnthropicCompletion(String text, int promptTokens, int completionTokens,
                                          String reqId, String stopReason) {
        server.stubFor(post(urlEqualTo("/v1/messages"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(anthropicResponseBody(text, promptTokens, completionTokens, reqId, stopReason))));
    }

    static String openAiResponseBody(String text, int promptTokens, int completionTokens,
                                       String reqId, String finishReason) {
        var escapedText = escapeJson(text);
        return """
            {
              "id": "%s",
              "object": "chat.completion",
              "created": %d,
              "model": "test-model",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "%s"},
                "finish_reason": "%s"
              }],
              "usage": {
                "prompt_tokens": %d,
                "completion_tokens": %d,
                "total_tokens": %d
              }
            }
            """.formatted(reqId, System.currentTimeMillis() / 1000,
                escapedText, finishReason, promptTokens, completionTokens,
                promptTokens + completionTokens);
    }

    static String anthropicResponseBody(String text, int promptTokens, int completionTokens,
                                          String reqId, String stopReason) {
        var escapedText = escapeJson(text);
        return """
            {
              "id": "%s",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "%s"}],
              "model": "test-model",
              "stop_reason": "%s",
              "usage": {
                "input_tokens": %d,
                "output_tokens": %d
              }
            }
            """.formatted(reqId, escapedText, stopReason, promptTokens, completionTokens);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
