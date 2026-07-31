package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1142 — proves the override → effectiveParams → runner
 * substitution chain end-to-end: a stored param override changes the value a
 * scheduled run sees, while an explicit per-run param still wins over the
 * stored tune (a steward forcing a value isn't second-guessed).
 */
class RecipeServiceParamMergeTest {

    /** A recipe whose single SHELL step echoes {@code {{lookback_days}}} so the
     *  substituted command reveals which value won the merge. */
    private static void writeRecipe(Path dir) throws IOException {
        Files.writeString(dir.resolve("mergeable.recipe.yaml"), """
            recipe: mergeable
            params:
              lookback_days: { type: number, default: 30 }
            steps:
              - id: run
                kind: SHELL
                command: "echo lookback={{lookback_days}}"
            """);
    }

    /** A runner that captures the (already-substituted) command text. */
    private static RecipeRunner capturingRunner(AtomicReference<String> seen) {
        CommandRunner cr = cmd -> {
            seen.set(cmd);
            return new CommandRunner.Result(0, "{}", "");
        };
        return new RecipeRunner(cr);
    }

    @Test void stored_override_is_applied_when_caller_omits_the_param(@TempDir Path dir)
            throws IOException {
        writeRecipe(dir);
        var seen = new AtomicReference<String>();
        var overrides = new SqlRecipeParamOverrides(
            "jdbc:sqlite:" + dir.resolve("ovr.db").toAbsolutePath());
        overrides.upsert("mergeable", null, "lookback_days", "90", "tester");

        var svc = new RecipeService(dir, capturingRunner(seen))
            .withParamOverrides(overrides);
        svc.run("mergeable", Map.of());   // caller supplies nothing

        assertThat(seen.get()).contains("lookback=90");
    }

    @Test void explicit_caller_param_wins_over_a_stored_override(@TempDir Path dir)
            throws IOException {
        writeRecipe(dir);
        var seen = new AtomicReference<String>();
        var overrides = new SqlRecipeParamOverrides(
            "jdbc:sqlite:" + dir.resolve("ovr.db").toAbsolutePath());
        overrides.upsert("mergeable", null, "lookback_days", "90", "tester");

        var svc = new RecipeService(dir, capturingRunner(seen))
            .withParamOverrides(overrides);
        // A steward forcing 7 must not be overruled by the stored tune of 90.
        svc.run("mergeable", Map.of("lookback_days", 7));

        assertThat(seen.get()).contains("lookback=7");
    }

    @Test void no_override_store_leaves_manifest_default(@TempDir Path dir)
            throws IOException {
        writeRecipe(dir);
        var seen = new AtomicReference<String>();
        // No withParamOverrides → caller params verbatim, manifest default fills in.
        var svc = new RecipeService(dir, capturingRunner(seen));
        svc.run("mergeable", Map.of());

        assertThat(seen.get()).contains("lookback=30");
    }
}
