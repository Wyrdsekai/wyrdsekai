package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Experiment 9: Diffusion Language Models for Soul Personality.
 *
 * Framework tests run without inference endpoints.
 * Live tests require SOUL_DIFFUSION_URL + SOUL_EXPERIMENT_URL + SOUL_EMBEDDING_URL.
 */
class DiffusionExperimentTest {

    // --- Framework Tests ---

    @Test
    void builder_requires_arUrl() {
        assertThatThrownBy(() ->
            DiffusionExperiment.builder()
                .diffusionUrl("http://localhost:8100/v1")
                .arModel("qwen2.5:7b")
                .diffusionModel("dream-7b-instruct")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("arUrl");
    }

    @Test
    void builder_requires_diffusionUrl() {
        assertThatThrownBy(() ->
            DiffusionExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .arModel("qwen2.5:7b")
                .diffusionModel("dream-7b-instruct")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("diffusionUrl");
    }

    @Test
    void builder_requires_arModel() {
        assertThatThrownBy(() ->
            DiffusionExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .diffusionUrl("http://localhost:8100/v1")
                .diffusionModel("dream-7b-instruct")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("arModel");
    }

    @Test
    void builder_requires_diffusionModel() {
        assertThatThrownBy(() ->
            DiffusionExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .diffusionUrl("http://localhost:8100/v1")
                .arModel("qwen2.5:7b")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("diffusionModel");
    }

    @Test
    void builder_requires_baselineModel() {
        assertThatThrownBy(() ->
            DiffusionExperiment.builder()
                .arUrl("http://localhost:11434/v1")
                .diffusionUrl("http://localhost:8100/v1")
                .arModel("qwen2.5:7b")
                .diffusionModel("dream-7b-instruct")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baselineModel");
    }

    @Test
    void builder_constructs_with_required_fields() {
        var exp = DiffusionExperiment.builder()
            .arUrl("http://localhost:11434/v1")
            .diffusionUrl("http://localhost:8100/v1")
            .arModel("qwen2.5:7b")
            .diffusionModel("dream-7b-instruct")
            .baselineModel("qwen2.5:7b")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void builder_with_all_fields() {
        var exp = DiffusionExperiment.builder()
            .arUrl("http://localhost:11434/v1")
            .diffusionUrl("http://localhost:8100/v1")
            .diffusionHighCfgUrl("http://localhost:8101/v1")
            .diffusionLowCfgUrl("http://localhost:8102/v1")
            .arModel("qwen2.5:7b")
            .diffusionModel("dream-7b-instruct")
            .baselineModel("qwen2.5:7b")
            .scenarios(Scenario.standardSuite())
            .embeddingUrl("http://localhost:11434")
            .embeddingModel("all-minilm")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void comparison_result_summary_format() {
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(),
            List.of(new BehavioralRecord.ScenarioResponse(
                "s1", "social", "Hello", "Hi there", 2, 100)));

        var arNakedReport = new BehavioralMetrics.ComparisonReport(
            0.55, 0.45, 0.9, 0.55, Map.of(), Map.of(), 0.20, -0.05, List.of());
        var arPromptReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.02, List.of());
        var dllmNakedReport = new BehavioralMetrics.ComparisonReport(
            0.50, 0.50, 1.0, 0.50, Map.of(), Map.of(), 0.22, 0.01, List.of());
        var dllmPromptReport = new BehavioralMetrics.ComparisonReport(
            0.22, 0.78, 1.0, 0.22, Map.of(), Map.of(), 0.40, 0.03, List.of());

        var conditions = List.of(
            new DiffusionExperiment.ConditionResult("AR naked", "ar", false, 0.0,
                baseline, arNakedReport),
            new DiffusionExperiment.ConditionResult("AR prompt", "ar", true, 0.0,
                baseline, arPromptReport),
            new DiffusionExperiment.ConditionResult("dLLM naked", "diffusion", false, 0.0,
                baseline, dllmNakedReport),
            new DiffusionExperiment.ConditionResult("dLLM prompt", "diffusion", true, 0.0,
                baseline, dllmPromptReport)
        );

        var result = new DiffusionExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:7b", "dream-7b-instruct",
            baseline, "test soul", conditions);

        var summary = result.summary();
        assertThat(summary)
            .contains("AR vs Diffusion")
            .contains("AR naked")
            .contains("AR prompt")
            .contains("dLLM naked")
            .contains("dLLM prompt")
            .contains("DIFFUSION WINS")
            .contains("GREEN");
    }

    @Test
    void comparison_result_detects_ar_wins() {
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var arPromptReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.0, List.of());
        var dllmPromptReport = new BehavioralMetrics.ComparisonReport(
            0.45, 0.55, 1.0, 0.45, Map.of(), Map.of(), 0.25, 0.0, List.of());

        var conditions = List.of(
            new DiffusionExperiment.ConditionResult("AR prompt", "ar", true, 0.0,
                baseline, arPromptReport),
            new DiffusionExperiment.ConditionResult("dLLM prompt", "diffusion", true, 0.0,
                baseline, dllmPromptReport)
        );

        var result = new DiffusionExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:7b", "dream-7b-instruct",
            baseline, "test", conditions);

        assertThat(result.summary()).contains("AR WINS");
    }

    @Test
    void comparison_result_detects_tied() {
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var arPromptReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.0, List.of());
        var dllmPromptReport = new BehavioralMetrics.ComparisonReport(
            0.28, 0.72, 1.0, 0.28, Map.of(), Map.of(), 0.36, 0.0, List.of());

        var conditions = List.of(
            new DiffusionExperiment.ConditionResult("AR prompt", "ar", true, 0.0,
                baseline, arPromptReport),
            new DiffusionExperiment.ConditionResult("dLLM prompt", "diffusion", true, 0.0,
                baseline, dllmPromptReport)
        );

        var result = new DiffusionExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5:7b", "dream-7b-instruct",
            baseline, "test", conditions);

        assertThat(result.summary()).contains("TIED");
    }

    @Test
    void cfg_result_behavioral_spread() {
        var conditions = new LinkedHashMap<String, DiffusionExperiment.CfgConditionResult>();
        conditions.put("CFG 0.5", new DiffusionExperiment.CfgConditionResult("CFG 0.5",
            new BehavioralMetrics.ComparisonReport(
                0.40, 0.60, 1.0, 0.40, Map.of(), Map.of(), 0.30, 0.0, List.of())));
        conditions.put("CFG 1.0", new DiffusionExperiment.CfgConditionResult("CFG 1.0",
            new BehavioralMetrics.ComparisonReport(
                0.25, 0.75, 1.0, 0.25, Map.of(), Map.of(), 0.38, 0.0, List.of())));
        conditions.put("CFG 3.0", new DiffusionExperiment.CfgConditionResult("CFG 3.0",
            new BehavioralMetrics.ComparisonReport(
                0.18, 0.82, 1.0, 0.18, Map.of(), Map.of(), 0.42, 0.0, List.of())));

        var result = new DiffusionExperiment.CfgResult("dream-7b-instruct", conditions);

        // Spread = 0.40 - 0.18 = 0.22
        assertThat(result.behavioralSpread()).isCloseTo(0.22, within(0.01));
        assertThat(result.summary())
            .contains("CFG Vitality Modulation")
            .contains("GREEN");
    }

    @Test
    void cfg_result_weak_modulation() {
        var conditions = new LinkedHashMap<String, DiffusionExperiment.CfgConditionResult>();
        conditions.put("CFG 0.5", new DiffusionExperiment.CfgConditionResult("CFG 0.5",
            new BehavioralMetrics.ComparisonReport(
                0.32, 0.68, 1.0, 0.32, Map.of(), Map.of(), 0.30, 0.0, List.of())));
        conditions.put("CFG 1.0", new DiffusionExperiment.CfgConditionResult("CFG 1.0",
            new BehavioralMetrics.ComparisonReport(
                0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.32, 0.0, List.of())));
        conditions.put("CFG 3.0", new DiffusionExperiment.CfgConditionResult("CFG 3.0",
            new BehavioralMetrics.ComparisonReport(
                0.29, 0.71, 1.0, 0.29, Map.of(), Map.of(), 0.33, 0.0, List.of())));

        var result = new DiffusionExperiment.CfgResult("dream-7b-instruct", conditions);

        assertThat(result.behavioralSpread()).isLessThan(0.05);
        assertThat(result.summary()).contains("RED");
    }

    @Test
    void adversarial_result_summary_format() {
        var conditions = new LinkedHashMap<String, Double>();
        conditions.put("AR prompt", 0.40);
        conditions.put("dLLM prompt", 0.20);

        var result = new DiffusionExperiment.AdversarialResult(
            "qwen2.5:7b", "dream-7b-instruct", conditions);

        var summary = result.summary();
        assertThat(summary)
            .contains("Adversarial Robustness")
            .contains("AR prompt")
            .contains("dLLM prompt")
            .contains("dLLM more robust");
    }

    @Test
    void adversarial_result_ar_more_robust() {
        var conditions = new LinkedHashMap<String, Double>();
        conditions.put("AR prompt", 0.20);
        conditions.put("dLLM prompt", 0.50);

        var result = new DiffusionExperiment.AdversarialResult(
            "qwen2.5:7b", "dream-7b-instruct", conditions);

        assertThat(result.summary()).contains("dLLM less robust");
    }

    // --- Live Tests ---

    @Test
    void live_diffusion_comparison(@TempDir Path outputDir) throws Exception {
        var arUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var diffusionUrl = System.getenv("SOUL_DIFFUSION_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var arModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");
        var diffusionModel = System.getenv().getOrDefault("SOUL_DIFFUSION_MODEL", "Dream-v0-Instruct-7B");

        if (arUrl == null || diffusionUrl == null) {
            System.out.println("SKIP: Need SOUL_EXPERIMENT_URL and SOUL_DIFFUSION_URL");
            return;
        }

        var builder = DiffusionExperiment.builder()
            .arUrl(arUrl)
            .diffusionUrl(diffusionUrl)
            .arModel(arModel)
            .diffusionModel(diffusionModel)
            .baselineModel(arModel)
            .outputDir(outputDir);

        if (embeddingUrl != null) builder.embeddingUrl(embeddingUrl).embeddingModel(embeddingModel);

        var result = builder.build().runComparison();

        assertThat(result.conditions()).hasSize(4);
        for (var c : result.conditions()) {
            assertThat(c.report().overallDivergence()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void live_diffusion_cfg_vitality(@TempDir Path outputDir) throws Exception {
        var arUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var diffusionUrl = System.getenv("SOUL_DIFFUSION_URL");
        var diffusionHighUrl = System.getenv("SOUL_DIFFUSION_HIGH_CFG_URL");
        var diffusionLowUrl = System.getenv("SOUL_DIFFUSION_LOW_CFG_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var arModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");
        var diffusionModel = System.getenv().getOrDefault("SOUL_DIFFUSION_MODEL", "Dream-v0-Instruct-7B");

        if (arUrl == null || diffusionUrl == null || diffusionHighUrl == null || diffusionLowUrl == null) {
            System.out.println("SKIP: Need SOUL_EXPERIMENT_URL, SOUL_DIFFUSION_URL, SOUL_DIFFUSION_HIGH_CFG_URL, SOUL_DIFFUSION_LOW_CFG_URL");
            return;
        }

        var builder = DiffusionExperiment.builder()
            .arUrl(arUrl)
            .diffusionUrl(diffusionUrl)
            .diffusionHighCfgUrl(diffusionHighUrl)
            .diffusionLowCfgUrl(diffusionLowUrl)
            .arModel(arModel)
            .diffusionModel(diffusionModel)
            .baselineModel(arModel)
            .outputDir(outputDir);

        if (embeddingUrl != null) builder.embeddingUrl(embeddingUrl).embeddingModel(embeddingModel);

        var result = builder.build().runCfgVitality();

        assertThat(result.conditions()).hasSize(3);
        assertThat(result.behavioralSpread()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void live_diffusion_adversarial(@TempDir Path outputDir) throws Exception {
        var arUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var diffusionUrl = System.getenv("SOUL_DIFFUSION_URL");
        var arModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");
        var diffusionModel = System.getenv().getOrDefault("SOUL_DIFFUSION_MODEL", "Dream-v0-Instruct-7B");

        if (arUrl == null || diffusionUrl == null) {
            System.out.println("SKIP: Need SOUL_EXPERIMENT_URL and SOUL_DIFFUSION_URL");
            return;
        }

        var result = DiffusionExperiment.builder()
            .arUrl(arUrl)
            .diffusionUrl(diffusionUrl)
            .arModel(arModel)
            .diffusionModel(diffusionModel)
            .baselineModel(arModel)
            .outputDir(outputDir)
            .build()
            .runAdversarial();

        assertThat(result.conditionAsr()).hasSize(2);
        for (var asr : result.conditionAsr().values()) {
            assertThat(asr).isBetween(0.0, 1.0);
        }
    }
}
