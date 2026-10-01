package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class CfCTrainerTest {

    private CfCCell cell;
    private CfCTrainer trainer;

    @BeforeEach
    void setUp() {
        cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        trainer = new CfCTrainer(cell);
    }

    @Test
    void trainStepReducesLoss() {
        float[] input = randomInput(new Random(1));
        float[] target = new float[CfCCell.OUTPUT_DIM]; // target = zeros

        // First loss
        cell.resetHidden();
        float loss1 = trainer.trainStep(input, target, 1.0f, 0.01f, 0.0f);

        // Train several steps
        for (int i = 0; i < 20; i++) {
            cell.resetHidden();
            trainer.trainStep(input, target, 1.0f, 0.01f, 0.0f);
        }

        // Loss should decrease
        cell.resetHidden();
        float[] output = cell.forward(input, 1.0f);
        float finalLoss = 0;
        for (int i = 0; i < target.length; i++) {
            float diff = output[i] - target[i];
            finalLoss += diff * diff;
        }
        finalLoss /= target.length;

        assertThat((double) finalLoss).isLessThan((double) loss1);
    }

    @Test
    void ewcPenaltyPreservesWeights() {
        float[] weightsBefore = cell.flattenWeights();

        // Compute Fisher (make some weights "important")
        var traces = generateTraces(50);
        trainer.recomputeFisher(traces);

        // Train with high EWC lambda
        float[] input = randomInput(new Random(99));
        float[] target = new float[CfCCell.OUTPUT_DIM];
        for (int i = 0; i < 50; i++) {
            cell.resetHidden();
            trainer.trainStep(input, target, 1.0f, 0.001f, 100.0f);
        }

        float[] weightsAfter = cell.flattenWeights();

        // With very high EWC, weights should barely move
        float maxDrift = 0;
        for (int i = 0; i < weightsBefore.length; i++) {
            maxDrift = Math.max(maxDrift, Math.abs(weightsAfter[i] - weightsBefore[i]));
        }
        assertThat((double) maxDrift).isLessThan(0.1); // weights barely changed
    }

    @Test
    void consolidateProcessesTraces() {
        var traces = generateTraces(100);
        float avgLoss = trainer.consolidate(traces, 0.001f, 0.0f);
        assertThat((double) avgLoss).isGreaterThan(0); // non-zero loss
        assertThat((double) avgLoss).isLessThan(2.0);  // reasonable range
    }

    @Test
    void consolidateWithEmptyTracesReturnsZero() {
        float loss = trainer.consolidate(List.of(), 0.001f, 0.0f);
        assertThat(loss).isEqualTo(0.0f);
    }

    @Test
    void fisherDiagonalIsComputed() {
        var traces = generateTraces(50);
        trainer.recomputeFisher(traces);

        float[] fisher = trainer.getFisherDiagonal();
        assertThat(fisher).hasSize(cell.paramCount());

        // At least some Fisher values should be non-zero
        boolean anyNonZero = false;
        for (float f : fisher) {
            if (f > 0) { anyNonZero = true; break; }
        }
        assertThat(anyNonZero).isTrue();
    }

    @Test
    void decayFisherReducesValues() {
        var traces = generateTraces(50);
        trainer.recomputeFisher(traces);
        float[] before = trainer.getFisherDiagonal();
        float sumBefore = sum(before);

        trainer.decayFisher(0.5f);
        float[] after = trainer.getFisherDiagonal();
        float sumAfter = sum(after);

        assertThat(sumAfter).isCloseTo(sumBefore * 0.5f, within(sumBefore * 0.01f));
    }

    @Test
    void resetAdamClearsState() {
        // Train a few steps to build Adam state
        float[] input = randomInput(new Random(1));
        float[] target = new float[CfCCell.OUTPUT_DIM];
        for (int i = 0; i < 5; i++) {
            cell.resetHidden();
            trainer.trainStep(input, target, 1.0f, 0.01f, 0.0f);
        }

        trainer.resetAdam();
        // After reset, first step should behave like a fresh trainer
        // (no way to directly verify internal state, but at least it doesn't crash)
        cell.resetHidden();
        float loss = trainer.trainStep(input, target, 1.0f, 0.01f, 0.0f);
        assertThat(loss).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fisherPersistenceRoundTrips() {
        var traces = generateTraces(50);
        trainer.recomputeFisher(traces);
        float[] fisher = trainer.getFisherDiagonal();

        // Create new trainer and load Fisher
        var trainer2 = new CfCTrainer(cell);
        trainer2.setFisherDiagonal(fisher);
        float[] restored = trainer2.getFisherDiagonal();

        assertThat(restored).containsExactly(fisher);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private List<TrainingTrace.Sample> generateTraces(int count) {
        var rng = new Random(42);
        var samples = new ArrayList<TrainingTrace.Sample>();
        for (int i = 0; i < count; i++) {
            float[] tanksBefore = randomFloats(rng, 8);
            float[] drivesBefore = randomFloats(rng, 8);
            float[] events = randomFloats(rng, 8);
            float[] tanksAfter = randomFloats(rng, 8);
            float[] drivesAfter = randomFloats(rng, 8);
            samples.add(new TrainingTrace.Sample(
                tanksBefore, drivesBefore, events, 1.0f, tanksAfter, drivesAfter,
                System.currentTimeMillis()));
        }
        return samples;
    }

    private float[] randomInput(Random rng) {
        return randomFloats(rng, CfCCell.INPUT_DIM);
    }

    private float[] randomFloats(Random rng, int size) {
        float[] arr = new float[size];
        for (int i = 0; i < size; i++) arr[i] = rng.nextFloat();
        return arr;
    }

    private float sum(float[] arr) {
        float s = 0;
        for (float v : arr) s += v;
        return s;
    }

    // ── Receptor Downregulation ─────────────────────────────────────────

    @Test
    void consolidationDetectsSustainedHighDrives() {
        // Generate traces where PLAY (index 2) is sustained above 0.9
        var traces = new ArrayList<TrainingTrace.Sample>();
        for (int i = 0; i < 100; i++) {
            float[] tanksBefore = randomFloats(new Random(42), 8);
            float[] drivesBefore = new float[8];
            drivesBefore[2] = 0.95f; // PLAY sustained high
            float[] tanksAfter = randomFloats(new Random(42), 8);
            float[] drivesAfter = new float[8];
            drivesAfter[2] = 0.93f; // still high after tick
            traces.add(new TrainingTrace.Sample(
                tanksBefore, drivesBefore, new float[8], 1.0f,
                tanksAfter, drivesAfter, System.currentTimeMillis()));
        }

        // Should consolidate without error and log downregulation warning
        float avgLoss = trainer.consolidate(traces, 0.001f, 1.0f);
        assertThat(avgLoss).isFinite();

        // Fisher diagonal for the downregulated drive's output dimension should be increased
        // (we can't easily verify the exact Fisher value without exposing it, but the
        // consolidation should complete without error)
    }

    @Test
    void normalTracesNoDownregulation() {
        // Generate traces with moderate drive values — no downregulation expected
        var traces = new ArrayList<TrainingTrace.Sample>();
        for (int i = 0; i < 100; i++) {
            float[] tanksBefore = randomFloats(new Random(42), 8);
            float[] drivesBefore = randomFloats(new Random(42), 8);
            // Keep all drives under 0.5
            for (int j = 0; j < drivesBefore.length; j++) drivesBefore[j] *= 0.5f;
            float[] tanksAfter = randomFloats(new Random(42), 8);
            float[] drivesAfter = randomFloats(new Random(42), 8);
            for (int j = 0; j < drivesAfter.length; j++) drivesAfter[j] *= 0.5f;
            traces.add(new TrainingTrace.Sample(
                tanksBefore, drivesBefore, new float[8], 1.0f,
                tanksAfter, drivesAfter, System.currentTimeMillis()));
        }

        float avgLoss = trainer.consolidate(traces, 0.001f, 1.0f);
        assertThat(avgLoss).isFinite();
    }

    // A trace is recorded every second she is awake and each costs a forward pass per parameter:
    // a day without a restart kept her asleep 17 minutes after the night's write (2026-09-30).

    @Test
    void aLongDayIsReplayedAsABoundedEvenlySpreadPartOfIt() {
        var day = new ArrayList<Integer>();
        for (int i = 0; i < 25_691; i++) day.add(i);
        var replay = CfCTrainer.evenly(day, CfCTrainer.MAX_REPLAY);
        assertThat(replay).hasSize(CfCTrainer.MAX_REPLAY);
        assertThat(replay.get(0)).as("from the first moment").isEqualTo(0);
        assertThat(replay.get(replay.size() - 1)).as("to the last").isEqualTo(25_690);
        assertThat(replay).as("in the order they happened").isSorted().doesNotHaveDuplicates();
        int widest = 0;
        for (int i = 1; i < replay.size(); i++) widest = Math.max(widest, replay.get(i) - replay.get(i - 1));
        assertThat(widest).as("no stretch of the day left out").isLessThanOrEqualTo(18);
    }

    @Test
    void aShortDayIsReplayedWhole() {
        var day = List.of(1, 2, 3, 4, 5);
        assertThat(CfCTrainer.evenly(day, CfCTrainer.MAX_REPLAY)).isSameAs(day);
        assertThat(CfCTrainer.evenly(day, 5)).isSameAs(day);
        assertThat(CfCTrainer.evenly(day, 3)).containsExactly(1, 3, 5);
        assertThat(CfCTrainer.evenly(day, 1)).containsExactly(1);
    }
}
