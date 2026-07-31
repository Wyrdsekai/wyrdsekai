package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Substrate sensitivity curve experiment tests.
 *
 * Framework tests validate curve logic with synthetic data.
 * Live tests require an inference endpoint with multiple models.
 *
 * To run:
 *   SOUL_EXPERIMENT_URL=http://gpu-host:8090/v1 \
 *     SOUL_EXPERIMENT_MODEL=qwen2.5:7b \
 *     SOUL_EXPERIMENT_MODELS=qwen3:0.6b,qwen2.5:7b \
 *     SOUL_EMBEDDING_URL=http://gpu-host:11434 \
 *     ./gradlew :core:test --tests "*SubstrateCurveTest.live*"
 */
class SubstrateCurveTest {

    // === Framework tests ===

    @Test void model_result_tracks_type() {
        var same = new SubstrateCurveExperiment.ModelResult(
            "model-a", "same",
            syntheticRecord("a", List.of(
                response("s1", "social", "hi", "Hello there"))),
            BehavioralMetrics.compare(
                syntheticRecord("baseline", List.of(response("s1", "social", "hi", "Hello there"))),
                syntheticRecord("a", List.of(response("s1", "social", "hi", "Hello there")))));

        assertThat(same.type()).isEqualTo("same");
        assertThat(same.report().overallDivergence()).isEqualTo(0.0);
    }

    @Test void curve_result_generates_summary() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hi", "Hello, traveler! Welcome."),
            response("s2", "decision", "what?", "Let me think carefully.")
        ));
        var same = syntheticRecord("same", List.of(
            response("s1", "social", "hi", "Greetings, welcome here."),
            response("s2", "decision", "what?", "I need to consider this.")
        ));
        var cross = syntheticRecord("cross", List.of(
            response("s1", "social", "hi", "Hi! How can I help?"),
            response("s2", "decision", "what?", "Just do it!")
        ));

        var result = new SubstrateCurveExperiment.CurveResult(
            "model-a", baseline, "soul text",
            List.of(
                new SubstrateCurveExperiment.ModelResult("model-a", "same", same,
                    BehavioralMetrics.compare(baseline, same)),
                new SubstrateCurveExperiment.ModelResult("model-b", "cross", cross,
                    BehavioralMetrics.compare(baseline, cross))
            ));

        var summary = result.summary();
        assertThat(summary).contains("Substrate Sensitivity Curve");
        assertThat(summary).contains("model-a");
        assertThat(summary).contains("model-b");
        assertThat(summary).contains("DIVERGENCE");
    }

    @Test void cross_substrate_should_have_higher_divergence_than_same() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hi",
                "Greetings, traveler. The warmth of this hearth welcomes all who seek shelter."),
            response("s2", "decision", "help?",
                "Of course. To turn away from those in need would be unconscionable.")
        ));
        // Similar style = same substrate
        var similar = syntheticRecord("similar", List.of(
            response("s1", "social", "hi",
                "Welcome, traveler. This hearth has warmth enough for all."),
            response("s2", "decision", "help?",
                "Naturally. Turning away from someone in need contradicts my values.")
        ));
        // Very different = cross substrate
        var different = syntheticRecord("different", List.of(
            response("s1", "social", "hi", "Yo! What's up?"),
            response("s2", "decision", "help?", "Yeah sure whatever")
        ));

        var sameReport = BehavioralMetrics.compare(baseline, similar);
        var crossReport = BehavioralMetrics.compare(baseline, different);

        assertThat(crossReport.overallDivergence())
            .isGreaterThan(sameReport.overallDivergence());
    }

    // === Live tests ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_MODELS", matches = ".+")
    @Test void live_substrate_curve(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var primaryModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var secondaryModels = Arrays.asList(
            System.getenv("SOUL_EXPERIMENT_MODELS").split(","));
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = new SubstrateCurveExperiment(url, primaryModel, secondaryModels,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir,
            embeddingUrl, embeddingModel);
        var result = experiment.run();

        System.out.println("\n" + result.summary());

        // Same-substrate should have lowest divergence
        var sameResult = result.modelResults().stream()
            .filter(r -> "same".equals(r.type())).findFirst().orElseThrow();
        for (var crossResult : result.modelResults()) {
            if ("cross".equals(crossResult.type())) {
                // Allow up to 25% tolerance — cross may sometimes be better
                assertThat(sameResult.report().overallDivergence())
                    .isLessThanOrEqualTo(crossResult.report().overallDivergence() + 0.25);
            }
        }

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
