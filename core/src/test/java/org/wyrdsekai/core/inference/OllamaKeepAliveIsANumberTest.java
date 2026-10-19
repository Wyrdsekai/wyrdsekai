package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ollama reads {@code keep_alive} as a number (seconds; negative = loaded for good) or a duration
 * string. We sent the string {@code "-1"}, which its parser refuses: {@code time: missing unit in
 * duration "-1"}. A household's GPU box answered 400 to every request for two weeks (2026-10-04 →
 * 10-19) while the companions ran on the local voice model instead.
 */
class OllamaKeepAliveIsANumberTest {

    @Test
    @DisplayName("unset: a number, -1, loaded for good")
    void unsetIsMinusOneAsANumber() {
        var node = ApiProvider.OpenAI.keepAlive(null);
        assertThat(node.isNumber()).isTrue();
        assertThat(node.asLong()).isEqualTo(-1);
        assertThat(ApiProvider.OpenAI.keepAlive("").asLong()).isEqualTo(-1);
    }

    @Test
    @DisplayName("a whole number goes as a number, a duration as a string")
    void numbersAndDurations() {
        assertThat(ApiProvider.OpenAI.keepAlive(" 600 ").isNumber()).isTrue();
        assertThat(ApiProvider.OpenAI.keepAlive("600").asLong()).isEqualTo(600);
        assertThat(ApiProvider.OpenAI.keepAlive("-1").asLong()).isEqualTo(-1);
        assertThat(ApiProvider.OpenAI.keepAlive("10m").isTextual()).isTrue();
        assertThat(ApiProvider.OpenAI.keepAlive("10m").asText()).isEqualTo("10m");
        assertThat(ApiProvider.OpenAI.keepAlive("-1m").asText()).isEqualTo("-1m");
    }

    @Test
    @DisplayName("the request on the wire carries keep_alive as a number")
    void onTheWire() throws Exception {
        var provider = new ApiProvider.OpenAI("ollama");
        var req = provider.buildChatRequest("http://127.0.0.1:11434", null,
            new InferenceClient.ChatRequest("gpt-oss:20b",
                List.of(new InferenceClient.ChatMessage("user", "Hello.")), 64, 0.7),
            Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.has("keep_alive")).isTrue();
        assertThat(body.get("keep_alive").isNumber()).as("a number, not the string \"-1\"").isTrue();
        assertThat(body.get("keep_alive").asLong()).isEqualTo(-1);
    }

    private static JsonNode bodyOf(HttpRequest req) throws Exception {
        var publisher = req.bodyPublisher().orElseThrow();
        var out = new StringBuilder();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer b) { out.append(java.nio.charset.StandardCharsets.UTF_8.decode(b)); }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(null); }
        });
        done.get();
        return Json.mapper().readTree(out.toString());
    }
}
