package org.wyrdsekai.core.substrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

class SoulBundleTest {

    @Test
    void extractCapturesCellState() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        cell.forward(new float[CfCCell.INPUT_DIM], 1.0f); // set hidden

        var bundle = SoulBundle.extract(cell, null,
            new float[]{1, 2, 3, 4, 5, 6, 7, 8},
            new double[]{0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1},
            new double[]{0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5},
            "ember", "scholar", 2);

        assertThat(bundle.cfcWeights()).hasSize(cell.paramCount());
        assertThat(bundle.cfcHidden()).hasSize(CfCCell.OUTPUT_DIM);
        assertThat(bundle.archetypeVector()).hasSize(8);
        assertThat(bundle.agentName()).isEqualTo("ember");
        assertThat(bundle.tier()).isEqualTo(2);
    }

    @Test
    void imprintRestoresState() {
        var cell1 = new CfCCell();
        cell1.initializeXavier(new Random(42));
        cell1.forward(new float[CfCCell.INPUT_DIM], 1.0f);

        var bundle = SoulBundle.extract(cell1, null, null, null, null, "test", "scholar", 0);

        var cell2 = new CfCCell();
        bundle.imprint(cell2, null);

        assertThat(cell2.flattenWeights()).containsExactly(cell1.flattenWeights());
        assertThat(cell2.getHidden()).containsExactly(cell1.getHidden());
    }

    @Test
    void jsonRoundTrip(@TempDir Path tmpDir) throws IOException {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));

        var bundle = SoulBundle.extract(cell, null,
            new float[]{1, 2, 3, 4, 5, 6, 7, 8},
            new double[8], new double[8],
            "ember", "scholar", 2);

        Path file = tmpDir.resolve("soul.json");
        bundle.saveJson(file);

        var loaded = SoulBundle.loadJson(file);
        assertThat(loaded.agentName()).isEqualTo("ember");
        assertThat(loaded.archetypeName()).isEqualTo("scholar");
        assertThat(loaded.tier()).isEqualTo(2);
        assertThat(loaded.cfcWeights()).containsExactly(bundle.cfcWeights());
        assertThat(loaded.archetypeVector()).containsExactly(bundle.archetypeVector());
    }

    @Test
    void estimatedSizeIsReasonable() {
        var cell = new CfCCell();
        cell.initializeXavier(new Random(42));
        var bundle = SoulBundle.extract(cell, null, null, null, null, "test", "scholar", 0);

        long size = bundle.estimatedSizeBytes();
        // ~4800 weights + ~4800 fisher + 16 hidden + 8 archetype + 16 baselines ≈ ~9600 floats
        // ~9600 × 10 bytes per float in JSON ≈ 96KB
        assertThat(size).isBetween(5_000L, 200_000L);
    }
}
