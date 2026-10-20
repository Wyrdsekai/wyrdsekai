package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under the single large model, the brain is the llama-server or MLX server on this host. A
 * household node whose brain had not come back after its move crowned the first model of another
 * machine's Ollama as its single brain, a 12B over the 20B beside it (2026-10-19). Nothing on
 * another machine is crowned now; while the brain is down the lanes wait for it; when it lists
 * several models the node's default model is the one.
 */
class TheSingleBrainIsTheNodesOwnTest {

    private static InferenceBackend llama(String name, String url, List<String> models) {
        return new InferenceBackend.LlamaServer(name, new InferenceClient(url), 5, models, null);
    }

    private static InferenceBackend ollama(String name, String url, List<String> models) {
        return new InferenceBackend.Ollama(name, new InferenceClient(url), 30, models);
    }

    private static String crowned(CapabilityRegistry registry) {
        return registry.resolve("quick").map(e -> e.backendName() + "/" + e.model()).orElse("nobody");
    }

    @Test
    @DisplayName("the household's night: a remote Ollama with two models is never the single brain")
    void aRemoteOllamaIsNotTheBrain() {
        var registry = new CapabilityRegistry();
        CapabilityRegistry.registerSingleBrain(registry,
            List.of(ollama("ollama", "http://198.51.100.161:11434", List.of("gemma4:12b-32k-nothink", "gpt-oss:20b"))),
            "gpt-oss:20b");
        assertThat(crowned(registry)).isEqualTo("nobody");
    }

    @Test
    @DisplayName("the llama-server on this host is the brain, ahead of anything remote")
    void theLocalBrainIsCrowned() {
        var registry = new CapabilityRegistry();
        CapabilityRegistry.registerSingleBrain(registry, List.of(
            ollama("ollama", "http://198.51.100.161:11434", List.of("gpt-oss:20b")),
            llama("llama-server", "http://localhost:8200", List.of("Qwen3.6-35B-A3B-UD-Q4_K_M"))), null);
        assertThat(crowned(registry)).isEqualTo("llama-server/Qwen3.6-35B-A3B-UD-Q4_K_M");
        for (var cap : CapabilityRegistry.SINGLE_BRAIN_CAPABILITIES) {
            assertThat(registry.resolve(cap).orElseThrow().backendName()).as(cap).isEqualTo("llama-server");
        }
    }

    @Test
    @DisplayName("a brain that has not said what it serves is waited for, not crowned blind")
    void aDownBrainIsWaitedFor() {
        var registry = new CapabilityRegistry();
        CapabilityRegistry.registerSingleBrain(registry, List.of(
            llama("llama-server", "http://127.0.0.1:8200", List.of()),
            ollama("ollama", "http://198.51.100.161:11434", List.of("gpt-oss:20b"))), null);
        assertThat(crowned(registry)).isEqualTo("nobody");
        // and when it comes up and is registered again, it is the brain
        CapabilityRegistry.registerSingleBrain(registry, List.of(
            llama("llama-server", "http://127.0.0.1:8200", List.of("Qwen3.6-35B-A3B-UD-Q4_K_M")),
            ollama("ollama", "http://198.51.100.161:11434", List.of("gpt-oss:20b"))), null);
        assertThat(crowned(registry)).isEqualTo("llama-server/Qwen3.6-35B-A3B-UD-Q4_K_M");
    }

    @Test
    @DisplayName("several models on the brain: the node's default model is the one")
    void theDefaultModelWhenSeveral() {
        var registry = new CapabilityRegistry();
        CapabilityRegistry.registerSingleBrain(registry, List.of(
            new InferenceBackend.Mlx("mlx", new InferenceClient("http://127.0.0.1:8200"), 5,
                List.of("small-4b", "Qwen3.6-35B-A3B"), "mlx://127.0.0.1:8200")), "Qwen3.6-35B-A3B");
        assertThat(crowned(registry)).isEqualTo("mlx/Qwen3.6-35B-A3B");
    }

    @Test
    @DisplayName("what counts as this host")
    void thisHost() {
        assertThat(CapabilityRegistry.isTheLocalBrain(llama("a", "http://127.0.0.1:8200", List.of()))).isTrue();
        assertThat(CapabilityRegistry.isTheLocalBrain(llama("a", "http://localhost:8200", List.of()))).isTrue();
        assertThat(CapabilityRegistry.isTheLocalBrain(llama("a", "http://198.51.100.20:8200", List.of()))).isFalse();
        assertThat(CapabilityRegistry.isTheLocalBrain(ollama("o", "http://127.0.0.1:11434", List.of()))).as("Ollama is not the brain").isFalse();
    }
}
