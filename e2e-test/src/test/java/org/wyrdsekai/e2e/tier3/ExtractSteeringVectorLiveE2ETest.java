package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.recipe.CommandRunner;
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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tier-3 live verify for {@code extract-steering-vector}
 * (#1024). Two scenarios:
 *
 * <ol>
 *   <li><b>Welfare gate STOPs at corpus floor</b>: seed a 10-pair file
 *       (below the 30-pair PERMANENT floor). Recipe halts at
 *       {@code gate-corpus} with no GPU work attempted. Fast (&lt;5s),
 *       no GPU needed.</li>
 *   <li><b>Real GPU extraction</b>: drive the full recipe end-to-end
 *       against the V8 {@code first_person_presence.jsonl} (50 pairs)
 *       and the HF Qwen3.5-4B weights at
 *       {@code /home/you/models/Qwen3.5-4B-hf}. Uses the dedicated
 *       repeng venv at {@code scripts/training/.venv-home-server/}. Validates
 *       the extraction step writes a candidate GGUF + emits a non-zero
 *       cosine_separation. Skips the parity-probe / deploy / smoke steps
 *       by overriding the parity-probe to a stub (parity probes need a
 *       held-out probe set that doesn't exist for this vector yet).</li>
 * </ol>
 *
 * <p>Both gated on {@code WYRDSEKAI_LIVE_RECIPE_E2E=1}. GPU test
 * additionally requires the venv + HF model + pair file to exist.</p>
 *
 * <p>Run on home-server:</p>
 * <pre>
 *   WYRDSEKAI_LIVE_RECIPE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.ExtractSteeringVectorLiveE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_RECIPE_E2E", matches = "1|true")
class ExtractSteeringVectorLiveE2ETest {

    private Path repoRoot;

    @BeforeEach
    void setUp() {
        repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null,
            "repo root with scripts/voice/extract_steering_vector.py not found");
    }

    @Test
    void welfare_gate_stops_at_corpus_floor_when_pairs_below_30(@TempDir Path tmp) throws Exception {
        Path pairsFile = tmp.resolve("tiny-pairs.jsonl");
        try (var w = Files.newBufferedWriter(pairsFile)) {
            for (int i = 0; i < 10; i++) {
                w.write("{\"positive\":\"present p" + i
                    + "\",\"negative\":\"absent n" + i + "\"}\n");
            }
        }
        Path outDir = tmp.resolve("vectors");
        Files.createDirectories(outDir);

        // The recipe's extract step references {{voice_model_dir}}, but
        // we never reach it — the corpus gate STOPs the run first.
        var params = new HashMap<String, Object>();
        params.put("vector", "tiny_test_vector");
        params.put("pairs_path", pairsFile.toAbsolutePath().toString());
        params.put("output_dir", outDir.toAbsolutePath().toString());
        params.put("voice_model_dir", "/nonexistent/voice-model");

        var run = runRecipe(params, /*usingGpuVenv=*/false);
        printRun(run);

        assertThat(run.status())
            .as("welfare gate-corpus must STOP at 10<30 — msg=%s", run.message())
            .isEqualTo(RecipeRunner.Status.GATE_FAILED);
        assertThat(run.message()).contains("gate-corpus");

        // GPU never invoked: no candidate file landed.
        Path candidate = outDir.resolve("tiny_test_vector.candidate.gguf");
        assertThat(Files.exists(candidate))
            .as("GPU step must NEVER run when welfare gate stops the recipe")
            .isFalse();
    }

    @Test
    void real_gpu_extraction_produces_candidate_with_positive_cosine_separation(@TempDir Path tmp)
            throws Exception {
        Path venvPython = repoRoot.resolve("scripts/training/.venv-home-server/bin/python3");
        assumeThat(Files.isExecutable(venvPython))
            .as("repeng venv at scripts/training/.venv-home-server must exist (live verify is home-server-only)")
            .isTrue();

        Path hfModel = Path.of("/home/you/models/Qwen3.5-4B-hf");
        assumeThat(Files.isDirectory(hfModel))
            .as("/home/you/models/Qwen3.5-4B-hf must exist on home-server")
            .isTrue();

        Path pairs = repoRoot.resolve("data/training/v8/pairs/first_person_presence.jsonl");
        assumeThat(Files.exists(pairs))
            .as("V8 first_person_presence pairs file required").isTrue();

        // The Qwen3.5-4B hybrid model needs ~12GB free VRAM for the
        // repeng forward pass (weights ~8GB + activations on a 32-layer
        // model with the gated-delta-rule's recurrent state). When the
        // wyrdsekai-llama 9B container is running (default home-server prod),
        // free VRAM drops below this threshold — extraction OOMs partway.
        // Stop the container temporarily and re-run when operator has
        // cycles (per the session memory:
        // [[session-2026-05-20-codeplane-gpu-state]]).
        int freeMiB = readFreeVramMiB();
        assumeThat(freeMiB)
            .as("real GPU extraction needs ≥12000 MiB free VRAM "
                + "(stop wyrdsekai-llama container if it's running). "
                + "Currently free: %d MiB", freeMiB)
            .isGreaterThan(12_000);

        Path outDir = tmp.resolve("vectors");
        Files.createDirectories(outDir);
        Path probeStub = tmp.resolve("probes").resolve("first_person_presence.jsonl");
        Files.createDirectories(probeStub.getParent());
        // The parity-probe step shells out to probe_vector_parity.py, which
        // is a structured "not implemented v01" stub — emits parity_delta=0
        // so the gate-parity (-0.05 floor) passes.
        Files.writeString(probeStub, "");

        var params = new HashMap<String, Object>();
        params.put("vector", "first_person_presence_test");
        params.put("pairs_path", pairs.toAbsolutePath().toString());
        params.put("output_dir", outDir.toAbsolutePath().toString());
        params.put("voice_model_dir", hfModel.toString());
        // Trim the layer range to keep GPU time bounded — every layer
        // doubles activation memory. 4 layers is enough to extract a
        // meaningful direction without burning the 4060Ti's 16GB.
        params.put("layer_start", 12);
        params.put("layer_end", 16);

        var run = runRecipe(params, /*usingGpuVenv=*/true);
        printRun(run);

        // Validate the extraction step itself produced a candidate.
        // Some steps DOWNSTREAM of extract may fail (parity-probe stub
        // emits a structured "not_implemented" with parity_delta=0; deploy
        // moves the candidate; smoke verifies non-empty). We only require
        // that extract itself produced the candidate file + positive
        // cosine_separation.
        Path candidate = outDir.resolve("first_person_presence_test.candidate.gguf");
        Path active = outDir.resolve("first_person_presence_test.gguf");
        // After SUCCESS the candidate gets renamed to active by the deploy
        // step. So check active OR candidate.
        assertThat(Files.exists(candidate) || Files.exists(active))
            .as("extract step must produce a GGUF (candidate or post-deploy active)")
            .isTrue();

        // The recipe context after run() should expose the cosine_separation
        // that the extract step emitted. If the run reached gate-cosine
        // either pass or fail, the value is in context.
        Object cosObj = run.context().get("cosine_separation");
        assertThat(cosObj)
            .as("cosine_separation must land in recipe context from extract step")
            .isNotNull();
        double cos = ((Number) cosObj).doubleValue();
        System.out.println("[extract] cosine_separation = " + cos);
        assertThat(cos)
            .as("real repeng extraction over 50 pairs must produce non-zero separation")
            .isGreaterThan(0.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private RecipeRunner.RecipeRun runRecipe(Map<String, Object> params, boolean usingGpuVenv)
            throws Exception {
        var manifest = loadBundledRecipe("recipes/extract-steering-vector.recipe.yaml");
        CommandRunner cmd = usingGpuVenv
            ? new VenvPathCommandRunner(repoRoot.toFile(),
                Duration.ofMinutes(15),
                repoRoot.resolve("scripts/training/.venv-home-server/bin"))
            : new ProcessCommandRunner(repoRoot.toFile(), Duration.ofMinutes(2));
        var runner = new RecipeRunner(cmd, null);
        return runner.run(manifest, params);
    }

    private static RecipeManifest loadBundledRecipe(String resource) {
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

    /** Reads {@code nvidia-smi} for free VRAM (MiB). Returns 0 if unreachable. */
    private static int readFreeVramMiB() {
        try {
            var p = new ProcessBuilder("nvidia-smi",
                "--query-gpu=memory.free", "--format=csv,noheader,nounits")
                .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (p.exitValue() != 0) return 0;
            // First GPU only — the 4060Ti is gpu 0 on home-server.
            return Integer.parseInt(out.split("\\n")[0].trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Path findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "scripts/voice/extract_steering_vector.py").isFile()) {
                return dir.toPath();
            }
        }
        return null;
    }

    /**
     * CommandRunner that prepends a venv's bin dir to PATH before running
     * each command — so {@code python3} inside the recipe resolves to the
     * repeng-enabled interpreter instead of system python. Modeled on
     * {@link ProcessCommandRunner}; the only delta is the env override.
     */
    private static final class VenvPathCommandRunner implements CommandRunner {
        private final File workingDir;
        private final Duration defaultTimeout;
        private final Path venvBin;

        VenvPathCommandRunner(File workingDir, Duration timeout, Path venvBin) {
            this.workingDir = workingDir;
            this.defaultTimeout = timeout;
            this.venvBin = venvBin;
        }

        @Override
        public CommandRunner.Result run(String command) {
            return run(command, defaultTimeout);
        }

        @Override
        public CommandRunner.Result run(String command, Duration timeout) {
            Duration effective = timeout == null ? defaultTimeout : timeout;
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(false);
                String existing = pb.environment().getOrDefault("PATH", "");
                pb.environment().put("PATH",
                    venvBin.toAbsolutePath() + ":" + existing);
                Process p = pb.start();
                byte[] out = p.getInputStream().readAllBytes();
                byte[] err = p.getErrorStream().readAllBytes();
                boolean finished = p.waitFor(effective.toMillis(),
                    TimeUnit.MILLISECONDS);
                if (!finished) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    return new CommandRunner.Result(124,
                        new String(out, StandardCharsets.UTF_8),
                        "venv runner: command timed out after "
                            + effective.toSeconds() + "s",
                        true);
                }
                int exit = p.exitValue();
                return new CommandRunner.Result(exit,
                    new String(out, StandardCharsets.UTF_8),
                    new String(err, StandardCharsets.UTF_8),
                    exit == 137);
            } catch (Exception e) {
                return new CommandRunner.Result(-1, "",
                    "venv runner: command failed to start: " + e.getMessage(),
                    true);
            }
        }
    }
}
