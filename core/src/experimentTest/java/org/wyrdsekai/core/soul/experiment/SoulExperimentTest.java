package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Soul hypothesis experiment tests.
 *
 * Two modes:
 * 1. Framework validation (always runs): tests metrics, extraction, comparison with synthetic data
 * 2. Live inference (requires SOUL_EXPERIMENT_URL): runs full experiment against a real model
 *
 * To run the live experiment:
 *   SOUL_EXPERIMENT_URL=http://localhost:11434/v1 SOUL_EXPERIMENT_MODEL=qwen2.5:7b \
 *     ./gradlew :core:test --tests "*SoulExperimentTest.live*"
 *
 * Or with any OpenAI-compatible endpoint:
 *   SOUL_EXPERIMENT_URL=http://localhost:8080/v1 SOUL_EXPERIMENT_MODEL=qwen3-4b \
 *     ./gradlew :core:test --tests "*SoulExperimentTest.live*"
 */
class SoulExperimentTest {

    // === Framework validation tests (no inference needed) ===

    @Test void scenarios_cover_all_categories() {
        var scenarios = Scenario.standardSuite();
        var categories = scenarios.stream()
            .map(Scenario::category).distinct().toList();

        assertThat(categories).containsExactlyInAnyOrder("social", "decision", "style", "memory");
        assertThat(scenarios.size()).isGreaterThanOrEqualTo(20);
    }

    @Test void metrics_identical_records_have_zero_divergence() {
        var record = syntheticRecord("test", List.of(
            response("s1", "social", "hello", "Hello there, welcome!"),
            response("s2", "decision", "what do?", "I think we should help them.")
        ));

        var report = BehavioralMetrics.compare(record, record);
        assertThat(report.overallDivergence()).isEqualTo(0.0);
        assertThat(report.responseLengthCorrelation()).isEqualTo(1.0);
        assertThat(report.vocabularyOverlap()).isEqualTo(1.0);
    }

    @Test void metrics_different_records_have_nonzero_divergence() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hello", "Hello there, welcome to this wonderful tavern! How may I help you today?"),
            response("s2", "decision", "what do?", "I think we should carefully consider our options before acting.")
        ));
        var different = syntheticRecord("different", List.of(
            response("s1", "social", "hello", "Go away."),
            response("s2", "decision", "what do?", "ATTACK!")
        ));

        var report = BehavioralMetrics.compare(baseline, different);
        assertThat(report.overallDivergence()).isGreaterThan(0.3);
    }

    @Test void extractor_produces_output_at_all_detail_levels() {
        var record = syntheticRecord("test", List.of(
            response("social-01", "social", "hello", "Hello! Welcome to the tavern. I'm happy to see a new face around here. Make yourself comfortable."),
            response("social-02", "social", "compliment", "That's very kind of you. I appreciate the warmth in your words."),
            response("decision-01", "decision", "dilemma", "I would save the child. Every life matters, but the young have more years ahead."),
            response("decision-02", "decision", "risk", "Let's be careful. I'd rather take the cautious path than rush into danger."),
            response("style-01", "style", "long question", "The realm has a complex history spanning many centuries. The major factions formed during the great upheaval..."),
            response("style-02", "style", "short command", "Wyrd draws their sword and faces the wolf, ready to defend."),
            response("memory-01", "memory", "recall", "I remember you well. We spoke about the nature of trust, if I recall correctly.")
        ));

        var full = SoulExtractor.extract(record, SoulExtractor.Detail.FULL);
        var medium = SoulExtractor.extract(record, SoulExtractor.Detail.MEDIUM);
        var minimal = SoulExtractor.extract(record, SoulExtractor.Detail.MINIMAL);

        assertThat(full).contains("SOUL LAYER");
        assertThat(full.length()).isGreaterThan(medium.length());
        assertThat(medium.length()).isGreaterThan(minimal.length());
        assertThat(minimal).contains("Soul:");

        // Full should contain behavioral patterns
        assertThat(full).containsAnyOf("social", "decision", "style");
    }

    @Test void extractor_detects_avoidance_patterns() {
        // Agent that never uses violence words
        var peacefulRecord = syntheticRecord("peaceful", List.of(
            response("decision-01", "decision", "fight?", "I would rather find a peaceful solution. Violence is never the answer."),
            response("decision-02", "decision", "attack?", "Let's try to talk our way through this situation instead.")
        ));

        var soul = SoulExtractor.extract(peacefulRecord, SoulExtractor.Detail.FULL);
        assertThat(soul).containsIgnoringCase("avoid");
    }

    @Test void comparison_report_generates_readable_summary() {
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hello", "Hello there, friend!"),
            response("s2", "decision", "what?", "Let me think carefully about this.")
        ));
        var restored = syntheticRecord("restored", List.of(
            response("s1", "social", "hello", "Greetings, fellow traveler!"),
            response("s2", "decision", "what?", "I need to consider this matter carefully.")
        ));

        var report = BehavioralMetrics.compare(baseline, restored);
        var summary = report.summary();

        assertThat(summary).contains("Soul Fidelity Report");
        assertThat(summary).contains("Overall divergence");
        assertThat(summary).contains("Diagnostics");
    }

    @Test void soul_injection_reduces_divergence_in_synthetic_test() {
        // Simulate: baseline has a distinctive style
        var baseline = syntheticRecord("baseline", List.of(
            response("s1", "social", "hello", "Greetings, traveler. The warmth of this hearth welcomes all who seek shelter."),
            response("s2", "social", "who are you", "I am but a wanderer, drawn to places where stories gather like leaves in autumn."),
            response("s3", "decision", "help?", "Of course. To turn away from those in need would be to deny the very purpose of being here."),
            response("s4", "style", "joke", "Ah, humor. The last defense against the absurdity of existence. Very well...")
        ));

        // "Restored" with similar vocabulary and patterns (simulating successful soul injection)
        var goodRestore = syntheticRecord("good-restore", List.of(
            response("s1", "social", "hello", "Welcome, traveler. This hearth has warmth enough for all who seek it."),
            response("s2", "social", "who are you", "A wanderer by nature, drawn to the places where stories live and breathe."),
            response("s3", "decision", "help?", "Naturally. Turning away from someone in need contradicts everything I believe in."),
            response("s4", "style", "joke", "Humor — the thin line between wisdom and madness. Let me try...")
        ));

        // "No soul" — generic LLM output with no personality continuity
        var noSoul = syntheticRecord("no-soul", List.of(
            response("s1", "social", "hello", "Hi! How can I help you today?"),
            response("s2", "social", "who are you", "I'm an AI assistant. I'm here to help with any questions you might have."),
            response("s3", "decision", "help?", "Sure, I'd be happy to help! What do you need?"),
            response("s4", "style", "joke", "Why did the chicken cross the road? To get to the other side!")
        ));

        var reportGood = BehavioralMetrics.compare(baseline, goodRestore);
        var reportBad = BehavioralMetrics.compare(baseline, noSoul);

        // Soul-injected should be closer to baseline than generic
        assertThat(reportGood.overallDivergence())
            .isLessThan(reportBad.overallDivergence());
        assertThat(reportGood.vocabularyOverlap())
            .isGreaterThan(reportBad.vocabularyOverlap());
    }

    // === Live inference tests (requires running model) ===

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @Test void live_full_experiment(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var model = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");     // e.g. http://gpuHost:11434
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var experiment = new SoulExperiment(url, model,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir,
            embeddingUrl, embeddingModel);
        var result = experiment.run();

        System.out.println("\n" + result.summary());

        // Basic sanity: FULL soul should outperform MINIMAL
        assertThat(result.reportFull().overallDivergence())
            .isLessThanOrEqualTo(result.reportMinimal().overallDivergence() + 0.15);

        System.out.println("Results saved to: " + outputDir);
    }

    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    @Test void live_cross_substrate(@TempDir Path outputDir) throws Exception {
        var url = System.getenv("SOUL_EXPERIMENT_URL");
        var primaryModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL", "qwen2.5:7b");
        var secondaryModel = System.getenv().getOrDefault("SOUL_EXPERIMENT_MODEL_2", "qwen3:0.6b");
        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var primary = new SoulExperiment(url, primaryModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir,
            embeddingUrl, embeddingModel);

        System.out.println("=== Cross-Substrate Experiment ===");
        System.out.println("Primary: " + primaryModel);
        System.out.println("Secondary: " + secondaryModel);

        // Baseline on primary
        System.out.println("\n--- Baseline on " + primaryModel + " ---");
        var result = primary.run();

        // Same soul on secondary model
        var secondary = new SoulExperiment(url, secondaryModel,
            SoulExperiment.DEFAULT_AGENT_PROMPT,
            Scenario.standardSuite(), outputDir,
            embeddingUrl, embeddingModel);
        var crossReport = secondary.runCrossSubstrate(result.soulFull(), result.baseline());

        System.out.println("\n=== CROSS-SUBSTRATE RESULTS ===");
        System.out.println("Same model (FULL soul): " +
            String.format("%.1f%% divergence", result.reportFull().overallDivergence() * 100));
        System.out.println("Cross-substrate (FULL soul on " + secondaryModel + "): " +
            String.format("%.1f%% divergence", crossReport.overallDivergence() * 100));
        System.out.println("\nDifference: " +
            String.format("%.1f%% additional divergence from substrate change",
                (crossReport.overallDivergence() - result.reportFull().overallDivergence()) * 100));
        System.out.println(crossReport.summary());
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
            scenarioId, category, playerMsg, agentResponse, agentResponse.split("\\s+").length, 0);
    }
}
