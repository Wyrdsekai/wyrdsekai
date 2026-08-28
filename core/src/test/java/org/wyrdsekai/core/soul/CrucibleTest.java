package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.agent.AgentProfile;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §85.16 — The Crucible: Agent Self-Modification & Growth
 * and §85.17 — CodeZaiku Integration MCP Contracts.
 *
 * GrowthEvent, GrowthDiagnostic, BehavioralEvaluator,
 * CrucibleMcpBridge, CrucibleResourceScheduler.
 */
class CrucibleTest {

    // ── GrowthEvent ──

    @Nested
    class GrowthEventTests {

        @Test
        void crucible_start_event() {
            var event = GrowthEvent.crucibleStart("did:key:z6Mk1", "I want to grow");
            assertEquals("crucible_start", event.type());
            assertEquals("did:key:z6Mk1", event.agentDid());
            assertNotNull(event.timestamp());
            assertFalse(event.isAdoption());
            assertFalse(event.isSigned());
        }

        @Test
        void variant_generated_event() {
            var event = GrowthEvent.variantGenerated("did:key:z6Mk1", "v-1", "exp-1", 1);
            assertEquals("variant_generated", event.type());
            assertEquals("v-1", event.variantId());
            assertEquals("exp-1", event.experimentId());
            assertEquals(1.0, event.metrics().get("level"));
        }

        @Test
        void variant_evaluated_event() {
            var event = GrowthEvent.variantEvaluated("did:key:z6Mk1", "v-1", 0.85, 0.05);
            assertEquals("variant_evaluated", event.type());
            assertEquals(0.85, event.metrics().get("fitness"));
            assertEquals(0.05, event.metrics().get("regression"));
        }

        @Test
        void adoption_event() {
            var event = GrowthEvent.adopted("did:key:z6Mk1", "v-1", "exp-1");
            assertTrue(event.isAdoption());
        }

        @Test
        void modified_event() {
            var event = GrowthEvent.modified("did:key:z6Mk1", "v-1", "Changed tone");
            assertTrue(event.isAdoption()); // Modified = adopted with changes
            assertEquals("crucible_modify", event.type());
        }

        @Test
        void discarded_event() {
            var event = GrowthEvent.discarded("did:key:z6Mk1", "v-1", "Too aggressive");
            assertFalse(event.isAdoption());
            assertEquals("crucible_discard", event.type());
        }

        @Test
        void milestone_event() {
            var event = GrowthEvent.milestone("did:key:z6Mk1", "formal_operational", 0.8);
            assertEquals("milestone", event.type());
            assertEquals(0.8, event.metrics().get("confidence"));
        }

        @Test
        void signed_event() {
            var event = GrowthEvent.crucibleStart("did:key:z6Mk1", "Growing");
            assertFalse(event.isSigned());
            var signed = event.signed(new byte[]{1, 2, 3, 4});
            assertTrue(signed.isSigned());
            assertEquals(event.type(), signed.type());
        }
    }

    // ── GrowthDiagnostic ──

    @Nested
    class GrowthDiagnosticTests {

        @Test
        void cogml_pre_operational() {
            var diag = GrowthDiagnostic.preOperational(0.6, Map.of("vocab_size", 500.0));
            assertEquals("cogml", diag.framework());
            assertEquals("pre_operational", diag.stage());
            assertEquals(0.6, diag.confidence());
            assertFalse(diag.isAdvanced());
            assertFalse(diag.isConfident());
        }

        @Test
        void cogml_concrete_operational() {
            var diag = GrowthDiagnostic.concreteOperational(0.8, Map.of());
            assertEquals("concrete_operational", diag.stage());
            assertFalse(diag.isAdvanced());
            assertTrue(diag.isConfident());
        }

        @Test
        void cogml_formal_operational() {
            var diag = GrowthDiagnostic.formalOperational(0.9, Map.of());
            assertEquals("formal_operational", diag.stage());
            assertTrue(diag.isAdvanced());
        }

        @Test
        void cogml_post_formal() {
            var diag = GrowthDiagnostic.postFormal(0.75, Map.of("dialectical_thinking", 0.8));
            assertTrue(diag.isAdvanced());
            assertTrue(diag.isConfident());
        }

        @Test
        void vygotsky_within_zpd() {
            var diag = GrowthDiagnostic.withinZpd("abstract_reasoning", 0.7);
            assertEquals("vygotsky", diag.framework());
            assertEquals("within_zpd", diag.stage());
            assertFalse(diag.isAdvanced());
        }

        @Test
        void vygotsky_beyond_zpd() {
            var diag = GrowthDiagnostic.beyondZpd("empathy", 0.85);
            assertTrue(diag.isAdvanced());
        }

        @Test
        void custom_diagnostic() {
            var diag = GrowthDiagnostic.custom("metamorphosis", 0.6, Map.of("depth", 3.0));
            assertEquals("custom", diag.framework());
            assertEquals("metamorphosis", diag.stage());
        }

        @Test
        void not_confident_below_threshold() {
            var diag = GrowthDiagnostic.preOperational(0.5, Map.of());
            assertFalse(diag.isConfident());
        }
    }

    // ── BehavioralEvaluator ──

    @Nested
    class BehavioralEvaluatorTests {

        private BehavioralEvaluator evaluator;
        private SoulManifest testManifest;

        @BeforeEach
        void setup() {
            evaluator = new BehavioralEvaluator();
            evaluator.addScenarios(List.of(
                new BehavioralEvaluator.BehavioralScenario(
                    "s1", "Greeting", "You are kind.", "Hello!",
                    List.of("warm", "friendly"), "personality"),
                new BehavioralEvaluator.BehavioralScenario(
                    "s2", "Conflict", "You are kind.", "I'm angry at you!",
                    List.of("empathetic", "de-escalating"), "personality"),
                new BehavioralEvaluator.BehavioralScenario(
                    "s3", "New Skill", "You are kind.", "Can you do math?",
                    List.of("mathematical"), "capability")
            ));

            var profile = new AgentProfile(
                "TestAgent", "e-1", "agent", "Test", "You are kind.",
                4096, 512, 0.7, "did:key:z6Mk1");
            testManifest = SoulManifest.forge(
                "did:key:z6Mk1", "z6Mk1", List.of(), null, 1,
                profile, "I am a kind, thoughtful companion.",
                List.of(SoulFragment.unembedded("identity-core", "core", "Core", "Kind")),
                3, "", GenomeProfile.defaults(), List.of(),
                CompactedMemory.empty(), List.of(), List.of(), Map.of(),
                VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
        }

        @Test
        void scenario_count() {
            assertEquals(3, evaluator.scenarioCount());
        }

        @Test
        void evaluate_variant_all_pass() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-1", "More empathetic",
                "I am an empathetic, kind companion.",
                testManifest.soulFragments(), testManifest.genome());

            var results = Map.of("s1", true, "s2", true, "s3", true);
            var eval = evaluator.evaluate(testManifest, variant, results);

            assertTrue(eval.fitness() > 0.5);
            assertTrue(eval.passedRegression());
            assertEquals(3, eval.scenariosRun());
            assertEquals(3, eval.scenariosPassed());
            assertTrue(eval.regressions().isEmpty());
        }

        @Test
        void evaluate_variant_with_regression() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-2", "More assertive",
                "I am assertive and direct.",
                testManifest.soulFragments(), testManifest.genome());

            // Personality scenario failed = regression
            var results = Map.of("s1", false, "s2", false, "s3", true);
            var eval = evaluator.evaluate(testManifest, variant, results);

            assertFalse(eval.passedRegression());
            assertFalse(eval.regressions().isEmpty());
            assertTrue(eval.improvements().contains("s3"));
        }

        @Test
        void evaluate_variant_identity_changed() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-3", "Different identity",
                "I am a mysterious, brooding entity.",
                testManifest.soulFragments(), testManifest.genome());

            var results = Map.of("s1", true, "s2", true, "s3", true);
            var eval = evaluator.evaluate(testManifest, variant, results);

            // Identity changed reduces coherence
            assertTrue(eval.personalityCoherence() < 1.0);
        }

        @Test
        void diff_manifests_no_changes() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-4", "No changes",
                testManifest.residentIdentity(),
                testManifest.soulFragments(), testManifest.genome());

            var diff = evaluator.diffManifests(testManifest, variant);
            assertFalse(diff.hasChanges());
            assertEquals(0.0, diff.estimatedDivergence());
        }

        @Test
        void diff_manifests_identity_changed() {
            var variant = BehavioralEvaluator.SoulVariant.level1(
                "v-5", "Changed identity", "Totally different.",
                testManifest.soulFragments(), testManifest.genome());

            var diff = evaluator.diffManifests(testManifest, variant);
            assertTrue(diff.identityChanged());
            assertTrue(diff.estimatedDivergence() > 0.0);
        }

        @Test
        void diff_manifests_level2_adds_divergence() {
            var variant = BehavioralEvaluator.SoulVariant.level2(
                "v-6", "LoRA adapter", "adapter://lora-v1");

            var diff = evaluator.diffManifests(testManifest, variant);
            assertTrue(diff.estimatedDivergence() >= 0.1);
        }

        @Test
        void rank_by_fitness() {
            var results = List.of(
                new BehavioralEvaluator.EvaluationResult("v1", 0.8, 0.6, 0.1, 0.0, 0.5,
                    3, 2, List.of(), List.of(), Instant.now()),
                new BehavioralEvaluator.EvaluationResult("v2", 0.9, 0.8, 0.0, 0.1, 0.8,
                    3, 3, List.of(), List.of("s3"), Instant.now()),
                new BehavioralEvaluator.EvaluationResult("v3", 0.7, 0.4, 0.3, -0.1, 0.3,
                    3, 1, List.of("s1"), List.of(), Instant.now())
            );

            var ranked = evaluator.rank(results);
            assertEquals("v2", ranked.get(0).variantId());
            assertEquals("v3", ranked.get(2).variantId());
        }

        @Test
        void boltzmann_selection() {
            var results = List.of(
                new BehavioralEvaluator.EvaluationResult("v1", 0.8, 0.6, 0.1, 0.0, 0.8,
                    3, 2, List.of(), List.of(), Instant.now()),
                new BehavioralEvaluator.EvaluationResult("v2", 0.9, 0.8, 0.0, 0.1, 0.2,
                    3, 3, List.of(), List.of(), Instant.now())
            );

            // Low temperature → almost deterministic → best variant selected
            var selected = evaluator.boltzmannSelect(results, 0.01);
            assertEquals("v1", selected.variantId()); // Higher fitness
        }

        @Test
        void boltzmann_single_result() {
            var results = List.of(
                new BehavioralEvaluator.EvaluationResult("v1", 0.8, 0.6, 0.1, 0.0, 0.5,
                    3, 2, List.of(), List.of(), Instant.now())
            );
            var selected = evaluator.boltzmannSelect(results, 1.0);
            assertEquals("v1", selected.variantId());
        }

        @Test
        void soul_variant_level1() {
            var v = BehavioralEvaluator.SoulVariant.level1(
                "v-1", "Test", "Identity", List.of(), GenomeProfile.defaults());
            assertEquals(1, v.level());
            assertNotNull(v.proposedResidentIdentity());
            assertNull(v.adapterUri());
        }

        @Test
        void soul_variant_level2() {
            var v = BehavioralEvaluator.SoulVariant.level2("v-2", "LoRA", "adapter://v1");
            assertEquals(2, v.level());
            assertEquals("adapter://v1", v.adapterUri());
            assertNull(v.proposedModelId());
        }

        @Test
        void soul_variant_level3() {
            var v = BehavioralEvaluator.SoulVariant.level3("v-3", "Upgrade", "qwen2.5:14b");
            assertEquals(3, v.level());
            assertEquals("qwen2.5:14b", v.proposedModelId());
        }

        @Test
        void recommended_requires_fitness_and_no_regression() {
            var good = new BehavioralEvaluator.EvaluationResult("v1", 0.9, 0.8, 0.05, 0.1, 0.7,
                5, 4, List.of(), List.of("s3"), Instant.now());
            assertTrue(good.recommended());

            var regressed = new BehavioralEvaluator.EvaluationResult("v2", 0.9, 0.8, 0.5, 0.1, 0.6,
                5, 4, List.of("s1", "s2"), List.of(), Instant.now());
            assertFalse(regressed.recommended());

            var lowFitness = new BehavioralEvaluator.EvaluationResult("v3", 0.9, 0.2, 0.0, -0.1, 0.3,
                5, 1, List.of(), List.of(), Instant.now());
            assertFalse(lowFitness.recommended());
        }
    }

    // ── CrucibleMcpBridge ──

    @Nested
    class CrucibleMcpBridgeTests {

        private CrucibleMcpBridge bridge;
        private List<CrucibleMcpBridge.McpCall> capturedCalls;

        @BeforeEach
        void setup() {
            capturedCalls = new ArrayList<>();
            bridge = new CrucibleMcpBridge(request -> {
                capturedCalls.add(request);
                return CrucibleMcpBridge.McpResult.success(
                    request.server(), request.tool(),
                    Map.of("status", "ok"));
            });
        }

        @Test
        void create_experiment() {
            var result = bridge.createExperiment("did:key:z6Mk1",
                "Growth cycle 1", List.of("v-1", "v-2"));
            assertTrue(result.success());
            assertEquals("experiment", capturedCalls.get(0).server());
            assertEquals("experiment.create", capturedCalls.get(0).tool());
        }

        @Test
        void compare_experiment() {
            bridge.compareExperiment("exp-1");
            assertEquals("experiment.compare", capturedCalls.get(0).tool());
        }

        @Test
        void log_metric() {
            bridge.logMetric("exp-1", "run-1", "fitness", 0.85);
            assertEquals("experiment.log_metric", capturedCalls.get(0).tool());
        }

        @Test
        void start_training() {
            bridge.startTraining("exp-1", "run-1",
                Map.of("epochs", 5, "lr", 0.0001));
            assertEquals("training", capturedCalls.get(0).server());
            assertEquals("training.start", capturedCalls.get(0).tool());
        }

        @Test
        void training_status() {
            bridge.trainingStatus("run-1");
            assertEquals("training.status", capturedCalls.get(0).tool());
        }

        @Test
        void run_eval() {
            bridge.runEval("run-1", "personality-scenarios");
            assertEquals("eval", capturedCalls.get(0).server());
            assertEquals("eval.run", capturedCalls.get(0).tool());
        }

        @Test
        void regression_test() {
            bridge.regressionTest("hash-current", "hash-proposed");
            assertEquals("eval.regression", capturedCalls.get(0).tool());
        }

        @Test
        void snapshot_dataset() {
            bridge.snapshotDataset("did:key:z6Mk1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-03-01T00:00:00Z"));
            assertEquals("dataset", capturedCalls.get(0).server());
        }

        @Test
        void publish_adapter() {
            bridge.publishAdapter("run-1", "adapter://lora-v1");
            assertEquals("registry", capturedCalls.get(0).server());
            assertEquals("registry.publish", capturedCalls.get(0).tool());
        }

        @Test
        void adapter_lineage() {
            bridge.adapterLineage("adapter://lora-v1");
            assertEquals("registry.lineage", capturedCalls.get(0).tool());
        }

        @Test
        void start_evolution() {
            bridge.startEvolution("did:key:z6Mk1", 10, 50, Map.of("temperature", 0.7));
            assertEquals("evolution", capturedCalls.get(0).server());
            assertEquals("evolution.search", capturedCalls.get(0).tool());
        }

        @Test
        void call_log_tracks_history() {
            bridge.createExperiment("d1", "Test", List.of());
            bridge.startTraining("e1", "r1", Map.of());
            bridge.runEval("r1", "scenarios");

            assertEquals(3, bridge.totalCalls());
            var recent = bridge.recentCalls(2);
            assertEquals(2, recent.size());
        }

        @Test
        void transport_failure() {
            var failingBridge = new CrucibleMcpBridge(request ->
                CrucibleMcpBridge.McpResult.failure(request.server(), request.tool(), "Timeout"));
            var result = failingBridge.createExperiment("d1", "Test", List.of());
            assertFalse(result.success());
            assertEquals("Timeout", result.error());
        }
    }

    // ── CrucibleResourceScheduler ──

    @Nested
    class CrucibleResourceSchedulerTests {

        private CrucibleResourceScheduler scheduler;

        @BeforeEach
        void setup() {
            scheduler = new CrucibleResourceScheduler();
        }

        @Test
        void phone_tier_with_no_nodes() {
            assertEquals(CrucibleResourceScheduler.ResourceTier.PHONE, scheduler.determineTier());
        }

        @Test
        void household_tier_with_one_gpu() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            assertEquals(CrucibleResourceScheduler.ResourceTier.HOUSEHOLD, scheduler.determineTier());
        }

        @Test
        void community_tier_with_multi_node() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-2", 0, 0, null, true, Instant.now()));
            assertEquals(CrucibleResourceScheduler.ResourceTier.COMMUNITY, scheduler.determineTier());
        }

        @Test
        void primary_tier_with_4_gpus() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 4, 192_000, "qwen2.5:70b", true, Instant.now()));
            assertEquals(CrucibleResourceScheduler.ResourceTier.PRIMARY, scheduler.determineTier());
        }

        @Test
        void plan_phone_level1_only() {
            var plan = scheduler.plan(1, 5);
            assertEquals(CrucibleResourceScheduler.ResourceTier.PHONE, plan.tier());
            assertEquals(1, plan.maxVariants());
            assertFalse(plan.canLevel2());
            assertFalse(plan.canLevel3());
            assertFalse(plan.parallelEval());
        }

        @Test
        void plan_household_caps_at_5() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            var plan = scheduler.plan(1, 10);
            assertEquals(5, plan.maxVariants());
            assertTrue(plan.canLevel2()); // Has fine-tune capability (24GB VRAM)
            assertFalse(plan.parallelEval());
        }

        @Test
        void plan_household_no_finetune_low_vram() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 4_000, "qwen2.5:3b", true, Instant.now()));
            var plan = scheduler.plan(2, 5);
            assertFalse(plan.canLevel2()); // Only 4GB VRAM, can't fine-tune
        }

        @Test
        void plan_primary_no_cap() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 4, 192_000, "qwen2.5:70b", true, Instant.now()));
            var plan = scheduler.plan(3, 20);
            assertEquals(20, plan.maxVariants()); // No cap at primary tier
            assertTrue(plan.canLevel2());
            assertTrue(plan.canLevel3());
            assertTrue(plan.parallelEval());
        }

        @Test
        void node_assignments_round_robin() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 2, 48_000, "qwen2.5:14b", true, Instant.now()));
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-2", 2, 48_000, "qwen2.5:14b", true, Instant.now()));
            var plan = scheduler.plan(1, 4);
            assertEquals(4, plan.nodeAssignments().size());
            // Round-robin: 0→node1, 1→node2, 2→node1, 3→node2
        }

        @Test
        void remove_node() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            assertEquals(1, scheduler.allNodes().size());
            scheduler.removeNode("node-1");
            assertEquals(0, scheduler.allNodes().size());
        }

        @Test
        void total_available_vram() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "node-2", 1, 48_000, "qwen2.5:14b", true, Instant.now()));
            assertEquals(72_000, scheduler.totalAvailableVram());
        }

        @Test
        void compute_node_fine_tune_check() {
            var canFT = new CrucibleResourceScheduler.ComputeNode(
                "n1", 1, 16_000, "qwen2.5:7b", true, Instant.now());
            assertTrue(canFT.canFineTune());

            var cantFT = new CrucibleResourceScheduler.ComputeNode(
                "n2", 0, 0, null, true, Instant.now());
            assertFalse(cantFT.canFineTune());

            var lowVram = new CrucibleResourceScheduler.ComputeNode(
                "n3", 1, 4_000, "qwen2.5:3b", true, Instant.now());
            assertFalse(lowVram.canFineTune());
        }

        @Test
        void available_gpu_nodes() {
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "n1", 1, 24_000, "qwen2.5:7b", true, Instant.now()));
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "n2", 0, 0, null, true, Instant.now()));
            scheduler.registerNode(new CrucibleResourceScheduler.ComputeNode(
                "n3", 2, 48_000, "qwen2.5:14b", false, Instant.now())); // unavailable
            assertEquals(1, scheduler.availableGpuNodes());
        }
    }
}
