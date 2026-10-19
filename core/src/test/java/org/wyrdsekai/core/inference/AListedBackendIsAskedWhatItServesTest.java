package org.wyrdsekai.core.inference;

import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend enabled in the configuration with no model list is asked what it serves at boot,
 * the way the configured-URL path always was. A household's GPU box sat at "models: []" for
 * five weeks; the registry saw only {@code default}, and every full turn went to the local 4B
 * voice on the CPU (2026-10-19).
 */
class AListedBackendIsAskedWhatItServesTest {

    private static HttpServer ollamaStandIn() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            var path = ex.getRequestURI().getPath();
            String body = switch (path) {
                case "/api/tags" -> "{\"models\":[{\"name\":\"gpt-oss:20b\",\"details\":{\"family\":\"gptoss\"}}]}";
                case "/api/ps" -> "{\"models\":[]}";
                default -> "{}";
            };
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders("/api/show".equals(path) && !"POST".equals(ex.getRequestMethod()) ? 404 : 200, bytes.length);
            try (var out = ex.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("an Ollama entry with models = [] comes up knowing its models")
    void ollamaEntryLearnsItsModels() throws Exception {
        var server = ollamaStandIn();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort();
            var config = ConfigFactory.parseString("""
                default-model = "default"
                health-check-interval = 30s
                backends = [
                  { name = "ollama", type = "ollama", enabled = true, priority = 30, url = "%s", models = [] }
                ]
                """.formatted(url));
            var inference = InferenceConfig.fromConfig(config);
            var ollama = inference.backends().stream().filter(b -> "ollama".equals(b.name())).findFirst().orElseThrow();
            assertThat(ollama.models()).containsExactly("gpt-oss:20b");
            assertThat(ollama.priority()).as("the list's own priority is kept").isEqualTo(30);

            var registry = CapabilityRegistry.fromBackends(List.of(ollama));
            assertThat(registry.resolve("full").orElseThrow().model()).isEqualTo("gpt-oss:20b");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a server that does not answer at boot is kept, with no models, and asked later")
    void silentAtBoot() {
        var config = ConfigFactory.parseString("""
            default-model = "default"
            health-check-interval = 30s
            backends = [
              { name = "ollama", type = "ollama", enabled = true, priority = 30, url = "http://127.0.0.1:9", models = [] }
            ]
            """);
        var inference = InferenceConfig.fromConfig(config);
        var ollama = inference.backends().stream().filter(b -> "ollama".equals(b.name())).findFirst().orElseThrow();
        assertThat(ollama.models()).isEmpty();
        assertThat(InferenceConfig.modelsServedBy(ollama)).isEmpty();
    }

    @Test
    @DisplayName("a managed server is called by its file")
    void managedServerName() {
        assertThat(InferenceConfig.modelFileName("/var/lib/wyrdsekai/models/wyrdsekai-3.5-4b-v10-q4km.gguf"))
            .isEqualTo("wyrdsekai-3.5-4b-v10-q4km");
        assertThat(InferenceConfig.modelFileName("C:\\\\models\\\\Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"))
            .isEqualTo("Qwen3.6-35B-A3B-UD-Q4_K_M");
        assertThat(CapabilityRegistry.extractSizeBillions("wyrdsekai-3.5-4b-v10-q4km")).isEqualTo(4.0);
    }
}
