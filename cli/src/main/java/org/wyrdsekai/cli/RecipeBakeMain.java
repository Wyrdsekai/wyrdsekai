package org.wyrdsekai.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.ConfigFactory;
import org.wyrdsekai.core.coding.AuthMode;
import org.wyrdsekai.core.coding.AuthResolver;
import org.wyrdsekai.core.coding.CodeZaikuBackend;
import org.wyrdsekai.core.coding.CodeZaikuRuntimeConfig;
import org.wyrdsekai.core.coding.CodingBackendBootstrap;
import org.wyrdsekai.core.coding.CodingTaskBackend;
import org.wyrdsekai.core.coding.GooseBackend;
import org.wyrdsekai.core.coding.GooseRuntimeConfig;
import org.wyrdsekai.core.recipe.CodingBackendDispatcher;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.RecipeForgeIngester;
import org.wyrdsekai.core.recipe.RecipeRunLog;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.soul.SoulFragment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * Track-B B1 — release-time evolution bake.
 *
 * <p>Runs the {@code retrain-classifier-head} recipe for one classifier
 * head against the <em>real</em> production code path
 * ({@link RecipeService} + {@link CodingBackendDispatcher} +
 * {@code GooseBackend}) and writes two pieces of evidence the release
 * bundle ships:</p>
 *
 * <ol>
 *   <li>{@code data/release-evidence/&lt;head&gt;-recipe-run-&lt;ts&gt;.json}
 *       — full run log with every step's outcome, durations, and the
 *       baseline-vs-evolved {@code .onnx} sha256s. Provenance: this is
 *       what proves the loop closed at build.</li>
 *   <li>{@code data/release-evidence/&lt;head&gt;-soul-fragment-seed.json}
 *       — the DEXTERITY {@link SoulFragment} {@link
 *       RecipeForgeIngester} would produce, attributed to
 *       {@code did:wyrd:release-bake}. Ingested at first boot so the
 *       bondholder sees the build-time learning from day 1.</li>
 * </ol>
 *
 * <p>On any failure (gate trip, deploy failure, smoke failure) we exit
 * non-zero and write a {@code -failed.json} variant of the run log — no
 * evolved {@code .onnx} commit, no soul-fragment-seed. The deploy step
 * inside the recipe already rolls back via {@code .bak}, so a failed
 * bake leaves the tree as it was.</p>
 *
 * <h2>Why this code path</h2>
 * <p>The same {@code RecipeService.run} that
 * {@code CompanionActor.handleRequestRecipeDirect} calls in production
 * (and that {@code RecipeAgentForgeE2ETest} proved end-to-end on home-server).
 * No stubs, no test harness. The point of B1 is that the artifact ships
 * with cryptographic evidence the agent's own ML pipeline ran clean —
 * "we evolved this classifier and you can verify the run."</p>
 *
 * <h3>Exit codes</h3>
 * <ul>
 *   <li>0 — recipe ran SUCCESS, evidence written, sha256 differs</li>
 *   <li>1 — recipe ran but did not deploy (gate trip, etc.); failure
 *       evidence written</li>
 *   <li>2 — usage / missing flags / setup error</li>
 *   <li>3 — recipe threw or returned ERROR; failure evidence written</li>
 * </ul>
 */
public final class RecipeBakeMain {

    /**
     * Binary routing heads that BENEFIT from LogisticRegression(class_weight=
     * "balanced") — validated in the runtime ClassifierArm path. Everything
     * else keeps MLP (the classifier param default). See the classifier param
     * note in RetrainClassifierHead.
     *
     * <p>substrate_present is deliberately NOT here: its shipped baseline is
     * already 0/90 in the runtime path and BOTH LR (6/90) and balanced-MLP
     * (12/90) retrains regress it (2026-07-21), so it is excluded from the
     * default bake set entirely (see build-evolved-artifact.sh). request_type
     * stays MLP (8-way non-linear split) but also awaits its label-split
     * before it can clear the accuracy gate.
     */
    private static final Set<String> BINARY_HEADS = Set.of(
        "task_present", "cleanliness");

    // (2026-07-22) The ±noise margins that padded the offline gate are gone:
    // the deciding probe now runs in the deterministic runtime ClassifierArm
    // space (same artifact → same counts), and the deploy rule is strict
    // improvement — see the over-routing gate block in runHead.
    /** First-ever bake for a head (no baseline to compare against). */
    private static final int FIRST_BAKE_MAX_MISSES = 24;          // ~27% of 90 anchors
    private static final int FIRST_BAKE_MAX_MISSES_PER_LANG = 10; // ~33% of 30 per-lang

    /**
     * Target number of GENERATED corpus rows for the build-time expand-corpus
     * step, used to derive {@code variants_per_seed} per head. The expand step
     * runs through the goose BACKEND, which has a ~15-minute per-attempt ceiling;
     * a fixed variants_per_seed=4 silently blew past it for large-seed heads
     * (request_type has 557 seeds → 2228 rows → timeout, 2026-07-22). Deriving
     * variants from a fixed row budget keeps EVERY head inside the window
     * regardless of seed count: variants = clamp(BUDGET / seedCount, 1, 4).
     * 900 is chosen so the already-validated ~200-seed heads (task_present 212,
     * cleanliness 168) stay at exactly 4 — no change to their baked corpus —
     * while request_type (557) drops to 1 (~557 rows, comfortably under the
     * ceiling). The upper clamp of 4 preserves the historical bake budget.
     */
    private static final int EXPAND_ROW_BUDGET = 900;

    /**
     * Synthetic DID the seed fragment is attributed to. At first boot
     * the steward's companion picks this up from {@code release-evidence/}
     * and ingests it as if it had run the recipes itself. The DID is
     * stable across releases so the agent can recognise "this came from
     * the bake," not from its own session.
     */
    public static final String RELEASE_BAKE_DID = "did:wyrd:release-bake";

    public static void main(String[] args) throws Exception {
        String head = null;
        Path projectDir = Path.of(System.getProperty("user.dir"));
        Double minAccuracy = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--head":
                    if (i + 1 >= args.length) usage("--head requires a value");
                    head = args[++i];
                    break;
                case "--project-dir":
                    if (i + 1 >= args.length) usage("--project-dir requires a value");
                    projectDir = Path.of(args[++i]).toAbsolutePath();
                    break;
                case "--min-accuracy":
                    if (i + 1 >= args.length) usage("--min-accuracy requires a value");
                    minAccuracy = Double.parseDouble(args[++i]);
                    break;
                case "--help": case "-h":
                    printUsage();
                    System.exit(0);
                    return;
                default:
                    usage("unexpected argument: " + args[i]);
            }
        }
        if (head == null) usage("--head <name> is required");

        var pretrainedDir = projectDir.resolve(
            "core/src/main/resources/classifier/pretrained");
        var onnxPath = pretrainedDir.resolve(head + ".onnx");
        var evidenceDir = projectDir.resolve("data/release-evidence");
        Files.createDirectories(evidenceDir);

        // (1) Snapshot baseline sha256 + write a versioned copy. The .bak
        // file the recipe's deploy step creates is also a baseline, but
        // we copy here so the evidence dir owns provenance independently.
        String baselineSha;
        Path baselineCopy = evidenceDir.resolve(
            head + "-baseline-" + utcTs() + ".onnx");
        if (Files.exists(onnxPath)) {
            baselineSha = sha256Hex(onnxPath);
            Files.copy(onnxPath, baselineCopy, StandardCopyOption.REPLACE_EXISTING);
            log("baseline " + head + ".onnx sha256=" + baselineSha);
        } else {
            // First-ever bake for this head — no baseline. Skip the copy
            // but record absence in evidence.
            baselineSha = "<missing>";
            log("baseline " + head + ".onnx ABSENT — first bake");
        }

        // (2) Wire production RecipeService + CodingBackendDispatcher.
        // CodingBackendDispatcher.usingPreferred picks goose first
        // (CodingFamiliarConfig steward default) then falls back to pi.
        // Time limit 30min — full retrain on CPU is slow but bounded.
        //
        // Standalone process — bootstrap the backend registry ourselves.
        // (Server boot does this via Main.java; tests via TestServerBootstrap.)
        //
        // Config resolution mirrors the server: layer the user's
        // ~/.wyrdsekai/wyrdsekai.conf (or $WYRDSEKAI_CONF, or
        // /etc/wyrdsekai/wyrdsekai.conf) over the classpath defaults so
        // backends gated by `wyrdsekai.coding.backends.<name>.enabled`
        // turn on for the bake the same way they turn on at runtime.
        // Load HOCON config (classpath defaults + user overlay) so the
        // GooseRuntimeConfig pulls executable_path / provider / model /
        // base-url from `wyrdsekai.coding.backends.goose.*` the same way
        // the server does.
        var base = ConfigFactory.load();
        var userConf = resolveUserConfPath();
        if (userConf != null && Files.isRegularFile(userConf)) {
            base = ConfigFactory.parseFile(userConf.toFile()).withFallback(base);
            log("loaded config overlay: " + userConf);
        }

        var procRunner = new ProcessCommandRunner(
            projectDir.toFile(), Duration.ofMinutes(30));

        // Direct backend construction — mirrors the production-verified
        // shape of A2 (RetrainClassifierHeadLiveE2ETest from 2026-05-24).
        // Why direct instead of CodingBackendBootstrap? The bundle
        // manifest's entries lack a `key_chest_slot`, so
        // DefaultAuthResolver returns AuthMissing → the backend rejects
        // every task in <5ms with LOGIN_REQUIRED. The bake targets the
        // bundled local llama-server, so no key ever crosses the
        // household boundary — a {@code "not-required"} sentinel is
        // correct AND safe.
        //
        // CODEZAIKU FIRST, GOOSE FALLBACK (2026-08-24). Goose held this
        // slot alone since 2026-05-24 because it was the only backend
        // live-validated against the recipe. CodeZaiku 01de82d2 has since
        // been proven across the full staging battery (artifact mode, one
        // file per build, honest exit codes, in-place repairs) and their
        // own measurements halve dispatch and repair counts vs goose —
        // and the bake's steps are exactly that shape of work. Goose
        // stays as the fallback; a box with neither still aborts loudly.
        CodingTaskBackend backend = null;
        var cpConfig = CodeZaikuRuntimeConfig.fromConfig(base);
        if (cpConfig.enabled()
                && Files.isExecutable(Path.of(cpConfig.executablePath()))) {
            backend = new CodeZaikuBackend(cpConfig, null);
            log("codezaiku backend wired direct: exe=" + cpConfig.executablePath()
                + " drive=" + cpConfig.effectiveDriveUrl()
                + " model=" + cpConfig.effectiveModel());
        } else {
            log("codezaiku not available (enabled=" + cpConfig.enabled()
                + ", exe=" + cpConfig.executablePath() + ") — trying goose");
        }
        if (backend == null) {
            var gooseConfig = GooseRuntimeConfig.fromConfig(base);
            if (!gooseConfig.enabled()) {
                log("ERROR: no coding backend for the bake — codezaiku binary not "
                    + "found and goose disabled in config "
                    + "(wyrdsekai.coding.backends.goose.enabled). "
                    + "Set in ~/.wyrdsekai/wyrdsekai.conf or $WYRDSEKAI_CONF.");
                System.exit(2);
            }
            if (!Files.isExecutable(Path.of(gooseConfig.executablePath()))) {
                log("ERROR: no coding backend for the bake — codezaiku binary not "
                    + "found and goose binary not found / not executable at "
                    + gooseConfig.executablePath()
                    + " (configure via wyrdsekai.coding.backends.goose.executable_path, "
                    + "or install codezaiku under $WYRDSEKAI_DATA_DIR/coding-cli-bundle/).");
                System.exit(2);
            }
            AuthResolver sentinel = name -> new AuthMode.ApiKey("not-required");
            backend = new GooseBackend(gooseConfig, sentinel);
            log("goose backend wired direct: exe=" + gooseConfig.executablePath()
                + " provider=" + gooseConfig.provider()
                + " model=" + gooseConfig.model()
                + " base_url=" + gooseConfig.baseUrl());
        }
        // The recipe's BACKEND steps name project-relative paths, so the
        // backend must run in the project — same root the SHELL steps use.
        // The per-task scratch default left the backend in an empty sandbox
        // where the 9B fabricated the script it was told to run (2026-08-24).
        var dispatcher = new CodingBackendDispatcher(
            backend, RELEASE_BAKE_DID, Duration.ofMinutes(30),
            projectDir.toAbsolutePath().toString());
        var runner = new RecipeRunner(procRunner, dispatcher);
        var recipesDir = projectDir.resolve("recipes"); // may not exist
        var scriptsRoot = projectDir.resolve("scripts");
        Path scriptsArg = Files.isDirectory(scriptsRoot) ? scriptsRoot : null;
        var service = new RecipeService(
            Files.isDirectory(recipesDir) ? recipesDir : null,
            runner, RELEASE_BAKE_DID, scriptsArg);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("head", head);
        params.put("pretrained_dir",
            "core/src/main/resources/classifier/pretrained");
        if (minAccuracy != null) params.put("min_accuracy", minAccuracy);
        // Release-bake defaults (local-first, ~15min bake budget). The
        // recipe's production defaults (variants_per_seed=20,
        // min_corpus_lines=800, min_accuracy=0.80) target a
        // CompanionActor's deeper sleep-pass evolution, not the
        // build-time bake's one-shot pipeline-closure floor.
        // variants_per_seed is derived from a fixed generated-row budget so the
        // goose expand step stays inside its ~15-min ceiling for ANY head size
        // (see EXPAND_ROW_BUDGET). Large-seed heads (request_type=557) get
        // fewer variants; the validated ~200-seed heads stay at 4.
        int seedCount = countSeeds(projectDir, head);
        int variants = seedCount > 0
            ? Math.max(1, Math.min(4, EXPAND_ROW_BUDGET / seedCount))
            : 4;
        log("variants_per_seed=" + variants + " (head=" + head + " seeds=" + seedCount
            + " budget=" + EXPAND_ROW_BUDGET + ")");
        params.putIfAbsent("variants_per_seed", variants);
        // Bake threshold tuned 2026-05-25: live run on home-server with V5 9B
        // produced ~120 lines (47 seeds + ~75 deduped variants) at
        // variants_per_seed=4 — sklearn LR over that is enough to make
        // the round-trip honest. Production CompanionActor runtime uses
        // the recipe's 800-line default for its sleep-pass evolution.
        params.putIfAbsent("min_corpus_lines", 100);
        // val_accuracy on the bake's small corpus is noisier than
        // production: 0.60 stays well above random (~0.15 for 6 labels)
        // while accepting the smaller train/val split. The recipe's
        // 0.80 default is the production gate for CompanionActor's
        // larger-corpus runtime evolution.
        if (minAccuracy == null) params.putIfAbsent("min_accuracy", 0.60);
        // Over-routing probe (OPEN-R5, multilingual 2026-05-25): bake's
        // smaller corpus produces a classifier that misclassifies more
        // anchors than production. Anchor set is now 90 (30 EN/ES/JA);
        // bake passes 30/90 overall + 12/30 per-lang to stay unblocked
        // while still catching catastrophic regressions (e.g. one
        // language fully flipped). Production runs use the recipe
        // defaults of 6/90 overall + 3/30 per-lang.
        // Over-routing gate = STRICT IMPROVEMENT in RUNTIME SPACE (2026-07-22;
        // supersedes the 07-21 offline baseline-relative gate). Both sides —
        // baseline (in-process here) and candidate (recipe's regression-probe
        // step) — are measured through the SAME ClassifierArm path production
        // runs. The runtime probe is deterministic (same artifact → same
        // counts), so the old ±noise margins are gone and the deploy rule is
        // honest evolution semantics:
        //   deploy  ⇔  candidate misses < baseline misses overall   (strict gain)
        //          AND candidate per-language <= baseline per-language (no
        //              language pays for another's improvement — ja stays ja)
        //   tie/lateral re-roll → kept_baseline. A baseline at 0 misses can
        //   never be beaten (max = -1) — correct: you don't re-roll perfection,
        //   you feed it new experience.
        BaselineProbe baseMisses = probeBaselineMisses(head, onnxPath,
            pretrainedDir.resolve(head + ".labels.json"));
        if (baseMisses != null) {
            var langMap = new StringBuilder();
            baseMisses.perLang().forEach((lang, m) -> {
                if (langMap.length() > 0) langMap.append(",");
                langMap.append(lang).append(":").append(m);
            });
            params.putIfAbsent("max_overrouting_misses", baseMisses.overall() - 1);
            params.putIfAbsent("max_overrouting_misses_per_lang_map", langMap.toString());
            log("over-routing gate (runtime space): strict improvement — baseline overall="
                + baseMisses.overall() + " per_lang=" + baseMisses.perLang()
                + " → deploy only if evolved <= " + (baseMisses.overall() - 1)
                + " overall AND per-lang <= [" + langMap + "]");
        } else {
            params.putIfAbsent("max_overrouting_misses", FIRST_BAKE_MAX_MISSES);
            params.putIfAbsent("max_overrouting_misses_per_lang", FIRST_BAKE_MAX_MISSES_PER_LANG);
            log("over-routing gate: no baseline — first-bake absolute floor "
                + FIRST_BAKE_MAX_MISSES + "/" + FIRST_BAKE_MAX_MISSES_PER_LANG);
        }
        // Classifier per head (2026-07-21). Binary routing heads train a
        // LogisticRegression(class_weight="balanced") — controlled A/B showed
        // MLP(128), which sklearn cannot class-weight, over-fires on the
        // imbalanced expanded corpus and loses to LR on every binary head's
        // over-routing probe. The multi-class request_type head keeps MLP
        // for the non-linear separation an 8-way split needs.
        params.putIfAbsent("classifier",
            BINARY_HEADS.contains(head) ? "lr" : "mlp");
        // FREEZE the encoder for the bake (2026-07-21). Empty setfit_encoder_path
        // makes setfit-pretrain a no-op and train/probe/deploy all use the ONE
        // COMMITTED encoder — the space the deployed head actually runs in. The
        // old per-head fresh encoder was gated-but-never-deployed (one shared
        // encoder path), so the bake gated a head in a space it never ran in.
        params.putIfAbsent("setfit_encoder_path", "");

        log("bake start: head=" + head);
        long t0 = System.currentTimeMillis();
        RecipeService.StartedRun started;
        try {
            started = service.run("retrain-classifier-head", params);
        } catch (RuntimeException ex) {
            writeFailureEvidence(evidenceDir, head, baselineSha, baselineCopy,
                null, ex.toString(), t0);
            log("ERROR: recipe threw: " + ex);
            System.exit(3);
            return;
        }
        long durationMs = System.currentTimeMillis() - t0;
        var status = started.run().status();
        log("bake done: status=" + status + " duration=" + (durationMs / 1000) + "s "
            + "message=" + started.run().message());

        if (status != RecipeRunner.Status.SUCCESS) {
            // THREE-OUTCOME SEMANTICS (2026-07-22). A quality-gate rejection is
            // not a failure of the loop — it IS the loop: the evolution ran end
            // to end, produced a candidate, measured it honestly, and found it
            // does not beat the proven head already shipping. On a fixed seed
            // corpus with an already-evolved baseline (07-21) that is the
            // EXPECTED steady-state outcome; improvement requires new
            // experience data, not a re-roll. Distinguish:
            //   evolved        — candidate beat the gates, deployed (below)
            //   kept_baseline  — loop ran honestly, candidate rejected by a
            //                    QUALITY gate (gate-accuracy/gate-regression),
            //                    proven head kept. Valid release outcome,
            //                    honest evidence + fragment, exit 0.
            //   failure        — infrastructure broke (expand timeout, train
            //                    crash, missing seeds, too-small corpus).
            //                    Release aborts: the loop could not run.
            String failedStep = null;
            for (var o : started.run().outcomes()) {
                if (!o.ok()) { failedStep = o.id(); break; }
            }
            boolean qualityRejection = status == RecipeRunner.Status.GATE_FAILED
                && ("gate-accuracy".equals(failedStep)
                    || "gate-regression".equals(failedStep));
            // Quality gates run BEFORE the deploy step, so on a genuine
            // rejection the shipped head must be untouched — verify, don't
            // assume. A mutated head here means something else went wrong.
            if (qualityRejection && sha256Hex(onnxPath).equals(baselineSha)) {
                log("kept baseline: candidate rejected by " + failedStep
                    + " — proven head retained (loop closed honestly)");
                writeRunLogEvidence(evidenceDir, head, baselineSha,
                    /* evolvedSha = unchanged */ baselineSha, baselineCopy,
                    started, durationMs, /* success */ true,
                    "kept_baseline");
                writeKeptBaselineFragmentEvidence(evidenceDir, head,
                    baselineSha, failedStep, durationMs);
                log("evidence written to " + evidenceDir);
                System.exit(0);
            }
            writeFailureEvidence(evidenceDir, head, baselineSha, baselineCopy,
                started, "non-success: " + status, t0);
            System.exit(1);
        }

        // (3) Evolved file should exist after the recipe's deploy step.
        if (!Files.exists(onnxPath)) {
            writeFailureEvidence(evidenceDir, head, baselineSha, baselineCopy,
                started, "deploy step left no " + onnxPath, t0);
            log("ERROR: evolved .onnx missing after success");
            System.exit(3);
        }
        String evolvedSha = sha256Hex(onnxPath);
        if (evolvedSha.equals(baselineSha)) {
            log("WARN: evolved sha256 matches baseline — "
                + "deploy step may have been a no-op");
        } else {
            log("evolved " + head + ".onnx sha256=" + evolvedSha + " (differs)");
        }

        // (4) Write evidence — run log + seed fragment.
        writeRunLogEvidence(evidenceDir, head, baselineSha, evolvedSha,
            baselineCopy, started, durationMs, /* success */ true, "evolved");
        writeSeedFragmentEvidence(evidenceDir, head, baselineSha, evolvedSha,
            started, durationMs);
        log("evidence written to " + evidenceDir);
        System.exit(0);
    }

    private static void writeRunLogEvidence(Path dir, String head,
            String baselineSha, String evolvedSha,
            Path baselineCopy, RecipeService.StartedRun started,
            long durationMs, boolean success, String outcome) throws IOException {
        var run = started.run();
        var outcomes = new ArrayList<Map<String, Object>>();
        for (var o : run.outcomes()) {
            outcomes.add(Map.of(
                "id", o.id() == null ? "" : o.id(),
                "kind", o.kind() == null ? "" : o.kind().name(),
                "ok", o.ok(),
                "message", o.detail() == null ? "" : o.detail()));
        }
        var doc = new LinkedHashMap<String, Object>();
        doc.put("schema", "wyrdsekai.release-evidence.recipe-run.v1");
        doc.put("head", head);
        doc.put("recipe", "retrain-classifier-head");
        doc.put("status", run.status().name());
        doc.put("message", run.message() == null ? "" : run.message());
        doc.put("success", success);
        // "evolved" = candidate beat the gates and was deployed;
        // "kept_baseline" = loop ran honestly, candidate rejected by a quality
        // gate, proven head retained. Both are valid release outcomes.
        doc.put("outcome", outcome);
        doc.put("baseline_sha256", baselineSha);
        doc.put("evolved_sha256", evolvedSha);
        doc.put("baseline_copy", baselineCopy == null
            ? null : baselineCopy.getFileName().toString());
        doc.put("duration_ms", durationMs);
        doc.put("runId", started.runId());
        doc.put("bake_did", RELEASE_BAKE_DID);
        doc.put("baked_at", Instant.now().toString());
        doc.put("outcomes", outcomes);
        Path out = dir.resolve(head + "-recipe-run-" + utcTs() + ".json");
        Files.writeString(out, new ObjectMapper()
            .writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        log("wrote " + out);
    }

    private static void writeSeedFragmentEvidence(Path dir, String head,
            String baselineSha, String evolvedSha,
            RecipeService.StartedRun started, long durationMs)
            throws IOException {
        // Build the same DEXTERITY fragment RecipeForgeIngester would
        // produce for a SUCCESS run — but stamped with RELEASE_BAKE_DID
        // so first-boot ingestion is attributable to the build.
        var batch = new RecipeForgeIngester.Batch(RELEASE_BAKE_DID,
            List.of(new RecipeForgeIngester.CompletedRun(
                "retrain-classifier-head", /* deploys */ true, started.run())));
        var result = RecipeForgeIngester.ingest(batch);
        if (result.newFragments().isEmpty()) {
            log("WARN: Forge produced no fragments — seed skipped");
            return;
        }
        SoulFragment frag = result.newFragments().get(0);
        var doc = new LinkedHashMap<String, Object>();
        doc.put("schema", "wyrdsekai.release-evidence.soul-fragment-seed.v1");
        doc.put("head", head);
        doc.put("recipe", "retrain-classifier-head");
        doc.put("bake_did", RELEASE_BAKE_DID);
        doc.put("baked_at", Instant.now().toString());
        doc.put("baseline_sha256", baselineSha);
        doc.put("evolved_sha256", evolvedSha);
        doc.put("duration_ms", durationMs);
        var fragmentDoc = new LinkedHashMap<String, Object>();
        fragmentDoc.put("id", frag.id());
        fragmentDoc.put("kind", frag.kind() == null ? "DEXTERITY" : frag.kind().name());
        fragmentDoc.put("category", frag.category());
        fragmentDoc.put("label", frag.label());
        fragmentDoc.put("text", frag.text());
        doc.put("fragment", fragmentDoc);
        Path out = dir.resolve(head + "-soul-fragment-seed.json");
        Files.writeString(out, new ObjectMapper()
            .writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        log("wrote " + out);
    }

    /**
     * Soul-fragment seed for the KEPT-BASELINE outcome. The companion's
     * first-boot memory of the release bake must be truthful in BOTH
     * directions: "I retrained and it was better, so I changed" (evolved) and
     * "I retrained, measured, and what I already am held — so I kept it"
     * (kept_baseline). The second is not a failure memory; it is the memory of
     * a working self-honesty loop.: procedure-as-memory.
     */
    private static void writeKeptBaselineFragmentEvidence(Path dir, String head,
            String baselineSha, String rejectingGate, long durationMs)
            throws IOException {
        var doc = new LinkedHashMap<String, Object>();
        doc.put("schema", "wyrdsekai.release-evidence.soul-fragment-seed.v1");
        doc.put("head", head);
        doc.put("recipe", "retrain-classifier-head");
        doc.put("bake_did", RELEASE_BAKE_DID);
        doc.put("baked_at", Instant.now().toString());
        doc.put("baseline_sha256", baselineSha);
        doc.put("evolved_sha256", baselineSha); // unchanged — that's the point
        doc.put("duration_ms", durationMs);
        var fragmentDoc = new LinkedHashMap<String, Object>();
        fragmentDoc.put("id", "recipe-forge-recipe-retrain-classifier-head-kept-"
            + UUID.randomUUID().toString().substring(0, 8));
        fragmentDoc.put("kind", "DEXTERITY");
        fragmentDoc.put("category", "procedure");
        fragmentDoc.put("label", "Recipe run: retrain-classifier-head (kept baseline)");
        fragmentDoc.put("text",
            "I ran the recipe `retrain-classifier-head` end to end for the "
            + head + " head. The retrained candidate did not surpass the head "
            + "I already carry — the " + rejectingGate + " check held it to my "
            + "current standard and it fell short, so I kept the proven one. "
            + "That is the loop working, not failing: I checked whether I "
            + "could see better with what I know now, and the honest answer "
            + "was that my current perception already holds. Improving further "
            + "will take new experience, not a re-roll. This is a procedure I "
            + "can run again.");
        doc.put("fragment", fragmentDoc);
        Path out = dir.resolve(head + "-soul-fragment-seed.json");
        Files.writeString(out, new ObjectMapper()
            .writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        log("wrote " + out);
    }

    private static void writeFailureEvidence(Path dir, String head,
            String baselineSha, Path baselineCopy,
            RecipeService.StartedRun started, String detail, long t0) {
        try {
            var doc = new LinkedHashMap<String, Object>();
            doc.put("schema", "wyrdsekai.release-evidence.recipe-run.v1");
            doc.put("head", head);
            doc.put("recipe", "retrain-classifier-head");
            doc.put("success", false);
            doc.put("detail", detail);
            doc.put("baseline_sha256", baselineSha);
            doc.put("baseline_copy", baselineCopy == null
                ? null : baselineCopy.getFileName().toString());
            doc.put("duration_ms", System.currentTimeMillis() - t0);
            doc.put("bake_did", RELEASE_BAKE_DID);
            doc.put("baked_at", Instant.now().toString());
            if (started != null) {
                doc.put("status", started.run().status().name());
                doc.put("runId", started.runId());
                var outcomes = new ArrayList<Map<String, Object>>();
                for (var o : started.run().outcomes()) {
                    outcomes.add(Map.of(
                        "id", o.id() == null ? "" : o.id(),
                        "kind", o.kind() == null ? "" : o.kind().name(),
                        "ok", o.ok(),
                        "message", o.detail() == null ? "" : o.detail()));
                }
                doc.put("outcomes", outcomes);
            }
            Path out = dir.resolve(head + "-recipe-run-" + utcTs() + "-failed.json");
            Files.writeString(out, new ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(doc));
            log("wrote failure evidence " + out);
        } catch (Exception ex) {
            log("WARN: failed to write failure evidence: " + ex);
        }
        // Drain RecipeRunLog so the leftover entry doesn't bleed into the
        // first-boot agent's sleep pass.
        try { RecipeRunLog.get().drain(RELEASE_BAKE_DID); } catch (Exception ignored) {}
    }

    /**
     * Count the non-blank seed lines for a head's bootstrap corpus. Used to
     * size {@code variants_per_seed} against a fixed row budget so the goose
     * expand step fits its timeout for any head. Returns 0 (caller falls back
     * to the historical default of 4) if the file is missing or unreadable.
     */
    private static int countSeeds(Path projectDir, String head) {
        var seeds = projectDir.resolve(
            "core/src/main/resources/classifier/bootstrap/" + head + "/seeds.jsonl");
        if (!Files.exists(seeds)) return 0;
        try (var lines = Files.lines(seeds)) {
            return (int) lines.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Baseline over-routing: overall miss count + per-language miss counts. */
    private record BaselineProbe(int overall, Map<String, Integer> perLang) {}

    /**
     * Probe the SHIPPED baseline head IN-PROCESS through the runtime
     * ClassifierArm path ({@link ProbeHeadMain#probe}) — the SAME space the
     * recipe's regression-probe step measures the candidate in (gate-runtime
     * parity, 2026-07-22; replaces the Python-subprocess probe whose
     * tokenizer diverges from production). Both sides of the baseline-relative
     * comparison now land in the space the companion actually runs in, and
     * the runtime probe is deterministic — same artifact, same counts — so
     * the strict-improvement thresholds need no noise margin.
     *
     * <p>Returns {@code null} when there is no baseline (first bake) or the
     * probe can't run (missing onnx/labels, encoder unavailable) — callers
     * fall back to a first-bake absolute floor.
     */
    private static BaselineProbe probeBaselineMisses(
            String head, Path onnxPath, Path labelsPath) {
        if (!Files.exists(onnxPath) || !Files.exists(labelsPath)) return null;
        try {
            var r = ProbeHeadMain.probe(head, onnxPath, labelsPath);
            // Include EVERY language present in the anchors — zero-miss
            // languages included — so the per-language non-regression cap
            // covers them too. A map built only from missed languages would
            // let a perfect language silently regress (its cap would simply
            // be absent from the gate).
            var perLang = new LinkedHashMap<String, Integer>();
            for (var lang : r.perLangTotal().keySet()) {
                perLang.put(lang, r.perLangMisses().getOrDefault(lang, 0));
            }
            return new BaselineProbe(r.misses(), perLang);
        } catch (Exception e) {
            log("baseline runtime probe failed (" + e.getMessage()
                + ") — falling back to first-bake floor");
            return null;
        }
    }

    private static String sha256Hex(Path p) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(p));
            byte[] d = md.digest();
            var sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String utcTs() {
        return Instant.now().toString().replace(":", "").replace("-", "")
            .replace(".", "").replace("T", "-").substring(0, 15);
    }

    private static void usage(String msg) {
        System.err.println("Error: " + msg);
        printUsage();
        System.exit(2);
    }

    private static void printUsage() {
        System.err.println("Usage: wyrd-bake --head <name> "
            + "[--project-dir <path>] [--min-accuracy <float>]");
        System.err.println();
        System.err.println("Runs retrain-classifier-head for one classifier "
            + "head through the real production code path and writes "
            + "release evidence + a soul-fragment seed into "
            + "data/release-evidence/.");
    }

    private static void log(String s) {
        System.err.println("[bake] " + s);
    }

    /** Same resolution order as bin/wyrd: $WYRDSEKAI_CONF → /etc → ~/.wyrdsekai. */
    private static Path resolveUserConfPath() {
        var env = System.getenv("WYRDSEKAI_CONF");
        if (env != null && !env.isBlank()) return Path.of(env);
        var system = Path.of("/etc/wyrdsekai/wyrdsekai.conf");
        if (Files.isRegularFile(system)) return system;
        var home = System.getProperty("user.home");
        if (home != null) {
            var p = Path.of(home, ".wyrdsekai", "wyrdsekai.conf");
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
