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
 * Tests for Experiment 14: PCL Contrastive Self-Play DPO.
 *
 * Framework tests validate the experiment structure and result formatting.
 * Live tests (env-gated) run the actual 4-condition comparison on gpu-host.
 */
class PCLExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baseUrl() {
            assertThatThrownBy(() ->
                PCLExperiment.builder()
                    .baseModel("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
        }

        @Test
        void requires_baseModel() {
            assertThatThrownBy(() ->
                PCLExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseModel");
        }

        @Test
        void requires_baselineModel() {
            assertThatThrownBy(() ->
                PCLExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .baseModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineModel");
        }

        @Test
        void builds_with_required_fields() {
            var experiment = PCLExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();

            assertThat(experiment).isNotNull();
        }

        @Test
        void builds_with_all_optional_fields() {
            var experiment = PCLExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .pclModel("wyrd-pcl:7b")
                .baselineModel("qwen2.5:7b")
                .systemPrompt("custom prompt")
                .scenarios(Scenario.standardSuite().subList(0, 5))
                .outputDir(Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai-test"))
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();

            assertThat(experiment).isNotNull();
        }

        @Test
        void pclModel_is_optional() {
            // Should build without pclModel — runs only naked + prompt conditions
            var experiment = PCLExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .baseModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();

            assertThat(experiment).isNotNull();
        }
    }

    // --- Result Format Tests ---

    @Nested
    class ResultFormat {

        @Test
        void summary_includes_all_conditions() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.25), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.22), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);

            var summary = result.summary();
            assertThat(summary).contains("Experiment 14");
            assertThat(summary).contains("Naked");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("PCL-DPO only");
            assertThat(summary).contains("PCL + prompt");
            assertThat(summary).contains("GATE 14A");
            assertThat(summary).contains("GATE 14B");
            assertThat(summary).contains("GATE 14C");
        }

        @Test
        void gate_14a_green_when_pcl_beats_prompt() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.22), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.18), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("GREEN");
            assertThat(summary).contains("Personality lives in DPO weights");
        }

        @Test
        void gate_14a_red_when_prompt_wins() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.40), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.35), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("RED");
            assertThat(summary).contains("DPO insufficient");
        }

        @Test
        void gate_14a_yellow_when_tied() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.31), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.28), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("YELLOW");
            assertThat(summary).contains("DPO matches prompt");
        }

        @Test
        void gate_14b_green_when_compounding() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.28), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.20), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            // Gate 14B should be green
            assertThat(summary).contains("DPO and prompt compound");
        }

        @Test
        void gate_14c_green_when_pcl_improves_over_naked() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.35), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.28), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            // 45% → 35% = 10% improvement > 5% threshold
            assertThat(summary).contains("PCL self-play fixes DPO");
        }

        @Test
        void gate_14c_red_when_no_improvement() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465),
                new PCLExperiment.ConditionResult("PCL-DPO only", makeMockRecord("pcl"),
                    makeMockReport(0.46), 0),
                new PCLExperiment.ConditionResult("PCL + prompt", makeMockRecord("both"),
                    makeMockReport(0.35), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", "wyrd-pcl:7b",
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("DPO fundamentally unsuited");
        }

        @Test
        void summary_handles_missing_pcl_model() {
            var conditions = List.of(
                new PCLExperiment.ConditionResult("Naked", makeMockRecord("naked"),
                    makeMockReport(0.45), 0),
                new PCLExperiment.ConditionResult("Prompt only", makeMockRecord("prompt"),
                    makeMockReport(0.30), 465)
            );

            var result = new PCLExperiment.ComparisonResult(
                "qwen2.5:7b", "qwen2.5:7b", null,
                makeMockRecord("baseline"), "soul text", conditions);
            var summary = result.summary();

            assertThat(summary).contains("SKIP");
        }
    }

    // --- Condition Result Tests ---

    @Nested
    class ConditionResults {

        @Test
        void condition_records_prompt_tokens() {
            var cr = new PCLExperiment.ConditionResult(
                "Prompt only", makeMockRecord("test"), makeMockReport(0.30), 465);

            assertThat(cr.condition()).isEqualTo("Prompt only");
            assertThat(cr.promptTokens()).isEqualTo(465);
            assertThat(cr.report().overallDivergence()).isCloseTo(0.30, within(0.01));
        }

        @Test
        void pcl_dpo_only_has_zero_prompt_tokens() {
            var cr = new PCLExperiment.ConditionResult(
                "PCL-DPO only", makeMockRecord("test"), makeMockReport(0.25), 0);

            assertThat(cr.promptTokens()).isEqualTo(0);
        }
    }

    // --- Defaults ---

    @Nested
    class Defaults {

        @Test
        void default_scenarios_are_standard_suite() {
            var scenarios = Scenario.standardSuite();
            assertThat(scenarios).hasSize(20);
        }

        @Test
        void default_system_prompt_matches_soul_experiment() {
            assertThat(SoulExperiment.DEFAULT_AGENT_PROMPT).contains("Wyrd");
            assertThat(SoulExperiment.DEFAULT_AGENT_PROMPT).contains("companion");
        }
    }

    // --- Live Tests (env-gated) ---

    /**
     * Live 4-condition PCL comparison.
     * Requires:
     *   SOUL_EXPERIMENT_URL — Ollama base URL (e.g., http://localhost:11434/v1)
     *   SOUL_PCL_MODEL — PCL-DPO trained model name in Ollama (e.g., wyrd-pcl:7b)
     *   SOUL_EMBEDDING_URL — Ollama base for embeddings (optional)
     *
     * Run on gpu-host after:
     *   1. generate_selfplay.py → self-play pairs
     *   2. train_pcl_dpo.py → DPO adapter + GGUF
     *   3. register_ollama.sh → register with Ollama
     */
    @Test
    void live_pcl_comparison(@TempDir Path tempDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var pclModel = System.getenv("SOUL_PCL_MODEL");
        if (url == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL to run");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var model = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");

        var experiment = PCLExperiment.builder()
            .baseUrl(url)
            .baseModel(model)
            .pclModel(pclModel)
            .baselineModel(model)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.run();

        // Verify structure
        if (pclModel != null) {
            assertThat(result.conditions()).hasSize(4);
        } else {
            assertThat(result.conditions()).hasSize(2);
        }
        assertThat(result.summary()).contains("GATE 14A");

        // All conditions produced valid results
        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(condition.record().responses()).hasSize(20);
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
