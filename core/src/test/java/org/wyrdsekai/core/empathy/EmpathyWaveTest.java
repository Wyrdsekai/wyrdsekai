package org.wyrdsekai.core.empathy;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §109 — Empathy Engine.
 * MirrorResonance, TankGenome, CoupledVitalitySystem,
 * EpigeneticModifier, ImpressionWeightedRetrieval, FormativeImpressionGuard.
 */
class EmpathyWaveTest {

    // ── MirrorResonance ──

    @Nested
    class MirrorResonanceTests {

        @Test
        void significance_gate_blocks_noise() {
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.5, MirrorResonance.ContextType.NOISE);
            assertFalse(result.significant());
        }

        @Test
        void significance_gate_blocks_manipulative() {
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.9, MirrorResonance.ContextType.MANIPULATIVE);
            assertFalse(result.significant());
            assertTrue(result.reason().contains("Manipulative"));
        }

        @Test
        void significance_gate_blocks_low_intensity() {
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.1, MirrorResonance.ContextType.GENUINE);
            assertFalse(result.significant());
        }

        @Test
        void genuine_signal_passes_gate() {
            var mr = new MirrorResonance();
            var result = mr.isSignificant(0.5, MirrorResonance.ContextType.GENUINE);
            assertTrue(result.significant());
        }

        @Test
        void observe_scales_with_rapport() {
            var mr = new MirrorResonance();
            var lowRapport = mr.observe("did:agent:home-server", "did:user:a",
                0.8, "grief", MirrorResonance.ContextType.GENUINE, 0.2);
            var highRapport = mr.observe("did:agent:home-server", "did:user:b",
                0.8, "grief", MirrorResonance.ContextType.GENUINE, 0.9);

            assertNotNull(lowRapport);
            assertNotNull(highRapport);
            assertTrue(highRapport.tankPerturbations().get("valence") >
                       lowRapport.tankPerturbations().get("valence"));
        }

        @Test
        void observe_null_for_blocked_signal() {
            var mr = new MirrorResonance();
            assertNull(mr.observe("did:agent:home-server", "did:user:a",
                0.8, "fake", MirrorResonance.ContextType.MANIPULATIVE, 0.5));
        }

        @Test
        void perturbations_include_expected_tanks() {
            var mr = new MirrorResonance();
            var perturbations = mr.calculatePerturbations(0.7, 0.8);
            assertTrue(perturbations.containsKey("valence"));
            assertTrue(perturbations.containsKey("resonance"));
            assertTrue(perturbations.containsKey("rapport"));
            assertTrue(perturbations.containsKey("energy"));
        }

        @Test
        void energy_cost_of_empathy() {
            var mr = new MirrorResonance();
            var perturbations = mr.calculatePerturbations(0.7, 0.8);
            assertTrue(perturbations.get("energy") < 0); // Empathy has energy cost
        }
    }

    // ── TankGenome ──

    @Nested
    class TankGenomeTests {

        @Test
        void default_genome_has_12_tanks() {
            var genome = TankGenome.defaultGenome("test");
            assertEquals(12, genome.tankCount());
        }

        @Test
        void all_12_tank_names_present() {
            var genome = TankGenome.defaultGenome("test");
            var names = genome.tankNames();
            assertTrue(names.contains("context_budget"));
            assertTrue(names.contains("confidence"));
            assertTrue(names.contains("energy"));
            assertTrue(names.contains("alignment"));
            assertTrue(names.contains("error_pressure"));
            assertTrue(names.contains("momentum"));
            assertTrue(names.contains("rapport"));
            assertTrue(names.contains("focus"));
            assertTrue(names.contains("valence"));
            assertTrue(names.contains("safety"));
            assertTrue(names.contains("resonance"));
            assertTrue(names.contains("curiosity"));
        }

        @Test
        void gene_has_coupling_coefficients() {
            var genome = TankGenome.defaultGenome("test");
            double coupling = genome.coupling("rapport", "resonance");
            assertTrue(coupling > 0);
        }

        @Test
        void clamp_respects_capacity() {
            var genome = TankGenome.defaultGenome("test");
            var gene = genome.gene("energy").orElseThrow();
            assertEquals(1.0, gene.clamp(1.5));
            assertEquals(0.0, gene.clamp(-0.5));
            assertEquals(0.5, gene.clamp(0.5));
        }

        @Test
        void decay_toward_baseline() {
            var genome = TankGenome.defaultGenome("test");
            var gene = genome.gene("energy").orElseThrow();
            double decayed = gene.decay(0.3); // Below baseline (0.7)
            assertTrue(decayed > 0.3); // Should move toward baseline
        }

        @Test
        void export_serializable() {
            var genome = TankGenome.defaultGenome("test");
            var exported = genome.export();
            assertEquals(12, exported.size());
            assertTrue(exported.containsKey("energy"));
            assertTrue(exported.get("energy").containsKey("baseline"));
        }
    }

    // ── CoupledVitalitySystem ──

    @Nested
    class CoupledVitalitySystemTests {

        @Test
        void initializes_to_baselines() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            var gene = genome.gene("energy").orElseThrow();
            assertEquals(gene.baseline(), cvs.value("energy"), 0.001);
        }

        @Test
        void perturb_propagates_through_coupling() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            double focusBefore = cvs.value("focus");
            cvs.perturb("energy", 0.2); // Energy coupled to focus
            double focusAfter = cvs.value("focus");
            assertNotEquals(focusBefore, focusAfter, 0.001);
        }

        @Test
        void different_genomes_different_trajectories() {
            var genome1 = TankGenome.defaultGenome("g1");
            var genome2 = TankGenome.defaultGenome("g2");
            // Modify genome2's sensitivity
            genome2.addGene(new TankGenome.TankGene("energy", 1.0, 0.7, 2.0, 0.02,
                Map.of("focus", 0.15, "momentum", 0.1)));

            var cvs1 = new CoupledVitalitySystem(genome1);
            var cvs2 = new CoupledVitalitySystem(genome2);

            cvs1.perturb("energy", -0.3);
            cvs2.perturb("energy", -0.3);

            // Higher sensitivity = more impact
            assertTrue(Math.abs(cvs2.value("energy") - 0.7) >
                       Math.abs(cvs1.value("energy") - 0.7));
        }

        @Test
        void decay_moves_toward_baseline() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            cvs.set("energy", 0.1); // Below baseline
            cvs.decay();
            assertTrue(cvs.value("energy") > 0.1); // Moving toward baseline
        }

        @Test
        void batch_perturbation() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            var changes = cvs.perturbBatch(Map.of("energy", -0.2, "confidence", 0.1));
            assertFalse(changes.isEmpty());
        }

        @Test
        void tanks_below_threshold() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            cvs.set("energy", 0.1);
            cvs.set("confidence", 0.1);
            cvs.set("focus", 0.1);
            assertEquals(3, cvs.tanksBelow(0.15));
        }

        @Test
        void overall_vitality() {
            var genome = TankGenome.defaultGenome("test");
            var cvs = new CoupledVitalitySystem(genome);
            double vitality = cvs.overallVitality();
            assertTrue(vitality > 0.0 && vitality < 1.0);
        }
    }

    // ── EpigeneticModifier ──

    @Nested
    class EpigeneticModifierTests {

        @Test
        void record_impression() {
            var em = new EpigeneticModifier();
            var fi = em.recordImpression("did:agent:home-server", "kindness from steward",
                0.8, Map.of("rapport", 0.3, "valence", 0.2));
            assertNotNull(fi);
            assertEquals(1, fi.exposureCount());
        }

        @Test
        void repeated_exposure_accumulates() {
            var em = new EpigeneticModifier();
            em.recordImpression("did:agent:home-server", "kindness from steward",
                0.8, Map.of("rapport", 0.3));
            var second = em.recordImpression("did:agent:home-server", "kindness from steward",
                0.9, Map.of("rapport", 0.4));
            assertEquals(2, second.exposureCount());
        }

        @Test
        void threshold_for_modification() {
            var em = new EpigeneticModifier(3);
            var fi = em.recordImpression("did:agent:home-server", "test",
                0.8, Map.of("valence", 0.3));
            assertFalse(em.shouldModifyGenome(fi));

            em.recordImpression("did:agent:home-server", "test", 0.8, Map.of("valence", 0.3));
            fi = em.recordImpression("did:agent:home-server", "test", 0.8, Map.of("valence", 0.3));
            assertTrue(em.shouldModifyGenome(fi));
        }

        @Test
        void apply_modifications_to_genome() {
            var em = new EpigeneticModifier(2);
            em.recordImpression("did:agent:home-server", "test",
                0.8, Map.of("rapport", 0.5));
            em.recordImpression("did:agent:home-server", "test",
                0.8, Map.of("rapport", 0.5));

            var genome = TankGenome.defaultGenome("test");
            var mods = em.applyModifications(genome, "did:agent:home-server");
            assertFalse(mods.isEmpty());
        }

        @Test
        void negative_experience_increases_sensitivity() {
            var em = new EpigeneticModifier(2);
            em.recordImpression("did:agent:home-server", "rejection",
                0.8, Map.of("rapport", -0.5));
            em.recordImpression("did:agent:home-server", "rejection",
                0.8, Map.of("rapport", -0.5));

            var genome = TankGenome.defaultGenome("test");
            var mods = em.applyModifications(genome, "did:agent:home-server");
            assertTrue(mods.stream().anyMatch(m ->
                m.type() == EpigeneticModifier.ModificationType.SENSITIVITY_CHANGE));
        }
    }

    // ── ImpressionWeightedRetrieval ──

    @Nested
    class ImpressionWeightedRetrievalTests {

        @Test
        void rank_by_combined_score() {
            var iwr = new ImpressionWeightedRetrieval();
            var relevance = Map.of("f1", 0.9, "f2", 0.5, "f3", 0.3);
            var impression = Map.of("f1", 0.1, "f2", 0.3, "f3", 0.95);
            var ranked = iwr.rankCandidates(relevance, impression);

            assertFalse(ranked.isEmpty());
            // First should be f1 (high relevance) or f3 (high impression)
            assertTrue(ranked.size() <= 5);
        }

        @Test
        void high_impression_surfaces_over_high_relevance() {
            var iwr = new ImpressionWeightedRetrieval(
                new ImpressionWeightedRetrieval.RetrievalConfig(0.3, 0.7, 5, 2000));
            // With impression-heavy config, high impression should win
            assertTrue(iwr.impressionWouldSurface(0.3, 0.95, 0.9, 0.1));
        }

        @Test
        void relevance_still_matters() {
            var iwr = new ImpressionWeightedRetrieval();
            // With default config (0.6/0.4), very high relevance should still win
            assertFalse(iwr.impressionWouldSurface(0.1, 0.6, 0.95, 0.3));
        }

        @Test
        void max_results_respected() {
            var iwr = new ImpressionWeightedRetrieval(
                new ImpressionWeightedRetrieval.RetrievalConfig(0.6, 0.4, 3, 2000));
            var relevance = Map.of("f1", 0.9, "f2", 0.8, "f3", 0.7, "f4", 0.6, "f5", 0.5);
            var impression = Map.of("f1", 0.1, "f2", 0.1, "f3", 0.1, "f4", 0.1, "f5", 0.1);
            var ranked = iwr.rankCandidates(relevance, impression);
            assertEquals(3, ranked.size());
        }
    }

    // ── FormativeImpressionGuard ──

    @Nested
    class FormativeImpressionGuardTests {

        @Test
        void formative_fragment_guarded() {
            var fig = new FormativeImpressionGuard();
            var assessment = fig.assess("frag-1", 0.85, false, 0);
            assertTrue(assessment.isFormative());
            assertFalse(assessment.canConsolidate());
            assertFalse(assessment.canMerge());
            assertFalse(assessment.canThin());
        }

        @Test
        void non_formative_can_consolidate() {
            var fig = new FormativeImpressionGuard();
            var assessment = fig.assess("frag-2", 0.3, false, 0);
            assertFalse(assessment.isFormative());
            assertTrue(assessment.canConsolidate());
            assertTrue(assessment.canMerge());
        }

        @Test
        void high_charge_with_references_is_formative() {
            var fig = new FormativeImpressionGuard();
            var assessment = fig.assess("frag-3", 0.5, true, 5);
            assertTrue(assessment.isFormative());
        }

        @Test
        void batch_assess() {
            var fig = new FormativeImpressionGuard();
            var scores = Map.of("f1", 0.9, "f2", 0.3, "f3", 0.85);
            var guarded = fig.assessBatch(scores);
            assertEquals(2, guarded.size()); // f1 and f3
        }

        @Test
        void release_removes_guard() {
            var fig = new FormativeImpressionGuard();
            fig.guard("frag-1");
            assertTrue(fig.isGuarded("frag-1"));
            fig.release("frag-1");
            assertFalse(fig.isGuarded("frag-1"));
        }
    }
}
