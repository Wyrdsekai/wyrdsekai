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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Continue (continue.dev) implementation of {@link CodingTaskBackend},
 * targeting the npm CLI {@code @continuedev/cli} v1.5+ (binary name
 * {@code cn}). Source: docs.continue.dev/cli/quickstart +
 * docs.continue.dev/guides/cli.
 *
 * <p>Pre-2026-05-04 the adapter spawned {@code cn run --message='<spec>'
 * --headless [--agent <name>]} — none of those flags exist in the
 * upstream CLI. The reconciliation reflects the actual surface:</p>
 * <ul>
 *   <li>argv: {@code cn -p "<prompt>" [extra flags]}; the prompt is
 *       <b>positional</b> after the {@code -p} (print-mode) flag,
 *       matching Claude Code's pattern. Outputs only the final response
 *       on stdout.</li>
 *   <li>auth: first run prompts browser OAuth; no documented
 *       {@code cn login} subcommand exists. Headless hosts use
 *       {@code CONTINUE_API_KEY}.</li>
 *   <li>{@code --agent <name>}: not documented in current upstream
 *       quickstart; only emitted when
 *       {@code coding.backends.continue.agent} is set.</li>
 * </ul>
 *
 * <p>Tier: {@link BackendTier#CLOUD_PAID} per SPEC §9.2 — Continue Hub
 * agents typically call paid LLM APIs.</p>
 *
 * <p>Configuration is read via {@link ContinueRuntimeConfig}; see
 * {@code application.conf} block at
 * {@code wyrdsekai.coding.backends.continue}.</p>
 */
public final class ContinueBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link ContinueEventAdapter#namespace()}. */
    public static final String NAME = "continue";

    private static final Logger log = LoggerFactory.getLogger(ContinueBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Continue CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 2000L;

    private final ContinueRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public ContinueBackend(ContinueRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public ContinueBackend(ContinueRuntimeConfig config,
                           AuthResolver authResolver,
                           ProcessRunner runner) {
        this.config = config != null ? config : ContinueRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "wyrd coding login continue",
                "AuthResolver not wired"));
        this.runner = runner != null ? runner : new DefaultProcessRunner();
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
                "Continue backend is disabled in config", started));
            return future;
        }

        var auth = authResolver.resolveAuth(NAME);
        if (auth instanceof AuthMode.AuthMissing missing) {
            future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                "LOGIN_REQUIRED: " + missing.reason()
                    + " (recovery: " + missing.recoveryCommand() + ")",
                List.of(), 0L, System.currentTimeMillis() - started));
            return future;
        }

        List<String> args;
        try {
            args = buildArgs(spec);
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct Continue invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);

        Thread.ofVirtual().name("continue-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, config.maxWallclock(),
                    CodingWorkspace.forTask(
                        spec != null ? spec.workspaceHint() : null,
                        taskId.toString()));
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Continue task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Continue exited with code " + result.exitCode()
                            + (result.stderr().isBlank() ? ""
                                : ": " + result.stderr().trim()),
                        List.of(), 0L, durationMs));
                    return;
                }

                var artifacts = parseArtifacts(taskId, spec, result);

                // CONTRACT REPAIR — the turn goose and CodeZaiku get. This lived inside
                // GooseBackend, so a file the bridge would refuse was silently downgraded
                // to a plain artifact for every OTHER backend. Same preamble, same
                // bridge, same defects: the repair belongs to all of them.
                ItemContractRepair.repairRun(artifacts, null, taskId.toString(),
                    Instant.ofEpochMilli(started),
                    ItemContractRepair.rerunWithPrompt(repairArgs -> {
                        try {
                            var r = runner.run(repairArgs, env, config.maxWallclock());
                            return !r.timedOut() && r.exitCode() == 0;
                        } catch (Exception e) {
                            return false;
                        }
                    }, args),
                    spec == null ? null : spec.description());

                // Re-parse AFTER the repair so the cache holds the fixed file.
                artifacts = parseArtifacts(taskId, spec, result);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, artifacts), List.copyOf(ids), 0L, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Continue subprocess error: " + e.getMessage(), started));
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
                    HEALTH_PROBE_TIMEOUT);
                if (result.timedOut() || result.exitCode() != 0) {
                    log.debug("Continue --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Continue binary not found at '{}' — "
                    + "run `wyrd coding install continue` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Continue health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        long base = 500L;
        if (spec == null || spec.taskType() == null) return Math.min(base, MAX_CU_ESTIMATE);
        return switch (spec.taskType().toLowerCase()) {
            case "explore", "explore_unknown_repo", "survey", "research" -> Math.min(base / 2, MAX_CU_ESTIMATE);
            case "implement_feature", "implement", "build" -> Math.min(base * 2, MAX_CU_ESTIMATE);
            case "refactor" -> Math.min((long) (base * 1.5), MAX_CU_ESTIMATE);
            default -> Math.min(base, MAX_CU_ESTIMATE);
        };
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public ContinueRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list. Wire shape (Continue CLI v1.5+):
     * {@code cn -p "<prompt>" [--agent <name>] [extra flags]}.
     *
     * <p>The prompt is <b>positional</b> after the {@code -p}
     * print-mode flag (matches Claude Code's pattern). The {@code -p}
     * flag tells {@code cn} to emit only the final response and
     * non-interactive structure on stdout. {@code --agent <name>} is
     * only emitted when the steward has configured
     * {@code coding.backends.continue.agent}; current upstream
     * quickstart docs don't list this flag, so households that need
     * named agents should verify the binding via {@code cn --help}
     * before enabling.</p>
     *
     * <p>Drops the pre-2026-05 {@code --message=}, {@code --headless},
     * {@code --workspace} flags (none exist upstream).</p>
     */
    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("-p");

        // Wyrdsekai items-as-tools contract — prepend the OpenHands
        // preamble (canonical source) so Continue emits the same
        // single-{@code .js}-with-{@code exports.manifest} shape every
        // backend must produce. See OpenHandsBackend's
        // ITEMS_AS_TOOLS_PREAMBLE_CWD for the full rationale.
        var description = spec != null ? spec.description() : null;
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        args.add(OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
            + "\n\n--- TASK ---\n" + promptBody);

        if (config.agent() != null && !config.agent().isBlank()) {
            // Document hazard: --agent <name> is not in the public
            // quickstart docs as of May 2026; only emit when the
            // steward has explicitly set it.
            args.add("--agent");
            args.add(config.agent());
        }

        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Build the env for the subprocess. The api-key (when {@link
     * AuthMode.ApiKey} resolved) lands in {@code CONTINUE_API_KEY}.
     * OAuth path leaves env empty — the upstream CLI reads its own
     * {@code ~/.continue/auth.json}.
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            env.put("CONTINUE_API_KEY", key.value());
        }
        return env;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Continue's stdout into {@link CodingArtifact}s. Mirrors
     * {@link OpenCodeBackend#parseArtifacts}'s tolerant approach.
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var files = new ArrayList<String>();
        // The workspace REPORTED on the artifact is what CodingTaskItemBridge scans for
        // the item's .js. Falling back to the process directory pointed that scan at the
        // install root on a packaged node — the same defect as running there.
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());

        var stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            for (var line : stdout.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // not every line is JSON — ignore
                }
            }
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
        metadata.put("source", "continue");
        metadata.put("backend", NAME);
        if (config.agent() != null) metadata.put("agent", config.agent());
        if (result.stdout() != null && !result.stdout().isBlank()) {
            var snippet = result.stdout();
            if (snippet.length() > 2000) {
                snippet = snippet.substring(0, 2000) + "…[truncated]";
            }
            metadata.put("raw_stdout", snippet);
        }

        var src = new SourceArtifact(
            UUID.randomUUID(),
            NAME,
            taskId.toString(),
            workspace,
            List.copyOf(dedupedFiles),
            null, // Continue doesn't surface git ref in JSON output
            Instant.now(),
            Map.copyOf(metadata)
        );
        return List.of(src);
    }

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

    private static String summarise(TaskSpec spec, List<CodingArtifact> artifacts) {
        int files = 0;
        for (var a : artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        if (artifacts.isEmpty()) {
            return "Continue completed the " + taskType + " (no artifacts captured).";
        }
        return "Continue completed the " + taskType + ", touching "
            + files + " file(s).";
    }

    // -- ProcessRunner indirection ----------------------------------------

    public record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut) {}

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessResult run(List<String> args, Map<String, String> env,
                          Duration timeout) throws IOException, InterruptedException;

        /**
         * Workspace-aware overload. Defaults to discarding the directory so existing
         * three-arg test lambdas keep compiling; {@link DefaultProcessRunner} overrides
         * it and actually sets the subprocess CWD.
         *
         * <p>Without a directory the subprocess inherits the JVM's — the INSTALL ROOT on
         * a packaged node. See {@link CodingWorkspace}.
         */
        default ProcessResult run(List<String> args, Map<String, String> env,
                                  Duration timeout, File workdir)
                throws IOException, InterruptedException {
            return run(args, env, timeout);
        }
    }

    /** Default {@link ProcessRunner} — spawns the real subprocess. */
    public static class DefaultProcessRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  Duration timeout) throws IOException, InterruptedException {
            return run(args, env, timeout, null);
        }

        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  Duration timeout, File workdir)
                throws IOException, InterruptedException {
            // route env through the shared egress gate
            // (scrubs SSH_AUTH_SOCK/ambient keys; enforcing by default).
            var pb = EgressGate.gatedProcessBuilder(args, env);
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
}
