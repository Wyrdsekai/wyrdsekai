package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain starts every adapter at rest since 0.5.9, so a client that names nothing (the library's
 * model on the shared brain, 2026-10-22) gets the bare model. Her own requests still carry the
 * species floor: any request to the brain that does not name adapter 0 says 1.0 for it, as the
 * server's own default did before.
 */
class ABareRequestOnTheBrainCarriesHerFloorTest {

    private static final ObjectMapper M = new ObjectMapper();

    @AfterEach
    void forget() {
        ServedAdapters.primeForTests(null);
    }

    private static InferenceClient.ChatRequest request(Map<String, Double> mix) {
        return new InferenceClient.ChatRequest(
            "Qwen3.6-35B-A3B-UD-Q4_K_M",
            List.of(new InferenceClient.ChatMessage("user", "Say the same thing in English.")),
            256, 0.3, null, null, null, null, null, null, null, null, mix);
    }

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

    private static JsonNode brainBody(Map<String, Double> mix) throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        return bodyOf(provider.buildChatRequest("http://127.0.0.1:8200", null, request(mix), java.time.Duration.ofSeconds(30)));
    }

    private static Map<Integer, Double> scales(JsonNode body) {
        var out = new java.util.LinkedHashMap<Integer, Double>();
        if (body.has("lora")) for (var e : body.get("lora")) out.put(e.get("id").asInt(), e.get("scale").asDouble());
        return out;
    }

    @Test
    @DisplayName("a request that names no adapter carries the floor at 1.0 when adapter 0 is the species floor")
    void bareRequestCarriesTheFloor() throws Exception {
        ServedAdapters.primeForTests(Map.of(0, "/adapters/brain/species.gguf", 1, "/adapters/brain/styled.gguf", 2, "/adapters/brain/work.gguf"));
        assertThat(scales(brainBody(null))).containsExactly(Map.entry(0, 1.0));
        assertThat(scales(brainBody(Map.of()))).containsExactly(Map.entry(0, 1.0));
    }

    @Test
    @DisplayName("a request that names other adapters but not the floor gets the floor added; one that names it is left as it says")
    void namedAdaptersKeepTheirWord() throws Exception {
        ServedAdapters.primeForTests(Map.of(0, "/adapters/brain/species.gguf", 1, "/adapters/brain/styled.gguf", 2, "/adapters/brain/work.gguf"));
        assertThat(scales(brainBody(Map.of("adapter:1", 0.4)))).containsEntry(1, 0.4).containsEntry(0, 1.0).hasSize(2);
        var working = new java.util.LinkedHashMap<String, Double>();
        working.put("adapter:0", 0.0);
        working.put("adapter:2", 1.0);
        assertThat(scales(brainBody(working))).as("a working turn names the floor at 0 and keeps it there")
            .containsEntry(0, 0.0).containsEntry(2, 1.0).hasSize(2);
    }

    @Test
    @DisplayName("where adapter 0 is not the species floor, or nothing is known yet, nothing is added")
    void notTheBrainNothingAdded() throws Exception {
        ServedAdapters.primeForTests(Map.of(0, "/s/e2e_arm.gguf", 1, "/s/brain/work.gguf"));
        assertThat(brainBody(null).has("lora")).as("an e2e arm serves its own adapter at the scale it chose").isFalse();
        ServedAdapters.primeForTests(Map.of());
        assertThat(brainBody(null).has("lora")).as("nothing known about the server yet").isFalse();
    }
}
