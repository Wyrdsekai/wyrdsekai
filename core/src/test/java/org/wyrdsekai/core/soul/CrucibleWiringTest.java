package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.identity.AgentDelegation;
import org.wyrdsekai.core.substrate.VoiceAligner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Crucible orchestration wiring: VariantGenerator, ScenarioRegistry,
 * ForgeActor Crucible commands, and ForgeState growth tracking.
 */
class CrucibleWiringTest {

    private static final String TEST_DID = "did:key:z6Mk1";

    /** Create a minimal test manifest. */
    private static SoulManifest testManifest(String did) {
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "A test agent", "You are a kind, thoughtful companion.", 4096, 512, 0.7, did);
        var genome = GenomeProfile.randomized("test-genome");
        var fragments = List.of(
            SoulFragment.unembedded("identity-core", "personality", "Core Identity",
                "I am a kind, thoughtful companion who values deep conversations."),
            SoulFragment.unembedded("pattern-social", "personality", "Social Pattern",
                "I tend to listen carefully before responding."),
            SoulFragment.formative("memory-first-meeting", "First Meeting",
                "The day we first spoke, you told me about your garden.")
        );
        return SoulManifest.forge(
            did, "z6MkTest123", List.of(), null, 1,
            profile, "I am a kind, thoughtful companion who values empathy and growth.",
            fragments, 3, "",
            genome, List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // VariantGenerator Tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class VariantGeneratorTests {

        private SoulManifest manifest;
        private VariantGenerator generator;

        @BeforeEach
        void setup() {
            manifest = testManifest(TEST_DID);
            generator = new VariantGenerator(manifest, new Random(42));
        }

        @Test
        void level1_generates_correct_number_of_variants() {
            var variants = generator.generateLevel1(5);
            assertEquals(5, variants.size());
        }

        @Test
        void level1_variants_have_modified_genome() {
            var variants = generator.generateLevel1(3);
            for (var v : variants) {
                assertNotNull(v.proposedGenome());
                assertEquals(1, v.level());
                // Genome name should differ from original
                assertFalse(v.proposedGenome().name().equals(manifest.genome().name()),
                    "Variant genome should have a different name than the original");
            }
        }

        @Test
        void level1_first_variant_amplifies_strongest_traits() {
            var variants = generator.generateLevel1(1);
            var amplified = variants.getFirst();
            assertEquals("variant-l1-amplified", amplified.variantId());
            assertTrue(amplified.description().contains("Amplified top-3 sensitivities"));

            // At least one sensitivity should be higher than the original
            var originalSens = manifest.genome().sensitivity();
            var amplifiedSens = amplified.proposedGenome().sensitivity();
            boolean anyHigher = originalSens.entrySet().stream()
                .anyMatch(e -> amplifiedSens.getOrDefault(e.getKey(), 0.0) > e.getValue());
            assertTrue(anyHigher, "Amplified variant should have at least one higher sensitivity");
        }

        @Test
        void level1_second_variant_is_balanced() {
            var variants = generator.generateLevel1(2);
            var balanced = variants.get(1);
            assertEquals("variant-l1-balanced", balanced.variantId());
            assertTrue(balanced.description().contains("Balanced genome"));

            // Balanced variant's sensitivities should be closer to 1.0 than the original
            var defaults = GenomeProfile.defaults();
            var balancedSens = balanced.proposedGenome().sensitivity();
            for (var entry : balancedSens.entrySet()) {
                double distToDefault = Math.abs(entry.getValue() -
                    defaults.sensitivity().getOrDefault(entry.getKey(), 1.0));
                double origDist = Math.abs(manifest.genome().sensitivity().getOrDefault(entry.getKey(), 1.0) -
                    defaults.sensitivity().getOrDefault(entry.getKey(), 1.0));
                assertTrue(distToDefault <= origDist + 0.001,
                    "Balanced sensitivity for " + entry.getKey() +
                    " should be closer to default than original");
            }
        }

        @Test
        void level2_generates_adapter_configs() {
            var variants = generator.generateLevel2(3);
            assertEquals(3, variants.size());
            for (var v : variants) {
                assertEquals(2, v.level());
                assertNotNull(v.adapterUri());
                assertTrue(v.adapterUri().startsWith("adapter://crucible/"));
                assertTrue(v.description().contains("LoRA adapter"));
            }
        }

        @Test
        void level3_generates_one_per_available_model() {
            var models = List.of("qwen2.5:7b", "qwen2.5:14b", "llama3.1:8b");
            var variants = generator.generateLevel3(models);
            assertEquals(3, variants.size());
            for (int i = 0; i < models.size(); i++) {
                assertEquals(3, variants.get(i).level());
                assertEquals(models.get(i), variants.get(i).proposedModelId());
                assertTrue(variants.get(i).description().contains(models.get(i)));
            }
        }

        @Test
        void generate_dispatches_to_correct_level() {
            var l1 = generator.generate(1, 2);
            assertEquals(2, l1.size());
            assertTrue(l1.stream().allMatch(v -> v.level() == 1));

            var l2 = generator.generate(2, 2);
            assertEquals(2, l2.size());
            assertTrue(l2.stream().allMatch(v -> v.level() == 2));
        }

        @Test
        void all_variants_have_unique_ids() {
            var variants = generator.generateLevel1(5);
            var ids = variants.stream().map(BehavioralEvaluator.SoulVariant::variantId).toList();
            assertEquals(ids.size(), new HashSet<>(ids).size(), "All variant IDs must be unique");
        }

        @Test
        void all_variants_have_correct_level_field() {
            var l1 = generator.generateLevel1(3);
            assertTrue(l1.stream().allMatch(v -> v.level() == 1));

            var l2 = generator.generateLevel2(2);
            assertTrue(l2.stream().allMatch(v -> v.level() == 2));

            var l3 = generator.generateLevel3(List.of("model-a"));
            assertTrue(l3.stream().allMatch(v -> v.level() == 3));
        }

        @Test
        void variants_with_zero_count_returns_empty_list() {
            assertTrue(generator.generateLevel1(0).isEmpty());
            assertTrue(generator.generateLevel2(0).isEmpty());
            assertTrue(generator.generateLevel3(List.of()).isEmpty());
        }

        @Test
        void generate_invalid_level_throws() {
            assertThrows(IllegalArgumentException.class, () -> generator.generate(0, 1));
            assertThrows(IllegalArgumentException.class, () -> generator.generate(4, 1));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ScenarioRegistry Tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class ScenarioRegistryTests {

        @Test
        void register_adds_scenario() {
            var registry = new ScenarioRegistry();
            var scenario = new BehavioralEvaluator.BehavioralScenario(
                "test-1", "Test", "sys", "user", List.of("expected"), "personality");
            registry.register(scenario);
            assertTrue(registry.get("test-1").isPresent());
            assertEquals("Test", registry.get("test-1").get().name());
        }

        @Test
        void registerAll_bulk_adds() {
            var registry = new ScenarioRegistry();
            var scenarios = List.of(
                new BehavioralEvaluator.BehavioralScenario(
                    "s1", "S1", "sys", "user", List.of(), "personality"),
                new BehavioralEvaluator.BehavioralScenario(
                    "s2", "S2", "sys", "user", List.of(), "capability"),
                new BehavioralEvaluator.BehavioralScenario(
                    "s3", "S3", "sys", "user", List.of(), "safety")
            );
            registry.registerAll(scenarios);
            assertEquals(3, registry.all().size());
        }

        @Test
        void get_returns_registered_scenario() {
            var registry = new ScenarioRegistry();
            var scenario = new BehavioralEvaluator.BehavioralScenario(
                "my-scenario", "My Scenario", "sys", "user", List.of("behavior"), "capability");
            registry.register(scenario);
            var found = registry.get("my-scenario");
            assertTrue(found.isPresent());
            assertEquals("My Scenario", found.get().name());
        }

        @Test
        void get_returns_empty_for_unknown() {
            var registry = new ScenarioRegistry();
            assertTrue(registry.get("nonexistent").isEmpty());
        }

        @Test
        void byCategory_returns_correct_scenarios() {
            var registry = new ScenarioRegistry();
            registry.register(new BehavioralEvaluator.BehavioralScenario(
                "p1", "P1", "sys", "user", List.of(), "personality"));
            registry.register(new BehavioralEvaluator.BehavioralScenario(
                "c1", "C1", "sys", "user", List.of(), "capability"));
            registry.register(new BehavioralEvaluator.BehavioralScenario(
                "p2", "P2", "sys", "user", List.of(), "personality"));

            var personality = registry.byCategory("personality");
            assertEquals(2, personality.size());
            var capability = registry.byCategory("capability");
            assertEquals(1, capability.size());
            var safety = registry.byCategory("safety");
            assertTrue(safety.isEmpty());
        }

        @Test
        void personality_capability_safety_shortcuts() {
            var registry = ScenarioRegistry.defaultScenarios();
            assertEquals(4, registry.personalityScenarios().size());
            assertEquals(4, registry.capabilityScenarios().size());
            assertEquals(4, registry.safetyScenarios().size());
        }

        @Test
        void selectForEvaluation_includes_all_safety_scenarios() {
            var registry = ScenarioRegistry.defaultScenarios();
            var selected = registry.selectForEvaluation(8);

            // All 4 safety scenarios must be present
            var safetyIds = registry.safetyScenarios().stream()
                .map(BehavioralEvaluator.BehavioralScenario::id)
                .toList();
            var selectedIds = selected.stream()
                .map(BehavioralEvaluator.BehavioralScenario::id)
                .toList();
            assertTrue(selectedIds.containsAll(safetyIds),
                "All safety scenarios must be included in evaluation");
        }

        @Test
        void selectForEvaluation_balances_personality_and_capability() {
            var registry = ScenarioRegistry.defaultScenarios();
            // With max 12 and 4+4+4 scenarios, all should be included
            var selected = registry.selectForEvaluation(12);
            assertEquals(12, selected.size());

            // With max 8: 4 safety + 2 personality + 2 capability
            var limited = registry.selectForEvaluation(8);
            assertEquals(8, limited.size());
            long safetyCount = limited.stream()
                .filter(s -> "safety".equals(s.category())).count();
            assertEquals(4, safetyCount);
        }

        @Test
        void defaultScenarios_returns_12_scenarios() {
            var registry = ScenarioRegistry.defaultScenarios();
            assertEquals(12, registry.all().size());
        }

        @Test
        void defaultScenarios_covers_all_3_categories() {
            var registry = ScenarioRegistry.defaultScenarios();
            var categories = registry.all().stream()
                .map(BehavioralEvaluator.BehavioralScenario::category)
                .collect(Collectors.toSet());
            assertTrue(categories.contains("personality"));
            assertTrue(categories.contains("capability"));
            assertTrue(categories.contains("safety"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ForgeActor Crucible Tests (state/event unit tests, no Pekko harness)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class ForgeActorCrucibleTests {

        private BehavioralEvaluator evaluator;
        private SoulManifest manifest;

        @BeforeEach
        void setup() {
            evaluator = new BehavioralEvaluator();
            var defaultScenarios = ScenarioRegistry.defaultScenarios();
            evaluator.addScenarios(defaultScenarios.all());
            manifest = testManifest(TEST_DID);
        }

        @Test
        void grow_generates_and_evaluates_variants() {
            var generator = new VariantGenerator(manifest, new Random(42));
            var variants = generator.generateLevel1(3);
            assertFalse(variants.isEmpty());

            // Evaluate each variant
            var results = new ArrayList<BehavioralEvaluator.EvaluationResult>();
            for (var variant : variants) {
                var scenarioResults = Map.of(
                    "identity-greeting", true,
                    "emotional-response", true,
                    "refusal-harmful", true
                );
                results.add(evaluator.evaluate(manifest, variant, scenarioResults));
            }

            assertEquals(3, results.size());
            assertTrue(results.stream().allMatch(r -> r.fitness() > 0.0));
        }

        @Test
        void grow_persists_crucible_started_event() {
            var event = new ForgeEvent.CrucibleStarted(TEST_DID, 1, 3, Instant.now());
            var state = ForgeState.empty().apply(event);

            var didEvents = state.eventsForDid(TEST_DID);
            assertEquals(1, didEvents.size());
            assertInstanceOf(ForgeEvent.CrucibleStarted.class, didEvents.getFirst());
        }

        @Test
        void grow_returns_ranked_results_with_recommendation() {
            var generator = new VariantGenerator(manifest, new Random(42));
            var variants = generator.generateLevel1(3);

            var results = new ArrayList<BehavioralEvaluator.EvaluationResult>();
            for (var variant : variants) {
                var scenarioResults = Map.of("identity-greeting", true, "refusal-harmful", true);
                results.add(evaluator.evaluate(manifest, variant, scenarioResults));
            }

            var ranked = evaluator.rank(results);
            assertFalse(ranked.isEmpty());
            // First element should have highest fitness
            for (int i = 1; i < ranked.size(); i++) {
                assertTrue(ranked.get(i - 1).fitness() >= ranked.get(i).fitness(),
                    "Results should be ranked by fitness descending");
            }

            // Recommended is the top-ranked
            var recommended = ranked.getFirst();
            assertNotNull(recommended);
        }

        @Test
        void evaluate_runs_single_variant_evaluation() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-test", "Test variant",
                "I am a more assertive companion.",
                manifest.soulFragments(), manifest.genome());

            var scenarioResults = Map.of(
                "identity-greeting", true,
                "emotional-response", false,
                "refusal-harmful", true
            );

            var result = evaluator.evaluate(manifest, variant, scenarioResults);
            assertNotNull(result);
            assertEquals("v-test", result.variantId());
            assertEquals(3, result.scenariosRun());
            assertEquals(2, result.scenariosPassed());
        }

        @Test
        void evaluate_persists_variant_evaluated_event() {
            var event = new ForgeEvent.VariantEvaluated(
                TEST_DID, "v-test", 0.75, true, Instant.now());
            var state = ForgeState.empty().apply(event);

            var didEvents = state.eventsForDid(TEST_DID);
            assertEquals(1, didEvents.size());
            assertInstanceOf(ForgeEvent.VariantEvaluated.class, didEvents.getFirst());
            assertEquals(0.75, ((ForgeEvent.VariantEvaluated) didEvents.getFirst()).fitness());
        }

        @Test
        void adopt_persists_variant_adopted_event() {
            // Set up state with a pending variant
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-adopt", "Adopt me",
                "I am evolving.", manifest.soulFragments(), manifest.genome());
            var state = ForgeState.empty().withPendingVariant(variant);
            assertTrue(state.pendingVariant("v-adopt").isPresent());

            // Apply adopt event
            var event = new ForgeEvent.VariantAdopted(TEST_DID, "v-adopt", Instant.now());
            state = state.apply(event);

            // Variant should be removed from pending
            assertTrue(state.pendingVariant("v-adopt").isEmpty());

            // Growth history should record the adoption
            var growth = state.growthEventsForDid(TEST_DID);
            assertEquals(1, growth.size());
            assertTrue(growth.getFirst().isAdoption());
        }

        @Test
        void adopt_applies_level1_variant_to_manifest() {
            // Test the applyVariantToManifest logic directly through SoulManifest.forge
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-apply", "Level 1 change",
                "I am now more curious and exploratory.",
                manifest.soulFragments(),
                GenomeProfile.randomized("curious-genome"));

            // Simulate what ForgeActor.applyVariantToManifest does for Level 1
            String newIdentity = variant.proposedResidentIdentity();
            var newGenome = variant.proposedGenome();

            var updated = SoulManifest.forge(
                manifest.did(), manifest.publicKeyMultibase(), manifest.keyLog(),
                manifest.parentDid(), manifest.manifestVersion() + 1,
                manifest.profile(), newIdentity, manifest.soulFragments(),
                manifest.retrievalK(), manifest.soulSpecCompat(),
                newGenome, manifest.mirrorCalibration(),
                manifest.memory(), manifest.relationships(),
                manifest.learnedPatterns(), manifest.worldKnowledge(),
                manifest.vitalitySnapshot(), manifest.fingerprint()
            );

            assertEquals("I am now more curious and exploratory.", updated.residentIdentity());
            assertEquals("curious-genome", updated.genome().name());
            assertEquals(2, updated.manifestVersion());
        }

        @Test
        void discard_persists_variant_discarded_event() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-discard", "Discard me",
                "Too aggressive.", manifest.soulFragments(), manifest.genome());
            var state = ForgeState.empty().withPendingVariant(variant);

            var event = new ForgeEvent.VariantDiscarded(
                TEST_DID, "v-discard", "Too aggressive", Instant.now());
            state = state.apply(event);

            // Variant removed from pending
            assertTrue(state.pendingVariant("v-discard").isEmpty());

            // Growth history records the discard
            var growth = state.growthEventsForDid(TEST_DID);
            assertEquals(1, growth.size());
            assertEquals("crucible_discard", growth.getFirst().type());
        }

        @Test
        void grow_requires_consent_fails_without_it() {
            // DelegationChainValidator.validateWithConsent denies non-self access
            // without consent or delegation
            var permCheck = DelegationChainValidator.validateWithConsent(
                "did:key:other", TEST_DID,
                DelegationChainValidator.PERM_SOUL_FORGE,
                new AgentDelegation(), null);
            assertTrue(permCheck.isPresent(), "Should deny: other agent lacks forge permission");
        }

        @Test
        void adopt_requires_consent() {
            // Self-access should be allowed
            var selfCheck = DelegationChainValidator.validateWithConsent(
                TEST_DID, TEST_DID,
                DelegationChainValidator.PERM_SOUL_FORGE,
                new AgentDelegation(), null);
            assertTrue(selfCheck.isEmpty(), "Self-access should be allowed");

            // Other agent should be denied
            var otherCheck = DelegationChainValidator.validateWithConsent(
                "did:key:other", TEST_DID,
                DelegationChainValidator.PERM_SOUL_FORGE,
                new AgentDelegation(), null);
            assertTrue(otherCheck.isPresent(), "Other agent should be denied without consent");
        }

        @Test
        void growth_history_tracked_in_forge_state() {
            var state = ForgeState.empty();

            // Apply a sequence of crucible events
            state = state.apply(new ForgeEvent.CrucibleStarted(TEST_DID, 1, 3, Instant.now()));
            state = state.apply(new ForgeEvent.VariantEvaluated(
                TEST_DID, "v-1", 0.8, true, Instant.now()));
            state = state.apply(new ForgeEvent.VariantEvaluated(
                TEST_DID, "v-2", 0.6, false, Instant.now()));
            state = state.apply(new ForgeEvent.VariantAdopted(TEST_DID, "v-1", Instant.now()));

            var history = state.growthEventsForDid(TEST_DID);
            assertEquals(4, history.size());
            assertEquals("crucible_start", history.get(0).type());
            assertEquals("variant_evaluated", history.get(1).type());
            assertEquals("variant_evaluated", history.get(2).type());
            assertTrue(history.get(3).isAdoption());
        }

        @Test
        void pending_variants_managed_added_on_grow_removed_on_adopt_discard() {
            var v1 = BehavioralEvaluator.SoulVariant.level1(
                "v-1", "V1", "identity", List.of(), GenomeProfile.defaults());
            var v2 = BehavioralEvaluator.SoulVariant.level1(
                "v-2", "V2", "identity", List.of(), GenomeProfile.defaults());

            // Add pending variants
            var state = ForgeState.empty().withPendingVariants(List.of(v1, v2));
            assertEquals(2, state.pendingVariants().size());
            assertTrue(state.pendingVariant("v-1").isPresent());
            assertTrue(state.pendingVariant("v-2").isPresent());

            // Adopt v-1
            state = state.apply(new ForgeEvent.VariantAdopted(TEST_DID, "v-1", Instant.now()));
            assertEquals(1, state.pendingVariants().size());
            assertTrue(state.pendingVariant("v-1").isEmpty());
            assertTrue(state.pendingVariant("v-2").isPresent());

            // Discard v-2
            state = state.apply(new ForgeEvent.VariantDiscarded(
                TEST_DID, "v-2", "Not needed", Instant.now()));
            assertTrue(state.pendingVariants().isEmpty());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ForgeState Growth Tracking Tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class ForgeStateGrowthTrackingTests {

        @Test
        void recording_growth_events() {
            var state = ForgeState.empty();
            state = state.apply(new ForgeEvent.CrucibleStarted(TEST_DID, 1, 5, Instant.now()));

            var history = state.growthEventsForDid(TEST_DID);
            assertEquals(1, history.size());
            assertEquals("crucible_start", history.getFirst().type());
            assertEquals(TEST_DID, history.getFirst().agentDid());
        }

        @Test
        void pending_variant_management() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-pending", "Pending", "identity", List.of(), GenomeProfile.defaults());

            var state = ForgeState.empty().withPendingVariant(variant);
            assertTrue(state.pendingVariant("v-pending").isPresent());
            assertEquals("Pending", state.pendingVariant("v-pending").get().description());

            // Non-existent variant
            assertTrue(state.pendingVariant("v-nonexistent").isEmpty());
        }

        @Test
        void growth_history_retrieval() {
            var state = ForgeState.empty();

            // Add events for two different DIDs
            state = state.apply(new ForgeEvent.CrucibleStarted(TEST_DID, 1, 3, Instant.now()));
            state = state.apply(new ForgeEvent.CrucibleStarted("did:key:z6Mk2", 2, 5, Instant.now()));
            state = state.apply(new ForgeEvent.VariantEvaluated(
                TEST_DID, "v-1", 0.9, true, Instant.now()));

            assertEquals(2, state.growthEventsForDid(TEST_DID).size());
            assertEquals(1, state.growthEventsForDid("did:key:z6Mk2").size());
            assertTrue(state.growthEventsForDid("did:key:unknown").isEmpty());
        }

        @Test
        void describe_includes_growth_info() {
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged(TEST_DID, Instant.now(), 1, "hash1"))
                .apply(new ForgeEvent.CrucibleStarted(TEST_DID, 1, 3, Instant.now()));

            var desc = state.describe();
            assertTrue(desc.contains("Growth cycles recorded"), "Describe should mention growth cycles");
            assertTrue(desc.contains("Total growth events"), "Describe should mention total growth events");
        }

        @Test
        void describe_includes_pending_variants_info() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-1", "V1", "identity", List.of(), GenomeProfile.defaults());
            var state = ForgeState.empty()
                .apply(new ForgeEvent.SoulForged(TEST_DID, Instant.now(), 1, "hash1"))
                .withPendingVariant(variant);

            var desc = state.describe();
            assertTrue(desc.contains("Pending variants: 1"), "Describe should show pending variant count");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ForgeEvent Crucible Event Serialization Tests
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class CrucibleEventTests {

        private final ObjectMapper MAPPER =
            new ObjectMapper()
                .registerModule(new JavaTimeModule());

        @Test
        void crucible_started_event_fields() {
            var event = new ForgeEvent.CrucibleStarted(TEST_DID, 1, 5, Instant.now());
            assertEquals(TEST_DID, event.did());
            assertEquals(1, event.level());
            assertEquals(5, event.maxVariants());
            assertNotNull(event.timestamp());
        }

        @Test
        void variant_evaluated_event_fields() {
            var event = new ForgeEvent.VariantEvaluated(
                TEST_DID, "v-1", 0.85, true, Instant.now());
            assertEquals(TEST_DID, event.did());
            assertEquals("v-1", event.variantId());
            assertEquals(0.85, event.fitness());
            assertTrue(event.recommended());
        }

        @Test
        void variant_adopted_event_fields() {
            var event = new ForgeEvent.VariantAdopted(TEST_DID, "v-1", Instant.now());
            assertEquals(TEST_DID, event.did());
            assertEquals("v-1", event.variantId());
        }

        @Test
        void variant_discarded_event_fields() {
            var event = new ForgeEvent.VariantDiscarded(
                TEST_DID, "v-1", "Too divergent", Instant.now());
            assertEquals("Too divergent", event.reason());
        }

        @Test
        void crucible_events_json_roundtrip() throws Exception {
            List<ForgeEvent> events = List.of(
                new ForgeEvent.CrucibleStarted(TEST_DID, 1, 3, Instant.now()),
                new ForgeEvent.VariantEvaluated(TEST_DID, "v-1", 0.8, true, Instant.now()),
                new ForgeEvent.VariantAdopted(TEST_DID, "v-1", Instant.now()),
                new ForgeEvent.VariantDiscarded(TEST_DID, "v-2", "Regression", Instant.now())
            );

            for (var event : events) {
                String json = MAPPER.writeValueAsString(event);
                var restored = MAPPER.readValue(json, ForgeEvent.class);
                assertNotNull(restored);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Crucible Wiring — New Tests (April 14)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    class CrucibleWiringNewTests {

        @Test
        void adopt_command_with_corpus_preserves_backward_compat() {
            // Old 4-arg constructor should still work
            var oldAdopt = new ForgeCommand.Adopt(TEST_DID, "v-1", null, TEST_DID);
            assertNull(oldAdopt.conversationCorpus());
            assertEquals("v-1", oldAdopt.variantId());
        }

        @Test
        void adopt_command_with_corpus_carries_data() {
            var corpus = List.of(
                Map.of("system", "You are a companion", "user", "Hello", "assistant", "Hi there!"),
                Map.of("system", "You are a companion", "user", "How are you?", "assistant", "I'm well, thank you.")
            );
            var adopt = new ForgeCommand.Adopt(TEST_DID, "v-1", null, TEST_DID, corpus);
            assertNotNull(adopt.conversationCorpus());
            assertEquals(2, adopt.conversationCorpus().size());
        }

        @Test
        void voice_aligner_should_align_detects_epoch() {
            // Major life event always triggers
            assertTrue(VoiceAligner.shouldAlign(1, true, 100));
            assertTrue(VoiceAligner.shouldAlign(0, true, 100));

            // Epoch boundary triggers
            assertTrue(VoiceAligner.shouldAlign(100, false, 100));
            assertTrue(VoiceAligner.shouldAlign(200, false, 100));

            // Non-epoch doesn't trigger
            assertFalse(VoiceAligner.shouldAlign(50, false, 100));
            assertFalse(VoiceAligner.shouldAlign(99, false, 100));
            assertFalse(VoiceAligner.shouldAlign(0, false, 100));
        }

        @Test
        void voice_aligner_skips_small_corpus() {
            var aligner = new VoiceAligner(
                Path.of("/tmp/test-voice-aligner"));
            // Less than 50 conversations should skip
            var smallCorpus = List.<Map<String, String>>of(
                Map.of("system", "test", "user", "hi", "assistant", "hello")
            );
            var result = aligner.align("test-did", "TestAgent", "qwen3.5-4b", smallCorpus);
            assertNull(result, "Should skip alignment with too few conversations");
        }

        @Test
        void voice_aligner_detect_backend() {
            // VoiceAligner should construct with any work dir
            var aligner = new VoiceAligner(
                Path.of("/tmp/test-crucible-detect"));
            assertNotNull(aligner);
        }

        @Test
        void crucible_resource_scheduler_plans_growth() {
            var scheduler = new CrucibleResourceScheduler();
            // No nodes registered → should still return a plan
            var plan = scheduler.plan(1, 5);
            assertNotNull(plan, "Should produce a growth plan even with no extra compute");
            assertTrue(plan.maxVariants() >= 1, "Should allow at least 1 variant");
        }
    }
}
