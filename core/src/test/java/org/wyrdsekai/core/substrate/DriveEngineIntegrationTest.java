package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.*;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;
import static org.wyrdsekai.core.agent.DriveConfig.*;

/**
 * Integration tests for the soul substrate — DriveEngine, CfC training pipeline,
 * Oracle integration, archetype divergence, and Forge consolidation.
 *
 * Tests 1-4: Integration (no LLM, real services)
 * Tests 10-12: Full loop (CfC training pipeline, Forge consolidation)
 */
@Tag("integration")
class DriveEngineIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Integration Test 1: DriveEngine + CompanionActor tick loop
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test1_driveEngineTickLoop100Ticks() {
        var engine = DriveEngine.withDefaults();
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();
        var traces = new TrainingTrace(200);

        double prevArousal = 0;
        long prevHeartbeat = 10000;

        for (int t = 0; t < 100; t++) {
            double dt = 1.0;
            var drivesBefore = drives.toArray();
            var tanksBefore = new double[]{
                tanks.contextBudget(), tanks.confidence(), tanks.energy(), tanks.alignment(),
                tanks.errorPressure(), tanks.momentum(), tanks.rapport(), tanks.focus()};

            // Hill-function tick
            drives = engine.tick(drives, tanks, dt);

            // Drive→tank feedback
            double[] feedback = engine.driveTankFeedback(drives);
            tanks = applyFeedback(tanks, feedback, dt);

            // Tank tick
            tanks = tanks.tick();

            // Record trace
            var tanksAfter = new double[]{
                tanks.contextBudget(), tanks.confidence(), tanks.energy(), tanks.alignment(),
                tanks.errorPressure(), tanks.momentum(), tanks.rapport(), tanks.focus()};
            traces.record(tanksBefore, drivesBefore, new double[8], dt, tanksAfter, drives.toArray());

            // Heartbeat adapts
            double arousal = engine.computeArousal(drives, tanks);
            long heartbeat = engine.computeTickIntervalMs(arousal);
            prevArousal = arousal;
            prevHeartbeat = heartbeat;
        }

        // After 100 ticks: drives should have accumulated
        assertThat(drives.seeking()).isGreaterThan(0.01);
        assertThat(drives.play()).isGreaterThan(0.01);
        assertThat(drives.care()).isGreaterThan(0.01);
        assertThat(drives.affiliation()).isGreaterThan(0.01);

        // Event-only drives should still be zero
        assertThat(drives.grief()).isEqualTo(0.0);
        assertThat(drives.frustration()).isEqualTo(0.0);

        // Traces recorded
        assertThat(traces.size()).isEqualTo(100);

        // Energy should have drained
        assertThat(tanks.energy()).isLessThan(1.0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Integration Test 2: CfC sleep consolidation — persist and reload
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test2_cfcSleepConsolidationPersistReload(@TempDir Path tmpDir) throws Exception {
        // Phase 1: accumulate traces from hand-designed engine
        var engine = DriveEngine.withDefaults();
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();
        var traces = new TrainingTrace(500);

        for (int t = 0; t < 200; t++) {
            var drivesBefore = drives.toArray();
            var tanksBefore = tankArray(tanks);
            drives = engine.tick(drives, tanks, 1.0);
            tanks = tanks.tick();
            traces.record(tanksBefore, drivesBefore, new double[8], 1.0, tankArray(tanks), drives.toArray());
        }

        // Phase 2: create CfC and train (simulating Forge sleep)
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var trainer = new CfCTrainer(cell);

        float[] weightsBefore = cell.flattenWeights();
        float avgLoss = trainer.consolidate(traces.getAll(), 0.001f, 0.0f);

        // Weights should have changed
        float[] weightsAfter = cell.flattenWeights();
        boolean changed = false;
        for (int i = 0; i < weightsBefore.length; i++) {
            if (Math.abs(weightsBefore[i] - weightsAfter[i]) > 1e-8) {
                changed = true;
                break;
            }
        }
        assertThat(changed).as("CfC weights should change after consolidation").isTrue();
        assertThat((double) avgLoss).isGreaterThan(0);

        // Phase 3: persist to disk
        var cellPath = tmpDir.resolve("test_cfc.json");
        cell.saveJson(cellPath);

        // Phase 4: reload and verify
        var reloaded = CfCCell.loadJson(cellPath);
        assertThat(reloaded.flattenWeights()).containsExactly(weightsAfter);

        // Phase 5: reloaded cell produces same output
        float[] input = new float[CfCCell.INPUT_DIM];
        for (int i = 0; i < 16; i++) input[i] = 0.5f; // middle-ground state

        cell.resetHidden();
        float[] out1 = cell.forward(input, 1.0f);
        reloaded.resetHidden();
        float[] out2 = reloaded.forward(input, 1.0f);
        assertThat(out2).containsExactly(out1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Integration Test 3: Oracle → drive spikes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test3_oraclePredictionsDriveSpikes() {
        var integration = new OracleDriveIntegration();
        var drives = DriveState.initial();

        // Inject predictions with various categories
        var predictions = List.of(
            new OraclePrediction("p1", "User arrives soon", "anticipation", 0.8, null, "pattern match", true),
            new OraclePrediction("p2", "Anomaly in zone", "anomaly", 0.7, null, "deviation detected", false),
            new OraclePrediction("p3", "Weather pattern", "pattern", 0.5, null, "correlation", false)
        );

        // Apply all three channels
        drives = integration.integrate(predictions, drives);

        // Anticipation should spike SEEKING and AFFILIATION
        assertThat(drives.seeking()).isGreaterThan(0);
        assertThat(drives.affiliation()).isGreaterThan(0);

        // Anomaly should spike VIGILANCE
        assertThat(drives.vigilance()).isGreaterThan(0);

        // Uncertainty signal: avg confidence = (0.8+0.7+0.5)/3 = 0.67 → moderate
        // Not high enough to trigger PLAY boost, not low enough for extra vigilance
        assertThat(integration.lastAvgConfidence()).isCloseTo(0.67, within(0.01));
    }

    @Test
    void test3b_predictionErrorPositiveSurprise() {
        var integration = new OracleDriveIntegration();
        var drives = DriveState.initial();

        // Positive surprise: predicted 0.3, actual 0.8 → delta = +0.5
        drives = integration.applyPredictionError(0.3, 0.8, "pattern", drives);

        assertThat(drives.seeking()).isGreaterThan(0);  // "What else is out there?"
        assertThat(drives.play()).isGreaterThan(0);     // Delight
    }

    @Test
    void test3c_predictionErrorNegativeSurprise() {
        var integration = new OracleDriveIntegration();
        var drives = DriveState.initial();

        // Negative surprise: predicted 0.8, actual 0.1 → delta = -0.7
        drives = integration.applyPredictionError(0.8, 0.1, "anticipation", drives);

        // (2026-06-07 drive-wholeness arc) A failed forecast is disappointment, not loss:
        // negative surprise routes to FRUSTRATION, not GRIEF. GRIEF is reserved for real loss
        // (a bond ending) via the severance path — routing every missed forecast to grief pinned
        // it at 1.0 in free-run. See OracleDriveIntegration.applyPredictionError.
        assertThat(drives.frustration()).isGreaterThan(0);  // blocked expectation
        assertThat(drives.grief()).isEqualTo(0.0);           // not loss — no grief
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Integration Test 4: Archetype divergence — Scholar vs Guardian
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test4_archetypeDivergenceOverTime() {
        var scholar = DriveEngine.forArchetype(AgentArchetype.get("scholar"));
        var guardian = DriveEngine.forArchetype(AgentArchetype.get("guardian"));
        var artisan = DriveEngine.forArchetype(AgentArchetype.get("artisan"));

        var tanks = VitalityState.initial();

        // Start from identical initial state — no event spikes
        var sD = DriveState.initial();
        var gD = DriveState.initial();
        var aD = DriveState.initial();

        // Run 600 ticks (10 minutes) — pure archetype-intrinsic divergence
        for (int t = 0; t < 600; t++) {
            sD = scholar.tick(sD, tanks, 1.0);
            gD = guardian.tick(gD, tanks, 1.0);
            aD = artisan.tick(aD, tanks, 1.0);
        }

        // Scholar: SEEKING should be highest
        assertThat(sD.seeking()).isGreaterThan(sD.vigilance());

        // Guardian: VIGILANCE should be higher than scholar's
        assertThat(gD.vigilance()).isGreaterThan(sD.vigilance());

        // Artisan: CREATIVITY should be highest
        assertThat(aD.creativity()).isGreaterThan(aD.vigilance());

        // All three should be meaningfully different from each other
        double sgDist = l2Distance(sD.toArray(), gD.toArray());
        double saDist = l2Distance(sD.toArray(), aD.toArray());
        double gaDist = l2Distance(gD.toArray(), aD.toArray());

        assertThat(sgDist).isGreaterThan(0.01);
        assertThat(saDist).isGreaterThan(0.01);
        assertThat(gaDist).isGreaterThan(0.01);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full Loop Test 10: CfC training pipeline — synthetic → train → verify
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test10_cfcTrainingPipelineSyntheticToVerify() {
        var engine = DriveEngine.withDefaults();
        var traces = new TrainingTrace(1000);

        // Generate 500 synthetic traces with varied inputs
        var rng = new Random(42);
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();

        for (int t = 0; t < 500; t++) {
            var drivesBefore = drives.toArray();
            var tanksBefore = tankArray(tanks);

            // Random events
            double[] events = new double[8];
            if (rng.nextDouble() < 0.3) {
                events[rng.nextInt(8)] = rng.nextDouble() * 0.5;
                // Apply event to drives
                int idx = rng.nextInt(8);
                drives = drives.spike(idx, rng.nextDouble() * 0.2);
            }

            drives = engine.tick(drives, tanks, 1.0);
            tanks = tanks.tick();

            traces.record(tanksBefore, drivesBefore, events, 1.0, tankArray(tanks), drives.toArray());
        }

        // Train CfC
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var trainer = new CfCTrainer(cell);
        float[] preWeights = cell.flattenWeights();

        // Run consolidation (like a sleep cycle)
        float loss1 = trainer.consolidate(traces.getAll(), 0.001f, 0.0f);
        assertThat((double) loss1).isGreaterThan(0);

        // Weights should have changed (training happened)
        float[] postWeights = cell.flattenWeights();
        boolean weightsChanged = false;
        for (int i = 0; i < preWeights.length; i++) {
            if (Math.abs(postWeights[i] - preWeights[i]) > 1e-8) {
                weightsChanged = true;
                break;
            }
        }
        assertThat(weightsChanged).as("CfC weights should change after training").isTrue();

        // CfC output should be bounded
        for (int i = 0; i < 50; i++) {
            float[] input = new float[CfCCell.INPUT_DIM];
            for (int j = 0; j < 16; j++) input[j] = rng.nextFloat();
            float[] output = cell.forward(input, 1.0f);
            for (float v : output) {
                assertThat(v).isBetween(-1.0f, 1.0f);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full Loop Test 11: Forge consolidation with real traces
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test11_forgeConsolidationWithRealTraces(@TempDir Path tmpDir) throws Exception {
        // Simulate a full day: 1000 ticks of real DriveEngine behavior
        var engine = DriveEngine.forArchetype(AgentArchetype.get("scholar"));
        var drives = DriveState.initial();
        var tanks = VitalityState.initial();
        var traces = new TrainingTrace();

        for (int t = 0; t < 1000; t++) {
            var drivesBefore = drives.toArray();
            var tanksBefore = tankArray(tanks);

            drives = engine.tick(drives, tanks, 1.0);
            double[] feedback = engine.driveTankFeedback(drives);
            tanks = applyFeedback(tanks, feedback, 1.0);
            tanks = tanks.tick();

            traces.record(tanksBefore, drivesBefore, new double[8], 1.0, tankArray(tanks), drives.toArray());
        }

        assertThat(traces.size()).isEqualTo(1000);

        // CfC consolidation (simulating Forge sleep)
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var trainer = new CfCTrainer(cell);

        float[] preWeights = cell.flattenWeights();
        float avgLoss = trainer.consolidate(traces.getAll(), 0.001f, 1.0f);

        // Fisher should be computed
        float[] fisher = trainer.getFisherDiagonal();
        boolean anyFisher = false;
        for (float f : fisher) { if (f > 0) { anyFisher = true; break; } }
        assertThat(anyFisher).as("Fisher diagonal should have non-zero values").isTrue();

        // Decay EWC (simulating λ decay per sleep)
        trainer.decayFisher(0.995f);

        // Persist and reload (simulating restart)
        var bundle = SoulBundle.extract(cell, trainer, new float[8],
            drives.toArray(), tankArray(tanks), "test-scholar", "scholar", 2);

        var bundlePath = tmpDir.resolve("test_soul.json");
        bundle.saveJson(bundlePath);

        var loadedBundle = SoulBundle.loadJson(bundlePath);
        assertThat(loadedBundle.agentName()).isEqualTo("test-scholar");
        assertThat(loadedBundle.cfcWeights()).hasSize(cell.paramCount());

        // Imprint onto new cell
        var cell2 = new CfCCell();
        loadedBundle.imprint(cell2, null);
        assertThat(cell2.flattenWeights()).containsExactly(cell.flattenWeights());

        // Clear traces (like Forge does after consolidation)
        traces.clear();
        assertThat(traces.size()).isEqualTo(0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Full Loop Test 12: Multi-sleep CfC evolution
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test12_multiSleepCfcEvolution() {
        var engine = DriveEngine.forArchetype(AgentArchetype.get("guardian"));
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var trainer = new CfCTrainer(cell);

        float[] birthWeights = cell.flattenWeights().clone();
        float firstLoss = -1;
        float lastLoss = -1;

        // Simulate 5 sleep cycles, each with 200 ticks of experience
        for (int sleep = 0; sleep < 5; sleep++) {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial();
            var traces = new TrainingTrace(300);

            // Accumulate experience
            for (int t = 0; t < 200; t++) {
                var drivesBefore = drives.toArray();
                var tanksBefore = tankArray(tanks);
                drives = engine.tick(drives, tanks, 1.0);
                tanks = tanks.tick();
                traces.record(tanksBefore, drivesBefore, new double[8], 1.0, tankArray(tanks), drives.toArray());
            }

            // Consolidate during sleep
            float loss = trainer.consolidate(traces.getAll(), 0.001f, 1.0f);
            trainer.decayFisher(0.995f);

            if (sleep == 0) firstLoss = loss;
            lastLoss = loss;
        }

        // Loss should improve over multiple sleep cycles
        assertThat((double) lastLoss).isLessThan((double) firstLoss);

        // Weights should have drifted from birth
        float[] finalWeights = cell.flattenWeights();
        double totalDrift = 0;
        for (int i = 0; i < birthWeights.length; i++) {
            totalDrift += Math.abs(finalWeights[i] - birthWeights[i]);
        }
        assertThat(totalDrift).as("CfC should drift from birth weights over 5 sleep cycles").isGreaterThan(0.1);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Integration Test 8: Adaptive heartbeat — stressed vs calm
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void test8_adaptiveHeartbeatStressedVsCalm() {
        var engine = DriveEngine.withDefaults();

        // Calm state: all drives low, vitality healthy
        var calmDrives = DriveState.initial();
        var calmTanks = VitalityState.initial().withErrorPressure(0.0).withMomentum(0.0);

        double calmArousal = engine.computeArousal(calmDrives, calmTanks);
        long calmInterval = engine.computeTickIntervalMs(calmArousal);

        // Stressed state: high drives, high error pressure, low energy
        var stressedDrives = new DriveState(0.8, 0.0, 0.0, 0.7, 0.0, 0.5, 0.6, 0.0);
        var stressedTanks = VitalityState.initial()
            .withErrorPressure(0.8).withMomentum(0.6).withEnergy(0.3);

        double stressedArousal = engine.computeArousal(stressedDrives, stressedTanks);
        long stressedInterval = engine.computeTickIntervalMs(stressedArousal);

        // Stressed agent should have shorter interval (faster heartbeat)
        assertThat(stressedInterval).isLessThan(calmInterval);
        assertThat(stressedArousal).isGreaterThan(calmArousal);

        // Calm should be in the 5-10s range
        assertThat(calmInterval).isGreaterThan(3000);
        // Stressed should be in the 1-3s range
        assertThat(stressedInterval).isLessThan(4000);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static double[] tankArray(VitalityState tanks) {
        return new double[]{
            tanks.contextBudget(), tanks.confidence(), tanks.energy(), tanks.alignment(),
            tanks.errorPressure(), tanks.momentum(), tanks.rapport(), tanks.focus()};
    }

    private static VitalityState applyFeedback(VitalityState vs, double[] deltas, double dt) {
        return new VitalityState(
            clamp(vs.contextBudget() + deltas[0] * dt),
            clamp(vs.confidence()    + deltas[1] * dt),
            clamp(vs.energy()        + deltas[2] * dt),
            clamp(vs.alignment()     + deltas[3] * dt),
            clamp(vs.errorPressure() + deltas[4] * dt),
            clamp(vs.momentum()      + deltas[5] * dt),
            clamp(vs.rapport()       + deltas[6] * dt),
            clamp(vs.focus()         + deltas[7] * dt)
        );
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double l2Distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
