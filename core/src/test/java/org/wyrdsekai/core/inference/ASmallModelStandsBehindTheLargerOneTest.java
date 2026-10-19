package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * On a household node the local 4B voice at priority 5 took every default turn for five weeks
 * while a 20B on the GPU box at priority 30 served only the overflow (2026-10-19). The default
 * lane now stands a 4B behind any larger local model whatever the backends' priorities; the full
 * lane goes to the largest model; the quick lane stays with the small one.
 */
class ASmallModelStandsBehindTheLargerOneTest {

    private static InferenceBackend llama(String name, int priority, String model) {
        return new InferenceBackend.LlamaServer(name, new InferenceClient("http://127.0.0.1:11525"),
            priority, List.of(model), null);
    }

    private static InferenceBackend ollama(String name, int priority, String model) {
        return new InferenceBackend.Ollama(name, new InferenceClient("http://198.51.100.161:11434"),
            priority, List.of(model));
    }

    @Test
    @DisplayName("the household's shape: a 4B voice first in the list, a 20B on the GPU box behind it")
    void theHouseholdsShape() {
        var registry = CapabilityRegistry.fromBackends(List.of(
            llama("llama-server", 5, "wyrdsekai-3.5-4b-v10-q4km"),
            ollama("ollama", 30, "gpt-oss:20b")));
        assertThat(registry.resolve("default").orElseThrow().backendName()).isEqualTo("ollama");
        assertThat(registry.resolve("full").orElseThrow().backendName()).isEqualTo("ollama");
        assertThat(registry.resolve("reasoning").orElseThrow().backendName()).isEqualTo("ollama");
        assertThat(registry.resolve("quick").orElseThrow().backendName()).isEqualTo("llama-server");
    }

    @Test
    @DisplayName("the two-model stack is unchanged: the 9B drive leads, the 4B voice answers quick")
    void theTwoModelStack() {
        var registry = CapabilityRegistry.fromBackends(List.of(
            llama("llama-server", 5, "wyrdsekai-3.5-9b-v5-q4km"),
            llama("llama-voice", 15, "wyrdsekai-3.5-4b-v10-q4km")));
        assertThat(registry.resolve("default").orElseThrow().backendName()).isEqualTo("llama-server");
        assertThat(registry.resolve("full").orElseThrow().backendName()).isEqualTo("llama-server");
        assertThat(registry.resolve("quick").orElseThrow().backendName()).isEqualTo("llama-voice");
    }

    @Test
    @DisplayName("alone, a 4B is the default; a paid cloud model never counts as the larger one")
    void aloneAndBesideACloud() {
        var alone = CapabilityRegistry.fromBackends(List.of(llama("llama-server", 5, "wyrdsekai-3.5-4b-v10-q4km")));
        assertThat(alone.resolve("default").orElseThrow().backendName()).isEqualTo("llama-server");

        var cloud = new InferenceBackend.Cloud("claude", new InferenceClient("https://api.example.test"),
            50, List.of("claude-sonnet-70b"));
        var besideCloud = CapabilityRegistry.fromBackends(List.of(
            llama("llama-server", 5, "wyrdsekai-3.5-4b-v10-q4km"), cloud));
        assertThat(besideCloud.resolve("default").orElseThrow().backendName())
            .as("the household's own compute keeps the default lane").isEqualTo("llama-server");
    }

    @Test
    @DisplayName("a backend that says what it serves after boot takes its place on the lanes")
    void saidAfterBoot() {
        var late = new InferenceBackend.Ollama("ollama", new InferenceClient("http://198.51.100.161:11434"),
            30, List.of());
        var backends = new java.util.ArrayList<>(List.of(llama("llama-server", 5, "wyrdsekai-3.5-4b-v10-q4km"), late));
        var registry = CapabilityRegistry.fromBackends(backends);
        assertThat(registry.resolve("full").orElseThrow().backendName())
            .as("before it says, the 4B is all there is").isEqualTo("llama-server");

        var known = late.withModels(List.of("gpt-oss:20b"));
        backends.set(1, known);
        registry.registerBackend(known, known.models(), CapabilityRegistry.largestLocalModelSize(backends));
        registry.rankDefaultLane(backends);
        registry.ensureReasoning(backends);
        assertThat(registry.resolve("default").orElseThrow().backendName()).isEqualTo("ollama");
        assertThat(registry.resolve("full").orElseThrow().backendName()).isEqualTo("ollama");
        assertThat(registry.resolve("quick").orElseThrow().backendName()).isEqualTo("llama-server");
    }
}
