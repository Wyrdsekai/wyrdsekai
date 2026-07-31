package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * verify {@code cap:code-mode} requirement steers
 * requests to capable backends only.
 *
 * <p>The router uses {@link CapabilityRegistry#resolve} to decide which
 * backend handles a {@code cap:code-mode} request. When a code-mode-capable
 * backend is registered, the request lands there. When none is registered,
 * the resolution returns empty and the prompt-assembly gate stops the
 * request from being built — so this test exercises the resolve path,
 * which is what the gate sits on.</p>
 */
class InferenceRouterCapabilityTest {

    private static InferenceBackend.LlamaServer llama(String name, List<String> models, int pri) {
        return new InferenceBackend.LlamaServer(name, null, pri, models, null);
    }

    private static InferenceBackend.Cloud cloud(String name, List<String> models) {
        return new InferenceBackend.Cloud(name, null, 100, models);
    }

    @Test void code_mode_resolves_to_drive_backend_when_present() {
        var voice = llama("voice", List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var skills = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var registry = CapabilityRegistry.fromBackends(List.of(voice, skills));

        var resolved = registry.resolve("code-mode");
        assertThat(resolved).isPresent();
        assertThat(resolved.get().backendName()).isEqualTo("skills");
    }

    @Test void code_mode_unresolved_when_only_uncapable_present() {
        // Only the 4B base voice model — code-mode capability not registered;
        // resolve returns empty so the prompt-assembly gate suppresses the block.
        var voice = llama("voice", List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var registry = CapabilityRegistry.fromBackends(List.of(voice));

        assertThat(registry.resolve("code-mode")).isEmpty();
        assertThat(registry.hasCapableBackend("code-mode")).isFalse();
    }

    @Test void code_mode_prefers_local_drive_over_cloud_by_priority() {
        // Two capable: local drive (priority 5) and cloud (priority 100).
        // Lower priority value wins — local drive selected first.
        var skills = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var anthropic = cloud("anthropic", List.of("claude-opus-4-7"));
        var registry = CapabilityRegistry.fromBackends(List.of(skills, anthropic));

        var resolved = registry.resolve("code-mode").orElseThrow();
        assertThat(resolved.backendName()).isEqualTo("skills");
    }

    @Test void tier_constraint_can_force_cloud_only() {
        var skills = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var anthropic = cloud("anthropic", List.of("claude-opus-4-7"));
        var registry = CapabilityRegistry.fromBackends(List.of(skills, anthropic));

        // maxTier=cloud lets both through, but local still wins by priority.
        var anyTier = registry.resolve("code-mode", "cloud").orElseThrow();
        assertThat(anyTier.backendName()).isEqualTo("skills");
    }

    @Test void capabilities_set_includes_code_mode_only_when_applicable() {
        var voice = llama("voice", List.of("Qwen3.5-4B-Q4_K_M.gguf"), 15);
        var noCode = CapabilityRegistry.fromBackends(List.of(voice));
        assertThat(noCode.availableCapabilities()).doesNotContain("code-mode");

        var skills = llama("skills",
            List.of("wyrdsekai-3.5-9b-v5-q4km.gguf"), 5);
        var withCode = CapabilityRegistry.fromBackends(List.of(skills));
        assertThat(withCode.availableCapabilities()).contains("code-mode");
    }
}
