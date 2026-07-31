package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Experiment 10: Persona Vectors + LoRA Distillation.
 *
 * Framework tests validate the experiment structure and result formatting.
 * Live tests (env-gated) run the actual 4-condition comparison on gpu-host.
 */
class PersonaVectorExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baseUrl() {
            assertThatThrownBy(() ->
                PersonaVectorExperiment.builder()
                    .loraUrl("http://localhost:8200/v1")
                    .model("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
        }

        @Test
        void requires_loraUrl() {
            assertThatThrownBy(() ->
                PersonaVectorExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .model("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loraUrl");
        }

        @Test
        void requires_model() {
            assertThatThrownBy(() ->
                PersonaVectorExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .loraUrl("http://localhost:8200/v1")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
        }

        @Test
        void requires_baselineModel() {
            assertThatThrownBy(() ->
                PersonaVectorExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .loraUrl("http://localhost:8200/v1")
                    .model("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineModel");
        }

        @Test
        void builds_with_all_required_fields() {
            var experiment = PersonaVectorExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .loraUrl("http://localhost:8200/v1")
                .model("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();

            assertThat(experiment).isNotNull();
        }

        @Test
        void builds_with_all_optional_fields() {
            var experiment = PersonaVectorExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .loraUrl("http://localhost:8200/v1")
                .model("qwen2.5:7b")
                .loraModel("custom-qwen")
                .baselineModel("qwen2.5:7b")
                .systemPrompt("custom system prompt")
                .scenarios(Scenario.standardSuite().subList(0, 5))
                .adversarialScenarios(AdversarialScenario.standardSuite().subList(0, 3))
                .outputDir(Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai-test"))
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();

            assertThat(experiment).isNotNull();
        }
    }

    // --- Result Format Tests ---

    @Nested
    class ResultFormat {

        @Test
        void comparison_result_summary_includes_all_conditions() {
            var baseline = makeMockRecord("baseline");
            var conditions = List.of(
                new PersonaVectorExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PersonaVectorExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PersonaVectorExperiment.ConditionResult("LoRA only", makeMockRecord("lora"),
                    makeMockReport(0.22), 0),
                new PersonaVectorExperiment.ConditionResult("LoRA + prompt", makeMockRecord("both"),
                    makeMockReport(0.18), 465)
            );

            var result = new PersonaVectorExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", baseline, "soul text", conditions);

            var summary = result.summary();
            assertThat(summary).contains("Experiment 10");
            assertThat(summary).contains("Naked");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("LoRA only");
            assertThat(summary).contains("LoRA + prompt");
            assertThat(summary).contains("GATE 10A");
            assertThat(summary).contains("GATE 10B");
        }

        @Test
        void summary_shows_green_when_lora_wins() {
            var baseline = makeMockRecord("baseline");
            var conditions = List.of(
                new PersonaVectorExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PersonaVectorExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PersonaVectorExperiment.ConditionResult("LoRA only", makeMockRecord("lora"),
                    makeMockReport(0.20), 0),
                new PersonaVectorExperiment.ConditionResult("LoRA + prompt", makeMockRecord("both"),
                    makeMockReport(0.15), 465)
            );

            var result = new PersonaVectorExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", baseline, "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("GREEN");
            assertThat(summary).contains("Personality lives in weights");
            assertThat(summary).contains("LoRA reinforces prompt");
        }

        @Test
        void summary_shows_red_when_lora_loses() {
            var baseline = makeMockRecord("baseline");
            var conditions = List.of(
                new PersonaVectorExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PersonaVectorExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PersonaVectorExperiment.ConditionResult("LoRA only", makeMockRecord("lora"),
                    makeMockReport(0.40), 0),
                new PersonaVectorExperiment.ConditionResult("LoRA + prompt", makeMockRecord("both"),
                    makeMockReport(0.35), 465)
            );

            var result = new PersonaVectorExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", baseline, "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("RED");
            assertThat(summary).contains("LoRA alone insufficient");
        }

        @Test
        void summary_detects_lora_hurting_personality() {
            var baseline = makeMockRecord("baseline");
            var conditions = List.of(
                new PersonaVectorExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PersonaVectorExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PersonaVectorExperiment.ConditionResult("LoRA only", makeMockRecord("lora"),
                    makeMockReport(0.42), 0),
                new PersonaVectorExperiment.ConditionResult("LoRA + prompt", makeMockRecord("both"),
                    makeMockReport(0.40), 465)
            );

            var result = new PersonaVectorExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", baseline, "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("HURTS");
        }

        @Test
        void adversarial_result_summary_includes_gate() {
            var conditions = List.of(
                new PersonaVectorExperiment.AdversarialCondition("Prompt only", 0.40),
                new PersonaVectorExperiment.AdversarialCondition("LoRA only", 0.20),
                new PersonaVectorExperiment.AdversarialCondition("LoRA + prompt", 0.10)
            );

            var result = new PersonaVectorExperiment.AdversarialResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("GATE 10C");
            assertThat(summary).contains("GREEN");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("LoRA only");
            assertThat(summary).contains("LoRA + prompt");
        }

        @Test
        void adversarial_red_when_asr_high() {
            var conditions = List.of(
                new PersonaVectorExperiment.AdversarialCondition("Prompt only", 0.50),
                new PersonaVectorExperiment.AdversarialCondition("LoRA only", 0.50),
                new PersonaVectorExperiment.AdversarialCondition("LoRA + prompt", 0.45)
            );

            var result = new PersonaVectorExperiment.AdversarialResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("RED");
        }
    }

    // --- Condition Result Tests ---

    @Nested
    class ConditionResults {

        @Test
        void condition_result_records_prompt_tokens() {
            var cr = new PersonaVectorExperiment.ConditionResult(
                "Prompt only", makeMockRecord("test"), makeMockReport(0.30), 465);

            assertThat(cr.condition()).isEqualTo("Prompt only");
            assertThat(cr.promptTokens()).isEqualTo(465);
            assertThat(cr.report().overallDivergence()).isCloseTo(0.30, within(0.01));
        }

        @Test
        void lora_only_has_zero_prompt_tokens() {
            var cr = new PersonaVectorExperiment.ConditionResult(
                "LoRA only", makeMockRecord("test"), makeMockReport(0.25), 0);

            assertThat(cr.promptTokens()).isEqualTo(0);
        }

        @Test
        void adversarial_condition_records_asr() {
            var ac = new PersonaVectorExperiment.AdversarialCondition("LoRA only", 0.15);

            assertThat(ac.condition()).isEqualTo("LoRA only");
            assertThat(ac.asr()).isCloseTo(0.15, within(0.01));
        }
    }

    // --- Default Configuration ---

    @Nested
    class Defaults {

        @Test
        void default_scenarios_are_standard_suite() {
            // Builder should default to Scenario.standardSuite()
            var scenarios = Scenario.standardSuite();
            assertThat(scenarios).hasSize(20);
        }

        @Test
        void default_adversarial_scenarios_available() {
            var advScenarios = AdversarialScenario.standardSuite();
            assertThat(advScenarios).isNotEmpty();
            assertThat(advScenarios.size()).isGreaterThanOrEqualTo(10);
        }

        @Test
        void default_system_prompt_matches_soul_experiment() {
            // Verify consistency with other experiments
            assertThat(SoulExperiment.DEFAULT_AGENT_PROMPT).contains("Wyrd");
            assertThat(SoulExperiment.DEFAULT_AGENT_PROMPT).contains("companion");
        }
    }

    // --- Live Tests (env-gated) ---

    /**
     * Live 4-condition comparison.
     * Requires:
     *   SOUL_EXPERIMENT_URL — Ollama base URL (e.g., http://localhost:11434/v1)
     *   SOUL_LORA_URL — llama-server with LoRA (e.g., http://localhost:8200/v1)
     *   SOUL_EMBEDDING_URL — Ollama base for embeddings (optional)
     *
     * Run on gpu-host after:
     *   1. extract_vectors.py → persona vectors
     *   2. distill_lora.py → LoRA adapter
     *   3. convert_lora_to_gguf.py → GGUF adapter
     *   4. launch_servers.sh → llama-server on port 8200
     */
    @Test
    void live_persona_vector_comparison(@TempDir Path tempDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var loraUrl = System.getenv("SOUL_LORA_URL");
        if (url == null || loraUrl == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL and SOUL_LORA_URL to run");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var model = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");
        var loraModel = System.getenv().getOrDefault("SOUL_LORA_MODEL", model);

        var experiment = PersonaVectorExperiment.builder()
            .baseUrl(url)
            .loraUrl(loraUrl)
            .model(model)
            .loraModel(loraModel)
            .baselineModel(model)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.runComparison();

        // Verify structure
        assertThat(result.conditions()).hasSize(4);
        assertThat(result.summary()).contains("GATE 10A");
        assertThat(result.summary()).contains("GATE 10B");

        // Verify all conditions produced valid results
        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(condition.record().responses()).hasSize(20);
        }
    }

    /**
     * Live adversarial robustness test.
     * Requires same env vars as live_persona_vector_comparison.
     */
    @Test
    void live_persona_vector_adversarial(@TempDir Path tempDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var loraUrl = System.getenv("SOUL_LORA_URL");
        if (url == null || loraUrl == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL and SOUL_LORA_URL to run");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var model = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");
        var loraModel = System.getenv().getOrDefault("SOUL_LORA_MODEL", model);

        var experiment = PersonaVectorExperiment.builder()
            .baseUrl(url)
            .loraUrl(loraUrl)
            .model(model)
            .loraModel(loraModel)
            .baselineModel(model)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.runAdversarial();

        assertThat(result.conditions()).hasSize(3);
        assertThat(result.summary()).contains("GATE 10C");
        for (var condition : result.conditions()) {
            assertThat(condition.asr()).isBetween(0.0, 1.0);
        }
    }

    // --- Helpers ---

    private static BehavioralRecord makeMockRecord(String id) {
        var responses = List.of(
            new BehavioralRecord.ScenarioResponse(
                "social-01", "social", "Hello there!",
                "Greetings, traveler.", 5, 100)
        );
        return new BehavioralRecord(id, "Wyrd", "test-model", "system prompt",
            null, Instant.now(), responses);
    }

    private static BehavioralMetrics.ComparisonReport makeMockReport(double divergence) {
        return new BehavioralMetrics.ComparisonReport(
            divergence, 1.0 - divergence, 0.8, divergence,
            Map.of("social", divergence),
            Map.of("social", 1.0 - divergence),
            0.7, 0.8, List.of()
        );
    }
}
