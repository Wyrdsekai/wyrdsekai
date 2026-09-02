package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code cap:full} (the prompt assembler's heavy tier) must resolve to the
 * largest model, exactly like {@code cap:reasoning} — not fall through to
 * whichever backend happens to sort first (2026-09-02).
 */
class CapFullReachesTheDriveTest {

    private static InferenceBackend llama(String name, String url, int priority, String model) {
        return new InferenceBackend.LlamaServer(name, new InferenceClient(url), priority,
            List.of(model), null);
    }

    @Test
    void full_and_reasoning_both_name_the_largest_model() {
        var registry = CapabilityRegistry.fromBackends(List.of(
            llama("llama-server", "http://127.0.0.1:8200", 5, "/models/wyrdsekai-3.5-9b-drive-v6-q4km.gguf"),
            llama("llama-voice", "http://127.0.0.1:8201", 15, "/models/wyrdsekai-3.5-4b-v10-q4km.gguf")));
        assertThat(registry.resolve("reasoning")).isPresent();
        assertThat(registry.resolve("full")).isPresent();
        assertThat(registry.resolve("full").get().model()).contains("9b-drive");
        assertThat(registry.resolve("full").get().backendName()).isEqualTo("llama-server");
        assertThat(registry.resolve("quick").get().model()).contains("4b-v10");
    }
}
