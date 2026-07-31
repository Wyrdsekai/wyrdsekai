package org.wyrdsekai.e2e.tier3;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;
import org.wyrdsekai.core.recipe.CodingBackendDispatcher;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeManifest;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeStep;
import org.wyrdsekai.core.recipe.StepKind;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * tier3 live E2E proof that the production-shape recipe
 * lifecycle (gates + reversible deploy + rollback) works end-to-end <b>with
 * a real BACKEND step firing live goose against the local 9B</b>.
 *
 * <p>The existing in-package {@code RetrainClassifierHeadE2ETest} (P4) proved
 * the real train + gates + deploy + rollback chain works against the real
 * Python script, but its BACKEND steps are skipped (corpus already clears the
 * gate) so goose is never invoked. This test closes that gap — runs train
 * + gates + deploy from the same shape, but the post-deploy smoke step is a
 * REAL BACKEND step dispatched to live goose. Two scenarios:</p>
 *
 * <ol>
 *   <li><b>Happy path</b>: goose verifies the deployed file → smoke passes →
 *       recipe SUCCESS, no rollback. Proves the dispatcher → goose → contract
 *       chain holds inside the full recipe lifecycle.</li>
 *   <li><b>Rollback path</b>: goose is asked to verify a path that does not
 *       exist → smoke fails → recipe STEP_FAILED, rollback fires, deploy
 *       target restored byte-for-byte. Proves the reversibility seam holds
 *       even when the failure trigger comes from a real backend.</li>
 * </ol>
 *
 * <p><b>Why not the full production recipe verbatim?</b> The production recipe's
 * {@code expand-corpus} step requires {@code ANTHROPIC_API_KEY} (see filed
 * task) — households without a cloud API can't currently evolve. That's a
 * separately tracked OSS release concern. Here we exercise the lifecycle
 * shape against what IS runnable today.</p>
 *
 * <p>Safety: never writes to the real production {@code pretrained/} tree.
 * Hashes the real production ONNX before + after to prove non-mutation.
 *
 * <p>Gated on {@code WYRDSEKAI_LIVE_GOOSE_E2E=1} + goose + :8200 reachable +
 * python ML stack importable. Run on home-server:</p>
 * <pre>
 *   WYRDSEKAI_LIVE_GOOSE_E2E=1 ./gradlew :e2e-test:test \
 *     --tests "org.wyrdsekai.e2e.tier3.RetrainClassifierHeadLiveE2ETest"
 * </pre>
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_LIVE_GOOSE_E2E", matches = "1|true")
class RetrainClassifierHeadLiveE2ETest {

    private static final String HEAD = "task_present";
    private static final String CORPUS_REL =
            "core/src/main/resources/classifier/bootstrap/" + HEAD + "/expanded.jsonl";
    private static final String PROD_ONNX_REL =
            "core/src/main/resources/classifier/pretrained/" + HEAD + ".onnx";
    private static final String EMBEDDING_REL =
            "core/src/main/resources/models/paraphrase-multilingual-MiniLM-L12-v2-q8.onnx";

    private static final String LLAMA_BASE_URL = "http://localhost:8200";
    private static final String LLAMA_HEALTH_URL = LLAMA_BASE_URL + "/v1/models";
    private static final String MODEL_ID = "wyrdsekai-3.5-9b-v5-q4km.gguf";
    private static final Duration BACKEND_TIMEOUT = Duration.ofMinutes(5);

    private Path repoRoot;
    private CodingBackendDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        assumeThat(canRunGoose())
            .as("goose --version must succeed").isTrue();
        assumeThat(canReachLlamaServer())
            .as("local llama-server :8200 must respond at /v1/models").isTrue();

        repoRoot = findRepoRoot();
        assumeTrue(repoRoot != null,
            "repo root with scripts/classifier/train_classifier.py not found");
        assumeTrue(Files.exists(repoRoot.resolve(CORPUS_REL)),
            "task_present corpus missing: " + CORPUS_REL);
        assumeTrue(Files.exists(repoRoot.resolve(EMBEDDING_REL)),
            "embedding ONNX missing: " + EMBEDDING_REL);
        assumeTrue(pythonMlStackPresent(repoRoot.toFile()),
            "python ML stack (sklearn/onnxruntime/skl2onnx/transformers) not importable");

        var config = new GooseRuntimeConfig(
            true, "goose", "openai", MODEL_ID,
            LLAMA_BASE_URL, Duration.ofMinutes(8), List.of());
        AuthResolver auth = name -> new AuthMode.ApiKey("not-required");
        var backend = new GooseBackend(config, auth);
        dispatcher = new CodingBackendDispatcher(
            backend, "did:wyrd:e2e-recipe-lifecycle", BACKEND_TIMEOUT);
    }

    @Test
    void happy_path_train_gates_deploy_with_real_backend_smoke(@TempDir Path tmp) throws Exception {
        Path corpus = repoRoot.resolve(CORPUS_REL);
        Path prodOnnx = repoRoot.resolve(PROD_ONNX_REL);
        String prodHashBefore = sha256(prodOnnx);

        Path trainOut = Files.createDirectories(tmp.resolve("train-out"));
        Path fakeProd = Files.createDirectories(tmp.resolve("prod"));
        Path fakeProdOnnx = fakeProd.resolve(HEAD + ".onnx");
        byte[] sentinel = "OLD-MODEL-SENTINEL-bytes-v0\n".getBytes();
        Files.write(fakeProdOnnx, sentinel);

        // Goose's smoke step: verify the deployed file exists by writing
        // its size into a probe file. success_contract: that probe file exists.
        Path smokeProbe = tmp.resolve("smoke-probe.txt");
        var manifest = buildManifest(corpus, trainOut, fakeProd, fakeProdOnnx, smokeProbe, /*forceFail=*/false);

        var runner = new RecipeRunner(
            new ProcessCommandRunner(repoRoot.toFile(), Duration.ofMinutes(5)),
            dispatcher);
        System.out.println("=== Happy-path: full lifecycle with real BACKEND smoke ===");
        var run = runner.run(manifest, Map.of());
        printRun(run);

        assertThat(run.status())
            .as("happy path must SUCCEED end-to-end (msg=%s)", run.message())
            .isEqualTo(RecipeRunner.Status.SUCCESS);

        // Every governed step fired and passed
        assertOk(run, "check-corpus");
        assertOk(run, "gate-corpus");
        assertOk(run, "train");
        assertOk(run, "gate-accuracy");
        assertOk(run, "regression-probe");      // ← OPEN-R5 real probe
        assertOk(run, "gate-regression");
        assertOk(run, "deploy");
        assertBackendOk(run, "smoke");          // ← the new live-goose seam

        // OPEN-R5: probe_overrouting.py emitted real per-lang stats —
        // mergeJsonStdout pulled them into RecipeContext. The probe's
        // contract: overrouting_probe_passes == true, plus anchors_tested,
        // misclassified, max_misses, and per_lang map landed.
        assertThat(run.context().get("overrouting_probe_passes"))
            .as("overrouting_probe_passes must land in context as TRUE")
            .isEqualTo(Boolean.TRUE);
        assertThat(((Number) run.context().get("anchors_tested")).intValue())
            .as("anchors_tested should be 90 (30 EN + 30 ES + 30 JA)")
            .isEqualTo(90);
        assertThat(run.context().get("max_misses"))
            .as("max_misses param must round-trip into context")
            .isNotNull();
        // per_lang is a JSON object; mergeJsonStdout currently flattens it
        // as a stringified value (it's not a number/bool). Just confirm
        // it's present and non-empty.
        assertThat(run.context().get("per_lang"))
            .as("per_lang summary must land in context for diagnostic visibility")
            .isNotNull();

        // The deployed file is the NEW training output (not the sentinel)
        assertThat(Files.exists(fakeProdOnnx)).isTrue();
        assertThat(sha256(fakeProdOnnx))
            .as("deploy replaced sentinel with the freshly trained ONNX")
            .isNotEqualTo(sha256Bytes(sentinel));

        // Goose's smoke verification produced its probe file
        assertThat(Files.exists(smokeProbe))
            .as("real-goose smoke step must have written the probe file at %s",
                smokeProbe).isTrue();

        // Production ONNX untouched
        assertThat(sha256(prodOnnx))
            .as("production ONNX must NOT be modified by the test")
            .isEqualTo(prodHashBefore);
    }

    @Test
    void rollback_path_backend_smoke_fails_triggers_reversibility(@TempDir Path tmp) throws Exception {
        Path corpus = repoRoot.resolve(CORPUS_REL);
        Path prodOnnx = repoRoot.resolve(PROD_ONNX_REL);
        String prodHashBefore = sha256(prodOnnx);

        Path trainOut = Files.createDirectories(tmp.resolve("train-out"));
        Path fakeProd = Files.createDirectories(tmp.resolve("prod"));
        Path fakeProdOnnx = fakeProd.resolve(HEAD + ".onnx");
        byte[] sentinel = "OLD-MODEL-SENTINEL-bytes-v0\n".getBytes();
        Files.write(fakeProdOnnx, sentinel);

        // forceFail=true → ask goose to write to /does/not/exist/... so the
        // success_contract "file:... exists" fails, triggering rollback.
        Path smokeProbe = Path.of("/does/not/exist/" + UUID.randomUUID() + ".txt");
        var manifest = buildManifest(corpus, trainOut, fakeProd, fakeProdOnnx, smokeProbe, /*forceFail=*/true);

        var runner = new RecipeRunner(
            new ProcessCommandRunner(repoRoot.toFile(), Duration.ofMinutes(5)),
            dispatcher);
        System.out.println("=== Rollback-path: real BACKEND smoke forced to fail ===");
        var run = runner.run(manifest, Map.of());
        printRun(run);

        assertThat(run.status())
            .as("smoke failure must halt the run and fire rollback (msg=%s)",
                run.message())
            .isEqualTo(RecipeRunner.Status.STEP_FAILED);
        assertOk(run, "deploy");
        assertBackendFailed(run, "smoke");      // ← real-goose smoke step failed contract
        assertOk(run, "rollback");               // ← compensation actually ran

        // After rollback the fake-prod file is the ORIGINAL sentinel bytes
        assertThat(Files.exists(fakeProdOnnx))
            .as("deploy target must still exist after rollback").isTrue();
        assertThat(sha256(fakeProdOnnx))
            .as("rollback must restore sentinel byte-for-byte (reversibility)")
            .isEqualTo(sha256Bytes(sentinel));
        assertThat(Files.exists(fakeProd.resolve(HEAD + ".onnx.bak")))
            .as("rollback should clean up its backup").isFalse();

        // Production ONNX untouched
        assertThat(sha256(prodOnnx))
            .as("production ONNX must NOT be modified by the test")
            .isEqualTo(prodHashBefore);
    }

    // -- manifest construction -------------------------------------------------

    /**
     * Build a manifest mirroring retrain-classifier-head's lifecycle but with
     * a REAL BACKEND smoke step (not a simulated shell exit). When
     * {@code forceSmokeFailure} is true the smoke step's contract is set to
     * require an impossible file, so even if goose succeeds the contract
     * fails → triggers rollback.
     */
    private static RecipeManifest buildManifest(
            Path corpus, Path trainOut, Path fakeProd, Path fakeProdOnnx,
            Path smokeProbe, boolean forceSmokeFailure) {
        String onnxOut = trainOut.resolve(HEAD + ".onnx").toString();
        String labelsOut = trainOut.resolve(HEAD + ".labels.json").toString();
        String sidecar = trainOut.resolve(HEAD + ".val-accuracy.json").toString();
        String prodOnnx = fakeProdOnnx.toString();
        String prodBak = fakeProd.resolve(HEAD + ".onnx.bak").toString();

        String checkCorpus =
                "echo \"{\\\"expanded_lines\\\": $(wc -l < '" + corpus + "' | tr -d ' ')}\"";

        String train =
                "export HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1; "
                + "python3 scripts/classifier/train_classifier.py "
                + "--corpus '" + corpus + "' "
                + "--output '" + onnxOut + "' "
                + "--labels-output '" + labelsOut + "' "
                + "--classifier lr 1>&2 "
                + "&& SIDE='" + sidecar + "' python3 -c "
                + "'import json,os; d=json.load(open(os.environ[\"SIDE\"])); "
                + "print(json.dumps({\"val_accuracy\": d[\"accuracy\"]}))'";

        // OPEN-R5: real overrouting probe (multilingual, 90 anchors). The
        // train step above no longer fakes `overrouting_probe_passes` — this
        // step runs probe_overrouting.py against the freshly-trained ONNX
        // and emits the canonical JSON `{"overrouting_probe_passes": …,
        // "anchors_tested": …, "misclassified": …, "per_lang": {…}}` for
        // gate-regression to read. Production thresholds.
        String regressionProbe =
                "python3 scripts/classifier/probe_overrouting.py "
                + "--head " + HEAD + " "
                + "--classifier '" + onnxOut + "' "
                + "--labels '" + labelsOut + "' "
                + "--max-misses 6 --max-misses-per-lang 3";

        String deploy =
                "cp -f '" + prodOnnx + "' '" + prodBak + "' && cp -f '" + onnxOut + "' '" + prodOnnx + "'";
        String rollback =
                "cp -f '" + prodBak + "' '" + prodOnnx + "' && rm -f '" + prodBak + "'";

        // Real BACKEND smoke step. Asks goose to verify the deployed ONNX
        // exists and has non-zero size, writing the size into smokeProbe as
        // a JSON {"deployed_size": N}. Goose uses its shell tool to do this.
        //
        // Happy path: smokeProbe is a writable temp path → goose writes → contract holds.
        // Rollback path: smokeProbe is /does/not/exist/... → goose tries to write
        // and either errors OR writes successfully somewhere else; either way
        // the success_contract "file:<smokeProbe> exists" can't hold → STEP_FAILED.
        String smokePrompt =
                "Use the shell tool to verify the deployed model file exists. "
                + "Run this exact command: "
                + "ls -la '" + prodOnnx + "' > '" + smokeProbe + "' 2>&1 ; "
                + "echo done"
                + "\nThen stop. No other commands.";
        var smokeContract = "file:" + smokeProbe + " exists";

        return new RecipeManifest(
                "retrain-classifier-head-live-e2e",
                "0.1.0",
                "Tier3 live E2E: real train + gates + reversible deploy + REAL goose smoke",
                Map.of("min_accuracy", new RecipeManifest.RecipeParam("number", false, 0.80)),
                RecipeManifest.Ownership.RUN,
                true,
                List.of(
                        new RecipeStep.Shell("check-corpus", checkCorpus),
                        // Gate uses the build-bake minimum (150), not the prod runtime
                        // minimum (800). Reason: the test reads the local-fanned
                        // expanded.jsonl (post-2026-05-24 local-first invariant) which
                        // for task_present is ~372 lines. The production scheduler
                        // calls expand_corpus.py first to re-fan up to ~800 lines; this
                        // test skips that step and just exercises the train→gate→deploy
                        // chain against the corpus that's already on disk. The 150 floor
                        // mirrors what RecipeBakeMain passes for release-time bakes.
                        new RecipeStep.Gate("gate-corpus", "expanded_lines >= 150",
                                RecipeStep.Gate.STOP, RecipeStep.WelfareClass.PERMANENT),
                        new RecipeStep.Shell("train", train),
                        new RecipeStep.Gate("gate-accuracy", "val_accuracy >= {{min_accuracy}}",
                                RecipeStep.Gate.STOP, RecipeStep.WelfareClass.PERMANENT),
                        new RecipeStep.Shell("regression-probe", regressionProbe),
                        new RecipeStep.Gate("gate-regression", "overrouting_probe_passes == true",
                                RecipeStep.Gate.STOP, RecipeStep.WelfareClass.PERMANENT),
                        new RecipeStep.Shell("deploy", deploy, rollback),
                        new RecipeStep.Backend("smoke", smokePrompt, List.of("shell"), smokeContract)
                ));
    }

    // -- helpers ---------------------------------------------------------------

    private static void printRun(RecipeRunner.RecipeRun run) {
        System.out.println("status=" + run.status() + " :: " + run.message());
        for (var o : run.outcomes()) {
            System.out.printf("  %-18s %-8s %s -> %s%n",
                o.id(), o.kind(), o.ok() ? "OK" : "FAIL", o.detail());
        }
        System.out.println("ctx:");
        run.context().snapshot().forEach((k, v) -> {
            String vs = v == null ? "null" : v.toString();
            if (vs.length() > 300) vs = vs.substring(0, 300) + "…";
            System.out.println("  " + k + " = " + vs);
        });
    }

    private static void assertOk(RecipeRunner.RecipeRun run, String stepId) {
        var o = outcome(run, stepId);
        assertThat(o)
            .as("expected outcome for step '%s'; outcomes=%s", stepId, run.outcomes())
            .isNotNull();
        assertThat(o.ok())
            .as("step '%s' should be ok :: %s", stepId, o.detail()).isTrue();
    }

    private static void assertBackendOk(RecipeRunner.RecipeRun run, String stepId) {
        var o = outcome(run, stepId);
        assertThat(o).isNotNull();
        assertThat(o.kind()).isEqualTo(StepKind.BACKEND);
        assertThat(o.ok())
            .as("BACKEND step '%s' should be ok :: %s", stepId, o.detail()).isTrue();
    }

    private static void assertBackendFailed(RecipeRunner.RecipeRun run, String stepId) {
        var o = outcome(run, stepId);
        assertThat(o).isNotNull();
        assertThat(o.kind()).isEqualTo(StepKind.BACKEND);
        assertThat(o.ok())
            .as("BACKEND step '%s' was expected to fail (rollback trigger)", stepId)
            .isFalse();
    }

    private static RecipeRunner.StepOutcome outcome(RecipeRunner.RecipeRun run, String stepId) {
        return run.outcomes().stream()
            .filter(o -> o.id().equals(stepId)).findFirst().orElse(null);
    }

    private static Path findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "scripts/classifier/train_classifier.py").isFile()) {
                return dir.toPath();
            }
        }
        return null;
    }

    private static boolean pythonMlStackPresent(File repoRoot) {
        var result = new ProcessCommandRunner(repoRoot, Duration.ofSeconds(60)).run(
            "python3 -c 'import sklearn, onnxruntime, skl2onnx, transformers, numpy'");
        return result.exitCode() == 0;
    }

    private static String sha256(Path p) throws Exception {
        return sha256Bytes(Files.readAllBytes(p));
    }

    private static String sha256Bytes(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean canRunGoose() {
        try {
            var proc = new ProcessBuilder("goose", "--version")
                .redirectErrorStream(true).start();
            var ok = proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0;
            if (!ok) proc.destroyForcibly();
            return ok;
        } catch (Exception e) { return false; }
    }

    private static boolean canReachLlamaServer() {
        try {
            var resp = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build()
                .send(HttpRequest.newBuilder(URI.create(LLAMA_HEALTH_URL))
                    .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() < 500;
        } catch (Exception e) { return false; }
    }
}
