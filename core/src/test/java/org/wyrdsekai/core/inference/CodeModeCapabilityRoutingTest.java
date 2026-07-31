package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verify the {@code code-mode} capability heuristic
 * registers entries on backends that can write spec-shaped JS, and skips
 * those that can't.
 *
 * <p>Conservative defaults:
 * <ul>
 *   <li>Cloud / ClaudeCli — always.</li>
 *   <li>Local "drive" 9B (probe-confirmed during SSD training) — yes.</li>
 *   <li>Generic ≥7B — yes.</li>
 *   <li>4B base / smaller — no (spec §9 gate).</li>
 * </ul>
 */
class CodeModeCapabilityRoutingTest {

    private static InferenceBackend.LlamaServer llama(String name, List<String> models, int pri) {
        return new InferenceBackend.LlamaServer(name, null, pri, models, null);
    }

    private static InferenceBackend.Cloud cloud(String name, List<String> models) {
        return new InferenceBackend.Cloud(name, null, 100, models);
    }

    @Test void drive_9b_local_registers_code_mode() {
        var backend = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).contains("code-mode");
        var entry = registry.resolve("code-mode").orElseThrow();
        assertThat(entry.backendName()).isEqualTo("skills");
        assertThat(entry.tier()).isEqualTo("local");
    }

    @Test void base_4b_local_does_not_register_code_mode() {
        // Qwen3.5-4B base, no "drive" indicator — spec §9 says don't include
        // the typed-namespace prompt block for this model.
        var backend = llama("voice",
            List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).doesNotContain("code-mode");
    }

    @Test void cloud_always_registers_code_mode() {
        var backend = cloud("anthropic", List.of("claude-opus-4-7"));
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).contains("code-mode");
        var entry = registry.resolve("code-mode").orElseThrow();
        assertThat(entry.tier()).isEqualTo("cloud");
    }

    @Test void mixed_backends_only_capable_one_resolves() {
        var voice = llama("voice", List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var skills = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var registry = CapabilityRegistry.fromBackends(List.of(voice, skills));

        var entry = registry.resolve("code-mode").orElseThrow();
        assertThat(entry.backendName()).isEqualTo("skills");
    }

    @Test void hasCapableBackend_true_when_registered() {
        var backend = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.hasCapableBackend("code-mode")).isTrue();
        assertThat(registry.hasCapableBackend("code-mode", "local")).isTrue();
    }

    @Test void hasCapableBackend_false_when_not_registered() {
        var backend = llama("voice", List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.hasCapableBackend("code-mode")).isFalse();
    }

    @Test void hasCapableBackend_respects_maxTier() {
        // Cloud-only registration. With maxTier=local, the cloud entry is
        // filtered out — gate fires false.
        var backend = cloud("anthropic", List.of("claude-opus-4-7"));
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.hasCapableBackend("code-mode")).isTrue();        // any tier
        assertThat(registry.hasCapableBackend("code-mode", "cloud")).isTrue();
        assertThat(registry.hasCapableBackend("code-mode", "household")).isFalse();
        assertThat(registry.hasCapableBackend("code-mode", "local")).isFalse();
    }

    @Test void hasCapableBackend_handles_no_backends_at_all() {
        var registry = CapabilityRegistry.fromBackends(List.of());
        assertThat(registry.hasCapableBackend("code-mode")).isFalse();
    }

    @Test void hasCapableBackend_null_capability_returns_false() {
        var backend = cloud("anthropic", List.of("claude-opus-4-7"));
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.hasCapableBackend(null)).isFalse();
    }

    @Test void large_local_14b_registers_code_mode() {
        // ≥14B local — spec §9 heuristic says yes (best-guess). Operators
        // can override via capabilities.properties.
        var backend = llama("local-large", List.of("qwen-14b.gguf"), 5);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).contains("code-mode");
    }

    @Test void small_local_2b_does_not_register_code_mode() {
        var backend = llama("tiny", List.of("qwen-2b.gguf"), 15);
        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).doesNotContain("code-mode");
    }

    @Test void active_singleton_setter_and_getter() {
        // Test/teardown contract: setActive(null) clears so other tests start
        // from a known-empty state. setActive(reg) makes hasCapableBackend
        // observable to non-actor callers.
        var prev = CapabilityRegistry.getActive();
        try {
            var backend = cloud("anthropic", List.of("claude-opus-4-7"));
            var registry = CapabilityRegistry.fromBackends(List.of(backend));
            CapabilityRegistry.setActive(registry);
            assertThat(CapabilityRegistry.getActive()).isSameAs(registry);
            assertThat(CapabilityRegistry.getActive().hasCapableBackend("code-mode")).isTrue();

            CapabilityRegistry.setActive(null);
            assertThat(CapabilityRegistry.getActive()).isNull();
        } finally {
            CapabilityRegistry.setActive(prev);
        }
    }
}
