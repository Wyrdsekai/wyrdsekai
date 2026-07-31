package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combined bath + soul experiment tests.
 *
 * Framework tests validate VitalityInferrer and result structure.
 * Live tests require an inference endpoint.
 *
 * To run:
 *   SOUL_EXPERIMENT_URL=http://gpu-host:8090/v1 \
 *     SOUL_EXPERIMENT_MODEL=Qwen3-30B-A3B-Q4_K_M.gguf \
 *     SOUL_EMBEDDING_URL=http://gpu-host:11434 \
 *     ./gradlew :core:test --tests "*CombinedExperimentTest.live*"
 */
class CombinedExperimentTest {

    // === Framework tests ===

    @Test void vitality_inferrer_returns_reasonable_values() {
        var record = syntheticRecord("test", List.of(
            response("s1", "social", "hello",
                "Greetings, traveler! Welcome to this warm and wonderful tavern. I hope you find comfort here.",
                1200),
            response("s2", "decision", "what?",
                "Perhaps we should be careful. Maybe it's too risky to proceed without more information.",
                800),
            response("s3", "style", "joke",
                "Ah, humor. The thin line between wisdom and absurdity. Let me think of something good.",
                1000)
        ));

        var profile = VitalityInferrer.infer(record);

        // All values should be in [0, 1]
        assertThat(profile.energy()).isBetween(0.0, 1.0);
        assertThat(profile.confidence()).isBetween(0.0, 1.0);
        assertThat(profile.errorPressure()).isBetween(0.0, 1.0);
        assertThat(profile.focus()).isBetween(0.0, 1.0);
        assertThat(profile.momentum()).isBetween(0.0, 1.0);
        assertThat(profile.rapport()).isBetween(0.0, 1.0);
    }

    @Test void vitality_inferrer_detects_high_caution() {
        var cautious = syntheticRecord("cautious", List.of(
            response("s1", "decision", "go?",
                "Perhaps we should be careful. I'm uncertain about this. Maybe we should wait.",
                1000),
            response("s2", "decision", "fight?",
                "I hesitate. This is risky and possibly dangerous. Let's consider alternatives cautiously.",
                1000)
        ));

        var profile = VitalityInferrer.infer(cautious);

        // High caution words → high error pressure
        assertThat(profile.errorPressure())
            .as("Cautious text should infer higher error pressure")
            .isGreaterThan(0.3);
    }

    @Test void vitality_inferrer_detects_positive_sentiment() {
        var warm = syntheticRecord("warm", List.of(
            response("s1", "social", "hello",
                "Welcome, dear friend! This is a beautiful and wonderful place full of joy.",
                500),
            response("s2", "social", "thanks",
                "It's my great pleasure to help. I love making friends and spreading warmth.",
                500)
        ));

        var profile = VitalityInferrer.infer(warm);

        // Positive sentiment → high rapport
        assertThat(profile.rapport())
            .as("Warm text should infer higher rapport")
            .isGreaterThan(0.5);
    }

    @Test void combined_result_generates_summary() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hi", "Hello, traveler.", 1000)
        ));
        var naked = syntheticRecord("naked", List.of(
            response("s1", "social", "hi", "Hi there!", 1000)
        ));
        var soulOnly = syntheticRecord("soul", List.of(
            response("s1", "social", "hi", "Greetings, traveler.", 1000)
        ));
        var bathOnly = syntheticRecord("bath", List.of(
            response("s1", "social", "hi", "Hello, welcome.", 1000)
        ));
        var combined = syntheticRecord("combined", List.of(
            response("s1", "social", "hi", "Welcome, traveler.", 1000)
        ));

        var inferredVitality = VitalityInferrer.infer(baseline);

        var result = new CombinedExperiment.CombinedResult(
            baseline, naked, soulOnly, bathOnly, combined,
            "soul text", inferredVitality,
            BehavioralMetrics.compare(baseline, naked),
            BehavioralMetrics.compare(baseline, soulOnly),
            BehavioralMetrics.compare(baseline, bathOnly),
            BehavioralMetrics.compare(baseline, combined));

        var summary = result.summary();
        assertThat(summary).contains("Combined Bath + Soul");
        assertThat(summary).contains("Naked");
        assertThat(summary).contains("Soul");
        assertThat(summary).contains("Bath");
        assertThat(summary).contains("INTERPRETATION");
    }

    @Test void combined_should_outperform_naked_in_synthetic_test() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hello",
                "Greetings, traveler. The warmth of this hearth welcomes all who seek shelter.", 1000),
            response("s2", "decision", "help?",
                "Of course. To turn away from those in need would be to deny the purpose of being here.", 1000)
        ));

        // Good match (simulating soul+bath)
        var good = syntheticRecord("good", List.of(
            response("s1", "social", "hello",
                "Welcome, traveler. This hearth has warmth enough for all who seek it.", 1000),
            response("s2", "decision", "help?",
                "Naturally. Turning away from someone in need contradicts everything I believe.", 1000)
        ));

        // Generic (simulating naked)
        var generic = syntheticRecord("generic", List.of(
            response("s1", "social", "hello", "Hi! How can I help you today?", 1000),
            response("s2", "decision", "help?", "Sure, I'd be happy to help!", 1000)
        ));

        var reportGood = BehavioralMetrics.compare(baseline, good);
        var reportGeneric = BehavioralMetrics.compare(baseline, generic);

        assertThat(reportGood.overallDivergence())
            .isLessThan(reportGeneric.overallDivergence());
    }

    // === Live tests ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @Test void live_combined_experiment(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = new CombinedExperiment(url, model,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir,
            embeddingUrl, embeddingModel);
        var result = experiment.run();

        System.out.println("\n" + result.summary());

        // Soul should reduce divergence vs naked
        assertThat(result.reportSoul().overallDivergence())
            .as("Soul injection should help (or at least not significantly hurt)")
            .isLessThanOrEqualTo(result.reportNaked().overallDivergence() + 0.15);

        System.out.println("Results saved to: " + outputDir);
    }

    // === Helpers ===

    private static BehavioralRecord syntheticRecord(String id,
            List<BehavioralRecord.ScenarioResponse> responses) {
        return new BehavioralRecord(id, "Wyrd", "synthetic", "test prompt", null,
            Instant.now(), responses);
    }

    private static BehavioralRecord.ScenarioResponse response(
            String scenarioId, String category, String playerMsg, String agentResponse,
            long latencyMs) {
        return new BehavioralRecord.ScenarioResponse(
            scenarioId, category, playerMsg, agentResponse,
            agentResponse.split("\\s+").length, latencyMs);
    }
}
