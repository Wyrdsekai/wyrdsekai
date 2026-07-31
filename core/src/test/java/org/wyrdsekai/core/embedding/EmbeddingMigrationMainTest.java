package org.wyrdsekai.core.embedding;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI surface coverage for {@link EmbeddingMigrationMain}.
 *
 * <p>Confirms the four subcommands ({@code --plan}, {@code --run},
 * {@code --status}, {@code --reset}) are reachable, return sensible exit
 * codes, and emit usable output. Exhaustive behavior coverage of the
 * underlying framework lives in {@link EmbeddingMigrationFrameworkTest}
 * and the per-migrator tests; this is just the wire-up.
 *
 * <p>We avoid invoking {@code --run} or {@code --plan} with a real
 * EmbeddingService because that loads the bundled ONNX model (slow,
 * native deps) — those paths are exercised via the multilingual
 * service test + framework tests separately.
 */
class EmbeddingMigrationMainTest {

    @Test
    void noArgs_printsUsage_andExitsOne() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = EmbeddingMigrationMain.run(
            new PrintStream(out), new PrintStream(err), new String[]{});
        assertThat(code).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
            .contains("usage: wyrd embed-migrate");
    }

    @Test
    void unknownSubcommand_printsError_andExitsOne() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = EmbeddingMigrationMain.run(
            new PrintStream(out), new PrintStream(err), new String[]{"--frobnicate"});
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("unknown subcommand");
    }

    @Test
    void help_exitsZero_andPrintsAllFourSubcommands() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = EmbeddingMigrationMain.run(
            new PrintStream(out), new PrintStream(err), new String[]{"--help"});
        assertThat(code).isEqualTo(0);
        var output = out.toString(StandardCharsets.UTF_8);
        // All four subcommands documented.
        assertThat(output).contains("--plan");
        assertThat(output).contains("--run");
        assertThat(output).contains("--status");
        assertThat(output).contains("--reset");
    }

    @Test
    void resetWithoutTable_returnsErrorOne() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = EmbeddingMigrationMain.run(
            new PrintStream(out), new PrintStream(err), new String[]{"--reset"});
        assertThat(code).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("--reset requires a table name");
    }
}
