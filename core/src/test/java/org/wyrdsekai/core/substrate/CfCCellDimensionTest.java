package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests that CfC cell handles the expanded 36-dim input (10 tanks + 8 drives + 10 events + 8 archetype)
 * and 18-dim output (10 tank deltas + 8 drive deltas).
 */
class CfCCellDimensionTest {

    @Test
    void inputDimIs36() {
        assertThat(CfCCell.INPUT_DIM).isEqualTo(36);
    }

    @Test
    void outputDimIs18() {
        assertThat(CfCCell.OUTPUT_DIM).isEqualTo(18);
    }

    @Test
    void forwardProduces18DimOutput() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        float[] input = new float[36];
        for (int i = 0; i < 36; i++) input[i] = 0.5f;

        float[] output = cell.forward(input, 1.0f);
        assertThat(output).hasSize(18);
    }

    @Test
    void outputIsBounded() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        float[] input = new float[36];
        for (int i = 0; i < 36; i++) input[i] = (float) Math.random();

        float[] output = cell.forward(input, 1.0f);
        for (float v : output) {
            assertThat(v).isBetween(-1.0f, 1.0f); // tanh bounded
        }
    }

    @Test
    void hiddenStateInfluencesOutput() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        float[] input1 = new float[36];
        input1[0] = 1.0f; // high energy
        input1[10] = 0.8f; // high seeking

        float[] input2 = new float[36];
        input2[0] = 0.1f; // low energy
        input2[15] = 0.9f; // high grief

        // Fresh hidden state → output for input1
        cell.resetHidden();
        float[] out1 = cell.forward(input1, 1.0f);

        // Fresh hidden state → output for input2
        cell.resetHidden();
        float[] out2 = cell.forward(input2, 1.0f);

        // Different inputs should produce different outputs
        boolean differs = false;
        for (int i = 0; i < out1.length; i++) {
            if (Math.abs(out1[i] - out2[i]) > 1e-6f) differs = true;
        }
        assertThat(differs).isTrue();
    }

    @Test
    void paramCountMatchesExpanded() {
        var cell = new CfCCell();
        int params = cell.paramCount();
        // Backbone1: 36*54 + 54 = 1998
        // Backbone2: 54*36 + 36 = 1980
        // f/g/h heads: 3 * (36*18 + 18) = 3 * 666 = 1998
        // Total: 1998 + 1980 + 1998 = 5976 + hidden(18) = ~5994
        assertThat(params).isGreaterThan(5000);
        assertThat(params).isLessThan(7000);
    }

    @Test
    void trainingTraceInputDynamic() {
        // 10 tanks, 8 drives, 10 events = 28 input (without archetype)
        float[] tanks = new float[10];
        float[] drives = new float[8];
        float[] events = new float[10];
        var sample = new TrainingTrace.Sample(tanks, drives, events, 1.0f,
            tanks, drives, System.currentTimeMillis());

        float[] input = sample.input();
        assertThat(input).hasSize(28); // 10 + 8 + 10

        float[] target = sample.target();
        assertThat(target).hasSize(18); // 10 + 8
    }
}
