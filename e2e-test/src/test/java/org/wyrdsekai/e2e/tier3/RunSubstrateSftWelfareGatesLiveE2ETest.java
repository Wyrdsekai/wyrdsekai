package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeParser;
import org.wyrdsekai.core.recipe.RecipeRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tier-3 live verify for the welfare-gate STOPs in
 * {@code run-substrate-sft} (#1025).
 *
 * <p>This recipe has FIVE PERMANENT welfare gates surrounding what is
 * the heaviest-stakes step in the autonomy stack — a real 2-hour LoRA
 * SFT against the production voice model. The gates exist so a bad
 * corpus or a regression in Ember / SubstrateArc / length-stratified
 * checks STOPs the recipe BEFORE the destructive deploy.</p>
 *
 * <p>This test runs the cheap, deterministic gate-STOPs against the
 * REAL bundled recipe + REAL bash + REAL Python:</p>
 *
 * <ol>
 *   <li><b>{@code check-corpus}</b> shell step on a missing path →
 *       corpus_lines=0 → {@code gate-corpus} STOPs at the 200-line floor.</li>
 *   <li><b>{@code check-corpus}</b> on a tiny 50-line corpus → still
 *       below 200 → {@code gate-corpus} STOPs.</li>
 * </ol>
 *
 * <p>The remaining four gates (train-loss / ember / substrate-arc /
 * length-collapse) live downstream of the actual 2-hour SFT step, so
 * exercising them requires a real GPU run — that's covered by the
 * dry-run sweep ({@code RecipeDryRunE2ETest}) for structural coverage
 * and gets triggered organically when operator runs the full recipe end
 * to end against a real corpus. See session memory
 * [[session-2026-05-20-codeplane-gpu-state]].</p>
 *
 * <p>Gated on {@code WYRDSEKAI_LIVE_RECIPE_E2E=1} + python3 reachable.</p>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_RECIPE_E2E", matches = "1|true")
class RunSubstrateSftWelfareGatesLiveE2ETest {

    private Path repoRoot;

    @BeforeEach
    void setUp() {
        repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null,
            "repo root with scripts/voice/run_substrate_sft.py not found");
    }

    @Test
    void corpus_gate_stops_at_missing_corpus_path(@TempDir Path tmp) throws Exception {
        // Point to a path that doesn't exist. The check-corpus shell step
        // detects missing file → emits {"corpus_lines": 0, "error":
        // "corpus_missing"} and exits 1. But: the shell exit 1 will halt
        // the runner with STEP_FAILED before gate-corpus even gets a turn.
        var params = baseParams(tmp);
        params.put("corpus_path", tmp.resolve("nonexistent.jsonl")
            .toAbsolutePath().toString());

        var run = runRecipe(params);
        printRun(run);

        // The check-corpus step exits 1 → STEP_FAILED ("shell step
        // 'check-corpus' exit 1"). This is the runner's normal "shell
        // failed" semantics, which surfaces an explicit error message
        // instead of silently passing the gate.
        assertThat(run.status())
            .as("missing corpus must STOP the recipe — msg=%s", run.message())
            .isEqualTo(RecipeRunner.Status.STEP_FAILED);
        assertThat(run.message()).contains("check-corpus");
    }

    @Test
    void corpus_gate_stops_when_corpus_below_200_line_floor(@TempDir Path tmp) throws Exception {
        // A tiny but well-formed corpus: 50 lines (below the 200 floor).
        Path corpus = tmp.resolve("tiny-corpus.jsonl");
        try (var w = Files.newBufferedWriter(corpus)) {
            for (int i = 0; i < 50; i++) {
                w.write("{\"prompt\":\"q" + i + "\",\"completion\":\"a" + i + "\"}\n");
            }
        }

        var params = baseParams(tmp);
        params.put("corpus_path", corpus.toAbsolutePath().toString());

        var run = runRecipe(params);
        printRun(run);

        assertThat(run.status())
            .as("under-curated corpus (50<200) must STOP at gate-corpus — msg=%s",
                run.message())
            .isEqualTo(RecipeRunner.Status.GATE_FAILED);
        assertThat(run.message()).contains("gate-corpus");

        // Train step must NEVER run when corpus gate STOPs — proves the
        // 2-hour SFT is gated by welfare floor as intended.
        boolean trainAttempted = run.outcomes().stream()
            .anyMatch(o -> o.id().equals("train"));
        assertThat(trainAttempted)
            .as("the 2-hour SFT step must NOT run when corpus gate STOPs")
            .isFalse();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> baseParams(Path tmp) throws IOException {
        Path outDir = tmp.resolve("sft-out");
        Files.createDirectories(outDir);
        var p = new HashMap<String, Object>();
        // The recipe's first SHELL step ({@code gpu-required}) is run before
        // any of these params matter. On home-server it passes (nvidia-smi reachable
        // + ≥1 CUDA device). On CPU-only it'd STEP_FAIL before reaching
        // check-corpus — that's the third welfare scenario but it can't
        // be exercised on home-server without unsetting CUDA visibility.
        p.put("voice_model_dir", "/home/you/models/Qwen3.5-4B-hf");
        p.put("output_dir", outDir.toAbsolutePath().toString());
        return p;
    }

    private RecipeRunner.RecipeRun runRecipe(Map<String, Object> params) {
        var manifest = loadBundledRecipe();
        var runner = new RecipeRunner(
            new ProcessCommandRunner(repoRoot.toFile(), Duration.ofMinutes(2)),
            null);
        return runner.run(manifest, params);
    }

    private static RecipeManifest loadBundledRecipe() {
        String resource = "recipes/run-substrate-sft.recipe.yaml";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new AssertionError("missing bundled recipe: " + resource);
            }
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return RecipeParser.parseManifest(yaml);
        } catch (IOException e) {
            throw new AssertionError("failed to load " + resource, e);
        }
    }

    private static void printRun(RecipeRunner.RecipeRun run) {
        System.out.println("=== Recipe outcome: " + run.status() + " — " + run.message());
        for (var o : run.outcomes()) {
            System.out.println("    " + o.id() + " [" + o.kind() + "] "
                + (o.ok() ? "OK" : "FAIL") + " :: " + o.detail());
        }
    }

    private static Path findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "scripts/voice/run_substrate_sft.py").isFile()) {
                return dir.toPath();
            }
        }
        return null;
    }
}
