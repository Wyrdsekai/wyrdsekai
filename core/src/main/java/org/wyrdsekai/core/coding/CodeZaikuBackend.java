package org.wyrdsekai.core.coding;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.codezaiku.CodeItemStore;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * CodeZaiku implementation of {@link CodingTaskBackend} — CLI edition.
 *
 * <p>Rewritten 2026-08-15. The original adapter dispatched
 * {@code codezaiku.create} zone commands over the zone bridge; CodeZaiku
 * archived that design (no WebSocket, no zone-bridge protocol on their
 * side), so the old adapter targeted a CodeZaiku that no longer exists.
 * The agreed integration is a CLI drop-in beside Goose:</p>
 *
 * <pre>
 *   codezaiku run --text &lt;TASK&gt; --output-format json --no-session -q [extra-flags…]
 * </pre>
 *
 * <ul>
 *   <li><b>Workspace</b> is the subprocess CWD — no flag.</li>
 *   <li><b>Model routing</b> travels only via env: {@code CODEZAIKU_DRIVE}
 *       (OpenAI-compatible endpoint) + {@code CODEZAIKU_MODEL}. Env beats
 *       CodeZaiku's own config files by first-hit precedence, so this
 *       injection is authoritative.</li>
 *   <li><b>Health</b> is {@code codezaiku --version}.</li>
 *   <li><b>Output</b> is one JSON document on stdout carrying the shared
 *       artifact shape ({@code files[]}, {@code status},
 *       {@code testsPassed}/{@code testsFailed}, {@code gitRef?},
 *       {@code workspacePath?}, extras). The same shape arrives in the
 *       final ACP {@code session/update} once the ACP client lands, so
 *       {@link #parseResultJson} is the single parser for both paths.</li>
 * </ul>
 *
 * <p><b>Typed contract, enforced:</b> unlike the tolerant extractors on
 * older backends, exit 0 with an unparseable or artifact-less stdout is
 * a FAILED task here, never a silent success — that failure class is the
 * reason CodeZaiku and wyrdsekai agreed a schema in the first place.</p>
 *
 * <p>Reuses {@link GooseBackend.ProcessRunner} /
 * {@link GooseBackend.DefaultProcessRunner} so there is exactly one
 * EgressGate-scrubbed subprocess path.</p>
 */
public final class CodeZaikuBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link CodeZaikuEventAdapter#namespace()}. */
    /** files[] beyond this is a dependency tree, not a deliverable. */
    static final int FILES_CAP = 200;
    static final Set<String> DEPENDENCY_DIRS =
        Set.of("node_modules", ".venv", "venv", "target", "build", ".gradle", "dist", "__pycache__");

    public static final String NAME = "codezaiku";

    private static final Logger log = LoggerFactory.getLogger(CodeZaikuBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final CodeZaikuRuntimeConfig config;
    private final CodeItemStore store;               // legacy artifact lookups, nullable
    private final GooseBackend.ProcessRunner runner;
    private final Map<String, List<CodingArtifact>> artifactCache = new ConcurrentHashMap<>();

    /** Production constructor. */
    public CodeZaikuBackend(CodeZaikuRuntimeConfig config, CodeItemStore store) {
        this(config, store, new GooseBackend.DefaultProcessRunner());
    }

    /** Test constructor — pluggable runner. */
    public CodeZaikuBackend(CodeZaikuRuntimeConfig config, CodeItemStore store,
                            GooseBackend.ProcessRunner runner) {
        this.config = config != null ? config : CodeZaikuRuntimeConfig.defaults();
        this.store = store;
        this.runner = runner != null ? runner : new GooseBackend.DefaultProcessRunner();
    }

    @Override public String name() { return NAME; }

    /** Local compute — real disk/GPU cost, no per-token billing. */
    @Override public BackendTier tier() { return BackendTier.LOCAL_HEAVY; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec != null && spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (!config.enabled()) {
            future.complete(failed(taskId, "CodeZaiku backend is disabled in config", started));
            return future;
        }

        var env = buildEnv();
        File workdir = resolveWorkdir(spec, taskId.toString());
        var args = fitForWindowsCommandLine(buildArgs(spec, taskId), workdir);

        Thread.ofVirtual().name("codezaiku-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, workdir, config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "CodeZaiku task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                // EXIT CODE IS NOT THE VERDICT; THE DISK IS. Twice on 2026-08-23 CodeZaiku
                // exited 1 with a valid, complete item on disk (wiki_briefing.js,
                // venture_scout2.js) and this threw the work away unread — the mirror of
                // the goose lesson that exit 0 with no file is not success. CodeZaiku
                // still emits its result JSON on a nonzero exit; if that names files that
                // exist, the task produced something and the bridge's disk check is the
                // authority on whether it registers. The exit code rides along in the
                // summary so nobody is told it was clean.
                var parsed = parseResultJson(result.stdout());
                if (result.exitCode() != 0) {
                    // EXIT 2 IS "RAN OUT OF ROOM", NOT "WENT WRONG". CodeZaiku 01de82d2: the
                    // exit code derives from status — 0 success/untested, 2 incomplete (turn
                    // budget exhausted, files[] still real work), 1 failed, 143 killed. Before
                    // that, both 1 and exhaustion were 1 and we read the disk to tell them
                    // apart; the disk check stays as the backstop, because incomplete work is
                    // only worth placing if the file is actually there.
                    var incomplete = result.exitCode() == 2
                        || (parsed != null && "incomplete".equalsIgnoreCase(parsed.path("status").asText("")));
                    var produced = parsed != null && namedFilesExist(workdir.toPath(), parsed);
                    if (incomplete && produced) {
                        log.info("[codezaiku] task {} ran out of turns with its file on disk "
                            + "(exit 2) — placing what it made", taskId);
                    } else if (!produced) {
                        future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                            "CodeZaiku exited with code " + result.exitCode()
                                + (result.stderr().isBlank() ? "" : ": " + lastLines(result.stderr(), 6)),
                            List.of(), 0L, durationMs));
                        return;
                    }
                    log.warn("[codezaiku] task {} exited {} but wrote the files it named — "
                        + "continuing on the evidence, not the code", taskId, result.exitCode());
                }

                if (parsed == null) {
                    // Typed contract: exit 0 with no parseable result JSON is
                    // a FAILURE, not a shrug — the silent-success class of bug
                    // this integration exists to eliminate.
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "CodeZaiku exited 0 but emitted no parseable result JSON "
                            + "(--output-format json contract violation)",
                        List.of(), 0L, durationMs));
                    return;
                }

                var artifacts = toArtifacts(taskId, spec, parsed);

                // CONTRACT REPAIR — the same turn goose gets. This lived inside
                // GooseBackend, so the capability existed for the DEFAULT backend and no
                // other; CodeZaiku was already named as the one taking over. A file the
                // bridge would refuse is handed back with every defect named, bounded to
                // MAX_ROUNDS, and whatever survives goes on exactly as before.
                var repairArtifacts = artifacts;
                ItemContractRepair.withoutEscalation(() ->
                    ItemContractRepair.repairRun(repairArtifacts,
                    workdir == null ? null : workdir.toPath(),
                    taskId.toString(), Instant.ofEpochMilli(started),
                    ItemContractRepair.rerunWithPrompt(repairArgs -> {
                        try {
                            var r = runner.run(
                                repairArgs, env, workdir, config.maxWallclock());
                            // RAN, not "exited 0". The same disk-is-the-verdict rule as the
                            // task path: three repairs on 2026-08-23 (venture_scout3,
                            // trip_compass2, storm_cellar) fixed the file, exited 1 on the
                            // turn budget, and were logged "did not complete" — so the loop
                            // shipped a corrected file believing it uncorrected. The loop
                            // re-reads the file itself; that read is the verdict.
                            if (r.exitCode() != 0) {
                                log.info("[codezaiku] repair run exited {} — the file on disk "
                                    + "decides whether the problems are gone", r.exitCode());
                            }
                            return !r.timedOut();
                        } catch (Exception e) {
                            return false;
                        }
                    }, args),
                    spec == null ? null : spec.description()));

                // Re-derive AFTER the repair so the cache holds the fixed file, not the
                // version the bridge would have binned.
                artifacts = toArtifacts(taskId, spec, parsed);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                var status = "failed".equalsIgnoreCase(parsed.path("status").asText(""))
                    ? TaskStatus.FAILED : TaskStatus.SUCCEEDED;
                future.complete(new TaskResult(taskId, NAME, status,
                    summarise(parsed), List.copyOf(ids), 0L, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "CodeZaiku subprocess error: " + e.getMessage(), started));
            }
        });
        return future;
    }

    /** Inside a dependency tree — node_modules, a venv, a build dir. Not a deliverable. */
    static boolean isDependencyPath(String rel) {
        if (rel == null || rel.isBlank()) return false;
        for (var seg : rel.replace('\\', '/').split("/")) {
            if (DEPENDENCY_DIRS.contains(seg)) return true;
        }
        return false;
    }

    /** Do the files the result JSON names actually exist in the workspace? */
    static boolean namedFilesExist(Path ws, JsonNode parsed) {
        var files = parsed.path("files");
        if (!files.isArray() || files.isEmpty()) return false;
        for (var f : files) {
            var name = f.isTextual() ? f.asText() : f.path("path").asText("");
            if (name.isBlank()) continue;
            var p = Path.of(name);
            if (p.isAbsolute() ? Files.isRegularFile(p)
                    : ws != null && Files.isRegularFile(ws.resolve(name))) return true;
        }
        return false;
    }

    /** The END of stderr, where the reason lives — not the JDK warnings at the top. */
    static String lastLines(String s, int n) {
        var lines = s.strip().split("\\R");
        var from = Math.max(0, lines.length - n);
        return String.join(" | ", Arrays.copyOfRange(lines, from, lines.length));
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        if (taskId == null) return Stream.empty();
        var cached = artifactCache.get(taskId);
        if (cached != null) return cached.stream();
        // Legacy fallback: boards persisted by the old zone-bridge era.
        if (store == null) return Stream.empty();
        return store.listSources().stream()
            .filter(s -> taskId.equals(s.taskId()))
            .map(s -> (CodingArtifact) s);
    }

    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            if (!config.enabled()) return false;
            try {
                var result = runner.run(
                    List.of(config.executablePath(), "--version"),
                    Map.of(), null, HEALTH_PROBE_TIMEOUT);
                return !result.timedOut() && result.exitCode() == 0;
            } catch (Exception e) {
                log.info("CodeZaiku binary not found at '{}' ({})",
                    config.executablePath(), e.getMessage());
                return false;
            }
        });
    }

    /** Local — no per-token billing; cost policy never gates it. */
    @Override
    public long estimatedCu(TaskSpec spec) { return 0L; }

    /** Snapshot for tests + diagnostics. */
    public CodeZaikuRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * {@code codezaiku run --text <TASK> --output-format json --no-session -q
     * [extra-flags…]} — deliberately byte-compatible in shape with the
     * Goose invocation so harness comparisons run at fixed model with a
     * like-for-like spawn. No provider/model flags: routing is env-only.
     */

    /** cmd.exe refuses lines past 8191 chars; leave margin for the launcher's own expansion. */
    static final int WINDOWS_CMDLINE_BUDGET = 7000;

    /**
     * On Windows, an over-long task is moved INTO the workspace instead of onto
     * the command line.
     *
     * <p>The launcher there is a {@code .bat}, so every argument passes through
     * cmd.exe and its 8191-character line limit — and our task preamble alone
     * is roughly double that, so any real dispatch died with "The command line
     * is too long" (found live on the Windows box, first probe ever run there).
     * CodeZaiku 0.1.0's {@code run --text} read its value literally; we
     * reported it and 0.1.1 (2026-08-27, same day) added {@code --text @file}
     * and {@code -}. This spill remains the COMPAT path: it works against
     * every version, while {@code @file} against a 0.1.0 install would be
     * taken as the literal task text "@/path" — a silently wrong dispatch.
     * Switch to {@code @file} once 0.1.0 installs are extinct (the bundle
     * ships 0.1.1+, but per-user installs upgrade on their own schedule).
     * POSIX execs never hit the limit and keep the direct path.</p>
     */
    static List<String> fitForWindowsCommandLine(List<String> args, File workdir) {
        boolean windows = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows) return args;
        int textIdx = args.indexOf("--text");
        if (textIdx < 0 || textIdx + 1 >= args.size() || workdir == null) return args;
        int total = args.stream().mapToInt(String::length).sum() + args.size();
        // Two Windows hazards force the spill, not one. Length is the obvious
        // hazard (cmd.exe's 8191 limit). EMBEDDED DOUBLE QUOTES are the sneaky
        // one: Java's CreateProcess argv encoding mangles an argument that
        // contains its own quotes, so a task like `print("ok")` reached
        // goose.exe SPLIT AT THE QUOTES — `error: unexpected argument 'this'`.
        // codezaiku dodged it only because its long preamble already spilled;
        // goose's short task rode the command line and hit it. Any quoted task
        // text goes to the file, whatever its length.
        boolean hazardous = total > WINDOWS_CMDLINE_BUDGET
            || args.get(textIdx + 1).indexOf('"') >= 0;
        if (!hazardous) return args;
        try {
            var taskFile = new File(workdir, "WYRD_TASK.md");
            java.nio.file.Files.writeString(taskFile.toPath(), args.get(textIdx + 1));
            var out = new ArrayList<>(args);
            out.set(textIdx + 1,
                "Open WYRD_TASK.md in this workspace and carry out exactly the task it "
                + "describes. Treat its contents as the full instruction; do not modify "
                + "or deliver WYRD_TASK.md itself.");
            log.info("[codezaiku] task text ({} chars) moved to WYRD_TASK.md — "
                + "cmd.exe line limit", args.get(textIdx + 1).length());
            return out;
        } catch (Exception e) {
            log.warn("[codezaiku] could not spill task text to workspace: {}", e.toString());
            return args;
        }
    }

    /**
     * A repair round any OTHER backend can borrow when its own rounds exhaust —
     * {@code CodingBackendBootstrap} hands this to {@link ItemContractRepair} when
     * codezaiku registers. Same argv contract as the in-backend repair (the bare
     * prompt, no items preamble: a repair prompt is self-contained), same
     * disk-is-the-verdict rule.
     */
    public ItemContractRepair.Escalation escalationRunner() {
        return (workspace, prompt) -> {
            var argv = new ArrayList<String>();
            argv.add(config.executablePath());
            argv.add("run");
            argv.add("--text");
            argv.add(prompt);
            argv.add("--mode");
            argv.add("artifact");
            argv.add("--output-format");
            argv.add("json");
            argv.add("--no-session");
            argv.add("-q");
            var workdir = workspace == null ? null : workspace.toFile();
            var args = fitForWindowsCommandLine(List.copyOf(argv), workdir);
            try {
                var r = runner.run(args, buildEnv(), workdir, config.maxWallclock());
                if (r.exitCode() != 0) {
                    log.info("[codezaiku] escalation run exited {} — the file on disk "
                        + "decides whether the problems are gone", r.exitCode());
                }
                return !r.timedOut();
            } catch (Exception e) {
                log.info("[codezaiku] escalation run failed to start: {}", e.toString());
                return false;
            }
        };
    }

    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("run");
        // One deliverable, no project. CodeZaiku 01de82d2 added ARTIFACT mode after we
        // reported a run spending 40 turns on a Python test suite for a one-file tool:
        // its task_done gate re-runs the project's tests and refuses while red, so the
        // model invents a suite to satisfy it. Measured by them on our wiki_briefing
        // task: default 23 turns / 4,324 files (it ran npm install); artifact 6 / 1. The
        // contract prose asked for this; the flag makes it a switch. Expect status
        // `untested` — nothing verified the file, and that is the honest word.
        args.add("--text");
        var description = spec != null && spec.description() != null
            ? spec.description() : "";
        boolean shellExec = spec != null && "shell-exec".equalsIgnoreCase(spec.taskType());
        args.add(shellExec
            ? description
            : (OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault()) + "\n\n--- TASK ---\n" + description));
        // Prompt size, as goose logs it — the 2026-08-23 wiki run could not be told apart
        // from a bare-description run without this (it had the contract; it still spent
        // 40 turns on a Python test suite). A number in the log is knowing, not guessing.
        { var sent = args.get(args.size() - 1); log.info("[codezaiku] task {} prompt {} chars (~{} tokens)", spec == null ? "?" : spec.taskId(), sent.length(), sent.length() / 4); }
        // One deliverable, no wandering — for EVERY task shape, shell-exec included.
        // This used to be gated to item builds only ("a shell task is not an
        // artifact"), and the CodeZaiku team's own measurement of our bake path
        // retired that reasoning (2026-08-24): in default mode the model did the asked work and
        // then spent 36 turns "improving" the repo — files nobody asked for, a
        // package install — because a large tree is endless scope; artifact mode was
        // 4 turns. They also measured default mode peaking at 95% of the context
        // window (8 compactions) vs 42% under artifact — on a bigger tree default
        // mode overflows first. A recipe step that runs one command and leaves one
        // file behind is exactly one deliverable. After --text so the argv contract
        // (`codezaiku run --text <TASK> …`, task at index 3) stands.
        args.add("--mode");
        args.add("artifact");
        args.add("--output-format");
        args.add("json");
        args.add("--no-session");
        args.add("-q");
        return List.copyOf(args);
    }

    /** Full argv incl. the per-task id echo (CodeZaiku's {@code --task-id},
     *  accepted 2026-08-15 — lets both sides' logs correlate one run). */
    public List<String> buildArgs(TaskSpec spec, UUID taskId) {
        var args = new ArrayList<>(buildArgs(spec));
        args.add("--task-id");
        args.add(taskId.toString());
        args.addAll(config.extraFlags());
        return List.copyOf(args);
    }

    /**
     * Env is the routing contract — and WHICH env key we use says how strongly we mean
     * it.
     *
     * <p>CodeZaiku's precedence (shipped in their 55ab5182) is: environment override →
     * the machine's own config file → {@code <KEY>_DEFAULT} → their built-in. So a value
     * an operator actually chose goes in {@code CODEZAIKU_DRIVE} and is authoritative;
     * a value we merely defaulted goes in {@code CODEZAIKU_DRIVE_DEFAULT}, which loses
     * to a config file the machine's owner wrote.
     *
     * <p>Injecting unconditionally — as this did until 2026-08-21 — silently redirected
     * a machine configured against a hosted endpoint to {@code localhost:8200}, and
     * {@code codezaiku doctor} reads the config FILE, so it reported healthy while the
     * summoned run went somewhere else entirely. Their suffix convention works for any
     * setting, and {@code codezaiku config list} reports which level each value came
     * from, so the wiring can be verified rather than guessed.
     *
     * <p>Never emits a blank: CodeZaiku treats blank as absent at every level, so an
     * empty {@code _DEFAULT} pins nothing. Falling through is the safe direction, but
     * it is not the behaviour we would be claiming.
     *
     * <p>No secrets travel here — see {@link #buildEnv()}'s caller note about
     * {@code EgressGate}, which is what actually decides whether a hosted key can reach
     * the subprocess at all.
     */
    public Map<String, String> buildEnv() {
        var env = new HashMap<String, String>();
        // CodeZaiku is a JVM app and its launcher resolves Java via JAVA_HOME
        // first, PATH second. EgressGate deliberately strips the inherited
        // environment, and on a Windows service install neither survives —
        // the child died with launcher exit 9009, "JAVA_HOME is not set and
        // no 'java' command could be found in your PATH" (found by the first
        // probe ever run on the Windows box). The parent IS a running JVM, so
        // hand the child the same one; an explicit put survives the scrub by
        // design, exactly like CODEZAIKU_DRIVE below.
        var javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            env.put("JAVA_HOME", javaHome);
        }
        putRouting(env, "CODEZAIKU_DRIVE", config.effectiveDriveUrl(), config.driveUrlFromConfig());
        putRouting(env, "CODEZAIKU_MODEL", config.effectiveModel(), config.modelFromConfig());
        // A hosted drive needs a credential, and ambient inheritance is not the route:
        // EgressGate clears the subprocess environment to an allowlist precisely so a
        // coding backend cannot pick up the daemon's secrets. So resolve it from the Key
        // Chest and inject it deliberately — the same path goose's provider key takes.
        // Absent slot = local drive = nothing to inject, which is the common case.
        // AuthResolver is the only abstraction that crosses into the Key Chest from the
        // coding subsystem — adapters never read keys directly.
        try {
            if (CodingBackendBootstrap.authResolver().resolveAuth(NAME)
                    instanceof AuthMode.ApiKey key
                    && key.value() != null && !key.value().isBlank()) {
                env.put("CODEZAIKU_AUTH_TOKEN", key.value());
            }
        } catch (Exception e) {
            // No resolver wired (bare boot, tests) or no slot set: a local drive needs
            // no token, so this must never stop a run.
            log.debug("[codezaiku] no auth token available: {}", e.toString());
        }
        // CodeZaiku's operator knobs: EgressGate scrubs the inherited environment,
        // so an operator's export dies at the subprocess boundary unless carried
        // across here deliberately. Found live (release bake 2026-08-24) with
        // CODEZAIKU_CTX; the shell-timeout pair joined in their 289d3046 — their
        // guidance: a legitimately minutes-long command routed through the agentic
        // path should DECLARE its cap rather than rely on the name-based
        // heavy-step guess. Knobs only, never credentials — the auth token above
        // has its own deliberate route.
        for (var knob : OPERATOR_KNOBS) {
            var v = System.getenv(knob);
            if (v != null && !v.isBlank()) env.put(knob, v);
        }
        return env;
    }

    /** CodeZaiku env knobs an operator may set on the host and expect to reach the
     *  subprocess. A closed list, not a CODEZAIKU_* wildcard: the wildcard would
     *  sweep CODEZAIKU_API_KEY past the EgressGate's whole reason for existing. */
    static final List<String> OPERATOR_KNOBS = List.of(
        "CODEZAIKU_CTX",
        "CODEZAIKU_SHELL_TIMEOUT_SEC",
        "CODEZAIKU_SHELL_HEAVY_TIMEOUT_SEC");

    /** Authoritative under {@code key}; advisory under {@code key_DEFAULT}; never blank. */
    private static void putRouting(Map<String, String> env, String key, String value,
                                   boolean chosenByOperator) {
        if (value == null || value.isBlank()) return;
        env.put(chosenByOperator ? key : key + "_DEFAULT", value);
    }

    /**
     * The directory this run may write in.
     *
     * <p>Workspace IS the subprocess CWD, and "null → inherit JVM cwd" means the INSTALL
     * ROOT on a packaged node. That is the defect {@link CodingWorkspace} exists to
     * close — goose wrote nine files into {@code /opt/wyrdsekai} before it did — and the
     * resolver was wired into goose alone while its own javadoc claimed every backend
     * shared it. CodeZaiku is the one taking over as default here, so it would have
     * inherited the original bug on the day of the switch.
     */
    static File resolveWorkdir(TaskSpec spec, String taskId) {
        return CodingWorkspace.forTask(
            spec != null ? spec.workspaceHint() : null, taskId);
    }

    // -- output parsing (shared shape with the future ACP client) ---------

    /**
     * Locate the result document in stdout: the whole document, or the
     * last parseable JSON line (stream-json tail). Returns null when
     * nothing parses — the caller treats that as FAILED by contract.
     */
    public static JsonNode parseResultJson(String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        try {
            var node = MAPPER.readTree(stdout);
            if (node != null && node.isObject()) return node;
        } catch (Exception ignored) {
            // fall through to line scan
        }
        JsonNode last = null;
        for (var line : stdout.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            try {
                var node = MAPPER.readTree(line);
                if (node != null && node.isObject()) last = node;
            } catch (Exception ignored) {
                // non-JSON line — fine
            }
        }
        return last;
    }

    /**
     * Build the SourceArtifact + sibling BuildArtifact pair from the agreed
     * fields. Sibling rides under {@code backendMetadata.__sibling_build}
     * — the same magic key {@link CodeZaikuEventAdapter} used, so
     * {@code CodingTaskItemBridge} places both without changes.
     */
    private List<CodingArtifact> toArtifacts(UUID taskId, TaskSpec spec, JsonNode parsed) {
        var files = new ArrayList<String>();
        if (parsed.has("files") && parsed.get("files").isArray()) {
            int kept = 0;
            // CAP, and say so. Their default arm returned 4,320 node_modules files in
            // files[] when the agent created node_modules in a repo with no .gitignore.
            // --mode artifact avoids the situation; it does not fix files[] for a run that
            // invokes a package manager. Whether CodeZaiku filters is a contract decision
            // they have not made, so it is bounded here: dependency trees are never the
            // deliverable.
            // CodeZaiku now prunes dependency trees itself and says so: when
            // `filesExcluded` is present the list was pruned and the number is how many.
            // Our cap stays as a backstop for any build that predates that, and the log
            // says who pruned what rather than implying we did.
            var excluded = parsed.path("filesExcluded").asInt(0);
            if (excluded > 0) {
                log.info("[codezaiku] task {} — CodeZaiku pruned {} dependency file(s) from files[]",
                    taskId, excluded);
            }
            if (parsed.get("files").size() > FILES_CAP) {
                log.warn("[codezaiku] task {} reported {} files — capping at {} and dropping "
                    + "dependency trees on our side", taskId, parsed.get("files").size(), FILES_CAP);
            }
            for (var f : parsed.get("files")) {
                var rel = f.asText();
                if (isDependencyPath(rel)) continue;     // never the deliverable
                if (++kept > FILES_CAP) break;             // see FILES_CAP
                files.add(rel);
            }
        }
        var workspace = parsed.hasNonNull("workspacePath")
            ? parsed.get("workspacePath").asText()
            // Never report the process's own directory as the workspace: on a packaged
            // node that is the install root, and the bridge then scans it for items.
            : CodingWorkspace.pathFor(
                spec != null ? spec.workspaceHint() : null, taskId.toString());
        var gitRef = parsed.hasNonNull("gitRef") ? parsed.get("gitRef").asText() : null;
        var status = parsed.path("status").asText("untested");

        var buildMeta = new HashMap<String, Object>();
        buildMeta.put("source", "codezaiku-cli");
        parsed.fields().forEachRemaining(e -> {
            switch (e.getKey()) {
                case "files", "workspacePath", "gitRef", "status",
                     "testsPassed", "testsFailed", "taskId" -> { /* modeled */ }
                default -> buildMeta.put(e.getKey(),
                    e.getValue().isValueNode() ? e.getValue().asText()
                                               : e.getValue().toString());
            }
        });

        var build = new BuildArtifact(
            UUID.randomUUID(), NAME, taskId.toString(), null,
            status,
            parsed.path("testsPassed").asInt(0),
            parsed.path("testsFailed").asInt(0),
            Instant.now(), Map.copyOf(buildMeta));

        var srcMeta = new HashMap<String, Object>();
        srcMeta.put("source", "codezaiku-cli");
        srcMeta.put("__sibling_build", build);

        var src = new SourceArtifact(
            UUID.randomUUID(), NAME, taskId.toString(),
            workspace, List.copyOf(files), gitRef,
            Instant.now(), Map.copyOf(srcMeta));
        return List.of(src, build);
    }

    private static String summarise(JsonNode parsed) {
        int files = parsed.has("files") && parsed.get("files").isArray()
            ? parsed.get("files").size() : 0;
        return "CodeZaiku " + parsed.path("status").asText("done")
            + ": " + files + " file(s), tests "
            + parsed.path("testsPassed").asInt(0) + " passed / "
            + parsed.path("testsFailed").asInt(0) + " failed";
    }

    private static TaskResult failed(UUID taskId, String message, long started) {
        return new TaskResult(taskId, NAME, TaskStatus.FAILED, message,
            List.of(), 0L, System.currentTimeMillis() - started);
    }
}
