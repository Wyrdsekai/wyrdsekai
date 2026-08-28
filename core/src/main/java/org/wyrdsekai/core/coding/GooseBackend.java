package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Goose (aaif-goose/goose) implementation of {@link CodingTaskBackend}.
 *
 * <p>Spawns the {@code goose} binary as a subprocess in headless mode:
 * {@code goose run --text '<spec>' --output-format json --no-session -q
 * [--provider <p>]}. Goose is provider-pluggable
 * (anthropic / openai / google / ollama / databricks / …) and is
 * MCP-native upstream — Phase 2d uses the simpler subprocess shape; a
 * future phase may migrate to MCP transport polymorphism.</p>
 *
 * <p>Tier: {@link BackendTier#CLOUD_PAID} per SPEC §9.2 — even though
 * Goose can target a local provider, the safer cost-policy posture is
 * to gate it behind the same checks as the paid tiers. Households that
 * point Goose at a local llama-server can still use it; the gate only
 * kicks in when the policy script would refuse a paid backend.</p>
 *
 * <p><b>Auth (2026-05-04 reconciliation)</b>: API-key only (no OAuth path).
 * Upstream Goose reads provider keys directly from their conventional env
 * vars — {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY},
 * {@code GOOGLE_API_KEY} (or {@code GEMINI_API_KEY}) — keyed off the
 * configured provider. The pre-2026-05 wyrdsekai indirection
 * ({@code GOOSE_PROVIDER_KEY}) was a wyrdsekai invention and is no
 * longer used; the adapter now maps {@code coding.backends.goose.provider}
 * → the right upstream env var via {@link #providerKeyEnvVarFor}.
 * Local providers (ollama; openai-against-llama-server) need no real key,
 * but Goose's OpenAI-compatible path still expects the env var to be set
 * to <i>something</i> non-blank — see {@link #buildEnv}.</p>
 *
 * <p><b>2026-05-05 reconciliation against {@code aaif-goose/goose@main}
 * v1.33.1 ({@code crates/goose-cli/src/cli.rs})</b>: argv shape rewritten.
 * Pre-reconciliation argv used {@code --task=…}, {@code --format=json},
 * and a fabricated {@code --workspace=…}; none of these flags exist in
 * upstream Goose. The current adapter uses:
 * <pre>
 *   goose run --text &lt;DESC&gt; --output-format json --no-session -q
 *             [--provider &lt;P&gt; --model &lt;M&gt;] [extra-flags…]
 * </pre>
 * Workspace travels via the subprocess CWD (set on
 * {@link ProcessRunner#run}), since Goose has no workspace flag.</p>
 *
 * <p>Configuration is read via {@link GooseRuntimeConfig}; see
 * {@code application.conf} block at
 * {@code wyrdsekai.coding.backends.goose}.</p>
 */
public final class GooseBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link GooseEventAdapter#namespace()}. */
    public static final String NAME = "goose";

    private static final Logger log = LoggerFactory.getLogger(GooseBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock so we don't stall the policy script. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Goose CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 2000L;

    private final GooseRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    /**
     * Cache of taskId → produced artifacts. Mirrors the OpenCode pattern;
     * Phase 5 will replace with a persistent index.
     */
    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public GooseBackend(GooseRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public GooseBackend(GooseRuntimeConfig config,
                        AuthResolver authResolver,
                        ProcessRunner runner) {
        this.config = config != null ? config : GooseRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "set the provider's API key (e.g. ANTHROPIC_API_KEY) "
                    + "in your Key Chest",
                "AuthResolver not wired"));
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    /**
     * Map a Goose provider name to the upstream env var Goose itself
     * reads. {@code ollama} returns {@code null} (no key required —
     * Ollama runs locally). {@code openai} maps to
     * {@code OPENAI_API_KEY}; pointed at a local llama-server it still
     * needs <i>some</i> non-blank value but the server ignores it.
     *
     * <p>Source: aaif-goose/goose v1.33.1 docs and
     * {@code crates/goose-cli/src/cli.rs}. Pre-2026-05 the wyrdsekai
     * adapter incorrectly set {@code GOOSE_PROVIDER_KEY}, which Goose
     * does not read.</p>
     */
    public static String providerKeyEnvVarFor(String provider) {
        if (provider == null) return null;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "google", "gemini", "gemini-cli" -> "GOOGLE_API_KEY";
            case "ollama" -> null;          // local Ollama daemon, no key
            case "databricks" -> "DATABRICKS_TOKEN";
            default -> null;                // unknown provider: don't guess
        };
    }

    @Override public String name() { return NAME; }

    @Override public BackendTier tier() { return BackendTier.CLOUD_PAID; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec != null && spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (!config.enabled()) {
            future.complete(failed(taskId,
                "Goose backend is disabled in config", started));
            return future;
        }

        // ── AuthResolver gate ──
        // Goose has no OAuth path; recovery is pointed at the Key Chest.
        // Ollama / local-OpenAI providers don't need a key — short-circuit
        // the AuthMissing case so a household running locally doesn't have
        // to plant a dummy key in the Key Chest just to flip the bit.
        //
        // The openai-provider + local-baseUrl posture (the recipe-bake
        // default for self-evolution) ALSO doesn't need a real key —
        // buildEnv() plants OPENAI_API_KEY=not-required, which the
        // local llama-server ignores. Discovered 2026-05-27 on mac-node —
        // BACKEND step failed in 22ms with LOGIN_REQUIRED before goose
        // ever launched. Mirrors the openai-host detection at line 432.
        var auth = authResolver.resolveAuth(NAME);
        boolean localOpenAi = "openai".equalsIgnoreCase(config.provider())
                && config.baseUrl() != null
                && !config.baseUrl().isBlank()
                && !config.baseUrl().contains("api.openai.com");
        if (auth instanceof AuthMode.AuthMissing missing
                && providerKeyEnvVarFor(config.provider()) != null
                && !localOpenAi) {
            future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                "LOGIN_REQUIRED: " + missing.reason()
                    + " (recovery: " + missing.recoveryCommand() + ")",
                List.of(), 0L, System.currentTimeMillis() - started));
            return future;
        }

        // Build argv. Construction stays exposed via buildArgs() so unit
        // tests can assert the wire shape (provider, --text value, no
        // leaking of api-key) without spawning a real process.
        List<String> builtArgs;
        try {
            builtArgs = buildArgs(spec);
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct Goose invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);
        File workdir = resolveWorkdir(spec, taskId.toString());
        // Same Windows command-line hazards as codezaiku (cmd length limit;
        // Java argv encoding mangling embedded double quotes -- goose.exe
        // received a quoted task SPLIT at the quotes: "unexpected argument").
        // The spill helper is codezaiku-named only by history; both backends
        // take `--text <TASK>` and both read their workspace.
        var args = CodeZaikuBackend.fitForWindowsCommandLine(builtArgs, workdir);

        // Run async on a virtual thread — submitTask() must not block.
        Thread.ofVirtual().name("goose-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, workdir, config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Goose task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Goose exited with code " + result.exitCode()
                            + (result.stderr().isBlank() ? ""
                                : ": " + result.stderr().trim()),
                        List.of(), 0L, durationMs));
                    return;
                }

                // CONTRACT REPAIR (2026-08-20). The backend writes the item; the bridge
                // decides whether the item is real — and measured live against the
                // household 9B, goose's first attempt is refused: two runs, two different
                // files, both missing the manifest's required `commands` block. The
                // items-as-tools preamble already demands it in the strongest terms it
                // has, and the model omits it anyway.
                //
                // So instead of shipping a file we know will be refused, hand the defect
                // back and let goose fix it. This plays to what the backend actually is —
                // an agentic coder that can read a specific complaint and edit its own
                // output — rather than hoping for correct one-shot generation. One extra
                // turn, bounded; if it still does not comply the artifact goes through as
                // before and the bridge logs the refusal exactly as it does today.
                // Backend-agnostic: goose is the default today and CodeZaiku is next;
                // every CLI backend rides the same items-as-tools preamble and the same
                // bridge, so the repair lives in ItemContractRepair and each backend only
                // supplies the one thing it alone knows — how to re-run itself.
                //
                // Parse FIRST so the repair works on the paths the run itself declared.
                // Guessing the directory does not work: live 2026-08-20 goose wrote to
                // /opt/wyrdsekai/ (its own cwd), which is neither the workspace it was
                // handed nor the /workspace the preamble teaches.
                ItemContractRepair.repairRun(
                    parseArtifacts(taskId, spec, result),
                    workdir == null ? null : workdir.toPath(),
                    taskId.toString(), Instant.ofEpochMilli(started),
                    ItemContractRepair.rerunWithPrompt(repairArgs -> {
                        try {
                            var r = runner.run(
                                repairArgs, env, workdir, config.maxWallclock());
                            return !r.timedOut() && r.exitCode() == 0;
                        } catch (Exception e) {
                            return false;
                        }
                    }, args),
                    spec == null ? null : spec.description());

                // Re-parse AFTER the repair so the cached artifact reflects the fixed
                // file, not the version the bridge would have refused.
                var artifacts = parseArtifacts(taskId, spec, result);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                // Exit 0 is not success. goose exits 0 and reports status "completed"
                // when it could not reach a model at all — staged 2026-08-21: "Network
                // error: Could not connect to localhost:8200", 7 seconds, no tokens, no
                // files — and this reported SUCCEEDED. The companion then told the
                // steward the workshop had finished, touching 0 files.
                var dead = neverReachedModel(result.stdout(), artifacts);
                if (dead.isPresent()) {
                    log.warn("[goose] task {} produced nothing: {}", taskId, dead.get());
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        dead.get(), List.copyOf(ids), 0L, durationMs));
                    return;
                }

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, artifacts), List.copyOf(ids), 0L, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Goose subprocess error: " + e.getMessage(), started));
            }
        });
        return future;
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        if (taskId == null) return Stream.empty();
        var list = artifactCache.get(taskId);
        return list == null ? Stream.empty() : list.stream();
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.enabled()) return false;
            try {
                var result = runner.run(
                    List.of(config.executablePath(), "--version"),
                    Map.of(),
                    null,
                    HEALTH_PROBE_TIMEOUT);
                if (result.timedOut() || result.exitCode() != 0) {
                    log.debug("Goose --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Goose binary not found at '{}' — "
                    + "run `wyrd coding install goose` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Goose health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        // Provider-aware: local backends have no per-token cost (200
        // baseline for sanity); cloud providers cost 500 baseline. SPEC
        // hard-capped at 2000 so a household never silently spends $20+
        // on one task because the estimate said "this is fine."
        long base = isLocalProvider(config.provider()) ? 200L : 500L;
        if (spec == null || spec.taskType() == null) return Math.min(base, MAX_CU_ESTIMATE);
        return switch (spec.taskType().toLowerCase()) {
            case "explore", "explore_unknown_repo", "survey", "research" -> Math.min(base / 2, MAX_CU_ESTIMATE);
            case "implement_feature", "implement", "build" -> Math.min(base * 2, MAX_CU_ESTIMATE);
            case "refactor" -> Math.min((long) (base * 1.5), MAX_CU_ESTIMATE);
            default -> Math.min(base, MAX_CU_ESTIMATE);
        };
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public GooseRuntimeConfig config() { return config; }

    /**
     * "Local" for cost-estimate purposes: ollama runs locally; openai
     * pointed at a non-{@code api.openai.com} {@code base-url} is also
     * local (the household's llama-server). Cloud-billed providers
     * (anthropic, gemini, default-openai) cost more.
     */
    static boolean isLocalProvider(String provider) {
        if (provider == null) return false;
        var p = provider.toLowerCase(Locale.ROOT);
        return "ollama".equals(p)
            || "local".equals(p);   // legacy alias — coerced to openai+local in env
    }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list passed to {@link ProcessRunner}. Exposed so
     * tests can assert the wire shape without spawning a real subprocess.
     *
     * <p>Wire shape ({@code aaif-goose/goose} v1.33.1):
     * {@code goose run --text <DESCRIPTION> --output-format json
     * --no-session -q [--provider <P>] [--model <M>] [extra-flags…]}.
     *
     * <ul>
     *   <li>{@code --text} (short {@code -t}) — primary input. Pre-2026-05
     *       this was {@code --task=}, which Goose has never supported.
     *   </li>
     *   <li>{@code --output-format json} — single trailing
     *       {@code {"messages": [...], "metadata": {…}}} document on stdout.
     *       Pre-2026-05 this was {@code --format=json} (not a real flag).
     *   </li>
     *   <li>{@code --no-session} — don't persist a session file under
     *       {@code ~/.local/state/goose/}; mandatory for a clean
     *       repeatable headless run.</li>
     *   <li>{@code -q} — suppress ANSI/banner noise so stdout is parseable.
     *   </li>
     *   <li>{@code --provider} / {@code --model} — overrides for
     *       {@code GOOSE_PROVIDER} / {@code GOOSE_MODEL} env vars.
     *       Redundant but explicit; survives a household that exports
     *       a different default in the parent shell.</li>
     * </ul>
     *
     * <p>Workspace is set as the subprocess CWD (Goose has no workspace
     * flag); see {@link #resolveWorkdir(TaskSpec)}.</p>
     *
     * <p>The api-key value never appears in argv — it travels via env
     * (see {@link #buildEnv}).</p>
     */
    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("run");

        // --text takes its value as a separate argv element (clap
        // value_name=TEXT, not =-style). Description as a single arg
        // preserves spaces/newlines.
        //
        // Wyrdsekai items-as-tools contract: prepend
        // {@link OpenHandsBackend#ITEMS_AS_TOOLS_PREAMBLE} so the agent
        // produces the same single-{@code .js}-with-{@code exports.manifest}
        // shape that {@link CodingTaskItemBridge} can register at
        // placement time. Without this Goose would write arbitrary code
        // (Python, multi-file scaffolds) and {@code use <id>} would have
        // nothing to invoke through {@code ItemScriptExecutor}. The
        // preamble is OpenHands-namespaced today but generic — every
        // backend is expected to emit Wyrdsekai-shaped artifacts.
        var description = spec != null ? spec.description() : null;
        args.add("--text");
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        // Recipe BACKEND steps with tools=[shell] set
        // taskType=CodingBackendDispatcher.TASK_TYPE_SHELL_EXEC; in that
        // mode the recipe author wants raw shell execution, not a
        // scripted-item .js file. Skip the items-as-tools wrap so the
        // local 9B doesn't drift into manifest-generation mode.
        boolean shellExec = spec != null
                && "shell-exec".equalsIgnoreCase(spec.taskType());
        args.add(shellExec
            ? promptBody
            : (OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
                + "\n\n--- TASK ---\n" + promptBody));

        // Say how big the prompt is. "Context size has been exceeded" is a server-side
        // error with no clue attached, and without this the only way to tell whether the
        // prompt or the agent loop blew the window is to guess — which is what I did for
        // three runs on 2026-08-21 before adding this line.
        var promptChars = args.get(args.size() - 1).length();
        log.info("[goose] prompt {} chars (~{} tokens) for task {}",
            promptChars, promptChars / 4,
            spec == null || spec.taskId() == null ? "?" : spec.taskId());

        // Headless triplet: --output-format json + --no-session + -q.
        args.add("--output-format");
        args.add("json");
        args.add("--no-session");
        args.add("-q");

        // Provider/model overrides. Both are redundant if env vars are
        // also set, but explicit-wins makes the invocation shape obvious
        // in process-listing audits and is robust against a parent shell
        // that already exported a different default.
        if (config.provider() != null && !config.provider().isBlank()) {
            args.add("--provider");
            // "local" is not a real Goose provider value — coerce to
            // openai (paired with OPENAI_HOST in buildEnv).
            args.add(coerceProvider(config.provider()));
        }
        // effectiveModel(), not model(). buildEnv() was taught to follow the node's
        // real inference; this line was not, and --model on the command line WINS over
        // GOOSE_MODEL in the environment. So the argv quietly reasserted the compiled-in
        // 9B name at a server that serves something else — the two halves of the same
        // invocation disagreeing about which model this is.
        var model = config.effectiveModel();
        if (model != null && !model.isBlank()) {
            args.add("--model");
            args.add(model);
        }

        // Steward-supplied extra flags (e.g. --max-turns 20).
        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Coerce legacy {@code provider=local} (used by the pre-2026-05
     * adapter to mean "talk to the bundled llama-server") into the real
     * upstream provider name {@code openai}. Paired with
     * {@code OPENAI_HOST} in {@link #buildEnv} this routes through
     * Goose's built-in OpenAI provider with the household's base URL.
     */
    static String coerceProvider(String provider) {
        if (provider == null) return DefaultsHolder.PROVIDER;
        return "local".equalsIgnoreCase(provider) ? "openai" : provider;
    }

    /**
     * Build the env for the subprocess. Layout:
     *
     * <ul>
     *   <li>{@code GOOSE_PROVIDER} / {@code GOOSE_MODEL} — primary
     *       provider+model selection mechanism per upstream docs.
     *   </li>
     *   <li>For OpenAI-compatible providers: {@code OPENAI_HOST} (base URL
     *       override — drops Goose's built-in OpenAI client at the
     *       household's llama-server) + {@code OPENAI_API_KEY} (any
     *       non-blank value — llama-server ignores the value but Goose's
     *       client requires the env var to be set).
     *   </li>
     *   <li>For other providers: the upstream provider key env var
     *       (ANTHROPIC_API_KEY / GOOGLE_API_KEY / DATABRICKS_TOKEN…)
     *       set from the resolved {@link AuthMode.ApiKey}.
     *   </li>
     * </ul>
     *
     * <p>We never inject the secret into argv — keeping it out of process
     * listings + logs.</p>
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        var coerced = coerceProvider(config.provider());

        env.put("GOOSE_PROVIDER", coerced);
        if (config.effectiveModel() != null && !config.effectiveModel().isBlank()) {
            env.put("GOOSE_MODEL", config.effectiveModel());
        }

        // Targeting a local llama-server through the openai provider —
        // OPENAI_HOST overrides the upstream {@code api.openai.com}
        // default. Only set when the configured baseUrl differs from
        // upstream OpenAI; otherwise Goose talks to real OpenAI.
        if ("openai".equalsIgnoreCase(coerced)
                && config.effectiveBaseUrl() != null
                && !config.effectiveBaseUrl().isBlank()
                && !config.effectiveBaseUrl().contains("api.openai.com")) {
            // Goose's openai provider APPENDS "/v1/chat/completions" to
            // OPENAI_HOST, so OPENAI_HOST must be the BARE host without a
            // trailing /v1 — else requests go to /v1/v1/... → 404 (confirmed
            // 2026-07-21 with goose 1.34.1; a stale corpus had masked the
            // silent expand-corpus failure this caused). config.effectiveBaseUrl()
            // carries the /v1 for backends that DO want the full base
            // (OpenCode etc.), so strip it only here for Goose.
            env.put("OPENAI_HOST", config.effectiveBaseUrl().replaceAll("/v1/?$", ""));
        }

        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            String envVar = providerKeyEnvVarFor(coerced);
            if (envVar != null) {
                env.put(envVar, key.value());
            } else {
                log.debug("[Goose] provider={} requires no env-var key; "
                        + "ApiKey resolved but discarded.", coerced);
            }
        } else if ("openai".equalsIgnoreCase(coerced)
                && env.containsKey("OPENAI_HOST")) {
            // Local llama-server: Goose's OpenAI client still requires
            // OPENAI_API_KEY to be set to *something*. Use a sentinel
            // value the server happily ignores. Mirrors the
            // OpenCodeBackend "not-required" pattern.
            env.put("OPENAI_API_KEY", "not-required");
        }
        return env;
    }

    /**
     * Resolve the subprocess CWD. Goose has no {@code --workspace}
     * flag — the workspace IS the subprocess CWD. Falls back to the
     * JVM's user.dir when no workspace hint is provided.
     */
    static File resolveWorkdir(TaskSpec spec) {
        return resolveWorkdir(spec, null);
    }

    /**
     * The directory this run may write in.
     *
     * <p>Used to return null when no workspace was named, which makes ProcessBuilder
     * inherit the JVM's current directory — the INSTALL ROOT on a packaged node. Live
     * 2026-08-20: goose wrote {@code /opt/wyrdsekai/library_query.js}, into the
     * application's own directory. {@link CodingWorkspace} gives each task a private
     * scratch directory instead; an explicit hint is still honoured.
     */
    static File resolveWorkdir(TaskSpec spec, String taskId) {
        return CodingWorkspace.forTask(
            spec != null ? spec.workspaceHint() : null, taskId);
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Goose's stdout into {@link CodingArtifact}s.
     *
     * <p>With {@code --output-format json} Goose emits a single trailing
     * pretty-printed JSON document of shape {@code {"messages": [...],
     * "metadata": {…}}}; with {@code --output-format stream-json} it
     * emits one event-line at a time. The parser handles both — it scans
     * line-by-line first (stream-json) and falls back to whole-document
     * parse (json), tolerantly extracting any file path it finds in
     * common keys ({@code path}, {@code file_path}, {@code touched_files},
     * Anthropic-tool-call {@code input.path}, …). Falls through to an
     * empty file list when stdout is opaque — the task is still recorded
     * as succeeded.</p>
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var files = new ArrayList<String>();
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());

        var stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            // stream-json format: one JSON object per line.
            for (var line : stdout.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // not every line is JSON — ignore
                }
            }
            // json format: single trailing pretty-printed document.
            if (files.isEmpty()) {
                try {
                    var node = MAPPER.readTree(stdout);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // ignore
                }
            }
        }

        var seen = new LinkedHashSet<>(files);
        var dedupedFiles = new ArrayList<>(seen);

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "goose");
        metadata.put("backend", NAME);
        metadata.put("provider", coerceProvider(config.provider()));
        if (result.stdout() != null && !result.stdout().isBlank()) {
            var stdoutSnippet = result.stdout();
            if (stdoutSnippet.length() > 2000) {
                stdoutSnippet = stdoutSnippet.substring(0, 2000) + "…[truncated]";
            }
            metadata.put("raw_stdout", stdoutSnippet);
        }

        var src = new SourceArtifact(
            UUID.randomUUID(),
            NAME,
            taskId.toString(),
            workspace,
            List.copyOf(dedupedFiles),
            null, // Goose doesn't surface git ref in JSON output
            Instant.now(),
            Map.copyOf(metadata)
        );
        return List.of(src);
    }

    /** Walk a JSON node and accumulate any file paths it mentions. */
    private static void extractFiles(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) return;
        for (var key : List.of("file", "path", "file_path", "edited_file",
                                 "touched_file", "filename")) {
            if (node.has(key) && node.get(key).isTextual()) {
                var v = node.get(key).asText();
                if (!v.isBlank()) out.add(v);
            }
        }
        for (var key : List.of("files", "edited_files", "touched_files")) {
            if (node.has(key) && node.get(key).isArray()) {
                for (var f : node.get(key)) {
                    if (f.isTextual() && !f.asText().isBlank()) out.add(f.asText());
                }
            }
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> extractFiles(e.getValue(), out));
        } else if (node.isArray()) {
            for (var e : node) extractFiles(e, out);
        }
    }

    private TaskResult failed(UUID taskId, String summary, long startedMs) {
        return new TaskResult(taskId, NAME, TaskStatus.FAILED, summary,
            List.of(), 0L, System.currentTimeMillis() - startedMs);
    }

    /** How goose re-runs itself in the same workspace — the one thing only it knows. */
    /**
     * Did goose actually talk to a model? Empty when yes; otherwise the reason, in
     * goose's own words where it gave them.
     *
     * <p>Decisive signal: {@code metadata.total_tokens} is null or zero in the final
     * JSON document — goose counts tokens only after a model answers — combined with no
     * files touched. Either alone is ambiguous (a trivial answer may touch nothing); both
     * together mean the run did not happen.
     */
    static Optional<String> neverReachedModel(String stdout, List<CodingArtifact> artifacts) {
        boolean anyFile = artifacts != null && artifacts.stream()
            .anyMatch(a -> a instanceof SourceArtifact s
                && s.files() != null && !s.files().isEmpty());
        if (anyFile || stdout == null || stdout.isBlank()) return Optional.empty();
        try {
            var start = stdout.indexOf('{');
            if (start < 0) return Optional.empty();
            var doc = MAPPER.readTree(stdout.substring(start));
            var tokens = doc.path("metadata").path("total_tokens");
            if (!(tokens.isMissingNode() || tokens.isNull() || tokens.asLong(0) == 0)) {
                return Optional.empty();
            }
            String said = null;
            for (var msg : doc.path("messages")) {
                if (!"assistant".equals(msg.path("role").asText())) continue;
                for (var c : msg.path("content")) {
                    if ("text".equals(c.path("type").asText())) said = c.path("text").asText();
                }
            }
            return Optional.of("goose never reached a model"
                + (said == null || said.isBlank() ? "" : ": " + said.strip()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String summarise(TaskSpec spec, List<CodingArtifact> artifacts) {
        int files = 0;
        for (var a : artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        if (artifacts.isEmpty()) {
            return "Goose completed the " + taskType + " (no artifacts captured).";
        }
        return "Goose completed the " + taskType + ", touching "
            + files + " file(s).";
    }

    // -- ProcessRunner indirection (mirrors OpenCodeBackend) --------------

    /** Result of a subprocess invocation. */
    public record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut) {}

    /**
     * Indirection seam between {@link GooseBackend} and the JDK
     * {@link ProcessBuilder}. Tests substitute a stub.
     *
     * <p>The {@code workdir} parameter is the subprocess CWD — Goose
     * uses CWD as its workspace (no flag for it), so the runner must
     * honour it. {@code null} means "inherit JVM cwd".</p>
     */
    @FunctionalInterface
    public interface ProcessRunner {
        ProcessResult run(List<String> args, Map<String, String> env,
                          File workdir, Duration timeout)
                throws IOException, InterruptedException;
    }

    /** Default {@link ProcessRunner} — spawns the real subprocess. */
    public static class DefaultProcessRunner implements ProcessRunner {
        private final EgressGate egressGate;

        /** Production — resolve the shared egress gate from config (default ON). */
        public DefaultProcessRunner() {
            this(EgressGate.defaultInstance());
        }

        /** Explicit gate — for tests / a backend that carries its own override. */
        public DefaultProcessRunner(EgressGate egressGate) {
            this.egressGate = egressGate != null ? egressGate : EgressGate.enforcing();
        }

        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  File workdir, Duration timeout)
                throws IOException, InterruptedException {
            var pb = new ProcessBuilder(args);
            // route the inherited env through the
            // egress gate instead of blindly inheriting it. Enforcing (default)
            // scrubs ambient credentials (SSH_AUTH_SOCK etc.) and re-adds only
            // the allowlisted vars + the backend's own env. Disabled = legacy
            // inherit-then-layer.
            egressGate.applyEnv(pb.environment(), env);
            if (workdir != null) pb.directory(workdir);
            pb.redirectErrorStream(false);
            var process = pb.start();
            var stdoutBuf = new StringBuilder();
            var stderrBuf = new StringBuilder();
            var stdoutThread = Thread.ofVirtual().start(() -> {
                try (var in = process.getInputStream()) {
                    var bytes = in.readAllBytes();
                    stdoutBuf.append(new String(bytes, StandardCharsets.UTF_8));
                } catch (Exception _) { /* swallow */ }
            });
            var stderrThread = Thread.ofVirtual().start(() -> {
                try (var in = process.getErrorStream()) {
                    var bytes = in.readAllBytes();
                    stderrBuf.append(new String(bytes, StandardCharsets.UTF_8));
                } catch (Exception _) { /* swallow */ }
            });

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(500);
                stderrThread.join(500);
                return new ProcessResult(-1, stdoutBuf.toString(), stderrBuf.toString(), true);
            }
            stdoutThread.join();
            stderrThread.join();
            return new ProcessResult(process.exitValue(),
                stdoutBuf.toString(), stderrBuf.toString(), false);
        }
    }

    /** Tiny holder so static methods can reference the default provider
     *  without depending on an instance. */
    private static final class DefaultsHolder {
        static final String PROVIDER = GooseRuntimeConfig.DEFAULT_PROVIDER;
    }
}
