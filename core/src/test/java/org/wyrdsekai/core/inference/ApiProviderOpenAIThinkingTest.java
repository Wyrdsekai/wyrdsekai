package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-contract test for {@link ApiProvider.OpenAI} thinking-mode disable.
 *
 * <p>Background: Qwen3.5 family ignores the in-prompt {@code /nothink} slash
 * (verified empirically against deployed Drive-9B on 2026-04-30). The only
 * reliable controls are:
 * <ul>
 *   <li>Request-side: {@code chat_template_kwargs.enable_thinking=false}</li>
 *   <li>Launch-side: {@code --reasoning off --reasoning-budget 0}</li>
 * </ul>
 *
 * <p>This test asserts the request-side injection. If it ever silently
 * regresses (e.g. someone reorganizes ApiProvider and drops the kwarg),
 * production prompts to Drive-9B start losing visible content while burning
 * the entire token budget on {@code reasoning_content} — and the failure mode
 * is silent (no error, just empty completions). Catch it here.
 */
class ApiProviderOpenAIThinkingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static InferenceClient.ChatRequest sampleRequest() {
        return new InferenceClient.ChatRequest(
            "wyrdsekai-3.5-9b-v5-q4km",
            List.of(
                new InferenceClient.ChatMessage("system", "You are Wyrd."),
                new InferenceClient.ChatMessage("user", "Hello.")
            ),
            256,
            0.7);
    }

    /** Capture the JSON body sent in the HTTP request. */
    private static JsonNode bodyOf(HttpRequest req) throws Exception {
        var bp = req.bodyPublisher().orElseThrow();
        var captured = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        bp.subscribe(new Flow.Subscriber<>() {
            private final StringBuilder buf = new StringBuilder();
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer b) {
                var bytes = new byte[b.remaining()];
                b.get(bytes);
                buf.append(new String(bytes, StandardCharsets.UTF_8));
            }
            @Override public void onError(Throwable t) { done.countDown(); }
            @Override public void onComplete() { captured.set(buf.toString()); done.countDown(); }
        });
        done.await(2, TimeUnit.SECONDS);
        return M.readTree(captured.get());
    }

    @Test
    void llamaServer_backend_injects_enable_thinking_false() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var req = provider.buildChatRequest(
            "http://localhost:8200", null, sampleRequest(), Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.has("chat_template_kwargs")).isTrue();
        assertThat(body.get("chat_template_kwargs").has("enable_thinking")).isTrue();
        assertThat(body.get("chat_template_kwargs").get("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void sglang_backend_injects_enable_thinking_false() throws Exception {
        var provider = new ApiProvider.OpenAI("sglang");
        var req = provider.buildChatRequest(
            "http://localhost:8000", null, sampleRequest(), Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void vllm_backend_injects_enable_thinking_false() throws Exception {
        var provider = new ApiProvider.OpenAI("vllm");
        var req = provider.buildChatRequest(
            "http://localhost:8000", null, sampleRequest(), Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void ollama_backend_uses_reasoning_effort_none_not_chat_template_kwargs() throws Exception {
        var provider = new ApiProvider.OpenAI("ollama");
        var req = provider.buildChatRequest(
            "http://localhost:11434", null, sampleRequest(), Duration.ofSeconds(30));
        var body = bodyOf(req);
        // Ollama path uses its own reasoning_effort=none (different surface area).
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none");
        // chat_template_kwargs should NOT be injected for Ollama (it doesn't read it).
        assertThat(body.has("chat_template_kwargs")).isFalse();
    }

    @Test
    void generic_openai_does_not_inject_thinking_disable() throws Exception {
        // Backend-hint=null is generic OpenAI (or unknown provider). We don't
        // know its thinking-disable surface, so we leave the request alone.
        var provider = new ApiProvider.OpenAI();
        var req = provider.buildChatRequest(
            "https://api.openai.com", "test-key", sampleRequest(), Duration.ofSeconds(30));
        var body = bodyOf(req);
        assertThat(body.has("chat_template_kwargs")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
    }
}
