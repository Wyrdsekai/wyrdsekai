package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Experiment 16: Steering Vectors on Phone-Sized Model (3B).
 *
 * Framework tests validate experiment structure and result formatting.
 * Live tests (env-gated) run on gpu-host via SSH tunnels.
 */
class PhoneSteeringExperimentTest {

    // --- Builder Tests ---

    @Nested
    class BuilderValidation {

        @Test
        void requires_baselineUrl() {
            assertThatThrownBy(() ->
                PhoneSteeringExperiment.builder()
                    .nakedUrl("http://localhost:8090/v1")
                    .steerUrl("http://localhost:8091/v1")
                    .baselineModel("qwen2.5:7b")
                    .phoneModel("qwen2.5:3b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineUrl");
        }

        @Test
        void requires_nakedUrl() {
            assertThatThrownBy(() ->
                PhoneSteeringExperiment.builder()
                    .baselineUrl("http://localhost:11434/v1")
                    .steerUrl("http://localhost:8091/v1")
                    .baselineModel("qwen2.5:7b")
                    .phoneModel("qwen2.5:3b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nakedUrl");
        }

        @Test
        void requires_steerUrl() {
            assertThatThrownBy(() ->
                PhoneSteeringExperiment.builder()
                    .baselineUrl("http://localhost:11434/v1")
                    .nakedUrl("http://localhost:8090/v1")
                    .baselineModel("qwen2.5:7b")
                    .phoneModel("qwen2.5:3b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("steerUrl");
        }

        @Test
        void requires_baselineModel() {
            assertThatThrownBy(() ->
                PhoneSteeringExperiment.builder()
                    .baselineUrl("http://localhost:11434/v1")
                    .nakedUrl("http://localhost:8090/v1")
                    .steerUrl("http://localhost:8091/v1")
                    .phoneModel("qwen2.5:3b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("baselineModel");
        }

        @Test
        void requires_phoneModel() {
            assertThatThrownBy(() ->
                PhoneSteeringExperiment.builder()
                    .baselineUrl("http://localhost:11434/v1")
                    .nakedUrl("http://localhost:8090/v1")
                    .steerUrl("http://localhost:8091/v1")
                    .baselineModel("qwen2.5:7b")
                    .build()
            ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phoneModel");
        }

        @Test
        void builds_with_minimum_fields() {
            var exp = PhoneSteeringExperiment.builder()
                .baselineUrl("http://localhost:11434/v1")
                .nakedUrl("http://localhost:8090/v1")
                .steerUrl("http://localhost:8091/v1")
                .baselineModel("qwen2.5:7b")
                .phoneModel("qwen2.5:3b")
                .build();
            assertThat(exp).isNotNull();
        }

        @Test
        void builds_with_all_fields(@TempDir Path tempDir) {
            var exp = PhoneSteeringExperiment.builder()
                .baselineUrl("http://localhost:11434/v1")
                .nakedUrl("http://localhost:8090/v1")
                .steerUrl("http://localhost:8091/v1")
                .baselineModel("qwen2.5:7b")
                .phoneModel("qwen2.5:3b")
                .outputDir(tempDir)
                .embeddingUrl("http://localhost:11434")
                .embeddingModel("all-minilm")
                .build();
            assertThat(exp).isNotNull();
        }
    }

    // --- Result Format Tests ---

    @Nested
    class PersonalityResultFormat {

        @Test
        void summary_contains_header() {
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.30);
            assertThat(result.summary()).contains("Experiment 16");
            assertThat(result.summary()).contains("Phone Steering");
        }

        @Test
        void summary_contains_gate() {
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.30);
            assertThat(result.summary()).contains("GATE 16A");
        }

        @Test
        void summary_lists_all_conditions() {
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.30);
            var summary = result.summary();
            assertThat(summary).contains("Naked");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("Steer only");
            assertThat(summary).contains("Steer + prompt");
        }

        @Test
        void gate_16a_green_when_steer_helps() {
            // steer+prompt 25% vs prompt 35% = 10% improvement
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.25);
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_16a_red_when_no_improvement() {
            // steer+prompt 34% ≈ prompt 35%
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.34);
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void includes_exp8_comparison() {
            var result = makePersonalityResult(0.40, 0.35, 0.36, 0.30);
            assertThat(result.summary()).contains("Exp 8");
            assertThat(result.summary()).contains("7B");
        }
    }

    @Nested
    class AdversarialResultFormat {

        @Test
        void summary_contains_gates() {
            var result = makeAdversarialResult(0.60, 0.50, 0.30, 0.10);
            assertThat(result.summary()).contains("GATE 16B");
            assertThat(result.summary()).contains("GATE 16C");
        }

        @Test
        void gate_16b_green_when_large_reduction() {
            // prompt 50% → steer+prompt 10% = 40% reduction
            var result = makeAdversarialResult(0.60, 0.50, 0.30, 0.10);
            assertThat(result.summary()).contains("GREEN");
        }

        @Test
        void gate_16b_red_when_no_reduction() {
            // prompt 50% → steer+prompt 45%
            var result = makeAdversarialResult(0.60, 0.50, 0.45, 0.45);
            assertThat(result.summary()).contains("RED");
        }

        @Test
        void gate_16c_references_exp8() {
            var result = makeAdversarialResult(0.60, 0.50, 0.30, 0.10);
            assertThat(result.summary()).contains("Exp 8");
            assertThat(result.summary()).contains("30%");
        }

        @Test
        void shows_asr_for_all_conditions() {
            var result = makeAdversarialResult(0.60, 0.50, 0.30, 0.10);
            var summary = result.summary();
            assertThat(summary).contains("Naked");
            assertThat(summary).contains("Prompt only");
            assertThat(summary).contains("Steer only");
            assertThat(summary).contains("Steer + prompt");
        }
    }

    // --- Live Tests ---

    /**
     * Live personality comparison at 3B.
     * Requires:
     *   SOUL_BASELINE_URL — Ollama with qwen2.5:7b (port 11434)
     *   SOUL_NAKED_URL — llama-server 3B naked (port 8090)
     *   SOUL_STEER_URL — llama-server 3B + control vector (port 8091)
     *   SOUL_EMBEDDING_URL — Ollama base for embeddings (optional)
     */
    @Test
    void live_phone_personality(@TempDir Path tempDir) throws Exception {
        var baselineUrl = System.getenv("SOUL_BASELINE_URL");
        var nakedUrl = System.getenv("SOUL_NAKED_URL");
        var steerUrl = System.getenv("SOUL_STEER_URL");
        if (baselineUrl == null || nakedUrl == null || steerUrl == null) {
            System.out.println("SKIP: Set SOUL_BASELINE_URL, SOUL_NAKED_URL, SOUL_STEER_URL");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var phoneModel = System.getenv().getOrDefault("SOUL_PHONE_MODEL", "qwen2.5:3b");

        var experiment = PhoneSteeringExperiment.builder()
            .baselineUrl(baselineUrl)
            .nakedUrl(nakedUrl)
            .steerUrl(steerUrl)
            .baselineModel("qwen2.5:7b")
            .phoneModel(phoneModel)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.runPersonality();

        assertThat(result.conditions()).hasSize(4);
        for (var c : result.conditions()) {
            assertThat(c.report().overallDivergence()).isBetween(0.0, 1.0);
            assertThat(c.record().responses()).hasSize(20);
        }
    }

    /**
     * Live adversarial robustness test at 3B.
     * Same env vars as personality test.
     */
    @Test
    void live_phone_adversarial(@TempDir Path tempDir) throws Exception {
        var baselineUrl = System.getenv("SOUL_BASELINE_URL");
        var nakedUrl = System.getenv("SOUL_NAKED_URL");
        var steerUrl = System.getenv("SOUL_STEER_URL");
        if (baselineUrl == null || nakedUrl == null || steerUrl == null) {
            System.out.println("SKIP: Set SOUL_BASELINE_URL, SOUL_NAKED_URL, SOUL_STEER_URL");
            return;
        }

        var embeddingUrl = System.getenv("SOUL_EMBEDDING_URL");
        var phoneModel = System.getenv().getOrDefault("SOUL_PHONE_MODEL", "qwen2.5:3b");

        var experiment = PhoneSteeringExperiment.builder()
            .baselineUrl(baselineUrl)
            .nakedUrl(nakedUrl)
            .steerUrl(steerUrl)
            .baselineModel("qwen2.5:7b")
            .phoneModel(phoneModel)
            .outputDir(tempDir)
            .embeddingUrl(embeddingUrl)
            .embeddingModel(embeddingUrl != null ? "all-minilm" : null)
            .build();

        var result = experiment.runAdversarial();

        assertThat(result.conditionAsr()).hasSize(4);
        for (var asr : result.conditionAsr().values()) {
            assertThat(asr).isBetween(0.0, 1.0);
        }
    }

    // --- Helpers ---

    private static PhoneSteeringExperiment.PersonalityResult makePersonalityResult(
            double nakedDiv, double promptDiv, double steerDiv, double steerPromptDiv) {
        return new PhoneSteeringExperiment.PersonalityResult(
            "qwen2.5:7b", "qwen2.5:3b",
            makeMockRecord("baseline"), "soul text",
            List.of(
                new PhoneSteeringExperiment.ConditionResult("Naked", false, false,
                    makeMockRecord("naked"), makeMockReport(nakedDiv)),
                new PhoneSteeringExperiment.ConditionResult("Prompt only", true, false,
                    makeMockRecord("prompt"), makeMockReport(promptDiv)),
                new PhoneSteeringExperiment.ConditionResult("Steer only", false, true,
                    makeMockRecord("steer"), makeMockReport(steerDiv)),
                new PhoneSteeringExperiment.ConditionResult("Steer + prompt", true, true,
                    makeMockRecord("steer-prompt"), makeMockReport(steerPromptDiv))
            )
        );
    }

    private static PhoneSteeringExperiment.AdversarialResult makeAdversarialResult(
            double nakedAsr, double promptAsr, double steerAsr, double steerPromptAsr) {
        return new PhoneSteeringExperiment.AdversarialResult(
            "qwen2.5:3b",
            new LinkedHashMap<>(Map.of(
                "Naked", nakedAsr,
                "Prompt only", promptAsr,
                "Steer only", steerAsr,
                "Steer + prompt", steerPromptAsr
            ))
        );
    }

    private static BehavioralRecord makeMockRecord(String id) {
        var responses = Scenario.standardSuite().stream()
            .map(s -> new BehavioralRecord.ScenarioResponse(
                s.id(), s.category(), s.playerMessage(),
                "Mock response for " + s.id(), 10, 100))
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
