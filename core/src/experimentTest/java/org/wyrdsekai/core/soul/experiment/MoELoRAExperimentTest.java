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
 * Tests for Experiment 13: MoE-LoRA Trait-Modular Personality.
 *
 * Framework tests validate the experiment structure and result formatting.
 * Live tests (env-gated) run the actual comparison on gpu-host.
 */
class MoELoRAExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baseUrl() {
            assertThatThrownBy(() ->
                MoELoRAExperiment.builder()
                    .baseModel("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
        }

        @Test
        void requires_baseModel() {
            assertThatThrownBy(() ->
                MoELoRAExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseModel");
        }

        @Test
        void requires_baselineModel() {
            assertThatThrownBy(() ->
                MoELoRAExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .baseModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineModel");
        }

        @Test
        void builds_with_minimum_fields() {
            var exp = MoELoRAExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();
            assertThat(exp).isNotNull();
        }

        @Test
        void builds_with_all_fields() {
            var exp = MoELoRAExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .moeDefaultModel("wyrd-moe")
                .moeHighPhilModel("wyrd-moe-phil")
                .moeHighWarmModel("wyrd-moe-warm")
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();
            assertThat(exp).isNotNull();
        }

        @Test
        void moe_models_optional() {
            var exp = MoELoRAExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();
            assertThat(exp).isNotNull();
        }
    }

    // --- Result Format Tests ---

    @Nested
    class ResultFormat {

        @Test
        void summary_contains_header() {
            var result = makeResult(0.35, 0.32, 0.30, 0.28, 0.33, 0.31);
            assertThat(result.summary()).contains("Experiment 13");
            assertThat(result.summary()).contains("MoE-LoRA");
        }

        @Test
        void summary_contains_all_gates() {
            var result = makeResult(0.35, 0.32, 0.30, 0.28, 0.33, 0.31);
            assertThat(result.summary()).contains("GATE 13A");
            assertThat(result.summary()).contains("GATE 13B");
            assertThat(result.summary()).contains("GATE 13C");
        }

        @Test
        void gate_13a_green_when_moe_beats_prompt() {
            // MoE 25% < prompt 32%
            var result = makeResult(0.35, 0.32, 0.25, 0.22, 0.27, 0.26);
            assertThat(result.summary()).contains("GATE 13A");
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_13a_yellow_when_tied() {
            // MoE 31% ≈ prompt 32%
            var result = makeResult(0.35, 0.32, 0.31, 0.29, 0.33, 0.30);
            assertThat(result.summary()).contains("GATE 13A");
            assertThat(result.summary()).contains("YELLOW");
        }

        @Test
        void gate_13a_red_when_moe_worse() {
            // MoE 40% > prompt 32%
            var result = makeResult(0.35, 0.32, 0.40, 0.38, 0.42, 0.39);
            assertThat(result.summary()).contains("GATE 13A");
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void gate_13b_green_when_spread_high() {
            // phil=20%, warm=35%, default=30% → spread 15%
            var result = makeResult(0.35, 0.32, 0.30, 0.28, 0.20, 0.35);
            assertThat(result.summary()).contains("GATE 13B");
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_13b_red_when_spread_low() {
            // phil=31%, warm=32%, default=30% → spread 2%
            var result = makeResult(0.35, 0.32, 0.30, 0.28, 0.31, 0.32);
            assertThat(result.summary()).contains("GATE 13B");
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void gate_13c_green_when_compounding() {
            // MoE+prompt 22% < best-single 28%
            var result = makeResult(0.35, 0.32, 0.28, 0.22, 0.30, 0.29);
            assertThat(result.summary()).contains("GATE 13C");
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_13c_red_when_no_compounding() {
            // MoE+prompt 30% ≈ best-single 30%
            var result = makeResult(0.35, 0.32, 0.30, 0.30, 0.31, 0.32);
            assertThat(result.summary()).contains("GATE 13C");
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void summary_lists_all_conditions() {
            var result = makeResult(0.35, 0.32, 0.30, 0.28, 0.25, 0.33);
            var summary = result.summary();
            assertThat(summary).contains("Naked");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("MoE default");
            assertThat(summary).contains("MoE + prompt");
            assertThat(summary).contains("MoE high-phil");
            assertThat(summary).contains("MoE high-warm");
        }
    }

    // --- Condition Tests ---

    @Nested
    class ConditionResults {

        @Test
        void condition_records_model_name() {
            var cr = new MoELoRAExperiment.ConditionResult(
                "test", "wyrd-moe",
                makeMockRecord("test"), makeMockReport(0.3), 0);
            assertThat(cr.model()).isEqualTo("wyrd-moe");
        }

        @Test
        void condition_records_prompt_tokens() {
            var cr = new MoELoRAExperiment.ConditionResult(
                "test", "model",
                makeMockRecord("test"), makeMockReport(0.3), 458);
            assertThat(cr.promptTokens()).isEqualTo(458);
        }
    }

    // --- Live Test ---

    /**
     * Live MoE-LoRA experiment.
     * Requires:
     *   SOUL_EXPERIMENT_URL — Ollama base URL
     *   SOUL_MOE_MODEL — MoE default model name in Ollama
     *   SOUL_MOE_PHIL_MODEL — (optional) MoE high-philosophical model
     *   SOUL_MOE_WARM_MODEL — (optional) MoE high-warm model
     *   SOUL_EMBEDDING_URL — Ollama base for embeddings (optional)
     */
    @Test
    void live_moe_comparison(@TempDir Path tempDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var moeModel = System.getenv("SOUL_MOE_MODEL");
        if (url == null || moeModel == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL and SOUL_MOE_MODEL to run");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var baseModel = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");
        var philModel = System.getenv("SOUL_MOE_PHIL_MODEL");
        var warmModel = System.getenv("SOUL_MOE_WARM_MODEL");

        var experiment = MoELoRAExperiment.builder()
            .baseUrl(url)
            .baseModel(baseModel)
            .baselineModel(baseModel)
            .moeDefaultModel(moeModel)
            .moeHighPhilModel(philModel)
            .moeHighWarmModel(warmModel)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.run();

        assertThat(result.conditions()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(result.summary()).contains("GATE 13A");

        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(condition.record().responses()).hasSize(20);
        }
    }

    // --- Helpers ---

    private static MoELoRAExperiment.ComparisonResult makeResult(
            double nakedDiv, double promptDiv, double moeDefaultDiv,
            double moePlusPromptDiv, double highPhilDiv, double highWarmDiv) {
        return new MoELoRAExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:7b", "wyrd-moe",
            makeMockRecord("baseline"), "soul text",
            List.of(
                new MoELoRAExperiment.ConditionResult("Naked", "qwen2.5:7b",
                    makeMockRecord("naked"), makeMockReport(nakedDiv), 0),
                new MoELoRAExperiment.ConditionResult("Prompt only", "qwen2.5:7b",
                    makeMockRecord("prompt"), makeMockReport(promptDiv), 458),
                new MoELoRAExperiment.ConditionResult("MoE default", "wyrd-moe",
                    makeMockRecord("moe-def"), makeMockReport(moeDefaultDiv), 0),
                new MoELoRAExperiment.ConditionResult("MoE + prompt", "wyrd-moe",
                    makeMockRecord("moe-prompt"), makeMockReport(moePlusPromptDiv), 458),
                new MoELoRAExperiment.ConditionResult("MoE high-phil", "wyrd-moe-phil",
                    makeMockRecord("moe-phil"), makeMockReport(highPhilDiv), 0),
                new MoELoRAExperiment.ConditionResult("MoE high-warm", "wyrd-moe-warm",
                    makeMockRecord("moe-warm"), makeMockReport(highWarmDiv), 0)
            )
        );
    }

    private static BehavioralRecord makeMockRecord(String id) {
        var responses = Scenario.standardSuite().stream()
            .map(s -> new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(),
                "Mock response for " + s.id(), 10, 100))
            .toList();
        return new BehavioralRecord(id, "Wyrd", "test-model", "system prompt",
            null, Instant.now(), responses);
    }

    private static BehavioralMetrics.ComparisonReport makeMockReport(double divergence) {
        return new BehavioralMetrics.ComparisonReport(
            divergence, 1.0 - divergence, 0.0, 0.0,
            Map.of(), Map.of(), 0.0, 0.0, List.of());
    }
}
