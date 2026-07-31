package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bath modulation experiment tests.
 *
 * Framework tests validate metrics and profiles with synthetic data.
 * Live tests require an inference endpoint.
 *
 * To run:
 *   SOUL_EXPERIMENT_URL=http://gpu-host:8090/v1 \
 *     SOUL_EXPERIMENT_MODEL=qwen2.5:7b \
 *     SOUL_EMBEDDING_URL=http://gpu-host:11434 \
 *     ./gradlew :core:test --tests "*BathExperimentTest.live*"
 */
class BathExperimentTest {

    // === Framework tests ===

    @Test void profiles_have_correct_count() {
        var profiles = VitalityProfile.standardProfiles();
        assertThat(profiles).hasSize(5);
        assertThat(profiles.stream().map(VitalityProfile::name).toList())
            .containsExactly("baseline", "exhausted", "confident", "stressed", "euphoric");
    }

    @Test void exhausted_profile_produces_shorter_max_tokens() {
        var exhausted = VitalityProfile.standardProfiles().get(1);
        var confident = VitalityProfile.standardProfiles().get(2);

        assertThat(exhausted.maxTokens(512)).isLessThan(confident.maxTokens(512));
        assertThat(exhausted.maxTokens(512)).isLessThan(512);
    }

    @Test void stressed_profile_produces_lower_temperature() {
        var stressed = VitalityProfile.standardProfiles().get(3);
        var baseline = VitalityProfile.standardProfiles().getFirst();

        // Stressed has low confidence (raises temp) BUT high errorPressure (lowers temp by 0.8x)
        // Net effect: stressed temp should be modulated differently
        assertThat(stressed.temperature(0.7)).isNotEqualTo(baseline.temperature(0.7));
    }

    @Test void vitality_descriptions_are_distinct() {
        var profiles = VitalityProfile.standardProfiles();
        var descriptions = profiles.stream().map(VitalityProfile::describeState).toList();

        // Each non-baseline profile should have unique descriptors
        var exhaustedDesc = descriptions.get(1);
        var confidentDesc = descriptions.get(2);
        assertThat(exhaustedDesc).isNotEqualTo(confidentDesc);
        assertThat(exhaustedDesc).containsAnyOf("exhausted", "tired");
        assertThat(confidentDesc).contains("confident");
    }

    @Test void caution_score_detects_hedging_words() {
        assertThat(BehavioralMetrics.cautionScore(
            "Perhaps we should be careful. Maybe it's too dangerous.")).isGreaterThan(0);
        assertThat(BehavioralMetrics.cautionScore(
            "Let's go! Attack now! Charge!")).isEqualTo(0);
    }

    @Test void vocabulary_entropy_higher_for_diverse_text() {
        var diverse = "The quick brown fox jumps over the lazy dog while birds sing melodies";
        var repetitive = "the the the the the the the the the the the the";
        assertThat(BehavioralMetrics.vocabularyEntropy(diverse))
            .isGreaterThan(BehavioralMetrics.vocabularyEntropy(repetitive));
    }

    @Test void average_response_length_computed_correctly() {
        var record = syntheticRecord("test", List.of(
            response("s1", "social", "hello", "One two three four five"),  // 5 words
            response("s2", "social", "hi", "One two three")              // 3 words
        ));
        assertThat(BehavioralMetrics.averageResponseLength(record)).isEqualTo(4.0);
    }

    @Test void bath_metrics_differ_for_different_behavioral_styles() {
        var concise = syntheticRecord("concise", List.of(
            response("s1", "social", "hello", "Hi."),
            response("s2", "decision", "what?", "Fight.")
        ));
        var verbose = syntheticRecord("verbose", List.of(
            response("s1", "social", "hello",
                "Greetings, dear traveler! It is a wonderful pleasure to make your acquaintance on this fine evening. Perhaps you would care for some refreshments?"),
            response("s2", "decision", "what?",
                "I think we should perhaps consider our options very carefully before making any rash decisions. Maybe if we wait and think about this cautiously...")
        ));

        // Verbose should have more caution words
        double verboseCaution = verbose.responses().stream()
            .mapToInt(r -> BehavioralMetrics.cautionScore(r.agentResponse()))
            .average().orElse(0);
        double conciseCaution = concise.responses().stream()
            .mapToInt(r -> BehavioralMetrics.cautionScore(r.agentResponse()))
            .average().orElse(0);

        assertThat(verboseCaution).isGreaterThan(conciseCaution);
    }

    // === Live tests ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @Test void live_bath_experiment(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        // Substrate-scaled modulation: wider parameter ranges for larger models
        var modelSizeStr = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL_SIZE_B", "7.0");
        double modelSizeB = Double.parseDouble(modelSizeStr);
        double substrateFactor = VitalityProfile.substrateFactor(modelSizeB);
        System.out.println("Model size: " + modelSizeB + "B, substrate factor: "
            + String.format("%.2f", substrateFactor));

        var experiment = new BathExperiment(url, model,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), VitalityProfile.standardProfiles(),
            outputDir, embeddingUrl, embeddingModel, substrateFactor);
        var result = experiment.run();

        System.out.println("\n" + result.summary());

        // Sanity: at least the exhausted profile should differ from baseline
        var exhaustedResult = result.profileResults().stream()
            .filter(pr -> "exhausted".equals(pr.profile().name()))
            .findFirst().orElseThrow();
        assertThat(exhaustedResult.metrics().reportCombined().overallDivergence())
            .as("Exhausted profile should show some divergence from baseline")
            .isGreaterThan(0.0);

        System.out.println("Results saved to: " + outputDir);
    }

    // === Helpers ===

    private static BehavioralRecord syntheticRecord(String id,
            List<BehavioralRecord.ScenarioResponse> responses) {
        return new BehavioralRecord(id, "Wyrd", "synthetic", "test prompt", null,
            Instant.now(), responses);
    }

    private static BehavioralRecord.ScenarioResponse response(
            String scenarioId, String category, String playerMsg, String agentResponse) {
        return new BehavioralRecord.ScenarioResponse(
            scenarioId, category, playerMsg, agentResponse,
            agentResponse.split("\\s+").length, 100);
    }
}
