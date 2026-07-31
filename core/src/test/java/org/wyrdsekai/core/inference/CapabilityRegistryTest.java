package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CapabilityRegistry} — capability-based routing for tool inference.
 */
class CapabilityRegistryTest {

    @Test void register_and_resolve_capability() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "ollama-local", "qwen2.5:72b", "household", 10));

        var result = registry.resolve("reasoning");
        assertThat(result).isPresent();
        assertThat(result.get().backendName()).isEqualTo("ollama-local");
        assertThat(result.get().model()).isEqualTo("qwen2.5:72b");
        assertThat(result.get().tier()).isEqualTo("household");
    }

    @Test void resolve_returns_lowest_priority_entry() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud-api", "gpt-4o", "cloud", 100));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "local-vllm", "qwen2.5:72b", "household", 20));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "ollama-local", "qwen2.5:14b", "local", 5));

        var result = registry.resolve("reasoning");
        assertThat(result).isPresent();
        assertThat(result.get().backendName()).isEqualTo("ollama-local");
        assertThat(result.get().priority()).isEqualTo(5);
    }

    @Test void resolve_with_tier_filtering_local_only() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud-api", "gpt-4o", "cloud", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "household-vllm", "qwen2.5:72b", "household", 5));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "ollama-local", "qwen2.5:7b", "local", 10));

        var result = registry.resolve("reasoning", "local");
        assertThat(result).isPresent();
        assertThat(result.get().backendName()).isEqualTo("ollama-local");
        assertThat(result.get().tier()).isEqualTo("local");
    }

    @Test void resolve_with_tier_filtering_household() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud-api", "gpt-4o", "cloud", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "household-vllm", "qwen2.5:72b", "household", 5));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "ollama-local", "qwen2.5:7b", "local", 10));

        var result = registry.resolve("reasoning", "household");
        assertThat(result).isPresent();
        // Should pick household (priority 5) over local (priority 10)
        assertThat(result.get().backendName()).isEqualTo("household-vllm");
        assertThat(result.get().tier()).isEqualTo("household");
    }

    @Test void resolve_with_tier_filtering_cloud_returns_all() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "cloud-api", "gpt-4o", "cloud", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "ollama-local", "codestral:22b", "local", 10));

        var result = registry.resolve("coding", "cloud");
        assertThat(result).isPresent();
        // Cloud priority 1 is preferred
        assertThat(result.get().backendName()).isEqualTo("cloud-api");
    }

    @Test void resolve_unknown_capability_returns_empty() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "ollama-local", "qwen2.5:72b", "household", 10));

        var result = registry.resolve("unknown-capability");
        assertThat(result).isEmpty();
    }

    @Test void resolve_with_tier_filtering_no_match_returns_empty() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud-api", "gpt-4o", "cloud", 1));

        var result = registry.resolve("reasoning", "local");
        assertThat(result).isEmpty();
    }

    @Test void available_capabilities_lists_all_registered() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "vllm", "qwen2.5:72b", "household", 10));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "ollama", "codestral:22b", "local", 5));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "ollama", "qwen2.5:3b", "local", 3));

        assertThat(registry.availableCapabilities())
            .containsExactlyInAnyOrder("reasoning", "coding", "quick");
    }

    @Test void entries_returns_all_for_capability_sorted_by_priority() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "cloud", "gpt-4o", "cloud", 100));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "local", "qwen2.5:7b", "local", 10));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "household", "qwen2.5:32b", "household", 50));

        var entries = registry.entries("default");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).backendName()).isEqualTo("local");
        assertThat(entries.get(1).backendName()).isEqualTo("household");
        assertThat(entries.get(2).backendName()).isEqualTo("cloud");
    }

    @Test void entries_returns_empty_for_unknown_capability() {
        var registry = new CapabilityRegistry();
        assertThat(registry.entries("nonexistent")).isEmpty();
    }

    // --- fromBackends auto-detection ---

    @Test void from_backends_coder_model_gets_coding_capability() {
        var backend = new InferenceBackend.Ollama(
            "ollama-local", new InferenceClient("http://localhost:11434"),
            10, List.of("codestral:22b"));

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("coding");
        var coding = registry.resolve("coding");
        assertThat(coding).isPresent();
        assertThat(coding.get().model()).isEqualTo("codestral:22b");
    }

    @Test void from_backends_large_model_gets_reasoning_capability() {
        var backend = new InferenceBackend.VLLM(
            "household-vllm", new InferenceClient("http://server:8000"),
            20, List.of("qwen2.5:72b"));

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("reasoning");
        var reasoning = registry.resolve("reasoning");
        assertThat(reasoning).isPresent();
        assertThat(reasoning.get().model()).isEqualTo("qwen2.5:72b");
        assertThat(reasoning.get().tier()).isEqualTo("household");
    }

    @Test void from_backends_small_model_gets_quick_capability() {
        var backend = new InferenceBackend.Ollama(
            "ollama-local", new InferenceClient("http://localhost:11434"),
            5, List.of("qwen2.5:3b"));

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("quick");
        var quick = registry.resolve("quick");
        assertThat(quick).isPresent();
        assertThat(quick.get().model()).isEqualTo("qwen2.5:3b");
        assertThat(quick.get().tier()).isEqualTo("local");
    }

    @Test void from_backends_all_models_get_default_capability() {
        var backend = new InferenceBackend.Ollama(
            "ollama-local", new InferenceClient("http://localhost:11434"),
            10, List.of("llama3.2:7b"));

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("default");
        var def = registry.resolve("default");
        assertThat(def).isPresent();
        assertThat(def.get().model()).isEqualTo("llama3.2:7b");
    }

    @Test void from_backends_tier_assignment_llama_server_is_local() {
        var backend = new InferenceBackend.LlamaServer(
            "local-llama", new InferenceClient("http://localhost:11525"),
            10, List.of("qwen2.5:7b"), null);

        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        var entry = registry.resolve("default");
        assertThat(entry).isPresent();
        assertThat(entry.get().tier()).isEqualTo("local");
    }

    @Test void from_backends_tier_assignment_cloud_is_cloud() {
        var backend = new InferenceBackend.Cloud(
            "openai", new InferenceClient("https://api.openai.com/v1"),
            100, List.of("gpt-4o"));

        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        var entry = registry.resolve("default");
        assertThat(entry).isPresent();
        assertThat(entry.get().tier()).isEqualTo("cloud");
    }

    @Test void from_backends_no_models_registers_default_only() {
        var backend = new InferenceBackend.LlamaServer(
            "local-bare", new InferenceClient("http://localhost:11525"),
            10, List.of(), null);

        var registry = CapabilityRegistry.fromBackends(List.of(backend));
        assertThat(registry.availableCapabilities()).containsExactly("default");
    }

    @Test void from_backends_multiple_backends() {
        var local = new InferenceBackend.Ollama(
            "ollama", new InferenceClient("http://localhost:11434"),
            10, List.of("qwen2.5:3b", "codestral:22b"));
        var household = new InferenceBackend.VLLM(
            "vllm", new InferenceClient("http://server:8000"),
            20, List.of("qwen2.5:72b"));
        var cloud = new InferenceBackend.Cloud(
            "openai", new InferenceClient("https://api.openai.com/v1"),
            100, List.of("gpt-4o"));

        var registry = CapabilityRegistry.fromBackends(List.of(local, household, cloud));

        // Should have multiple capabilities
        assertThat(registry.availableCapabilities())
            .contains("default", "quick", "coding", "reasoning");

        // Quick should prefer local (priority 10) over cloud (priority 100)
        var quick = registry.resolve("quick");
        assertThat(quick).isPresent();
        assertThat(quick.get().tier()).isEqualTo("local");
    }

    // --- Dual-inference routing (skills 9B + voice 4B) ---

    @Test void from_backends_wyrdsekai_dual_inference_routes_quick_and_reasoning() {
        // Exactly the layout home-server serves in prod: skills on :8200 (9B), voice on :8201 (4B).
        var skills = new InferenceBackend.LlamaServer(
            "llama-skills-auto", new InferenceClient("http://127.0.0.1:8200"),
            5, List.of("wyrdsekai-3.5-9b-v5-q4km"), null);
        var voice = new InferenceBackend.LlamaServer(
            "llama-voice-auto", new InferenceClient("http://127.0.0.1:8201"),
            15, List.of("wyrdsekai-3.5-4b-balanced-q4km"), null);

        var registry = CapabilityRegistry.fromBackends(List.of(skills, voice));

        // cap:quick must resolve to the 4B voice backend.
        var quick = registry.resolve("quick");
        assertThat(quick).isPresent();
        assertThat(quick.get().backendName()).isEqualTo("llama-voice-auto");
        assertThat(quick.get().model()).isEqualTo("wyrdsekai-3.5-4b-balanced-q4km");

        // cap:reasoning must resolve to the 9B skills backend (via fallback — 9B doesn't
        // trip the >30B heuristic, but it's the largest model, so it wins).
        var reasoning = registry.resolve("reasoning");
        assertThat(reasoning).isPresent();
        assertThat(reasoning.get().backendName()).isEqualTo("llama-skills-auto");
        assertThat(reasoning.get().model()).isEqualTo("wyrdsekai-3.5-9b-v5-q4km");
    }

    @Test void from_backends_reasoning_falls_back_to_largest_when_no_model_over_30b() {
        // Single mid-size backend — no heuristic match for reasoning. Fallback should
        // still register this backend so cap:reasoning resolves rather than dropping
        // through to priority-order default selection.
        var backend = new InferenceBackend.LlamaServer(
            "only-backend", new InferenceClient("http://localhost:11525"),
            10, List.of("llama3.2:7b"), null);

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("reasoning");
        var reasoning = registry.resolve("reasoning");
        assertThat(reasoning).isPresent();
        assertThat(reasoning.get().model()).isEqualTo("llama3.2:7b");
    }

    @Test void from_backends_4b_model_registers_quick() {
        // Regression guard: 4B is our voice model. The old heuristic (<4) missed it.
        var backend = new InferenceBackend.LlamaServer(
            "voice", new InferenceClient("http://localhost:8201"),
            15, List.of("wyrdsekai-3.5-4b-balanced-q4km"), null);

        var registry = CapabilityRegistry.fromBackends(List.of(backend));

        assertThat(registry.availableCapabilities()).contains("quick");
        var quick = registry.resolve("quick");
        assertThat(quick).isPresent();
        assertThat(quick.get().model()).isEqualTo("wyrdsekai-3.5-4b-balanced-q4km");
    }

    // --- extractSizeBillions ---

    @Test void extract_size_billions_standard_patterns() {
        assertThat(CapabilityRegistry.extractSizeBillions("qwen2.5:72b")).isEqualTo(72.0);
        assertThat(CapabilityRegistry.extractSizeBillions("qwen2.5:7b")).isEqualTo(7.0);
        assertThat(CapabilityRegistry.extractSizeBillions("qwen2.5:3b")).isEqualTo(3.0);
        assertThat(CapabilityRegistry.extractSizeBillions("qwen3.5:0.5b")).isEqualTo(0.5);
        assertThat(CapabilityRegistry.extractSizeBillions("llama3.2:7b")).isEqualTo(7.0);
    }

    @Test void extract_size_billions_no_match_returns_negative() {
        assertThat(CapabilityRegistry.extractSizeBillions("gpt-4o")).isEqualTo(-1.0);
        assertThat(CapabilityRegistry.extractSizeBillions("codestral")).isEqualTo(-1.0);
    }

    @Test void extract_size_billions_uppercase() {
        assertThat(CapabilityRegistry.extractSizeBillions("model-72B")).isEqualTo(72.0);
    }

    // --- inferTier ---

    @Test void infer_tier_local_types() {
        var llama = new InferenceBackend.LlamaServer(
            "l", new InferenceClient("http://localhost:11525"), 10, List.of(), null);
        var ollama = new InferenceBackend.Ollama(
            "o", new InferenceClient("http://localhost:11434"), 10, List.of());

        assertThat(CapabilityRegistry.inferTier(llama)).isEqualTo("local");
        assertThat(CapabilityRegistry.inferTier(ollama)).isEqualTo("local");
    }

    @Test void infer_tier_household_types() {
        var vllm = new InferenceBackend.VLLM(
            "v", new InferenceClient("http://server:8000"), 20, List.of());
        var sglang = new InferenceBackend.SGLang(
            "s", new InferenceClient("http://server:8001"), 20, List.of());

        assertThat(CapabilityRegistry.inferTier(vllm)).isEqualTo("household");
        assertThat(CapabilityRegistry.inferTier(sglang)).isEqualTo("household");
    }

    @Test void infer_tier_cloud_types() {
        var cloud = new InferenceBackend.Cloud(
            "c", new InferenceClient("https://api.openai.com/v1"), 100, List.of());

        assertThat(CapabilityRegistry.inferTier(cloud)).isEqualTo("cloud");
    }

    // --- buildPromptContext ---

    @Test void build_prompt_context_returns_null_when_empty() {
        var registry = new CapabilityRegistry();
        assertThat(registry.buildPromptContext()).isNull();
    }

    @Test void build_prompt_context_lists_capabilities() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "vllm", "qwen2.5:72b", "household", 20));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "ollama", "qwen2.5:3b", "local", 5));

        var ctx = registry.buildPromptContext();
        assertThat(ctx).contains("## Available Reasoning Tools");
        assertThat(ctx).contains("reasoning");
        assertThat(ctx).contains("quick");
        assertThat(ctx).contains("qwen2.5:72b");
        assertThat(ctx).contains("qwen2.5:3b");
    }

    // --- Validation ---

    @Test void capability_entry_rejects_null_fields() {
        assertThatThrownBy(() -> new CapabilityRegistry.CapabilityEntry(
            null, "backend", "model", "local", 1))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CapabilityRegistry.CapabilityEntry(
            "cap", null, "model", "local", 1))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CapabilityRegistry.CapabilityEntry(
            "cap", "backend", null, "local", 1))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CapabilityRegistry.CapabilityEntry(
            "cap", "backend", "model", null, 1))
            .isInstanceOf(NullPointerException.class);
    }

    @Test void capability_entry_rejects_unknown_tier() {
        assertThatThrownBy(() -> new CapabilityRegistry.CapabilityEntry(
            "cap", "backend", "model", "galaxy", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown tier");
    }

    // --- Tier filtering with cloud backend ---

    @Test void resolve_with_local_tier_excludes_cloud_backend() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud-openai", "gpt-4o", "cloud", 1));

        // Only cloud backend registered — local tier should find nothing
        var result = registry.resolve("reasoning", "local");
        assertThat(result).isEmpty();
    }

    @Test void resolve_with_household_tier_includes_local_and_household() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "cloud-api", "gpt-4o", "cloud", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "household-vllm", "codestral:22b", "household", 5));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "coding", "local-ollama", "qwen2.5:7b", "local", 10));

        var result = registry.resolve("coding", "household");
        assertThat(result).isPresent();
        // Household tier 5 is preferred over local tier 10
        assertThat(result.get().backendName()).isEqualTo("household-vllm");
    }

    @Test void resolve_with_cloud_tier_allows_all_tiers() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "analysis", "cloud-api", "gpt-4o", "cloud", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "analysis", "local-ollama", "qwen2.5:14b", "local", 10));

        var result = registry.resolve("analysis", "cloud");
        assertThat(result).isPresent();
        // Cloud priority 1 is preferred
        assertThat(result.get().backendName()).isEqualTo("cloud-api");
    }

    @Test void resolve_tier_filtering_with_only_local_backend_and_cloud_tier() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "local-ollama", "qwen2.5:3b", "local", 5));

        // Cloud tier is unrestricted — should find the local backend
        var result = registry.resolve("quick", "cloud");
        assertThat(result).isPresent();
        assertThat(result.get().tier()).isEqualTo("local");
    }

    // --- Tier model registration (env var pattern from Main.java) ---

    @Test void register_explicit_routine_model_overrides_auto() {
        var registry = new CapabilityRegistry();
        // Auto-detected "quick" from small model
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "auto-ollama", "qwen2.5:3b", "local", 20));
        // Explicit registration with priority 1 (from WYRDSEKAI_MODEL_ROUTINE)
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "ollama", "qwen3.5:4b", "local", 1));

        var result = registry.resolve("quick");
        assertThat(result).isPresent();
        assertThat(result.get().model()).isEqualTo("qwen3.5:4b");
        assertThat(result.get().priority()).isEqualTo(1);
    }

    @Test void register_explicit_reasoning_model_overrides_auto() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "auto-ollama", "qwen2.5:72b", "household", 20));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud", "gpt-4o", "cloud", 1));

        var result = registry.resolve("reasoning");
        assertThat(result).isPresent();
        assertThat(result.get().model()).isEqualTo("gpt-4o");
        assertThat(result.get().tier()).isEqualTo("cloud");
    }

    // --- Triage → capability mapping integration ---

    @Test void triage_classifier_tier_maps_to_valid_capabilities() {
        var registry = new CapabilityRegistry();
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "quick", "local", "small-model", "local", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "default", "local", "default-model", "local", 1));
        registry.register(new CapabilityRegistry.CapabilityEntry(
            "reasoning", "cloud", "big-model", "cloud", 1));

        // ROUTINE → quick
        var cap = TriageClassifier.tierToCapability(TriageClassifier.Tier.ROUTINE);
        assertThat(registry.resolve(cap)).isPresent();
        assertThat(registry.resolve(cap).get().model()).isEqualTo("small-model");

        // SIMPLE → default
        cap = TriageClassifier.tierToCapability(TriageClassifier.Tier.SIMPLE);
        assertThat(registry.resolve(cap)).isPresent();
        assertThat(registry.resolve(cap).get().model()).isEqualTo("default-model");

        // COMPLEX → reasoning
        cap = TriageClassifier.tierToCapability(TriageClassifier.Tier.COMPLEX);
        assertThat(registry.resolve(cap)).isPresent();
        assertThat(registry.resolve(cap).get().model()).isEqualTo("big-model");
    }

    // --- inferTier (now public) ---

    @Test void inferTier_local_backends() {
        assertThat(CapabilityRegistry.inferTier(
            new InferenceBackend.Ollama("test", null, 10, List.of()))).isEqualTo("local");
        assertThat(CapabilityRegistry.inferTier(
            new InferenceBackend.LlamaServer("test", null, 10, List.of(), null))).isEqualTo("local");
    }

    @Test void inferTier_household_backends() {
        assertThat(CapabilityRegistry.inferTier(
            new InferenceBackend.VLLM("test", null, 10, List.of()))).isEqualTo("household");
        assertThat(CapabilityRegistry.inferTier(
            new InferenceBackend.SGLang("test", null, 10, List.of()))).isEqualTo("household");
    }

    @Test void inferTier_cloud_backends() {
        assertThat(CapabilityRegistry.inferTier(
            new InferenceBackend.Cloud("test", null, 10, List.of()))).isEqualTo("cloud");
    }
}
