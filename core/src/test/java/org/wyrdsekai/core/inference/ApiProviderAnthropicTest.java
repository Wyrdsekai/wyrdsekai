package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-contract tests for {@link ApiProvider.Anthropic}. These do NOT hit the
 * Anthropic API — they exercise request build + response parse offline so a
 * future regression in the wire shape (e.g. an Anthropic SDK quirk creeps in,
 * a header gets dropped) gets caught without burning credits or needing keys.
 *
 * <p>Reference for expected shape: Anthropic Messages API v2023-06-01 +
 * CodeZaiku's AnthropicProvider.java (commit 3290271). When updating these
 * tests, mirror the canonical CodeZaiku behavior — both repos must agree on
 * how to talk to Anthropic.</p>
 */
class ApiProviderAnthropicTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static InferenceClient.ChatRequest sampleRequest() {
        return new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(
                new InferenceClient.ChatMessage("system", "You are Wyrd."),
                new InferenceClient.ChatMessage("user", "Hello.")
            ),
            512,
            0.7);
    }

    // ── buildChatRequest ────────────────────────────────────────────────

    @Test
    void buildChatRequest_uses_v1_messages_endpoint() throws Exception {
        var p = new ApiProvider.Anthropic();
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "sk-ant-test",
            sampleRequest(), Duration.ofSeconds(30));

        assertThat(http.uri().toString())
            .isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(http.method()).isEqualTo("POST");
    }

    @Test
    void buildChatRequest_sets_anthropic_headers_not_bearer() throws Exception {
        var p = new ApiProvider.Anthropic();
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "sk-ant-test",
            sampleRequest(), Duration.ofSeconds(30));

        var headers = http.headers().map();
        assertThat(headers).containsKey("x-api-key");
        assertThat(headers.get("x-api-key")).containsExactly("sk-ant-test");
        assertThat(headers).containsKey("anthropic-version");
        assertThat(headers.get("anthropic-version")).containsExactly("2023-06-01");
        // Content-Type may be canonicalized to Content-Type by HttpClient
        assertThat(headers.keySet().stream().map(String::toLowerCase))
            .contains("content-type");
        // Critical negative: no Bearer auth (that's OpenAI's pattern).
        assertThat(headers.keySet().stream().map(String::toLowerCase))
            .doesNotContain("authorization");
    }

    @Test
    void buildChatRequest_promotes_system_message_to_top_level_field() throws Exception {
        var p = new ApiProvider.Anthropic();
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "sk-ant-test",
            sampleRequest(), Duration.ofSeconds(30));

        var body = httpBody(http);
        var json = M.readTree(body);

        // System content lifted to top-level "system" field, NOT in messages array.
        assertThat(json.path("system").asText()).isEqualTo("You are Wyrd.");
        assertThat(json.path("messages").isArray()).isTrue();
        assertThat(json.path("messages").size()).isEqualTo(1);
        assertThat(json.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(json.path("messages").get(0).path("content").asText()).isEqualTo("Hello.");
    }

    @Test
    void buildChatRequest_concatenates_multiple_system_messages_with_newlines() throws Exception {
        var req = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(
                new InferenceClient.ChatMessage("system", "First clause."),
                new InferenceClient.ChatMessage("system", "Second clause."),
                new InferenceClient.ChatMessage("user", "ok")
            ),
            512, 0.7);

        var p = new ApiProvider.Anthropic();
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "k", req, Duration.ofSeconds(30));
        var json = M.readTree(httpBody(http));

        assertThat(json.path("system").asText())
            .isEqualTo("First clause.\nSecond clause.");
        assertThat(json.path("messages").size()).isEqualTo(1);
    }

    @Test
    void buildChatRequest_max_tokens_is_required_and_defaults_to_8192() throws Exception {
        var req = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(new InferenceClient.ChatMessage("user", "hi")));
        // Explicitly null max_tokens — Anthropic API requires it; provider must default.
        assertThat(req.maxTokens()).isNull();

        var p = new ApiProvider.Anthropic();
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "k", req, Duration.ofSeconds(30));
        var json = M.readTree(httpBody(http));

        assertThat(json.has("max_tokens")).isTrue();
        assertThat(json.path("max_tokens").asInt()).isEqualTo(8192);
    }

    @Test
    void buildChatRequest_passes_temperature_and_top_p_when_set() throws Exception {
        var req = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(new InferenceClient.ChatMessage("user", "hi")),
            256, 0.3, 0.95, null);

        var p = new ApiProvider.Anthropic();
        var json = M.readTree(httpBody(p.buildChatRequest(
            "https://api.anthropic.com", "k", req, Duration.ofSeconds(30))));

        assertThat(json.path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(json.path("top_p").asDouble()).isEqualTo(0.95);
        assertThat(json.path("max_tokens").asInt()).isEqualTo(256);
    }

    @Test
    void buildChatRequest_no_system_message_omits_system_field() throws Exception {
        var req = new InferenceClient.ChatRequest(
            "claude-sonnet-4-6",
            List.of(new InferenceClient.ChatMessage("user", "hi")));

        var p = new ApiProvider.Anthropic();
        var json = M.readTree(httpBody(p.buildChatRequest(
            "https://api.anthropic.com", "k", req, Duration.ofSeconds(30))));

        // Anthropic accepts requests without a system field — don't send empty string.
        assertThat(json.has("system")).isFalse();
    }

    // ── parseChatResponse ───────────────────────────────────────────────

    @Test
    void parseChatResponse_extracts_text_from_content_array() throws Exception {
        var body = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "Hello, Wyrd."}],
              "model": "claude-sonnet-4-6",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 12, "output_tokens": 5}
            }
            """;

        var p = new ApiProvider.Anthropic();
        var resp = p.parseChatResponse(body);

        assertThat(resp.id()).isEqualTo("msg_01");
        assertThat(resp.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(resp.choices()).hasSize(1);
        assertThat(resp.choices().get(0).message().content()).isEqualTo("Hello, Wyrd.");
        assertThat(resp.choices().get(0).message().role()).isEqualTo("assistant");
    }

    @Test
    void parseChatResponse_maps_stop_reasons_to_openai_finish_reasons() throws Exception {
        var p = new ApiProvider.Anthropic();
        // end_turn → stop
        var stop = p.parseChatResponse(responseWithStopReason("end_turn"));
        assertThat(stop.choices().get(0).finishReason()).isEqualTo("stop");
        // max_tokens → length
        var length = p.parseChatResponse(responseWithStopReason("max_tokens"));
        assertThat(length.choices().get(0).finishReason()).isEqualTo("length");
        // tool_use → tool_calls
        var tools = p.parseChatResponse(responseWithStopReason("tool_use"));
        assertThat(tools.choices().get(0).finishReason()).isEqualTo("tool_calls");
        // unknown → passthrough (so we can debug in logs)
        var weird = p.parseChatResponse(responseWithStopReason("dragons"));
        assertThat(weird.choices().get(0).finishReason()).isEqualTo("dragons");
    }

    @Test
    void parseChatResponse_maps_token_usage() throws Exception {
        var body = """
            {
              "id": "msg_02",
              "type": "message",
              "content": [{"type": "text", "text": "ok"}],
              "model": "claude-sonnet-4-6",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 100, "output_tokens": 25}
            }
            """;

        var resp = new ApiProvider.Anthropic().parseChatResponse(body);

        assertThat(resp.usage().promptTokens()).isEqualTo(100);
        assertThat(resp.usage().completionTokens()).isEqualTo(25);
        assertThat(resp.usage().totalTokens()).isEqualTo(125);
    }

    @Test
    void parseChatResponse_handles_empty_content_array_gracefully() throws Exception {
        // Anthropic occasionally returns an empty content list (e.g. stop_reason=tool_use
        // with no text block). Don't NPE — return empty string.
        var body = """
            {
              "id": "msg_03",
              "type": "message",
              "content": [],
              "model": "claude-sonnet-4-6",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 5, "output_tokens": 0}
            }
            """;

        var resp = new ApiProvider.Anthropic().parseChatResponse(body);

        assertThat(resp.choices().get(0).message().content()).isEmpty();
    }

    @Test
    void parseChatResponse_picks_first_text_block_when_multiple_blocks() throws Exception {
        // Anthropic may interleave thinking + text blocks. We want the text block.
        var body = """
            {
              "id": "msg_04",
              "type": "message",
              "content": [
                {"type": "thinking", "thinking": "let me reason..."},
                {"type": "text", "text": "Final answer."}
              ],
              "model": "claude-sonnet-4-6",
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 10, "output_tokens": 8}
            }
            """;

        var resp = new ApiProvider.Anthropic().parseChatResponse(body);

        assertThat(resp.choices().get(0).message().content()).isEqualTo("Final answer.");
    }

    // ── Misc ────────────────────────────────────────────────────────────

    @Test
    void name_and_health_path() {
        var p = new ApiProvider.Anthropic();
        assertThat(p.name()).isEqualTo("anthropic");
        // No /health on Anthropic — backend health check skipped via null.
        assertThat(p.healthPath()).isNull();
    }

    @Test
    void custom_api_version_overrides_default() throws Exception {
        var p = new ApiProvider.Anthropic("2025-01-01");
        var http = p.buildChatRequest(
            "https://api.anthropic.com", "k", sampleRequest(), Duration.ofSeconds(30));
        assertThat(http.headers().firstValue("anthropic-version"))
            .contains("2025-01-01");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String httpBody(HttpRequest http) {
        var sub = new BodyCapture();
        http.bodyPublisher().orElseThrow().subscribe(sub);
        return sub.text();
    }

    private static String responseWithStopReason(String reason) {
        return """
            {
              "id": "msg_x",
              "type": "message",
              "content": [{"type": "text", "text": "."}],
              "model": "claude-sonnet-4-6",
              "stop_reason": "%s",
              "usage": {"input_tokens": 1, "output_tokens": 1}
            }
            """.formatted(reason);
    }

    /**
     * Drains an HttpRequest body publisher into a String. HttpRequest doesn't
     * expose its body directly — only via reactive Flow, so we subscribe and
     * collect.
     */
    private static final class BodyCapture
            implements Flow.Subscriber<ByteBuffer> {
        private final StringBuilder sb = new StringBuilder();
        @Override
        public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
        @Override
        public void onNext(ByteBuffer item) {
            var bytes = new byte[item.remaining()];
            item.get(bytes);
            sb.append(new String(bytes, StandardCharsets.UTF_8));
        }
        @Override public void onError(Throwable t) {}
        @Override public void onComplete() {}
        String text() { return sb.toString(); }
    }
}
