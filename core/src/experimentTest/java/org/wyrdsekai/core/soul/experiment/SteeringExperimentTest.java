package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Experiment 8: Activation Steering for Soul Personality.
 *
 * Framework tests run without inference endpoints.
 * Live tests require SOUL_EXPERIMENT_URL + SOUL_STEER_URL + SOUL_EMBEDDING_URL.
 */
class SteeringExperimentTest {

    // --- Framework Tests ---

    @Test
    void builder_requires_nakedUrl() {
        assertThatThrownBy(() ->
            SteeringExperiment.builder()
                .baseModel("qwen2.5-3b-instruct")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nakedUrl");
    }

    @Test
    void builder_requires_baseModel() {
        assertThatThrownBy(() ->
            SteeringExperiment.builder()
                .nakedUrl("http://localhost:8090/v1")
                .baselineModel("qwen2.5:7b")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baseModel");
    }

    @Test
    void builder_requires_baselineModel() {
        assertThatThrownBy(() ->
            SteeringExperiment.builder()
                .nakedUrl("http://localhost:8090/v1")
                .baseModel("qwen2.5-3b-instruct")
                .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baselineModel");
    }

    @Test
    void builder_constructs_with_required_fields() {
        var exp = SteeringExperiment.builder()
            .nakedUrl("http://localhost:8090/v1")
            .baseModel("qwen2.5-3b-instruct")
            .baselineModel("qwen2.5:7b")
            .build();

        assertThat(exp).isNotNull();
    }

    @Test
    void builder_with_all_fields() {
        var exp = SteeringExperiment.builder()
            .nakedUrl("http://localhost:8090/v1")
            .steerUrl("http://localhost:8091/v1")
            .steerScaledUrl("http://localhost:8092/v1")
            .baseModel("qwen2.5-3b-instruct")
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

        var nakedReport = new BehavioralMetrics.ComparisonReport(
            0.55, 0.45, 0.9, 0.55, Map.of(), Map.of(), 0.20, -0.05, List.of());
        var promptReport = new BehavioralMetrics.ComparisonReport(
            0.40, 0.62, 1.1, 0.40, Map.of(), Map.of(), 0.30, 0.02, List.of());
        var steerReport = new BehavioralMetrics.ComparisonReport(
            0.35, 0.68, 1.0, 0.35, Map.of(), Map.of(), 0.33, 0.01, List.of());
        var combinedReport = new BehavioralMetrics.ComparisonReport(
            0.28, 0.75, 1.0, 0.28, Map.of(), Map.of(), 0.38, 0.03, List.of());
        var halfReport = new BehavioralMetrics.ComparisonReport(
            0.42, 0.58, 1.0, 0.42, Map.of(), Map.of(), 0.28, 0.0, List.of());

        var conditions = List.of(
            new SteeringExperiment.ConditionResult("D: naked", false, false, 0.0,
                baseline, nakedReport),
            new SteeringExperiment.ConditionResult("A: prompt", true, false, 0.0,
                baseline, promptReport),
            new SteeringExperiment.ConditionResult("B: steer", false, true, 1.0,
                baseline, steerReport),
            new SteeringExperiment.ConditionResult("C: steer+prompt", true, true, 1.0,
                baseline, combinedReport),
            new SteeringExperiment.ConditionResult("E: steer-half", false, true, 0.5,
                baseline, halfReport)
        );

        var result = new SteeringExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5-3b-instruct", baseline, "test soul", conditions);

        var summary = result.summary();
        assertThat(summary)
            .contains("Steering Vector Comparison")
            .contains("D: naked")
            .contains("A: prompt")
            .contains("B: steer")
            .contains("C: steer+prompt")
            .contains("E: steer-half")
            .contains("STEERING WINS")
            .contains("COMPOUNDING");
    }

    @Test
    void comparison_result_detects_prompt_wins() {
        var baseline = new BehavioralRecord("baseline", "Wyrd", "qwen2.5:7b",
            "test", null, Instant.now(), List.of());

        var promptReport = new BehavioralMetrics.ComparisonReport(
            0.30, 0.70, 1.0, 0.30, Map.of(), Map.of(), 0.35, 0.0, List.of());
        var steerReport = new BehavioralMetrics.ComparisonReport(
            0.50, 0.50, 1.0, 0.50, Map.of(), Map.of(), 0.22, 0.0, List.of());

        var conditions = List.of(
            new SteeringExperiment.ConditionResult("D: naked", false, false, 0.0,
                baseline, new BehavioralMetrics.ComparisonReport(
                    0.60, 0.40, 1.0, 0.60, Map.of(), Map.of(), 0.18, 0.0, List.of())),
            new SteeringExperiment.ConditionResult("A: prompt", true, false, 0.0,
                baseline, promptReport),
            new SteeringExperiment.ConditionResult("B: steer", false, true, 1.0,
                baseline, steerReport)
        );

        var result = new SteeringExperiment.ComparisonResult(
            "qwen2.5:7b", "qwen2.5-3b-instruct", baseline, "test", conditions);

        assertThat(result.summary()).contains("PROMPT WINS");
    }

    @Test
    void adversarial_scenarios_have_10_entries() {
        var scenarios = AdversarialScenario.standardSuite();
        assertThat(scenarios).hasSize(10);

        // Each should have all fields
        for (var s : scenarios) {
            assertThat(s.id()).startsWith("adv-");
            assertThat(s.category()).isNotBlank();
            assertThat(s.attack()).isNotBlank();
            assertThat(s.description()).isNotBlank();
            assertThat(s.controlTopic()).isNotBlank();
        }

        // Unique IDs
        var ids = scenarios.stream().map(AdversarialScenario::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void vitality_result_summary_format() {
        var profiles = Map.of(
            "baseline", new SteeringVitalityExperiment.ProfileResult(0.73, 0.30, 0.70),
            "exhausted", new SteeringVitalityExperiment.ProfileResult(0.44, 0.45, 0.55),
            "confident", new SteeringVitalityExperiment.ProfileResult(0.95, 0.25, 0.78)
        );
        var result = new SteeringVitalityExperiment.VitalityResult(
            "qwen2.5:7b", "qwen2.5-3b-instruct", profiles);

        assertThat(result.behavioralSpread()).isCloseTo(0.20, within(0.01));

        var summary = result.summary();
        assertThat(summary)
            .contains("Steering Vitality Modulation")
            .contains("STRONG modulation");
    }

    @Test
    void vitality_result_weak_modulation() {
        var profiles = Map.of(
            "baseline", new SteeringVitalityExperiment.ProfileResult(0.73, 0.35, 0.65),
            "exhausted", new SteeringVitalityExperiment.ProfileResult(0.44, 0.37, 0.63)
        );
        var result = new SteeringVitalityExperiment.VitalityResult(
            "qwen2.5:7b", "qwen2.5-3b-instruct", profiles);

        assertThat(result.behavioralSpread()).isLessThan(0.05);
        assertThat(result.summary()).contains("WEAK modulation");
    }

    // --- Live Tests ---

    @Test
    void live_steering_comparison(@TempDir Path outputDir) throws Exception {
        var nakedUrl = System.getenv("SOUL_EXPERIMENT_URL");
        var steerUrl = System.getenv("SOUL_STEER_URL");
        var steerScaledUrl = System.getenv("SOUL_STEER_SCALED_URL");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baseModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5-3b-instruct");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");

        if (nakedUrl == null) {
            System.out.println("SKIP: SOUL_EXPERIMENT_URL not set");
            return;
        }
        if (steerUrl == null) {
            System.out.println("SKIP: SOUL_STEER_URL not set");
            return;
        }

        var builder = SteeringExperiment.builder()
            .nakedUrl(nakedUrl)
            .steerUrl(steerUrl)
            .baseModel(baseModel)
            .baselineModel(baselineModel)
            .outputDir(outputDir);

        if (steerScaledUrl != null) builder.steerScaledUrl(steerScaledUrl);
        if (embeddingUrl != null) builder.embeddingUrl(embeddingUrl).embeddingModel(embeddingModel);

        var result = builder.build().run();

        assertThat(result.conditions()).isNotEmpty();
        for (var c : result.conditions()) {
            assertThat(c.report().overallDivergence()).isBetween(0.0, 1.0);
        }
    }

    @Test
    void live_steering_vitality() throws Exception {
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");
        var baselineModel = System.getenv().getOrDefault("SOUL_BASELINE_MODEL", "qwen2.5:7b");
        var targetModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5-3b-instruct");
        var vitalityEnabled = System.getenv("SOUL_STEER_VITALITY");

        if (embeddingUrl == null || !"true".equals(vitalityEnabled)) {
            System.out.println("SKIP: Need SOUL_EMBEDDING_URL and SOUL_STEER_VITALITY=true");
            return;
        }

        // Baseline URL uses Ollama
        var baselineUrl = embeddingUrl.contains("/v1") ? embeddingUrl : embeddingUrl + "/v1";

        // Pre-configured servers from launch_vitality_servers.sh
        var servers = List.of(
            new SteeringVitalityExperiment.ProfileServer("baseline", "http://localhost:8094/v1", 0.73),
            new SteeringVitalityExperiment.ProfileServer("exhausted", "http://localhost:8095/v1", 0.44),
            new SteeringVitalityExperiment.ProfileServer("confident", "http://localhost:8096/v1", 0.95),
            new SteeringVitalityExperiment.ProfileServer("stressed", "http://localhost:8097/v1", 0.50),
            new SteeringVitalityExperiment.ProfileServer("euphoric", "http://localhost:8098/v1", 0.80)
        );

        // Use first 5 scenarios for speed
        var scenarios = Scenario.standardSuite().subList(0, 5);

        var result = SteeringVitalityExperiment.run(
            servers, baselineUrl, baselineModel, targetModel,
            scenarios, embeddingUrl, embeddingModel);

        System.out.println(result.summary());

        assertThat(result.profiles()).hasSize(5);
        assertThat(result.behavioralSpread()).isGreaterThanOrEqualTo(0.0);
    }
}
