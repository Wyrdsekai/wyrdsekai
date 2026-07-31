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
 * Tests for Experiment 17: Hybrid Soul Retrieval.
 *
 * Framework tests validate experiment structure, retrieval logic, and result formatting.
 * Live test (env-gated) runs against local Ollama.
 */
class HybridSoulExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baseUrl() {
            assertThatThrownBy(() ->
                HybridSoulExperiment.builder()
                    .model("qwen2.5:7b")
                    .embeddingUrl("http://localhost:11434")
                    .embeddingModel("all-minilm")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baseUrl");
        }

        @Test
        void requires_model() {
            assertThatThrownBy(() ->
                HybridSoulExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .embeddingUrl("http://localhost:11434")
                    .embeddingModel("all-minilm")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model");
        }

        @Test
        void requires_embeddingUrl() {
            assertThatThrownBy(() ->
                HybridSoulExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .model("qwen2.5:7b")
                    .embeddingModel("all-minilm")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embeddingUrl");
        }

        @Test
        void requires_embeddingModel() {
            assertThatThrownBy(() ->
                HybridSoulExperiment.builder()
                    .baseUrl("http://localhost:11434/v1")
                    .model("qwen2.5:7b")
                    .embeddingUrl("http://localhost:11434")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("embeddingModel");
        }

        @Test
        void builds_with_minimum_fields() {
            var exp = HybridSoulExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .model("qwen2.5:7b")
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();
            assertThat(exp).isNotNull();
        }

        @Test
        void builds_with_all_fields(@TempDir Path tempDir) {
            var exp = HybridSoulExperiment.builder()
                .baseUrl("http://localhost:11434/v1")
                .model("qwen2.5:7b")
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .outputDir(tempDir)
                .build();
            assertThat(exp).isNotNull();
        }
    }

    // --- Fragment Tests ---

    @Nested
    class FragmentExtraction {

        @Test
        void fragmentDeep_returns_multiple_fragments() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            assertThat(fragments).hasSizeGreaterThanOrEqualTo(3);
        }

        @Test
        void fragmentDeep_includes_identity_core() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            assertThat(fragments).anyMatch(f -> "identity-core".equals(f.id()));
        }

        @Test
        void fragmentDeep_includes_pattern_fragments() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            assertThat(fragments).anyMatch(f -> f.id().startsWith("pattern-"));
        }

        @Test
        void fragmentDeep_includes_style_guide() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            assertThat(fragments).anyMatch(f -> "style-guide".equals(f.id()));
        }

        @Test
        void fragmentDeep_identity_core_matches_medium() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            var medium = SoulExtractor.extract(record, SoulExtractor.Detail.MEDIUM);
            var identityCore = fragments.stream()
                .filter(f -> "identity-core".equals(f.id()))
                .findFirst().orElseThrow();
            assertThat(identityCore.text()).isEqualTo(medium);
        }

        @Test
        void fragmentDeep_has_unique_ids() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            var ids = fragments.stream().map(SoulExtractor.SoulFragment::id).toList();
            assertThat(ids).doesNotHaveDuplicates();
        }

        @Test
        void fragmentDeep_all_fragments_nonempty() {
            var record = makeMockRecord("test");
            var fragments = SoulExtractor.fragmentDeep(record);
            for (var f : fragments) {
                assertThat(f.text()).isNotBlank();
                assertThat(f.label()).isNotBlank();
                assertThat(f.category()).isNotBlank();
            }
        }

        @Test
        void fragmentDeep_empty_record_returns_empty() {
            var empty = new BehavioralRecord("empty", "Wyrd", "model", "prompt",
                null, Instant.now(), List.of());
            var fragments = SoulExtractor.fragmentDeep(empty);
            assertThat(fragments).isEmpty();
        }
    }

    // --- Retrieval Logic Tests ---

    @Nested
    class RetrievalLogic {

        @Test
        void rankFragments_returns_topK() {
            var fragments = List.of(
                new SoulExtractor.SoulFragment("identity-core", "identity", "Core", "core text"),
                new SoulExtractor.SoulFragment("pattern-social", "pattern", "Social", "social text"),
                new SoulExtractor.SoulFragment("pattern-decision", "pattern", "Decision", "decision text"),
                new SoulExtractor.SoulFragment("values", "values", "Values", "values text")
            );
            // Mock embeddings — 3D vectors
            var embeddings = List.of(
                new double[]{1, 0, 0},  // identity-core
                new double[]{0, 1, 0},  // pattern-social
                new double[]{0, 0, 1},  // pattern-decision
                new double[]{0.5, 0.5, 0}  // values
            );
            var query = new double[]{0, 1, 0.1}; // closest to social

            var ranked = HybridSoulExperiment.rankFragments(query, fragments, embeddings, 2);
            assertThat(ranked).hasSize(2);
            // Should NOT include identity-core (skipped)
            assertThat(ranked).noneMatch(f -> "identity-core".equals(f.id()));
            // social should be first (most similar)
            assertThat(ranked.getFirst().id()).isEqualTo("pattern-social");
        }

        @Test
        void rankFragments_excludes_identity_core() {
            var fragments = List.of(
                new SoulExtractor.SoulFragment("identity-core", "identity", "Core", "core"),
                new SoulExtractor.SoulFragment("values", "values", "Values", "values")
            );
            var embeddings = List.of(
                new double[]{1, 0, 0},
                new double[]{0, 1, 0}
            );
            var query = new double[]{1, 0, 0}; // identical to identity-core

            var ranked = HybridSoulExperiment.rankFragments(query, fragments, embeddings, 5);
            assertThat(ranked).noneMatch(f -> "identity-core".equals(f.id()));
        }

        @Test
        void rankFragments_caps_at_available() {
            var fragments = List.of(
                new SoulExtractor.SoulFragment("identity-core", "identity", "Core", "core"),
                new SoulExtractor.SoulFragment("values", "values", "Values", "values")
            );
            var embeddings = List.of(
                new double[]{1, 0},
                new double[]{0, 1}
            );
            var query = new double[]{0.5, 0.5};

            var ranked = HybridSoulExperiment.rankFragments(query, fragments, embeddings, 10);
            assertThat(ranked).hasSize(1); // only 1 non-identity fragment
        }

        @Test
        void buildAugmentedSoul_empty_fragments_returns_medium() {
            var result = HybridSoulExperiment.buildAugmentedSoul("medium soul", List.of());
            assertThat(result).isEqualTo("medium soul");
        }

        @Test
        void buildAugmentedSoul_includes_fragments() {
            var fragments = List.of(
                new SoulExtractor.SoulFragment("values", "values", "Core values", "I value honesty."),
                new SoulExtractor.SoulFragment("pattern-social", "pattern", "Social patterns", "I am warm.")
            );
            var result = HybridSoulExperiment.buildAugmentedSoul("medium soul", fragments);
            assertThat(result).contains("medium soul");
            assertThat(result).contains("Retrieved Context");
            assertThat(result).contains("Core values");
            assertThat(result).contains("I value honesty.");
            assertThat(result).contains("Social patterns");
            assertThat(result).contains("I am warm.");
        }
    }

    // --- Result Format Tests ---

    @Nested
    class ResultFormat {

        @Test
        void summary_contains_header() {
            var result = makeResult();
            assertThat(result.summary()).contains("Experiment 17");
            assertThat(result.summary()).contains("Hybrid");
        }

        @Test
        void summary_contains_all_conditions() {
            var result = makeResult();
            var summary = result.summary();
            assertThat(summary).contains("MEDIUM only");
            assertThat(summary).contains("top-1");
            assertThat(summary).contains("top-3");
            assertThat(summary).contains("top-5");
            assertThat(summary).contains("DEEP flat");
        }

        @Test
        void summary_contains_gates() {
            var result = makeResult();
            var summary = result.summary();
            assertThat(summary).contains("GATE 17A");
            assertThat(summary).contains("GATE 17B");
            assertThat(summary).contains("GATE 17C");
        }

        @Test
        void gate_17a_green_when_hybrid_beats_medium() {
            // MEDIUM 35%, top-3 28% = 7% improvement
            var result = makeResult(0.35, 0.32, 0.28, 0.30, 0.26);
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_17a_red_when_hybrid_no_better() {
            // MEDIUM 30%, all hybrid ≈ 30%
            var result = makeResult(0.30, 0.30, 0.29, 0.30, 0.26);
            var summary = result.summary();
            // Should have RED or YELLOW for 17A
            assertThat(summary).containsAnyOf("RED", "YELLOW");
        }

        @Test
        void gate_17b_green_when_hybrid_matches_deep() {
            // DEEP 26%, hybrid 27% = within 2%
            var result = makeResult(0.35, 0.30, 0.27, 0.28, 0.26);
            var summary = result.summary();
            // 17B should be GREEN (27% vs 26% = 1% gap < 2%)
            assertThat(summary).contains("GREEN");
        }

        @Test
        void summary_contains_exp15_comparison() {
            var result = makeResult();
            var summary = result.summary();
            assertThat(summary).contains("Exp 15");
        }

        @Test
        void summary_contains_trend() {
            var result = makeResult();
            assertThat(result.summary()).contains("TREND");
        }
    }

    // --- Live Test ---

    @Test
    void live_hybrid_retrieval(@TempDir Path tempDir) throws Exception {
        var experimentUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        if (experimentUrl == null || embeddingUrl == null) {
            System.out.println("SKIP: Set SOUL_EXPERIMENT_URL and SOUL_EMBEDDING_URL");
            return;
        }

        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var embModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = HybridSoulExperiment.builder()
            .baseUrl(experimentUrl)
            .model(model)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embModel)
            .outputDir(tempDir)
            .build();

        var result = experiment.run();

        assertThat(result.conditions()).hasSize(5);
        for (var c : result.conditions()) {
            assertThat(c.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(c.report().semanticSimilarity()).isBetween(0.0, 1.0);
            assertThat(c.record().responses()).hasSize(20);
        }
    }

    // --- Helpers ---

    private static BehavioralRecord makeMockRecord(String id) {
        var responses = Scenario.standardSuite().stream()
            .map(s -> new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(),
                "Mock response for " + s.id() + ". This is a detailed response "
                    + "that tests the personality of the agent in " + s.category()
                    + " situations. The agent shows warmth and careful consideration.",
                30, 100))
            .toList();
        return new BehavioralRecord(id, "Wyrd", "test-model", "system prompt",
            null, Instant.now(), responses);
    }

    private static HybridSoulExperiment.HybridResult makeResult() {
        return makeResult(0.30, 0.28, 0.25, 0.27, 0.22);
    }

    private static HybridSoulExperiment.HybridResult makeResult(
            double mediumDiv, double top1Div, double top3Div, double top5Div, double deepDiv) {
        var record = makeMockRecord("test");
        var fragments = SoulExtractor.fragmentDeep(record);
        return new HybridSoulExperiment.HybridResult(
            "qwen2.5:7b", record,
            SoulExtractor.extract(record, SoulExtractor.Detail.MEDIUM),
            SoulExtractor.extract(record, SoulExtractor.Detail.DEEP),
            fragments,
            List.of(
                new HybridSoulExperiment.HybridCondition("MEDIUM only", 0, 71,
                    record, makeMockReport(mediumDiv)),
                new HybridSoulExperiment.HybridCondition("MEDIUM + top-1", 1, 200,
                    record, makeMockReport(top1Div)),
                new HybridSoulExperiment.HybridCondition("MEDIUM + top-3", 3, 450,
                    record, makeMockReport(top3Div)),
                new HybridSoulExperiment.HybridCondition("MEDIUM + top-5", 5, 700,
                    record, makeMockReport(top5Div)),
                new HybridSoulExperiment.HybridCondition("DEEP flat", -1, 3927,
                    record, makeMockReport(deepDiv))
            )
        );
    }

    private static BehavioralMetrics.ComparisonReport makeMockReport(double divergence) {
        return new BehavioralMetrics.ComparisonReport(
            divergence, 1.0 - divergence, 0.0, 0.0,
            Map.of(), Map.of(), 0.0, 0.0, List.of());
    }
}
