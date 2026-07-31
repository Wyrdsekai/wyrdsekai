package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class CfCCellTest {

    private CfCCell cell;

    @BeforeEach
    void setUp() {
        cell = new CfCCell();
        cell.initializeXavier(new Random(42));
    }

    @Test
    void forwardProducesOutputOfCorrectSize() {
        float[] input = new float[CfCCell.INPUT_DIM];
        float[] output = cell.forward(input, 1.0f);
        assertThat(output).hasSize(CfCCell.OUTPUT_DIM);
    }

    @Test
    void outputIsBounded() {
        // CfC output is bounded by tanh(g) and tanh(h), so in [-1, 1]
        var rng = new Random(123);
        for (int trial = 0; trial < 100; trial++) {
            float[] input = randomInput(rng);
            float[] output = cell.forward(input, rng.nextFloat() * 10);
            for (float v : output) {
                assertThat(v).isBetween(-1.0f, 1.0f);
            }
        }
    }

    @Test
    void deltaTimeAffectsOutput() {
        float[] input = randomInput(new Random(42));

        cell.resetHidden();
        float[] out1 = cell.forward(input, 0.1f);

        cell.resetHidden();
        float[] out2 = cell.forward(input, 10.0f);

        // Different deltaTime should produce different interpolation
        boolean anyDifferent = false;
        for (int i = 0; i < out1.length; i++) {
            if (Math.abs(out1[i] - out2[i]) > 0.001) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).isTrue();
    }

    @Test
    void hiddenStateUpdates() {
        float[] input = randomInput(new Random(42));
        float[] hiddenBefore = cell.getHidden();
        cell.forward(input, 1.0f);
        float[] hiddenAfter = cell.getHidden();

        // Hidden state should change after forward pass
        boolean changed = false;
        for (int i = 0; i < hiddenBefore.length; i++) {
            if (Math.abs(hiddenBefore[i] - hiddenAfter[i]) > 1e-6) {
                changed = true;
                break;
            }
        }
        assertThat(changed).isTrue();
    }

    @Test
    void resetHiddenZerosState() {
        cell.forward(randomInput(new Random(42)), 1.0f);
        cell.resetHidden();
        float[] h = cell.getHidden();
        for (float v : h) {
            assertThat(v).isEqualTo(0.0f);
        }
    }

    @Test
    void paramCountIsConsistent() {
        int count = cell.paramCount();
        float[] flat = cell.flattenWeights();
        assertThat(flat).hasSize(count);
    }

    @Test
    void flattenAndLoadRoundTrips() {
        float[] original = cell.flattenWeights();
        var cell2 = new CfCCell();
        cell2.loadWeights(original);
        float[] restored = cell2.flattenWeights();
        assertThat(restored).containsExactly(original);
    }

    @Test
    void addWeightNoiseChangesWeights() {
        float[] before = cell.flattenWeights();
        cell.addWeightNoise(new Random(99), 0.1f);
        float[] after = cell.flattenWeights();

        boolean changed = false;
        for (int i = 0; i < before.length; i++) {
            if (Math.abs(before[i] - after[i]) > 1e-6) {
                changed = true;
                break;
            }
        }
        assertThat(changed).isTrue();
    }

    @Test
    void forwardWithGradProducesSameOutputAsForward() {
        float[] input = randomInput(new Random(42));

        cell.resetHidden();
        float[] out1 = cell.forward(input, 1.0f);

        cell.resetHidden();
        var result = cell.forwardWithGrad(input, 1.0f);

        assertThat(result.output()).containsExactly(out1);
    }

    @Test
    void jsonRoundTrip(@TempDir Path tmpDir) throws IOException {
        float[] input = randomInput(new Random(42));
        cell.forward(input, 1.0f); // set hidden state

        Path file = tmpDir.resolve("test_cell.json");
        cell.saveJson(file);

        var loaded = CfCCell.loadJson(file);
        assertThat(loaded.flattenWeights()).containsExactly(cell.flattenWeights());
        assertThat(loaded.getHidden()).containsExactly(cell.getHidden());
    }

    @Test
    void deterministicWithSameSeed() {
        var cell1 = new CfCCell();
        cell1.initializeXavier(new Random(42));
        var cell2 = new CfCCell();
        cell2.initializeXavier(new Random(42));
        assertThat(cell1.flattenWeights()).containsExactly(cell2.flattenWeights());
    }

    private float[] randomInput(Random rng) {
        float[] input = new float[CfCCell.INPUT_DIM];
        for (int i = 0; i < input.length; i++) {
            input[i] = rng.nextFloat();
        }
        return input;
    }
}
