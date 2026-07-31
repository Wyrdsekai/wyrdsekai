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
 * Tests for Experiment 11: Hybrid Architecture (Jamba) Personality.
 */
class HybridArchExperimentTest {

    @Nested
    class BuilderValidation {

        @Test
        void requires_arUrl() {
            assertThatThrownBy(() ->
                HybridArchExperiment.builder()
                    .hybridUrl("http://localhost:8300/v1")
                    .arModel("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arUrl");
        }

        @Test
        void requires_hybridUrl() {
            assertThatThrownBy(() ->
                HybridArchExperiment.builder()
                    .arUrl("http://localhost:11434/v1")
                    .arModel("qwen2.5:7b")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hybridUrl");
        }

        @Test
        void requires_arModel() {
            assertThatThrownBy(() ->
                HybridArchExperiment.builder()
                    .arUrl("http://localhost:11434/v1")
                    .hybridUrl("http://localhost:8300/v1")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arModel");
        }

        @Test
        void requires_baselineModel() {
            assertThatThrownBy(() ->
                HybridArchExperiment.builder()
                    .arUrl("http://localhost:11434/v1")
                    .hybridUrl("http://localhost:8300/v1")
                    .arModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineModel");
        }

        @Test
        void builds_with_required_fields() {
            var experiment = HybridArchExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .hybridUrl("http://localhost:8300/v1")
                .arModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build();

            assertThat(experiment).isNotNull();
        }

        @Test
        void builds_with_all_optional_fields() {
            var experiment = HybridArchExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .hybridUrl("http://localhost:8300/v1")
                .arModel("qwen2.5:7b")
                .hybridModel("jamba-1.5-mini")
                .baselineModel("qwen2.5:7b")
                .systemPrompt("custom prompt")
                .scenarios(Scenario.standardSuite().subList(0, 5))
                .outputDir(Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai-test"))
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();

            assertThat(experiment).isNotNull();
        }
    }

    @Nested
    class ResultFormat {

        @Test
        void summary_includes_all_conditions() {
            var conditions = makeConditions(0.45, 0.30, 0.40, 0.25);
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("Experiment 11");
            assertThat(summary).contains("AR naked");
            assertThat(summary).contains("AR prompt");
            assertThat(summary).contains("Hybrid naked");
            assertThat(summary).contains("Hybrid prompt");
            assertThat(summary).contains("GATE 11A");
            assertThat(summary).contains("GATE 11B");
        }

        @Test
        void gate_11a_green_when_hybrid_wins() {
            var conditions = makeConditions(0.45, 0.35, 0.40, 0.25);
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("GREEN");
            assertThat(summary).contains("Hybrid architecture improves");
        }

        @Test
        void gate_11a_yellow_when_tied() {
            var conditions = makeConditions(0.45, 0.35, 0.40, 0.33);
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("YELLOW");
            assertThat(summary).contains("No personality advantage");
        }

        @Test
        void gate_11a_red_when_hybrid_loses() {
            var conditions = makeConditions(0.45, 0.30, 0.50, 0.45);
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("RED");
            assertThat(summary).contains("Hybrid architecture hurts");
        }

        @Test
        void gate_11b_green_when_quality_ok() {
            var conditions = makeConditions(0.45, 0.35, 0.40, 0.30);
            var result = makeResult(conditions);
            var summary = result.summary();

            // div=30% < 40% and semantic=70% > 50%
            assertThat(summary).contains("coherent");
        }

        @Test
        void gate_11b_red_when_poor_quality() {
            // High divergence, low semantic
            var conditions = List.of(
                new HybridArchExperiment.ConditionResult("AR naked", "qwen2.5:7b",
                    makeMockRecord("ar-naked"), makeMockReport(0.45), 0),
                new HybridArchExperiment.ConditionResult("AR prompt", "qwen2.5:7b",
                    makeMockRecord("ar-prompt"), makeMockReport(0.30), 465),
                new HybridArchExperiment.ConditionResult("Hybrid naked", "jamba-mini",
                    makeMockRecord("h-naked"), makeMockReport(0.60), 0),
                new HybridArchExperiment.ConditionResult("Hybrid prompt", "jamba-mini",
                    makeMockRecord("h-prompt"),
                    new BehavioralMetrics.ComparisonReport(0.55, 0.40, 0.5, 0.55,
                        Map.of("social", 0.55), Map.of("social", 0.40),
                        0.4, 0.5, List.of()),
                    465)
            );
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("poor quality");
        }

        @Test
        void summary_shows_prompt_injection_delta() {
            var conditions = makeConditions(0.45, 0.30, 0.40, 0.25);
            var result = makeResult(conditions);
            var summary = result.summary();

            assertThat(summary).contains("Prompt Injection Effect");
            assertThat(summary).contains("improvement");
        }
    }

    @Nested
    class ConditionResults {

        @Test
        void condition_records_model_name() {
            var cr = new HybridArchExperiment.ConditionResult(
                "Hybrid prompt", "jamba-mini",
                makeMockRecord("test"), makeMockReport(0.25), 465);

            assertThat(cr.model()).isEqualTo("jamba-mini");
            assertThat(cr.promptTokens()).isEqualTo(465);
        }
    }

    // --- Live Test ---

    /**
     * Live hybrid architecture comparison.
     * Requires:
     *   SOUL_EXPERIMENT_URL — Ollama base URL
     *   SOUL_JAMBA_URL — Jamba serve.py URL (e.g., http://localhost:8300/v1)
     *   SOUL_EMBEDDING_URL — Ollama base for embeddings (optional)
     */
    @Test
    void live_hybrid_comparison(@TempDir Path tempDir) throws Exception {
        var arUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var jambaUrl = System.getenv("SOUL_JAMBA_URL");
        if (arUrl == null || jambaUrl == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL and SOUL_JAMBA_URL to run");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var arModel = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");
        var jambaModel = System.getenv().getOrDefault("SOUL_JAMBA_MODEL", "jamba-mini");

        var experiment = HybridArchExperiment.builder()
            .arUrl(arUrl)
            .hybridUrl(jambaUrl)
            .arModel(arModel)
            .hybridModel(jambaModel)
            .baselineModel(arModel)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.run();

        assertThat(result.conditions()).hasSize(4);
        assertThat(result.summary()).contains("GATE 11A");
        assertThat(result.summary()).contains("GATE 11B");

        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(condition.record().responses()).hasSize(20);
        }
    }

    // --- Helpers ---

    private static List<HybridArchExperiment.ConditionResult> makeConditions(
            double arNakedDiv, double arPromptDiv, double hybridNakedDiv, double hybridPromptDiv) {
        return List.of(
            new HybridArchExperiment.ConditionResult("AR naked", "qwen2.5:7b",
                makeMockRecord("ar-naked"), makeMockReport(arNakedDiv), 0),
            new HybridArchExperiment.ConditionResult("AR prompt", "qwen2.5:7b",
                makeMockRecord("ar-prompt"), makeMockReport(arPromptDiv), 465),
            new HybridArchExperiment.ConditionResult("Hybrid naked", "jamba-mini",
                makeMockRecord("h-naked"), makeMockReport(hybridNakedDiv), 0),
            new HybridArchExperiment.ConditionResult("Hybrid prompt", "jamba-mini",
                makeMockRecord("h-prompt"), makeMockReport(hybridPromptDiv), 465)
        );
    }

    private static HybridArchExperiment.ComparisonResult makeResult(
            List<HybridArchExperiment.ConditionResult> conditions) {
        return new HybridArchExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:7b", "jamba-mini",
            makeMockRecord("baseline"), "soul text", conditions);
    }

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
