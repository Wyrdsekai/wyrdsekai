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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cline (cline.bot) implementation of {@link CodingTaskBackend}, targeting
 * the <b>v2.18+ npm CLI</b> (cline.bot/blog/announcing-cline-cli-2-0).
 *
 * <p>Pre-2026-05-04 this adapter spawned the binary as
 * {@code cline task --message='<spec>' --no-interactive} and parsed
 * unspecified gRPC-event JSON. None of those flags exist in the upstream
 * CLI; the contract was reconstructed from out-of-date research notes.
 * This rewrite reflects the reality from
 * docs.cline.bot/cline-cli/three-core-flows:</p>
 * <ul>
 *   <li>argv: {@code cline --json "<task>"} (or {@code cline -y "<task>"}
 *       for yolo mode); the prompt is <b>positional</b>, not a flag.</li>
 *   <li>JSON event schema is flat: {@code {type: "ask"|"say", text:
 *       <string>, ts: <epoch_ms>, reasoning?, partial?}}. Files are
 *       mentioned <i>inside</i> {@code text} strings; the parser
 *       extracts paths via regex on common patterns.</li>
 *   <li>auth: {@code cline auth -p <provider> -k <key>} pre-stages
 *       credentials; runtime falls back to standard provider env vars
 *       (e.g. {@code ANTHROPIC_API_KEY}) keyed off
 *       {@code coding.backends.cline.provider}. The pre-2026-05
 *       wyrdsekai indirection {@code CLINE_PROVIDER_KEY} was an
 *       invention and is no longer used.</li>
 * </ul>
 *
 * <p>The {@code MAX_PARSE_ERRORS} defensive wrapper from the pre-2026-05
 * adapter has been dropped: it was defending against an upstream gRPC-
 * instability claim that no longer applies for the npm-distributed CLI.
 * Malformed JSON lines are still tolerated as no-ops; the adapter
 * doesn't fail the task on schema drift.</p>
 *
 * <p>Tier: {@link BackendTier#CLOUD_PAID}. Configuration is read via
 * {@link ClineRuntimeConfig}.</p>
 */
public final class ClineBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link ClineEventAdapter#namespace()}. */
    public static final String NAME = "cline";

    private static final Logger log = LoggerFactory.getLogger(ClineBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Cline CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 2000L;

    /**
     * Patterns for extracting file paths from Cline event {@code text}
     * fields. The schema is "files mentioned inside prose" — we surface
     * what we can without inventing structured fields.
     */
    private static final List<Pattern> FILE_PATTERNS = List.of(
        Pattern.compile("(?:Edited|Editing|Created|Modified|Wrote)\\s+([\\w./_-]+)"),
        Pattern.compile("^([\\w./_-]+):\\d+:?\\s", Pattern.MULTILINE),
        Pattern.compile("```\\w*\\s+([\\w./_-]+)$", Pattern.MULTILINE)
    );

    private final ClineRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public ClineBackend(ClineRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public ClineBackend(ClineRuntimeConfig config,
                        AuthResolver authResolver,
                        ProcessRunner runner) {
        this.config = config != null ? config : ClineRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "wyrd coding login cline",
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
                "Cline backend is disabled in config", started));
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
                "Failed to construct Cline invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);

        Thread.ofVirtual().name("cline-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, config.maxWallclock(),
                    CodingWorkspace.forTask(
                        spec != null ? spec.workspaceHint() : null,
                        taskId.toString()));
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Cline task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Cline exited with code " + result.exitCode()
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
                    "Cline subprocess error: " + e.getMessage(), started));
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
                    log.debug("Cline --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Cline binary not found at '{}' — "
                    + "run `wyrd coding install cline` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Cline health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        long base = 500L;
        if (spec == null || spec.taskType() == null) return Math.min(base, MAX_CU_ESTIMATE);
        return switch (spec.taskType().toLowerCase(Locale.ROOT)) {
            case "explore", "explore_unknown_repo", "survey", "research" -> Math.min(base / 2, MAX_CU_ESTIMATE);
            case "implement_feature", "implement", "build" -> Math.min(base * 2, MAX_CU_ESTIMATE);
            case "refactor" -> Math.min((long) (base * 1.5), MAX_CU_ESTIMATE);
            default -> Math.min(base, MAX_CU_ESTIMATE);
        };
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public ClineRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list. Wire shape (Cline CLI 2.18+):
     * {@code cline --json "<task>" [extra flags]} or
     * {@code cline -y "<task>" [extra flags]} for yolo mode.
     *
     * <p>The prompt is <b>positional</b> — a hangover from the SDK
     * pattern (cf. Claude Code's {@code claude -p}). The pre-2026-05
     * adapter used {@code cline task --message=... --no-interactive
     * --provider=...} flags; none exist upstream.</p>
     */
    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add(config.yolo() ? "-y" : "--json");

        // Wyrdsekai items-as-tools contract — prepend the OpenHands
        // preamble (kept there as the canonical source) so Cline emits
        // the same single-{@code .js}-with-{@code exports.manifest}
        // shape every backend must produce. See OpenHandsBackend's
        // ITEMS_AS_TOOLS_PREAMBLE_CWD for the full rationale.
        var description = spec != null ? spec.description() : null;
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        args.add(OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
            + "\n\n--- TASK ---\n" + promptBody);

        // Note: --provider / --workspace flags don't exist on the
        // upstream CLI. Provider selection happens via `cline auth -p`
        // pre-staging or via standard env vars; workspace is the cwd.
        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Build the env for the subprocess. Cline reads standard provider
     * env vars (ANTHROPIC_API_KEY / OPENAI_API_KEY / etc.) directly when
     * not pre-staged via {@code cline auth}. The adapter maps
     * {@code config.provider()} → the right upstream env var via
     * {@link #providerKeyEnvVarFor}; the {@code CLINE_PROVIDER_KEY}
     * indirection used pre-2026-05 was a wyrdsekai invention.
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            String envVar = providerKeyEnvVarFor(config.provider());
            if (envVar != null) {
                env.put(envVar, key.value());
            } else {
                log.debug("[Cline] provider={} not in upstream env-var matrix; "
                    + "ApiKey resolved but discarded — pre-stage via `cline auth -p`.",
                    config.provider());
            }
        }
        return env;
    }

    /**
     * Map a Cline provider name to the env var Cline itself reads.
     * {@code null} for unknown / no-key providers (e.g. local).
     */
    public static String providerKeyEnvVarFor(String provider) {
        if (provider == null) return null;
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "openai" -> "OPENAI_API_KEY";
            case "google", "gemini" -> "GOOGLE_API_KEY";
            case "local", "chatgpt-passthrough" -> null;
            default -> null;
        };
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Cline's stdout into {@link CodingArtifact}s.
     *
     * <p>The flat-event schema is {@code {type, text, ts, reasoning?,
     * partial?}}. Files mentioned inside {@code text} are extracted via
     * regex on a small set of common patterns (see
     * {@link #FILE_PATTERNS}). Malformed JSON lines are tolerated as
     * no-ops — the {@code MAX_PARSE_ERRORS} defensive wrapper from the
     * pre-2026-05 adapter has been removed.</p>
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
                JsonNode node = null;
                try {
                    node = MAPPER.readTree(line);
                } catch (Exception _) {
                    // Malformed JSON line — tolerated. Try the line as
                    // plain prose for path extraction.
                    extractFilesFromText(line, files);
                    continue;
                }
                // Defensive: also accept the legacy structured-field
                // shape (so a household upgrading from a manifest with
                // older Cline-event mocks doesn't lose artifacts).
                extractFilesFromStructuredFields(node, files);
                // The current Cline CLI emits {type, text, ...} where
                // file paths live inside `text`.
                String text = node.path("text").asText("");
                if (!text.isBlank()) {
                    extractFilesFromText(text, files);
                }
            }
        }

        var seen = new LinkedHashSet<>(files);
        var dedupedFiles = new ArrayList<>(seen);

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "cline");
        metadata.put("backend", NAME);
        if (config.provider() != null) metadata.put("provider", config.provider());
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
            null, // Cline doesn't surface git ref in JSON output
            Instant.now(),
            Map.copyOf(metadata)
        );
        return List.of(src);
    }

    /**
     * Apply {@link #FILE_PATTERNS} to a prose blob and accumulate any
     * file paths the regex catches. Best-effort; the schema doesn't
     * give us structured file fields.
     */
    static void extractFilesFromText(String text, List<String> out) {
        if (text == null || text.isBlank()) return;
        for (Pattern p : FILE_PATTERNS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String path = m.group(1);
                if (path != null && !path.isBlank()) out.add(path);
            }
        }
    }

    /**
     * Walk a JSON node looking for explicit file fields (legacy /
     * speculative future shape — current Cline doesn't emit these, but
     * tolerating them costs nothing and helps the adapter survive a
     * future schema reshuffle).
     */
    static void extractFilesFromStructuredFields(JsonNode node, List<String> out) {
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
            return "Cline completed the " + taskType + " (no artifacts captured).";
        }
        return "Cline completed the " + taskType + ", touching "
            + files + " file(s).";
    }

    // -- ProcessRunner indirection ----------------------------------------

    /** Result of a subprocess invocation. */
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
