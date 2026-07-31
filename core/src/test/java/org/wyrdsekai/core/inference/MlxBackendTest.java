package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * acceptance: prove the Java InferenceConfig
 * recognizes the {@code mlx://} URL scheme, wires an
 * {@link InferenceBackend.Mlx}, and that responses decode identically to
 * the llama-server path. The OpenAI chat-completion wire format is shared,
 * so this is mostly config-plumbing verification — but it does need to
 * cover the scheme-stripping, the type-tag, and a real HTTP round-trip
 * against a mock {@code /v1/chat/completions} endpoint that mimics what
 * {@code mlx_lm.server} returns.
 */
class MlxBackendTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startMockServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // /v1/models — for discoverModels + health.
        server.createContext("/v1/models", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().toString());
            var body = "{\"object\":\"list\",\"data\":[{\"id\":\"qwen3-5-4b-mlx\",\"object\":\"model\"}]}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        // /v1/chat/completions — mimics the mlx_lm.server response shape.
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestPath.set(exchange.getRequestURI().toString());
            var reqBytes = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(reqBytes, StandardCharsets.UTF_8));
            var body = """
                {
                  "id": "mlxchat-abc",
                  "object": "chat.completion",
                  "created": 1700000000,
                  "model": "qwen3-5-4b-mlx",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "hello from mlx"},
                    "finish_reason": "stop"
                  }],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
                }""";
            var out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void isMlxScheme_matchesPrefix() {
        assertThat(InferenceConfig.isMlxScheme("mlx://127.0.0.1:8201")).isTrue();
        assertThat(InferenceConfig.isMlxScheme("MLX://example.com")).isTrue();   // case-insensitive
        assertThat(InferenceConfig.isMlxScheme("http://127.0.0.1:8201")).isFalse();
        assertThat(InferenceConfig.isMlxScheme("")).isFalse();
        assertThat(InferenceConfig.isMlxScheme(null)).isFalse();
    }

    @Test
    void stripMlxScheme_rewritesToHttp() {
        assertThat(InferenceConfig.stripMlxScheme("mlx://127.0.0.1:8201"))
            .isEqualTo("http://127.0.0.1:8201");
        assertThat(InferenceConfig.stripMlxScheme("mlx://host:8201/v1"))
            .isEqualTo("http://host:8201/v1");
        // Non-mlx URLs pass through unchanged.
        assertThat(InferenceConfig.stripMlxScheme("http://x")).isEqualTo("http://x");
    }

    @Test
    void config_mlxUrl_buildsMlxBackend() {
        var hocon = """
            default-model = "qwen3-5-4b-mlx"
            health-check-interval = 30s
            backends = [
              {
                name = "voice-mlx"
                type = "mlx"
                url  = "mlx://127.0.0.1:%d"
                priority = 15
                enabled = true
              }
            ]
            """.formatted(port);
        var cfg = ConfigFactory.parseString(hocon);
        var parsed = InferenceConfig.fromConfig(cfg);

        var voice = findByName(parsed, "voice-mlx");
        assertThat(voice).isInstanceOf(InferenceBackend.Mlx.class);
        var mlx = (InferenceBackend.Mlx) voice;
        assertThat(mlx.type()).isEqualTo("mlx");
        assertThat(mlx.url()).startsWith("mlx://");           // display tag preserved
        assertThat(mlx.client().getBaseUrl()).startsWith("http://"); // wire is HTTP
        assertThat(mlx.models()).contains("qwen3-5-4b-mlx");  // discovered via /v1/models
    }

    @Test
    void config_legacyTypeLlamaServer_butMlxScheme_stillBuildsMlx() {
        // Defensive: if someone leaves type:"llama-server" but flips url to
        // mlx://, the scheme alone should route to the Mlx backend so they
        // don't lose the macOS runtime.
        var hocon = """
            default-model = "qwen3-5-4b-mlx"
            health-check-interval = 30s
            backends = [
              {
                name = "voice-via-scheme"
                type = "llama-server"
                url  = "mlx://127.0.0.1:%d"
                priority = 15
                enabled = true
              }
            ]
            """.formatted(port);
        var cfg = ConfigFactory.parseString(hocon);
        var parsed = InferenceConfig.fromConfig(cfg);
        var b = findByName(parsed, "voice-via-scheme");
        assertThat(b).isInstanceOf(InferenceBackend.Mlx.class);
    }

    @Test
    void chatCompletion_throughMlxBackend_decodesSameAsLlamaServer() throws Exception {
        var client = new InferenceClient(
            "http://127.0.0.1:" + port, null,
            Duration.ofSeconds(5),
            new ApiProvider.OpenAI("mlx"));
        var mlxBackend = new InferenceBackend.Mlx(
            "voice-mlx", client, 15,
            List.of("qwen3-5-4b-mlx"), "mlx://127.0.0.1:" + port);

        var req = new InferenceClient.ChatRequest(
            "qwen3-5-4b-mlx",
            List.of(new InferenceClient.ChatMessage("user", "ping")),
            32, 0.7);

        var resp = mlxBackend.chatCompletion(req).get(5, TimeUnit.SECONDS);

        assertThat(resp).isNotNull();
        assertThat(resp.model()).isEqualTo("qwen3-5-4b-mlx");
        assertThat(resp.choices()).hasSize(1);
        assertThat(resp.choices().getFirst().message().content()).isEqualTo("hello from mlx");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(resp.usage().completionTokens()).isEqualTo(4);

        // The mlx hint should inject chat_template_kwargs.enable_thinking=false
        // — same shape llama-server gets — so the body the mock saw must contain it.
        var body = lastRequestBody.get();
        assertThat(body).isNotNull();
        var node = mapper.readTree(body);
        assertThat(node.path("chat_template_kwargs").path("enable_thinking").asBoolean(true))
            .as("mlx backend should disable <think> via chat_template_kwargs")
            .isFalse();
    }

    @Test
    void healthCheck_mlx_usesModelsEndpoint() throws Exception {
        var client = new InferenceClient(
            "http://127.0.0.1:" + port, null,
            Duration.ofSeconds(3),
            new ApiProvider.OpenAI("mlx"));
        var mlx = new InferenceBackend.Mlx(
            "voice-mlx", client, 15, List.of(), "mlx://127.0.0.1:" + port);

        var ok = mlx.healthCheck().get(5, TimeUnit.SECONDS);
        assertThat(ok).isTrue();
        assertThat(lastRequestPath.get()).startsWith("/v1/models");
    }

    @Test
    void inferTier_mlx_isLocal() {
        var client = new InferenceClient(
            "http://127.0.0.1:" + port, null, Duration.ofSeconds(1),
            new ApiProvider.OpenAI("mlx"));
        var mlx = new InferenceBackend.Mlx(
            "voice-mlx", client, 15, List.of(), "mlx://127.0.0.1:" + port);
        assertThat(CapabilityRegistry.inferTier(mlx)).isEqualTo("local");
    }

    private static InferenceBackend findByName(InferenceConfig cfg, String name) {
        return cfg.backends().stream()
            .filter(b -> name.equals(b.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "backend '" + name + "' not found in: " + cfg.backends()));
    }
}
