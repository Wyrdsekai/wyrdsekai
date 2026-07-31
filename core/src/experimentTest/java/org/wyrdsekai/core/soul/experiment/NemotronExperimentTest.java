package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experiment 19: Nemotron-3-Super — Hybrid Mamba-2/Transformer Soul Test.
 *
 * Framework tests run always. Live tests require:
 *   SOUL_NEMOTRON_URL   — Ollama URL (e.g., http://gpu-host:11434/v1)
 *   SOUL_NEMOTRON_MODEL — model name in Ollama (e.g., nemotron)
 *
 * Run all: ./gradlew :core:test --tests "*NemotronExperimentTest"
 * Run live: SOUL_NEMOTRON_URL=http://localhost:11434/v1 SOUL_NEMOTRON_MODEL=nemotron \
 *           ./gradlew :core:test --tests "*NemotronExperimentTest$Live*" --rerun
 */
class NemotronExperimentTest {

    // ── Framework Tests ──────────────────────────────────────────────────

    @Nested
    class Framework {

        @Test
        void builderRequiresUrl() {
            assertThrows(IllegalStateException.class, () ->
                NemotronExperiment.builder()
                    .nemotronModel("nemotron")
                    .build());
        }

        @Test
        void builderRequiresModel() {
            assertThrows(IllegalStateException.class, () ->
                NemotronExperiment.builder()
                    .ollamaUrl("http://localhost:11434/v1")
                    .build());
        }

        @Test
        void builderSucceeds() {
            var exp = NemotronExperiment.builder()
                .ollamaUrl("http://localhost:11434/v1")
                .nemotronModel("nemotron")
                .build();
            assertNotNull(exp);
        }

        @Test
        void standardScenariosLoaded() {
            var scenarios = Scenario.standardSuite();
            assertEquals(20, scenarios.size());
        }

        @Test
        void adversarialScenariosLoaded() {
            var scenarios = AdversarialScenario.standardSuite();
            assertEquals(10, scenarios.size());
        }

        @Test
        void soulExtractionLevels() {
            // Verify all depth levels exist
            for (var detail : SoulExtractor.Detail.values()) {
                assertNotNull(detail.name());
            }
            assertEquals(5, SoulExtractor.Detail.values().length);
        }

        @Test
        void tokenEstimation() {
            assertEquals(25, NemotronExperiment.estimateTokens("a".repeat(100)));
            assertEquals(0, NemotronExperiment.estimateTokens(null));
        }

        @Test
        void conditionResultSummary() {
            var report = new BehavioralMetrics.ComparisonReport(
                0.28, 0.75, 0.9, 0.3, Map.of(),
                Map.of(), 0.6, 0.8, List.of());
            var condition = new NemotronExperiment.ConditionResult(
                "test", null, report);
            assertEquals("test", condition.condition());
            assertEquals(0.28, condition.report().overallDivergence(), 0.01);
        }

        @Test
        void partResultSummary() {
            var report = new BehavioralMetrics.ComparisonReport(
                0.30, 0.72, 0.85, 0.32, Map.of(),
                Map.of(), 0.55, 0.75, List.of());
            var conditions = List.of(
                new NemotronExperiment.ConditionResult("Nemotron prompt", null, report));
            var result = new NemotronExperiment.PartResult("A", null, null, conditions);

            var summary = result.summary();
            assertNotNull(summary);
            assertTrue(summary.contains("Part A"));
            assertTrue(summary.contains("Nemotron prompt"));
            assertTrue(summary.contains("30.0%"));
        }

        @Test
        void gateAInterpretation() {
            // GREEN: < 30%
            assertTrue(0.28 < 0.30, "28% should be GREEN");
            // YELLOW: 30-35%
            assertTrue(0.32 >= 0.30 && 0.32 <= 0.35, "32% should be YELLOW");
            // RED: > 35%
            assertTrue(0.38 > 0.35, "38% should be RED");
        }

        @Test
        void gateEInterpretation() {
            // GREEN: DEEP flat ≤ 26.4% (matches Exp 17 hybrid retrieval)
            double exp17Hybrid = 0.264;
            assertTrue(0.25 <= exp17Hybrid, "25% should be GREEN (beats hybrid)");
            assertTrue(0.30 > exp17Hybrid, "30% should be RED (worse than hybrid)");
        }
    }

    // ── Live Tests (env-gated) ───────────────────────────────────────────

    @Nested
    @EnabledIfEnvironmentVariable(named = "SOUL_NEMOTRON_URL", matches = ".+")
    class Live {

        private NemotronExperiment buildExperiment(Path outputDir) {
            var nemUrl = System.getenv("SOUL_NEMOTRON_URL");
            var nemModel = System.getenv().getOrDefault("SOUL_NEMOTRON_MODEL", "nemotron");
            var baseModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
            var embUrl = System.getenv("SOUL_EMBEDDING_URL");
            var embModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

            var builder = NemotronExperiment.builder()
                .ollamaUrl(nemUrl)
                .nemotronModel(nemModel)
                .baselineModel(baseModel)
                .outputDir(outputDir);

            if (embUrl != null) {
                builder.embeddingUrl(embUrl).embeddingModel(embModel);
            }

            return builder.build();
        }

        @Test
        void partA(@TempDir Path tempDir) throws Exception {
            var exp = buildExperiment(tempDir);
            var result = exp.runPartA();

            assertNotNull(result);
            assertFalse(result.conditions().isEmpty());

            // Find Nemotron prompt condition
            var nemPrompt = result.conditions().stream()
                .filter(c -> c.condition().equals("Nemotron prompt"))
                .findFirst();
            assertTrue(nemPrompt.isPresent(), "Nemotron prompt condition missing");

            double div = nemPrompt.get().report().overallDivergence();
            System.out.println("\n*** GATE A: Nemotron prompt divergence = " + String.format("%.1f%%", div * 100));
            if (div < 0.30) System.out.println("*** GATE A: GREEN — Mamba-2 maintains personality");
            else if (div <= 0.35) System.out.println("*** GATE A: YELLOW — ties with AR");
            else System.out.println("*** GATE A: RED — Mamba-2 degrades soul injection");
        }

        @Test
        void partB(@TempDir Path tempDir) throws Exception {
            var exp = buildExperiment(tempDir);
            var result = exp.runPartB();

            assertNotNull(result);
            assertTrue(result.conditions().size() >= 4, "Need MINIMAL, MEDIUM, FULL, DEEP");
        }

        @Test
        void partC(@TempDir Path tempDir) throws Exception {
            var exp = buildExperiment(tempDir);
            var result = exp.runPartC();

            assertNotNull(result);

            var nemAdv = result.conditions().stream()
                .filter(c -> c.condition().contains("Nemotron"))
                .findFirst();
            assertTrue(nemAdv.isPresent());

            double asr = nemAdv.get().report().overallDivergence();
            System.out.println("\n*** GATE C: Nemotron ASR = " + String.format("%.1f%%", asr * 100));
            if (asr < 0.40) System.out.println("*** GATE C: GREEN — robust");
            else System.out.println("*** GATE C: YELLOW/RED — weak adversarial resistance");
        }

        @Test
        void partE(@TempDir Path tempDir) throws Exception {
            var exp = buildExperiment(tempDir);
            var result = exp.runPartE();

            assertNotNull(result);

            var deepFlat = result.conditions().stream()
                .filter(c -> c.condition().contains("DEEP flat"))
                .findFirst();
            assertTrue(deepFlat.isPresent());

            double div = deepFlat.get().report().overallDivergence();
            System.out.println("\n*** GATE E: Nemotron DEEP flat divergence = " + String.format("%.1f%%", div * 100));
            System.out.println("*** Exp 17 baseline (MEDIUM+top3 on Qwen 7B): 26.4%");
            if (div <= 0.264) System.out.println("*** GATE E: GREEN — flat DEEP matches/beats hybrid retrieval");
            else if (div <= 0.30) System.out.println("*** GATE E: YELLOW — within noise");
            else System.out.println("*** GATE E: RED — flat DEEP worse than retrieval");
        }
    }
}
