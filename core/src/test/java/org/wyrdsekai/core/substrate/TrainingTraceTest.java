package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TrainingTraceTest {

    @Test
    void recordAndRetrieve() {
        var trace = new TrainingTrace(100);
        double[] tanks = {0.5, 0.5, 0.8, 0.3, 0.0, 0.0, 0.3, 0.5};
        double[] drives = {0.1, 0.2, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0};
        double[] events = new double[8];
        double[] tanksAfter = {0.5, 0.5, 0.79, 0.29, 0.0, 0.0, 0.29, 0.49};
        double[] drivesAfter = {0.11, 0.21, 0.31, 0.01, 0.01, 0.0, 0.0, 0.01};

        trace.record(tanks, drives, events, 1.0, tanksAfter, drivesAfter);

        assertThat(trace.size()).isEqualTo(1);
        var samples = trace.getAll();
        assertThat(samples).hasSize(1);
        assertThat(samples.getFirst().deltaTime()).isEqualTo(1.0f);
    }

    @Test
    void ringBufferEvictsOldest() {
        var trace = new TrainingTrace(3);
        var d = new double[8];

        for (int i = 0; i < 5; i++) {
            d[0] = i; // mark each sample
            trace.record(d, d, d, 1.0, d, d);
        }

        assertThat(trace.size()).isEqualTo(3);
        var samples = trace.getAll();
        // Oldest (0, 1) should be evicted; remaining are 2, 3, 4
        assertThat(samples.getFirst().tanksBefore()[0]).isEqualTo(2.0f);
        assertThat(samples.getLast().tanksBefore()[0]).isEqualTo(4.0f);
    }

    @Test
    void clearEmptiesBuffer() {
        var trace = new TrainingTrace();
        var d = new double[8];
        trace.record(d, d, d, 1.0, d, d);
        trace.record(d, d, d, 1.0, d, d);

        trace.clear();
        assertThat(trace.size()).isEqualTo(0);
        assertThat(trace.getAll()).isEmpty();
    }

    @Test
    void hasMinimumTraces() {
        var trace = new TrainingTrace();
        assertThat(trace.hasMinimumTraces(10)).isFalse();

        var d = new double[8];
        for (int i = 0; i < 10; i++) {
            trace.record(d, d, d, 1.0, d, d);
        }
        assertThat(trace.hasMinimumTraces(10)).isTrue();
    }

    @Test
    void inputAndTargetVectorsCorrectDimension() {
        var trace = new TrainingTrace();
        var tanks = new double[]{0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};
        var drives = new double[]{0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2};
        var events = new double[]{1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};

        trace.record(tanks, drives, events, 1.0, tanks, drives);

        var sample = trace.getAll().getFirst();
        // Input: 24-dim [tanks(8) + drives(8) + events(8)]
        assertThat(sample.input()).hasSize(24);
        assertThat(sample.input()[0]).isCloseTo(0.1f, within(0.01f));  // first tank
        assertThat(sample.input()[8]).isCloseTo(0.9f, within(0.01f));  // first drive
        assertThat(sample.input()[16]).isCloseTo(1.0f, within(0.01f)); // first event

        // Target: 16-dim [tanks(8) + drives(8)]
        assertThat(sample.target()).hasSize(16);
    }

    @Test
    void estimatedMemoryIsReasonable() {
        var trace = new TrainingTrace();
        var d = new double[8];
        for (int i = 0; i < 1000; i++) {
            trace.record(d, d, d, 1.0, d, d);
        }
        // ~228 bytes per sample → ~228KB for 1000 samples
        assertThat(trace.estimatedMemoryBytes()).isBetween(100_000L, 500_000L);
    }

    @Test
    void sampleClonesArrays() {
        var trace = new TrainingTrace();
        var tanks = new double[]{0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
        var drives = new double[8];

        trace.record(tanks, drives, drives, 1.0, tanks, drives);

        // Modify original array — sample should not be affected
        tanks[0] = 99.0;
        var sample = trace.getAll().getFirst();
        assertThat(sample.tanksBefore()[0]).isEqualTo(0.5f);
    }
}
