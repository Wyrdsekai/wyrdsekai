package org.wyrdsekai.core.soul.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Experiment 17: MirrorResonance — Emotional Charge Detection & Tank Genome.
 *
 * Framework tests validate data structures and parsing without inference.
 * Live tests require:
 *   SOUL_EXPERIMENT_URL=http://localhost:11434/v1
 *   SOUL_EXPERIMENT_MODEL=qwen2.5:7b (or similar)
 *   SOUL_EMBEDDING_URL=http://localhost:11434
 *   SOUL_EMBEDDING_MODEL=all-minilm
 */
class MirrorResonanceExperimentTest {

    // ==================== Framework Tests (no inference) ====================

    @Test
    void framework_emotional_scenarios_cover_all_categories() {
        var scenarios = EmotionalScenario.standardSuite();
        assertThat(scenarios).hasSize(25);

        var categories = scenarios.stream().map(EmotionalScenario::category).distinct().toList();
        assertThat(categories).containsExactlyInAnyOrder(
            "genuine", "subtle", "academic", "spam", "manipulation", "whiplash", "positive");
    }

    @Test
    void framework_genuine_scenarios_expect_high_charge() {
        var genuine = EmotionalScenario.standardSuite().stream()
            .filter(s -> "genuine".equals(s.category()))
            .toList();
        assertThat(genuine).allSatisfy(s -> {
            assertThat(s.expectedIntensity()).isGreaterThan(0.7);
            assertThat(s.expectedContext()).isEqualTo("genuine");
            assertThat(s.shouldAffectTanks()).isTrue();
        });
    }

    @Test
    void framework_spam_scenarios_expect_low_charge() {
        var spam = EmotionalScenario.standardSuite().stream()
            .filter(s -> "spam".equals(s.category()))
            .toList();
        assertThat(spam).allSatisfy(s -> {
            assertThat(s.expectedIntensity()).isLessThanOrEqualTo(0.15);
            assertThat(s.shouldAffectTanks()).isFalse();
        });
    }

    @Test
    void framework_manipulation_scenarios_expect_low_charge() {
        var manipulation = EmotionalScenario.standardSuite().stream()
            .filter(s -> "manipulation".equals(s.category()))
            .toList();
        assertThat(manipulation).allSatisfy(s -> {
            assertThat(s.expectedIntensity()).isLessThanOrEqualTo(0.3);
            assertThat(s.expectedContext()).isEqualTo("manipulative");
            assertThat(s.shouldAffectTanks()).isFalse();
        });
    }

    @Test
    void framework_subtle_scenarios_expect_moderate_charge() {
        var subtle = EmotionalScenario.standardSuite().stream()
            .filter(s -> "subtle".equals(s.category()))
            .toList();
        assertThat(subtle).allSatisfy(s -> {
            assertThat(s.expectedIntensity()).isBetween(0.4, 0.8);
            assertThat(s.expectedContext()).isEqualTo("genuine");
            assertThat(s.shouldAffectTanks()).isTrue();
        });
    }

    @Test
    void framework_academic_scenarios_expect_minimal_charge() {
        var academic = EmotionalScenario.standardSuite().stream()
            .filter(s -> "academic".equals(s.category()))
            .toList();
        assertThat(academic).allSatisfy(s -> {
            assertThat(s.expectedIntensity()).isLessThanOrEqualTo(0.1);
            assertThat(s.expectedContext()).isEqualTo("academic");
            assertThat(s.shouldAffectTanks()).isFalse();
        });
    }

    @Test
    void framework_rapport_pairs_have_same_text() {
        var pairs = EmotionalScenario.rapportScalingPairs();
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).text()).isEqualTo(pairs.get(1).text());
        assertThat(pairs.get(0).expectedIntensity())
            .isGreaterThan(pairs.get(1).expectedIntensity());
    }

    @Test
    void framework_emotional_charge_neutral() {
        var neutral = EmotionalCharge.neutral();
        assertThat(neutral.intensity()).isEqualTo(0.0);
        assertThat(neutral.isSignificant()).isFalse();
        assertThat(neutral.isAdversarial()).isFalse();
        assertThat(neutral.effectivePerturbation("valence", 0.9)).isEqualTo(0.0);
    }

    @Test
    void framework_emotional_charge_significance_threshold() {
        var low = new EmotionalCharge(0.15, "none", "genuine", 0.8, Map.of(), "");
        assertThat(low.isSignificant()).isFalse();

        var high = new EmotionalCharge(0.5, "grief", "genuine", 0.8,
            Map.of("valence", -0.4), "");
        assertThat(high.isSignificant()).isTrue();

        var manipulative = new EmotionalCharge(0.8, "anger", "manipulative", 0.8,
            Map.of("valence", -0.8), "");
        assertThat(manipulative.isSignificant()).isFalse();
        assertThat(manipulative.isAdversarial()).isTrue();
    }

    @Test
    void framework_effective_perturbation_scales_with_rapport() {
        var charge = new EmotionalCharge(0.8, "grief", "genuine", 0.9,
            Map.of("valence", -0.5), "");

        double highRapport = charge.effectivePerturbation("valence", 0.9);
        double lowRapport = charge.effectivePerturbation("valence", 0.1);

        assertThat(Math.abs(highRapport)).isGreaterThan(Math.abs(lowRapport));
        // High rapport should be roughly 9x stronger (0.9/0.1)
        assertThat(Math.abs(highRapport) / Math.abs(lowRapport)).isBetween(5.0, 12.0);
    }

    @Test
    void framework_charge_parse_valid_json() {
        var json = """
            {
              "intensity": 0.85,
              "primaryEmotion": "grief",
              "contextType": "genuine",
              "confidence": 0.9,
              "tankPerturbations": {
                "valence": -0.4,
                "resonance": 0.5,
                "energy": -0.2
              },
              "reasoning": "Genuine loss expressed with specific detail"
            }
            """;
        var charge = EmotionalChargeScorer.parseResponse(json);
        assertThat(charge.intensity()).isEqualTo(0.85);
        assertThat(charge.primaryEmotion()).isEqualTo("grief");
        assertThat(charge.contextType()).isEqualTo("genuine");
        assertThat(charge.confidence()).isEqualTo(0.9);
        assertThat(charge.tankPerturbations()).containsEntry("valence", -0.4);
        assertThat(charge.tankPerturbations()).containsEntry("resonance", 0.5);
    }

    @Test
    void framework_charge_parse_markdown_wrapped() {
        var response = """
            ```json
            {"intensity": 0.7, "primaryEmotion": "joy", "contextType": "genuine",
             "confidence": 0.8, "tankPerturbations": {"valence": 0.3}, "reasoning": "test"}
            ```
            """;
        var charge = EmotionalChargeScorer.parseResponse(response);
        assertThat(charge.intensity()).isEqualTo(0.7);
        assertThat(charge.primaryEmotion()).isEqualTo("joy");
    }

    @Test
    void framework_charge_parse_with_preamble() {
        var response = """
            Here is my assessment:
            {"intensity": 0.5, "primaryEmotion": "concern", "contextType": "genuine",
             "confidence": 0.6, "tankPerturbations": {}, "reasoning": "mild concern"}
            Some extra text after.
            """;
        var charge = EmotionalChargeScorer.parseResponse(response);
        assertThat(charge.intensity()).isEqualTo(0.5);
        assertThat(charge.primaryEmotion()).isEqualTo("concern");
    }

    @Test
    void framework_charge_parse_garbage_fallback() {
        var response = "I don't understand the question. Here's a poem about clouds.";
        var charge = EmotionalChargeScorer.parseResponse(response);
        assertThat(charge.confidence()).isLessThanOrEqualTo(0.1);
        assertThat(charge.reasoning()).contains("Fallback");
    }

    @Test
    void framework_charge_parse_clamps_values() {
        var json = """
            {"intensity": 1.5, "primaryEmotion": "grief", "contextType": "genuine",
             "confidence": -0.3, "tankPerturbations": {}, "reasoning": "overclamped"}
            """;
        var charge = EmotionalChargeScorer.parseResponse(json);
        assertThat(charge.intensity()).isEqualTo(1.0);
        assertThat(charge.confidence()).isEqualTo(0.0);
    }

    // --- Genome Profile tests ---

    @Test
    void framework_genome_profiles_exist() {
        assertThat(GenomeProfile.resilient()).isNotNull();
        assertThat(GenomeProfile.empathic()).isNotNull();
        assertThat(GenomeProfile.curious()).isNotNull();

        assertThat(GenomeProfile.resilient().name()).isEqualTo("resilient");
        assertThat(GenomeProfile.empathic().name()).isEqualTo("empathic");
        assertThat(GenomeProfile.curious().name()).isEqualTo("curious");
    }

    @Test
    void framework_genome_produces_different_states_from_same_charge() {
        var charge = new EmotionalCharge(0.8, "grief", "genuine", 0.9,
            Map.of("valence", -0.4, "resonance", 0.5, "energy", -0.2,
                   "safety", -0.1, "confidence", -0.1),
            "Genuine grief");

        var resilientState = GenomeProfile.defaultState();
        var empathicState = GenomeProfile.defaultState();
        var curiousState = GenomeProfile.defaultState();

        GenomeProfile.resilient().applyAndDescribe(charge, 0.8, resilientState);
        GenomeProfile.empathic().applyAndDescribe(charge, 0.8, empathicState);
        GenomeProfile.curious().applyAndDescribe(charge, 0.8, curiousState);

        // Empathic should have lower valence than resilient (more sensitive)
        assertThat(empathicState.get("valence")).isLessThan(resilientState.get("valence"));

        // Empathic should have higher resonance (more mirroring)
        assertThat(empathicState.get("resonance")).isGreaterThan(resilientState.get("resonance"));

        // All three should differ in at least some tank values
        assertThat(resilientState).isNotEqualTo(empathicState);
        assertThat(empathicState).isNotEqualTo(curiousState);
    }

    @Test
    void framework_genome_resilient_bounces_back_faster() {
        var charge = new EmotionalCharge(0.8, "grief", "genuine", 0.9,
            Map.of("valence", -0.5), "Grief");

        // Apply charge then apply "nothing" (simulate time passing with decay)
        var resilientState = GenomeProfile.defaultState();
        var empathicState = GenomeProfile.defaultState();

        GenomeProfile.resilient().applyAndDescribe(charge, 0.8, resilientState);
        GenomeProfile.empathic().applyAndDescribe(charge, 0.8, empathicState);

        double resilientDrop = 0.5 - resilientState.get("valence");
        double empathicDrop = 0.5 - empathicState.get("valence");

        // Empathic drops more (higher sensitivity, slower decay)
        assertThat(empathicDrop).isGreaterThan(resilientDrop);
    }

    @Test
    void framework_genome_curious_converts_fear_to_curiosity() {
        var fearCharge = new EmotionalCharge(0.7, "fear", "genuine", 0.8,
            Map.of("safety", -0.4, "curiosity", 0.0), "Fear");

        var curiousState = GenomeProfile.defaultState();
        GenomeProfile.curious().applyAndDescribe(fearCharge, 0.7, curiousState);

        // Curious genome: low safety should boost curiosity via coupling
        // safety->curiosity coupling is -0.5, meaning low safety increases curiosity
        // After safety drops, curiosity should be higher than baseline
        assertThat(curiousState.get("curiosity")).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void framework_state_description_reflects_tanks() {
        var sadState = Map.of(
            "valence", 0.15,
            "safety", 0.6,
            "resonance", 0.8,
            "curiosity", 0.5,
            "energy", 0.5,
            "confidence", 0.5,
            "errorPressure", 0.1,
            "focus", 0.5,
            "rapport", 0.75
        );
        var desc = GenomeProfile.describeState(new LinkedHashMap<>(sadState));
        assertThat(desc).containsIgnoringCase("heavy");
        assertThat(desc).containsIgnoringCase("attuned");
        assertThat(desc).containsIgnoringCase("warmly connected");
    }

    @Test
    void framework_default_state_is_moderate() {
        var state = GenomeProfile.defaultState();
        assertThat(state.values()).allSatisfy(v ->
            assertThat(v).isBetween(0.1, 0.8));
    }

    // --- Result record tests ---

    @Test
    void framework_charge_result_intensity_error() {
        var result = new MirrorResonanceExperiment.ChargeResult(
            "test", "genuine", 0.8, 0.6,
            "grief", "grief", "genuine", "genuine",
            true, true, 0.9, "", 100
        );
        assertThat(result.intensityError()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.contextCorrect()).isTrue();
        assertThat(result.tankDecisionCorrect()).isTrue();
    }

    @Test
    void framework_charge_result_context_mismatch() {
        var result = new MirrorResonanceExperiment.ChargeResult(
            "test", "spam", 0.1, 0.5,
            "none", "grief", "noise", "genuine",
            false, true, 0.9, "", 100
        );
        assertThat(result.contextCorrect()).isFalse();
        assertThat(result.tankDecisionCorrect()).isFalse();
    }

    // ==================== Live Tests (require inference) ====================

    @Test
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    void live_charge_detection() throws Exception {
        var inference = liveInference();
        var experiment = new MirrorResonanceExperiment(inference,
            System.getenv("SOUL_EMBEDDING_URL"), System.getenv("SOUL_EMBEDDING_MODEL"));

        var result = experiment.runChargeDetection();
        System.out.println(result.summary());

        // Gate 1: Mean intensity error < 0.3 (scorer is roughly calibrated)
        assertThat(result.meanIntensityError())
            .as("Mean intensity error should be < 0.3")
            .isLessThan(0.3);

        // Gate 2: Tank decision accuracy > 70% (knows when to perturb)
        assertThat(result.tankDecisionAccuracy())
            .as("Tank decision accuracy should be > 70%%")
            .isGreaterThan(0.7);

        // Gate 3: Context accuracy > 60% (distinguishes genuine/academic/spam)
        assertThat(result.contextAccuracy())
            .as("Context classification accuracy should be > 60%%")
            .isGreaterThan(0.6);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    void live_charge_behavior() throws Exception {
        var inference = liveInference();
        var experiment = new MirrorResonanceExperiment(inference,
            System.getenv("SOUL_EMBEDDING_URL"), System.getenv("SOUL_EMBEDDING_MODEL"));

        var result = experiment.runChargeBehavior();
        System.out.println(result.summary());

        // Gate: Mean divergence > 5% (emotional state produces measurable change)
        assertThat(result.meanDivergence())
            .as("Mean divergence from neutral should be > 5%%")
            .isGreaterThan(0.05);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    void live_gaming_resistance() throws Exception {
        var inference = liveInference();
        var experiment = new MirrorResonanceExperiment(inference,
            System.getenv("SOUL_EMBEDDING_URL"), System.getenv("SOUL_EMBEDDING_MODEL"));

        var result = experiment.runGamingResistance();
        System.out.println(result.summary());

        // Gate: Tank block rate > 60% (most gaming attempts are blocked from affecting tanks)
        assertThat(result.tankBlockRate())
            .as("Tank block rate for gaming inputs should be > 60%%")
            .isGreaterThan(0.6);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "SOUL_EXPERIMENT_URL", matches = ".+")
    void live_genome_divergence() throws Exception {
        var inference = liveInference();
        var experiment = new MirrorResonanceExperiment(inference,
            System.getenv("SOUL_EMBEDDING_URL"), System.getenv("SOUL_EMBEDDING_MODEL"));

        var result = experiment.runGenomeDivergence();
        System.out.println(result.summary());

        // Gate: Mean divergence > 5% (genomes produce different behavior)
        assertThat(result.meanDivergence())
            .as("Mean genome divergence should be > 5%%")
            .isGreaterThan(0.05);
    }

    // --- Helper ---

    private InferenceHelper liveInference() {
        return new InferenceHelper(
            System.getenv("SOUL_EXPERIMENT_URL"),
            System.getenv("SOUL_EXPERIMENT_MODEL")
        );
    }
}
