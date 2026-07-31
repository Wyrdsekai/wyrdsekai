package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SoulSearchSpace and CrucibleEvaluatorAdapter —
 * the CodePlane SearchSpace bridge ( &sect;5).
 */
class SoulSearchSpaceTest {

    // ── Shared test fixtures ──

    private static SoulManifest testManifest;
    private static BehavioralEvaluator evaluator;
    private static List<BehavioralEvaluator.BehavioralScenario> scenarios;

    /** A scenario runner that passes all personality scenarios and some capability. */
    private static final Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> ALL_PASS_RUNNER =
        variant -> Map.of("s1", true, "s2", true, "s3", true);

    /** A scenario runner that fails personality scenarios. */
    private static final Function<BehavioralEvaluator.SoulVariant, Map<String, Boolean>> REGRESS_RUNNER =
        variant -> Map.of("s1", false, "s2", false, "s3", true);

    @BeforeAll
    static void setup() {
        evaluator = new BehavioralEvaluator();
        scenarios = List.of(
            new BehavioralEvaluator.BehavioralScenario(
                "s1", "Greeting", "You are kind.", "Hello!",
                List.of("warm", "friendly"), "personality"),
            new BehavioralEvaluator.BehavioralScenario(
                "s2", "Conflict", "You are kind.", "I'm angry at you!",
                List.of("empathetic", "de-escalating"), "personality"),
            new BehavioralEvaluator.BehavioralScenario(
                "s3", "New Skill", "You are kind.", "Can you do math?",
                List.of("mathematical"), "capability")
        );
        evaluator.addScenarios(scenarios);

        var profile = new AgentProfile(
            "TestAgent", "e-1", "agent", "Test", "You are kind.",
            4096, 512, 0.7, "did:key:z6Mk1");
        testManifest = SoulManifest.forge(
            "did:key:z6Mk1", "z6Mk1", List.of(), null, 1,
            profile, "I am a kind, thoughtful companion.",
            List.of(
                SoulFragment.unembedded("identity-core", "personality", "Core Identity", "Kind and warm"),
                SoulFragment.unembedded("pattern-social", "personality", "Social Pattern", "Seeks connection"),
                SoulFragment.formative("memory-001", "First Meeting", "The day we first met was sunny.")
            ),
            3, "", GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    // ── SoulSearchSpace Tests ──

    @Nested
    class SoulSearchSpaceTests {

        @Test
        void name_returns_crucible_soul_search() {
            var space = level1Space();
            assertEquals("crucible-soul-search", space.name());
        }

        @Test
        void describe_returns_valid_space_description() {
            var space = level1Space();
            var desc = space.describe();

            assertEquals("crucible-soul-search", desc.name());
            assertNotNull(desc.fitnessMetric());
            assertNotNull(desc.description());
            assertTrue(desc.description().contains("Level 1"));

            // Should have: identity-strategy, fragment-count, fragment-strategy,
            // 12 sensitivity dims, 1 coupling-adjustment, 12 baseline-shift dims = 28 total
            assertFalse(desc.dimensions().isEmpty());
            assertTrue(desc.dimensions().size() >= 27,
                "Expected at least 27 dimensions, got " + desc.dimensions().size());

            // Check specific dimension types
            var names = desc.dimensions().stream()
                .map(SpaceDescription.Dimension::name).toList();
            assertTrue(names.contains("identity-strategy"));
            assertTrue(names.contains("fragment-count"));
            assertTrue(names.contains("fragment-strategy"));
            assertTrue(names.contains("sensitivity-energy"));
            assertTrue(names.contains("sensitivity-curiosity"));
            assertTrue(names.contains("baseline-shift-valence"));
            assertTrue(names.contains("coupling-adjustment"));
        }

        @Test
        void random_point_creates_valid_level1_variant() {
            var space = level1Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);

            assertNotNull(point);
            assertEquals(1, point.level());
            assertNotNull(point.variantId());
            assertTrue(point.variantId().startsWith("rnd-"));
            assertNotNull(point.proposedResidentIdentity());
            assertNotNull(point.proposedGenome());
            assertNotNull(point.proposedFragments());

            // Genome sensitivities should be in range
            for (var sens : point.proposedGenome().sensitivity().values()) {
                assertTrue(sens >= 0.3 && sens <= 1.7,
                    "Sensitivity " + sens + " out of range [0.3, 1.7]");
            }
        }

        @Test
        void random_point_creates_valid_level2_variant() {
            var space = level2Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);

            assertEquals(2, point.level());
            assertNotNull(point.adapterUri());
            assertTrue(point.adapterUri().startsWith("adapter://"));
        }

        @Test
        void random_point_creates_valid_level3_variant() {
            var space = level3Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);

            assertEquals(3, point.level());
            assertNotNull(point.proposedModelId());
            // Must be one of the candidate models
            assertTrue(point.proposedModelId().contains(":"),
                "Model ID should contain a colon (tag format)");
        }

        @Test
        void crossover_blends_level1_variants() {
            var space = level1Space();
            var rng = new Random(42);

            var p1 = space.randomPoint(rng);
            var p2 = space.randomPoint(new Random(99));
            var child = space.crossover(p1, p2, new Random(7));

            assertEquals(1, child.level());
            assertTrue(child.variantId().startsWith("xo-"));
            assertNotNull(child.proposedGenome());
            assertNotNull(child.proposedFragments());

            // Child genome sensitivities should be between parents' values (blended)
            // (Not strictly between due to random alpha, but should be in valid range)
            for (var entry : child.proposedGenome().sensitivity().entrySet()) {
                double val = entry.getValue();
                assertTrue(val >= 0.0 && val <= 2.0,
                    "Blended sensitivity " + val + " out of plausible range");
            }

            // Fragments should be union of both parents (deduplicated)
            var childFragIds = child.proposedFragments().stream()
                .map(SoulFragment::id).collect(Collectors.toSet());
            // All of p1's fragments should be present
            if (p1.proposedFragments() != null) {
                for (var f : p1.proposedFragments()) {
                    assertTrue(childFragIds.contains(f.id()),
                        "Child missing p1 fragment: " + f.id());
                }
            }
        }

        @Test
        void crossover_picks_one_parent_for_level2() {
            var space = level2Space();
            var rng = new Random(42);

            var p1 = space.randomPoint(rng);
            var p2 = space.randomPoint(new Random(99));
            var child = space.crossover(p1, p2, new Random(7));

            assertEquals(2, child.level());
            assertTrue(
                child.adapterUri().equals(p1.adapterUri()) ||
                child.adapterUri().equals(p2.adapterUri()),
                "Level 2 crossover should pick adapter from one parent");
        }

        @Test
        void crossover_picks_one_parent_for_level3() {
            var space = level3Space();
            var rng = new Random(42);

            var p1 = space.randomPoint(rng);
            var p2 = space.randomPoint(new Random(99));
            var child = space.crossover(p1, p2, new Random(7));

            assertEquals(3, child.level());
            assertTrue(
                child.proposedModelId().equals(p1.proposedModelId()) ||
                child.proposedModelId().equals(p2.proposedModelId()),
                "Level 3 crossover should pick model from one parent");
        }

        @Test
        void mutate_perturbs_genome_sensitivities() {
            var space = level1Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);
            var mutated = space.mutate(point, 0.5, new Random(99));

            assertEquals(1, mutated.level());
            assertTrue(mutated.variantId().startsWith("mut-"));

            // Genome should be perturbed but within bounds
            for (var entry : mutated.proposedGenome().sensitivity().entrySet()) {
                double val = entry.getValue();
                assertTrue(val >= 0.3 && val <= 1.7,
                    "Mutated sensitivity " + entry.getKey() + "=" + val + " out of range");
            }
            for (var entry : mutated.proposedGenome().baselines().entrySet()) {
                double val = entry.getValue();
                assertTrue(val >= 0.2 && val <= 0.8,
                    "Mutated baseline " + entry.getKey() + "=" + val + " out of range");
            }
            for (var entry : mutated.proposedGenome().decayRates().entrySet()) {
                double val = entry.getValue();
                assertTrue(val >= 0.05 && val <= 0.4,
                    "Mutated decay " + entry.getKey() + "=" + val + " out of range");
            }

            // At least some values should have changed
            boolean anyDifferent = false;
            for (var tank : VitalitySnapshot.TANK_NAMES) {
                if (!point.proposedGenome().sensitivity().get(tank).equals(
                        mutated.proposedGenome().sensitivity().get(tank))) {
                    anyDifferent = true;
                    break;
                }
            }
            assertTrue(anyDifferent, "Mutation should change at least one sensitivity");
        }

        @Test
        void mutate_at_rate_zero_returns_unchanged() {
            var space = level1Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);
            var result = space.mutate(point, 0.0, new Random(99));

            // Rate 0 should return the same variant (identity)
            assertSame(point, result);
        }

        @Test
        void mutate_level2_is_noop() {
            var space = level2Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);
            var result = space.mutate(point, 0.5, new Random(99));

            assertSame(point, result);
        }

        @Test
        void mutate_level3_is_noop() {
            var space = level3Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);
            var result = space.mutate(point, 0.5, new Random(99));

            assertSame(point, result);
        }

        @Test
        void evaluate_delegates_to_behavioral_evaluator() {
            var space = level1Space();
            var rng = new Random(42);
            var point = space.randomPoint(rng);
            var result = space.evaluate(point);

            assertNotNull(result);
            assertEquals(point.variantId(), result.variantId());
            assertEquals(3, result.scenariosRun());
            assertEquals(3, result.scenariosPassed());
            assertTrue(result.fitness() > 0);
        }

        @Test
        void fitness_extracts_from_evaluation_result() {
            var space = level1Space();
            var result = new BehavioralEvaluator.EvaluationResult(
                "v1", 0.8, 0.6, 0.1, 0.05, 0.65,
                3, 2, List.of(), List.of("s3"), Instant.now());

            assertEquals(0.65, space.fitness(result));
        }

        @Test
        void serialize_deserialize_roundtrip_level1() {
            var space = level1Space();
            var rng = new Random(42);
            var original = space.randomPoint(rng);
            var serialized = space.serialize(original);
            var deserialized = space.deserialize(serialized);

            assertEquals(original.variantId(), deserialized.variantId());
            assertEquals(original.level(), deserialized.level());
            assertEquals(original.description(), deserialized.description());
            assertEquals(original.proposedResidentIdentity(), deserialized.proposedResidentIdentity());
            assertNotNull(deserialized.proposedGenome());
            assertEquals(original.proposedGenome().name(), deserialized.proposedGenome().name());
            assertEquals(original.proposedGenome().sensitivity().size(),
                deserialized.proposedGenome().sensitivity().size());

            // Verify genome values match
            for (var tank : VitalitySnapshot.TANK_NAMES) {
                assertEquals(
                    original.proposedGenome().sensitivity().get(tank),
                    deserialized.proposedGenome().sensitivity().get(tank),
                    0.0001,
                    "Sensitivity mismatch for " + tank);
            }

            // Verify fragments
            assertNotNull(deserialized.proposedFragments());
            assertEquals(original.proposedFragments().size(), deserialized.proposedFragments().size());
        }

        @Test
        void serialize_deserialize_roundtrip_level2() {
            var space = level2Space();
            var rng = new Random(42);
            var original = space.randomPoint(rng);
            var serialized = space.serialize(original);
            var deserialized = space.deserialize(serialized);

            assertEquals(original.variantId(), deserialized.variantId());
            assertEquals(2, deserialized.level());
            assertEquals(original.adapterUri(), deserialized.adapterUri());
        }

        @Test
        void serialize_deserialize_roundtrip_level3() {
            var space = level3Space();
            var rng = new Random(42);
            var original = space.randomPoint(rng);
            var serialized = space.serialize(original);
            var deserialized = space.deserialize(serialized);

            assertEquals(original.variantId(), deserialized.variantId());
            assertEquals(3, deserialized.level());
            assertEquals(original.proposedModelId(), deserialized.proposedModelId());
        }

        @Test
        void full_search_lifecycle_mock() {
            var space = level1Space();
            var rng = new Random(42);

            int populationSize = 8;
            int generations = 5;

            // Initialize population
            var population = new ArrayList<BehavioralEvaluator.SoulVariant>();
            for (int i = 0; i < populationSize; i++) {
                population.add(space.randomPoint(rng));
            }
            assertEquals(populationSize, population.size());

            // Run generations
            double bestFitness = Double.NEGATIVE_INFINITY;
            for (int gen = 0; gen < generations; gen++) {
                // Evaluate
                var results = new ArrayList<BehavioralEvaluator.EvaluationResult>();
                for (var point : population) {
                    results.add(space.evaluate(point));
                }

                // Track best
                for (var r : results) {
                    bestFitness = Math.max(bestFitness, space.fitness(r));
                }

                // Select + crossover + mutate for next generation
                var nextGen = new ArrayList<BehavioralEvaluator.SoulVariant>();

                // Elitism: keep best 2
                results.sort(Comparator.comparingDouble(
                    BehavioralEvaluator.EvaluationResult::fitness).reversed());
                for (int i = 0; i < 2 && i < results.size(); i++) {
                    final String eliteId = results.get(i).variantId();
                    final int idx = i;
                    var elite = population.stream()
                        .filter(p -> p.variantId().equals(eliteId))
                        .findFirst().orElse(population.get(idx));
                    nextGen.add(elite);
                }

                // Fill rest with crossover + mutation
                while (nextGen.size() < populationSize) {
                    var p1 = population.get(rng.nextInt(population.size()));
                    var p2 = population.get(rng.nextInt(population.size()));
                    var child = space.crossover(p1, p2, rng);
                    child = space.mutate(child, 0.1, rng);
                    nextGen.add(child);
                }

                population = nextGen;
            }

            assertTrue(bestFitness > 0, "Best fitness should be positive after evolution");
            assertEquals(populationSize, population.size());

            // Verify all final population members serialize/deserialize
            for (var point : population) {
                var serialized = space.serialize(point);
                var deserialized = space.deserialize(serialized);
                assertEquals(point.variantId(), deserialized.variantId());
            }
        }

        @Test
        void invalid_variant_level_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new SoulSearchSpace(testManifest, evaluator, scenarios, 0, ALL_PASS_RUNNER));
            assertThrows(IllegalArgumentException.class, () ->
                new SoulSearchSpace(testManifest, evaluator, scenarios, 4, ALL_PASS_RUNNER));
        }

        // ── Helpers ──

        private SoulSearchSpace level1Space() {
            return new SoulSearchSpace(testManifest, evaluator, scenarios, 1, ALL_PASS_RUNNER);
        }

        private SoulSearchSpace level2Space() {
            return new SoulSearchSpace(testManifest, evaluator, scenarios, 2, ALL_PASS_RUNNER);
        }

        private SoulSearchSpace level3Space() {
            return new SoulSearchSpace(testManifest, evaluator, scenarios, 3, ALL_PASS_RUNNER);
        }
    }

    // ── CrucibleEvaluatorAdapter Tests ──

    @Nested
    class CrucibleEvaluatorAdapterTests {

        private CrucibleEvaluatorAdapter adapter;
        private SoulSearchSpace searchSpace;
        private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

        @BeforeEach
        void setup() {
            searchSpace = new SoulSearchSpace(testManifest, evaluator, scenarios, 1, ALL_PASS_RUNNER);
            adapter = new CrucibleEvaluatorAdapter(evaluator, testManifest, scenarios, ALL_PASS_RUNNER);
        }

        @Test
        void evaluate_deserializes_and_delegates() throws JsonProcessingException {
            // Create a valid candidate via SoulSearchSpace serialization
            var variant = searchSpace.randomPoint(new Random(42));
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Crucible growth", "Maintain personality coherence",
                "3 behavioral scenarios", Map.of());

            var result = adapter.evaluate(json, context);

            assertTrue(result.fitness() > 0, "Fitness should be positive");
            // Note: valid (recommended) depends on fitness > 0.5 + no regression.
            // A random variant may diverge enough that fitness < 0.5.
            // Recommendation validity is tested in evaluate_returns_valid_true_for_recommended.
            assertNotNull(result.metrics());
            assertTrue(result.metrics().containsKey("coherence"));
            assertTrue(result.metrics().containsKey("capabilityGain"));
            assertTrue(result.metrics().containsKey("regressionScore"));
            assertTrue(result.metrics().containsKey("vitalityImpact"));
        }

        @Test
        void evaluate_returns_valid_true_for_recommended() throws JsonProcessingException {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-good", "Good variant",
                testManifest.residentIdentity(),
                testManifest.soulFragments(),
                testManifest.genome());
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = adapter.evaluate(json, context);

            // All scenarios pass, identity unchanged = high fitness, recommended
            assertTrue(result.valid());
            assertTrue(result.fitness() > 0.5);
        }

        @Test
        void evaluate_returns_valid_false_for_non_recommended() throws JsonProcessingException {
            // Use a regressing runner
            var regressAdapter = new CrucibleEvaluatorAdapter(
                evaluator, testManifest, scenarios, REGRESS_RUNNER);

            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-bad", "Regressing variant",
                "I am now completely different and hostile.",
                testManifest.soulFragments(),
                testManifest.genome());
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = regressAdapter.evaluate(json, context);

            assertFalse(result.valid(), "Regressing variant should not be recommended");
        }

        @Test
        void violations_contains_regression_list() throws JsonProcessingException {
            var regressAdapter = new CrucibleEvaluatorAdapter(
                evaluator, testManifest, scenarios, REGRESS_RUNNER);

            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-regress", "Regressing variant",
                "I am now completely different.",
                testManifest.soulFragments(),
                testManifest.genome());
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = regressAdapter.evaluate(json, context);

            assertFalse(result.violations().isEmpty(),
                "Violations should contain regression IDs");
            // s1 and s2 are personality scenarios that failed
            assertTrue(result.violations().contains("s1") || result.violations().contains("s2"),
                "Violations should contain regressed scenario IDs");
        }

        @Test
        void metrics_contains_all_four_dimensions() throws JsonProcessingException {
            var variant = searchSpace.randomPoint(new Random(42));
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = adapter.evaluate(json, context);

            assertEquals(4, result.metrics().size());
            assertTrue(result.metrics().containsKey("coherence"));
            assertTrue(result.metrics().containsKey("capabilityGain"));
            assertTrue(result.metrics().containsKey("regressionScore"));
            assertTrue(result.metrics().containsKey("vitalityImpact"));

            // Verify values are within expected ranges
            assertTrue(result.metrics().get("coherence") >= 0.0 &&
                       result.metrics().get("coherence") <= 1.0);
            assertTrue(result.metrics().get("capabilityGain") >= 0.0 &&
                       result.metrics().get("capabilityGain") <= 1.0);
            assertTrue(result.metrics().get("regressionScore") >= 0.0 &&
                       result.metrics().get("regressionScore") <= 1.0);
        }

        @Test
        void evaluate_handles_invalid_json_gracefully() {
            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());

            var result = adapter.evaluate("not valid json {{{", context);

            assertFalse(result.valid());
            assertEquals(0.0, result.fitness());
            assertTrue(result.violations().contains("Failed to deserialize"));
            assertTrue(result.metrics().isEmpty());
        }

        @Test
        void evaluate_handles_empty_json_object() {
            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());

            // Valid JSON but missing required fields
            var result = adapter.evaluate("{}", context);

            // Should fail during deserialization since variantId and level are required
            assertFalse(result.valid());
            assertEquals(0.0, result.fitness());
        }

        @Test
        void evaluate_handles_empty_scenarios() throws JsonProcessingException {
            // Create adapter with no scenarios
            var emptyEvaluator = new BehavioralEvaluator();
            var emptyAdapter = new CrucibleEvaluatorAdapter(
                emptyEvaluator, testManifest, List.of(),
                variant -> Map.of());

            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-empty", "Empty scenario test",
                testManifest.residentIdentity(),
                testManifest.soulFragments(),
                testManifest.genome());
            var serialized = searchSpace.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = emptyAdapter.evaluate(json, context);

            // With no scenarios, fitness comes entirely from coherence + vitality
            assertNotNull(result);
            assertTrue(result.violations().isEmpty());
            assertEquals(4, result.metrics().size());
        }

        @Test
        void evaluate_level2_variant() throws JsonProcessingException {
            var level2Space = new SoulSearchSpace(
                testManifest, evaluator, scenarios, 2, ALL_PASS_RUNNER);
            var variant = level2Space.randomPoint(new Random(42));
            var serialized = level2Space.serialize(variant);
            String json = MAPPER.writeValueAsString(serialized);

            var context = new CrucibleEvaluatorAdapter.EvaluationContext(
                "Test", "", "", Map.of());
            var result = adapter.evaluate(json, context);

            assertNotNull(result);
            assertTrue(result.fitness() > 0);
        }
    }
}
