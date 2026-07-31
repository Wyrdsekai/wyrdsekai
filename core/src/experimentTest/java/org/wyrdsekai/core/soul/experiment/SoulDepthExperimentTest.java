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
 * Tests for Experiment 15: Soul Depth Sweep.
 *
 * Framework tests validate experiment structure and result formatting.
 * Live test (env-gated) runs the full 5-condition sweep on local Ollama.
 */
class SoulDepthExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baseUrl() {
            assertThatThrownBy(() ->
                SoulDepthExperiment.builder()
                    .model("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
        }

        @Test
        void requires_model() {
            assertThatThrownBy(() ->
                SoulDepthExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
        }

        @Test
        void builds_with_minimum_fields() {
            var exp = SoulDepthExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .model("qwen2.5:7b")
                .build();
            assertThat(exp).isNotNull();
        }

        @Test
        void builds_with_all_fields(@TempDir Path tempDir) {
            var exp = SoulDepthExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .model("qwen2.5:7b")
                .outputDir(tempDir)
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();
            assertThat(exp).isNotNull();
        }
    }

    // --- Result Format Tests ---

    @Nested
    class ResultFormat {

        @Test
        void summary_contains_header() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("Experiment 15");
            assertThat(result.summary()).contains("Soul Depth Sweep");
        }

        @Test
        void summary_contains_all_gates() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("GATE 15A");
            assertThat(result.summary()).contains("GATE 15B");
            assertThat(result.summary()).contains("GATE 15C");
        }

        @Test
        void summary_lists_all_depths() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            var summary = result.summary();
            assertThat(summary).contains("MINIMAL");
            assertThat(summary).contains("MEDIUM");
            assertThat(summary).contains("FULL");
            assertThat(summary).contains("FULL_EXAMPLES");
            assertThat(summary).contains("DEEP");
        }

        @Test
        void summary_contains_trend() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("TREND");
        }

        @Test
        void gate_15a_green_when_deep_beats_full() {
            // FULL 30%, DEEP 20% → 10% improvement
            var result = makeResult(0.40, 0.32, 0.30, 0.22, 0.20);
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_15a_yellow_when_marginal() {
            // FULL 30%, DEEP 27% → 3% improvement
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("YELLOW");
        }

        @Test
        void gate_15a_red_when_no_improvement() {
            // FULL 30%, DEEP 30%
            var result = makeResult(0.40, 0.32, 0.30, 0.31, 0.30);
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void gate_15c_warns_when_depth_hurts() {
            // FULL 30%, DEEP 40% → context pollution
            var result = makeResult(0.40, 0.32, 0.30, 0.35, 0.40);
            assertThat(result.summary()).contains("WARNING");
            assertThat(result.summary()).contains("pollution");
        }

        @Test
        void gate_15c_ok_when_depth_neutral() {
            // FULL 30%, DEEP 29%
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.29);
            assertThat(result.summary()).contains("OK");
        }

        @Test
        void shows_token_efficiency() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("div/tok");
        }

        @Test
        void shows_divergence_bars() {
            var result = makeResult(0.40, 0.32, 0.30, 0.28, 0.27);
            assertThat(result.summary()).contains("█");
        }
    }

    // --- Depth Condition Tests ---

    @Nested
    class DepthConditions {

        @Test
        void condition_records_detail_level() {
            var cond = new SoulDepthExperiment.DepthCondition(
                SoulExtractor.Detail.FULL, 461, 1844,
                makeMockRecord("full"), makeMockReport(0.3));
            assertThat(cond.detail()).isEqualTo(SoulExtractor.Detail.FULL);
        }

        @Test
        void condition_records_token_count() {
            var cond = new SoulDepthExperiment.DepthCondition(
                SoulExtractor.Detail.DEEP, 2000, 8000,
                makeMockRecord("deep"), makeMockReport(0.25));
            assertThat(cond.soulTokens()).isEqualTo(2000);
            assertThat(cond.soulChars()).isEqualTo(8000);
        }
    }

    // --- SoulExtractor New Detail Levels ---

    @Nested
    class ExtractorDepths {

        @Test
        void full_examples_longer_than_full() {
            var record = makeMockRecord("test");
            var full = SoulExtractor.extract(record, SoulExtractor.Detail.FULL);
            var fullExamples = SoulExtractor.extract(record, SoulExtractor.Detail.FULL_EXAMPLES);
            assertThat(fullExamples.length()).isGreaterThan(full.length());
        }

        @Test
        void deep_longer_than_full_examples() {
            var record = makeMockRecord("test");
            var fullExamples = SoulExtractor.extract(record, SoulExtractor.Detail.FULL_EXAMPLES);
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);
            assertThat(deep.length()).isGreaterThan(fullExamples.length());
        }

        @Test
        void deep_contains_core_values_section() {
            var record = makeMockRecord("test");
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);
            assertThat(deep).contains("Core Values");
        }

        @Test
        void deep_contains_episodic_memories_section() {
            var record = makeMockRecord("test");
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);
            assertThat(deep).contains("Episodic Memories");
        }

        @Test
        void deep_contains_style_guide_section() {
            var record = makeMockRecord("test");
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);
            assertThat(deep).contains("Style Guide");
        }

        @Test
        void deep_contains_continuity_anchors() {
            var record = makeMockRecord("test");
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);
            assertThat(deep).contains("Continuity Anchors");
        }

        @Test
        void full_examples_contains_example_exchanges() {
            var record = makeMockRecord("test");
            var fullExamples = SoulExtractor.extract(record, SoulExtractor.Detail.FULL_EXAMPLES);
            assertThat(fullExamples).contains("Extended Example Exchanges");
        }

        @Test
        void all_depths_contain_soul_layer_markers() {
            var record = makeMockRecord("test");
            for (var detail : new SoulExtractor.Detail[]{
                    SoulExtractor.Detail.FULL,
                    SoulExtractor.Detail.FULL_EXAMPLES,
                    SoulExtractor.Detail.DEEP}) {
                var soul = SoulExtractor.extract(record, detail);
                assertThat(soul).contains("SOUL LAYER");
                assertThat(soul).contains("END SOUL LAYER");
            }
        }

        @Test
        void ordering_minimal_lt_medium_lt_full_lt_examples_lt_deep() {
            var record = makeMockRecord("test");
            var minimal = SoulExtractor.extract(record, SoulExtractor.Detail.MINIMAL);
            var medium = SoulExtractor.extract(record, SoulExtractor.Detail.MEDIUM);
            var full = SoulExtractor.extract(record, SoulExtractor.Detail.FULL);
            var examples = SoulExtractor.extract(record, SoulExtractor.Detail.FULL_EXAMPLES);
            var deep = SoulExtractor.extract(record, SoulExtractor.Detail.DEEP);

            assertThat(minimal.length())
                .isLessThan(medium.length())
                .isLessThan(full.length());
            assertThat(full.length()).isLessThan(examples.length());
            assertThat(examples.length()).isLessThan(deep.length());
        }
    }

    // --- Live Test ---

    /**
     * Live soul depth sweep experiment.
     * Requires:
     *   SOUL_EXPERIMENT_URL — Ollama base URL (e.g. http://localhost:11434/v1)
     *   SOUL_EMBEDDING_URL — (optional) Ollama base for embeddings
     *
     * Uses qwen2.5:7b by default, override with SOUL_MODEL.
     * Runs 5 conditions × 20 scenarios = 120 inference calls (~30-60 min on GPU).
     */
    @Test
    void live_depth_sweep(@TempDir Path tempDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        if (url == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL to run");
            return;
        }

        var model = System.getenv().getOrDefault("SOUL_MODEL", "qwen2.5:7b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");

        var experiment = SoulDepthExperiment.builder()
            .baseUrl(url)
            .model(model)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.run();

        // Verify all 5 conditions ran
        assertThat(result.conditions()).hasSize(5);

        // Verify ordering matches detail enum
        assertThat(result.conditions().get(0).detail()).isEqualTo(SoulExtractor.Detail.FULL);
        assertThat(result.conditions().get(1).detail()).isEqualTo(SoulExtractor.Detail.MEDIUM);
        assertThat(result.conditions().get(2).detail()).isEqualTo(SoulExtractor.Detail.MINIMAL);
        assertThat(result.conditions().get(3).detail()).isEqualTo(SoulExtractor.Detail.FULL_EXAMPLES);
        assertThat(result.conditions().get(4).detail()).isEqualTo(SoulExtractor.Detail.DEEP);

        // All divergences should be in valid range
        for (var condition : result.conditions()) {
            assertThat(condition.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(condition.record().responses()).hasSize(20);
        }

        // Summary should be well-formed
        assertThat(result.summary()).contains("GATE 15A");
    }

    // --- Helpers ---

    private static SoulDepthExperiment.DepthResult makeResult(
            double minimalDiv, double mediumDiv, double fullDiv,
            double fullExamplesDiv, double deepDiv) {
        return new SoulDepthExperiment.DepthResult(
            "qwen2.5:7b",
            makeMockRecord("baseline"),
            Map.of(
                SoulExtractor.Detail.MINIMAL, "minimal soul",
                SoulExtractor.Detail.MEDIUM, "medium soul text with more details",
                SoulExtractor.Detail.FULL, "full soul text " + "x".repeat(400),
                SoulExtractor.Detail.FULL_EXAMPLES, "full examples " + "x".repeat(900),
                SoulExtractor.Detail.DEEP, "deep soul " + "x".repeat(1800)
            ),
            List.of(
                new SoulDepthExperiment.DepthCondition(SoulExtractor.Detail.MINIMAL, 18, 72,
                    makeMockRecord("minimal"), makeMockReport(minimalDiv)),
                new SoulDepthExperiment.DepthCondition(SoulExtractor.Detail.MEDIUM, 71, 284,
                    makeMockRecord("medium"), makeMockReport(mediumDiv)),
                new SoulDepthExperiment.DepthCondition(SoulExtractor.Detail.FULL, 461, 1844,
                    makeMockRecord("full"), makeMockReport(fullDiv)),
                new SoulDepthExperiment.DepthCondition(SoulExtractor.Detail.FULL_EXAMPLES, 1000, 4000,
                    makeMockRecord("examples"), makeMockReport(fullExamplesDiv)),
                new SoulDepthExperiment.DepthCondition(SoulExtractor.Detail.DEEP, 2000, 8000,
                    makeMockRecord("deep"), makeMockReport(deepDiv))
            )
        );
    }

    private static BehavioralRecord makeMockRecord(String id) {
        var responses = Scenario.standardSuite().stream()
            .map(s -> new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(),
                "Mock response for " + s.id() + " with some words to analyze and test the behavioral metrics properly", 15, 100))
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
