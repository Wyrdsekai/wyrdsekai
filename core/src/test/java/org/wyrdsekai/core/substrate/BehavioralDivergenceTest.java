package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.*;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that different archetypes, birth noise, and CfC produce meaningfully different behavior.
 * Validates §14.3 and §14.4 of.
 */
class BehavioralDivergenceTest {

    // ── Archetype Divergence ─────────────────────────────────────────────

    @Test
    void allSixArchetypesDiverge() {
        var archetypes = new String[]{"scholar", "guardian", "artisan", "diplomat", "explorer", "steward"};
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();

        double[][] finalStates = new double[archetypes.length][];

        for (int a = 0; a < archetypes.length; a++) {
            var engine = DriveEngine.forArchetype(AgentArchetype.get(archetypes[a]));
            var d = drives;
            for (int t = 0; t < 300; t++) { // 5 minutes
                d = engine.tick(d, tanks, 1.0);
            }
            finalStates[a] = d.toArray();
        }

        // Every pair should have meaningful divergence (L2 distance — magnitude matters)
        for (int i = 0; i < archetypes.length; i++) {
            for (int j = i + 1; j < archetypes.length; j++) {
                double dist = l2Distance(finalStates[i], finalStates[j]);
                assertThat(dist)
                    .as("L2 divergence between %s and %s", archetypes[i], archetypes[j])
                    .isGreaterThan(0.001);
            }
        }
    }

    @Test
    void scholarSeeksMoreThanGuardianVigilates() {
        var scholar = DriveEngine.forArchetype(AgentArchetype.get("scholar"));
        var guardian = DriveEngine.forArchetype(AgentArchetype.get("guardian"));
        var tanks = VitalityState.initial();

        var sD = DriveState.initial();
        var gD = DriveState.initial();

        for (int t = 0; t < 600; t++) {
            sD = scholar.tick(sD, tanks, 1.0);
            gD = guardian.tick(gD, tanks, 1.0);
        }

        // Scholar's primary drive (SEEKING) should exceed guardian's
        assertThat(sD.seeking()).isGreaterThan(gD.seeking());
        // Guardian's VIGILANCE should be boosted
        assertThat(gD.vigilance()).isGreaterThan(sD.vigilance());
    }

    @Test
    void artisanCreatesMoreThanDiplomat() {
        var artisan = DriveEngine.forArchetype(AgentArchetype.get("artisan"));
        var diplomat = DriveEngine.forArchetype(AgentArchetype.get("diplomat"));
        var tanks = VitalityState.initial();

        var aD = DriveState.initial();
        var dD = DriveState.initial();

        for (int t = 0; t < 600; t++) {
            aD = artisan.tick(aD, tanks, 1.0);
            dD = diplomat.tick(dD, tanks, 1.0);
        }

        assertThat(aD.creativity()).isGreaterThan(dD.creativity());
        assertThat(dD.affiliation()).isGreaterThan(aD.affiliation());
    }

    // ── Birth Noise Divergence ───────────────────────────────────────────

    @Test
    void twoAgentsSameArchetypeDivergeWithNoise() {
        var cell1 = new CfCCell();
        cell1.initializeXavier(new Random(42));
        cell1.addWeightNoise(new Random(1), 0.02f);

        var cell2 = new CfCCell();
        cell2.initializeXavier(new Random(42));
        cell2.addWeightNoise(new Random(2), 0.02f);

        // Same input should produce different outputs
        float[] input = new float[CfCCell.INPUT_DIM];
        for (int i = 0; i < input.length; i++) input[i] = 0.5f;

        float[] out1 = cell1.forward(input, 1.0f);
        float[] out2 = cell2.forward(input, 1.0f);

        boolean anyDifferent = false;
        for (int i = 0; i < out1.length; i++) {
            if (Math.abs(out1[i] - out2[i]) > 0.001) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).isTrue();
    }

    // ── CfC Regression: Approximates Hand-Designed ───────────────────────

    @Test
    void cfcOutputIsBoundedLikeHandDesigned() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        // Run many random inputs — all outputs must be in [-1, 1]
        var rng = new Random(99);
        for (int i = 0; i < 200; i++) {
            float[] input = new float[CfCCell.INPUT_DIM];
            for (int j = 0; j < input.length; j++) input[j] = rng.nextFloat();
            float[] output = cell.forward(input, rng.nextFloat() * 5);
            for (float v : output) {
                assertThat(v).isBetween(-1.0f, 1.0f);
            }
        }
    }

    @Test
    void cfcRespondsToHighGriefInput() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        // Input with high grief (index 13 in 32-dim: drives[5]=grief)
        float[] calm = new float[CfCCell.INPUT_DIM];
        float[] grieving = new float[CfCCell.INPUT_DIM];
        grieving[13] = 0.9f; // high grief

        cell.resetHidden();
        float[] calmOut = cell.forward(calm, 1.0f);
        cell.resetHidden();
        float[] griefOut = cell.forward(grieving, 1.0f);

        // Outputs should differ
        boolean anyDiff = false;
        for (int i = 0; i < calmOut.length; i++) {
            if (Math.abs(calmOut[i] - griefOut[i]) > 0.001) {
                anyDiff = true;
                break;
            }
        }
        assertThat(anyDiff).isTrue();
    }

    @Test
    void consolidationReducesLossOverTraces() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var trainer = new CfCTrainer(cell);

        // Generate traces from hand-designed engine
        var engine = DriveEngine.withDefaults();
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();
        var trace = new TrainingTrace(200);

        for (int i = 0; i < 100; i++) {
            var drivesBefore = drives.toArray();
            var tanksBefore = new double[]{
                tanks.contextBudget(), tanks.confidence(), tanks.energy(), tanks.alignment(),
                tanks.errorPressure(), tanks.momentum(), tanks.rapport(), tanks.focus()};
            drives = engine.tick(drives, tanks, 1.0);
            trace.record(tanksBefore, drivesBefore, new double[8], 1.0,
                tanksBefore, drives.toArray()); // simplified: tanks don't change in this test
        }

        var samples = trace.getAll();

        // First forward pass loss
        cell.resetHidden();
        float[] firstOut = cell.forward(samples.getFirst().input(), 1.0f);
        float[] target = samples.getFirst().target();
        float firstLoss = 0;
        for (int i = 0; i < target.length; i++) {
            float diff = firstOut[i] - target[i];
            firstLoss += diff * diff;
        }

        // Consolidate
        float avgLoss = trainer.consolidate(samples, 0.005f, 0.0f);

        // Loss after consolidation should be lower than initial
        assertThat((double) avgLoss).isLessThan((double) firstLoss);
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private double l2Distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
