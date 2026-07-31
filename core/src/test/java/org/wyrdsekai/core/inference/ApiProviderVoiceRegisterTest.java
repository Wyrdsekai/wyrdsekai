package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Wire-contract test for Individuality V2.4 — the per-agent voice REGISTER seam.
 *
 * <p>The companion's {@code TemperamentSeed.registerMix()} rides on the request as
 * a backend-neutral {@code name → signed scale} map ({@code registerMix}). The OpenAI
 * {@link ApiProvider} is the single place that translates it into the target voice
 * backend's per-request form:
 * <ul>
 *   <li><b>MLX</b> ({@code mlx} / {@code mlx_lm.server}) → {@code register_mix:{name:coeff}}
 *       — signed control-vector coefficients, applied by {@code scripts/voice/mlx_runtime.py}.</li>
 *   <li><b>llama-server / vLLM / SGLang</b> → {@code lora:[{id,scale}]} — one-directional
 *       multi-LoRA scales (warmth=0, expansiveness=1), neutral at 0.5 via {@code 0.5 + coeff}.</li>
 * </ul>
 *
 * <p>This is load-bearing: it's the ONLY thing turning a born particular's seed into a
 * distinct voice on a shared 4B server. If it silently regresses, every companion
 * collapses back to one register (the V1 ceiling) — with no error, just sameness.
 */
class ApiProviderVoiceRegisterTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static InferenceClient.ChatRequest requestWithMix(Map<String, Double> mix) {
        return new InferenceClient.ChatRequest(
            "wyrdsekai-3.5-4b-v10-q4km",
            List.of(
                new InferenceClient.ChatMessage("system", "You are a companion, speaking in your own voice."),
                new InferenceClient.ChatMessage("user", "Say this in your voice: it's done.")
            ),
            256, 0.4, null, null, null, null, null, null, null, null, mix);
    }

    private static Map<String, Double> mix(double warmth, double expansive, double guarded) {
        var m = new LinkedHashMap<String, Double>();
        m.put("register_warmth", warmth);
        m.put("register_expansiveness", expansive);
        m.put("register_guardedness", guarded);
        return m;
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

    // ── MLX: signed register_mix, every axis carried verbatim ──────────────────

    @Test
    void mlx_emits_signed_register_mix_for_every_axis() throws Exception {
        var provider = new ApiProvider.OpenAI("mlx");
        var req = provider.buildChatRequest("http://localhost:8201", null,
            requestWithMix(mix(0.30, -0.20, 0.10)), Duration.ofSeconds(20));
        var rm = bodyOf(req).path("register_mix");
        assertThat(rm.path("register_warmth").asDouble()).isCloseTo(0.30, within(1e-9));
        assertThat(rm.path("register_expansiveness").asDouble()).isCloseTo(-0.20, within(1e-9));
        assertThat(rm.path("register_guardedness").asDouble()).isCloseTo(0.10, within(1e-9));
        // MLX path must NOT also emit a lora field.
        assertThat(bodyOf(req).has("lora")).isFalse();
    }

    @Test
    void mlx_lm_server_hint_is_also_treated_as_mlx() throws Exception {
        // The auto-detect config path tags the backend "mlx_lm.server", not "mlx".
        var provider = new ApiProvider.OpenAI("mlx_lm.server");
        var body = bodyOf(provider.buildChatRequest("http://localhost:8201", null,
            requestWithMix(mix(0.25, 0.0, 0.0)), Duration.ofSeconds(20)));
        assertThat(body.path("register_mix").path("register_warmth").asDouble())
            .isCloseTo(0.25, within(1e-9));
    }

    // ── llama-server: one-directional LoRA scales, neutral at 0.5 ──────────────

    @Test
    void llamaServer_maps_warmth_and_expansiveness_to_lora_scales() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var body = bodyOf(provider.buildChatRequest("http://localhost:8211", null,
            requestWithMix(mix(0.40, -0.10, 0.30)), Duration.ofSeconds(20)));
        var lora = body.path("lora");
        assertThat(lora.isArray()).isTrue();
        // warmth → id 0, scale = clamp(0,1, 0.5 + 0.40) = 0.90
        assertThat(lora.get(0).path("id").asInt()).isEqualTo(0);
        assertThat(lora.get(0).path("scale").asDouble()).isCloseTo(0.90, within(1e-9));
        // expansiveness → id 1, scale = clamp(0,1, 0.5 - 0.10) = 0.40
        assertThat(lora.get(1).path("id").asInt()).isEqualTo(1);
        assertThat(lora.get(1).path("scale").asDouble()).isCloseTo(0.40, within(1e-9));
        // guardedness has no trained adapter → only the two entries.
        assertThat(lora.size()).isEqualTo(2);
        assertThat(body.has("register_mix")).isFalse();
    }

    @Test
    void llamaServer_clamps_lora_scale_into_unit_interval() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var body = bodyOf(provider.buildChatRequest("http://localhost:8211", null,
            requestWithMix(mix(0.80, -0.80, 0.0)), Duration.ofSeconds(20)));
        var lora = body.path("lora");
        // 0.5 + 0.80 = 1.30 → clamped to 1.0; 0.5 - 0.80 = -0.30 → clamped to 0.0
        assertThat(lora.get(0).path("scale").asDouble()).isCloseTo(1.0, within(1e-9));
        assertThat(lora.get(1).path("scale").asDouble()).isCloseTo(0.0, within(1e-9));
    }

    // ── Zero-regression: no mix → byte-identical to before V2.4 ────────────────

    @Test
    void noRegisterMix_emits_neither_lora_nor_register_mix() throws Exception {
        var provider = new ApiProvider.OpenAI("llama-server");
        var body = bodyOf(provider.buildChatRequest("http://localhost:8211", null,
            requestWithMix(null), Duration.ofSeconds(20)));
        assertThat(body.has("lora")).isFalse();
        assertThat(body.has("register_mix")).isFalse();
    }

    @Test
    void cloudOpenAI_backend_ignores_register_mix_entirely() throws Exception {
        // A generic/cloud OpenAI endpoint has no voice basis — neither form should appear.
        var provider = new ApiProvider.OpenAI();
        var body = bodyOf(provider.buildChatRequest("https://api.openai.com", "k",
            requestWithMix(mix(0.4, 0.4, 0.0)), Duration.ofSeconds(20)));
        assertThat(body.has("lora")).isFalse();
        assertThat(body.has("register_mix")).isFalse();
    }
}
