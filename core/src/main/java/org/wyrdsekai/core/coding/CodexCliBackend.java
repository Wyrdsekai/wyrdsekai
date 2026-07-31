package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.stream.Stream;

/**
 * OpenAI Codex CLI implementation of {@link CodingTaskBackend}, targeting
 * the Rust binary distributed under {@code openai/codex} GitHub releases.
 * Headless mode per
 * <a href="https://developers.openai.com/codex/noninteractive">developers.openai.com/codex/noninteractive</a>.
 *
 * <p><b>Wire shape (Phase 2e, May 2026)</b>:
 * {@code codex exec "<prompt>" --json [--provider …] [extra flags]}.
 * NB: it's {@code codex exec}, not {@code codex run} — the latter doesn't
 * exist as a non-interactive subcommand. Stdout is JSON-line keyed by
 * {@code type}; the final event carries {@code result} or {@code summary}.</p>
 *
 * <p><b>Auth</b> — dual-path per SPEC §9.2:
 * <ul>
 *   <li>{@link AuthMode.OAuthSession} → no extra args; {@code codex} reads
 *       {@code ~/.codex/auth.json} for the device-flow OAuth credentials.
 *       Pass nothing key-related.</li>
 *   <li>{@link AuthMode.ApiKey} → set {@code OPENAI_API_KEY} (and the
 *       {@code CODEX_API_KEY} alias upstream honours inside
 *       {@code codex exec}) on the subprocess env. There is NO
 *       {@code --api-key} flag — the upstream argv parser doesn't have
 *       one.</li>
 *   <li>{@link AuthMode.AuthMissing} → return {@code TaskStatus.FAILED}
 *       with {@code LOGIN_REQUIRED}.</li>
 * </ul>
 *
 * <p><b>Tier</b>: {@link BackendTier#CLOUD_PAID}. CU estimate: 200 baseline,
 * scaled by description length (mirrors {@link ClaudeSdkBackend}).</p>
 *
 * <p>Configuration is read via {@link CodexCliRuntimeConfig}.</p>
 */
public final class CodexCliBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link CodexCliEventAdapter#namespace()}. */
    public static final String NAME = "codex";

    private static final Logger log = LoggerFactory.getLogger(CodexCliBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Codex CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 5000L;

    /** Baseline CU per task before length scaling. */
    private static final long BASELINE_CU = 200L;

    private final CodexCliRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public CodexCliBackend(CodexCliRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public CodexCliBackend(CodexCliRuntimeConfig config,
                           AuthResolver authResolver,
                           ProcessRunner runner) {
        this.config = config != null ? config : CodexCliRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "wyrd coding login codex",
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
                "Codex CLI backend is disabled in config", started));
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
                "Failed to construct Codex invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);

        Thread.ofVirtual().name("codex-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Codex task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Codex exited with code " + result.exitCode()
                            + (result.stderr().isBlank() ? ""
                                : ": " + result.stderr().trim()),
                        List.of(), 0L, durationMs));
                    return;
                }

                var artifacts = parseArtifacts(taskId, spec, result);
                artifactCache.put(taskId.toString(), artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : artifacts) ids.add(a.artifactId());

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, artifacts), List.copyOf(ids), 0L, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Codex subprocess error: " + e.getMessage(), started));
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
                    log.debug("Codex --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Codex binary not found at '{}' — "
                    + "run `wyrd coding install codex` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Codex health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        if (spec == null) return Math.min(BASELINE_CU, MAX_CU_ESTIMATE);
        long base = BASELINE_CU;
        if (spec.description() != null) {
            base += (spec.description().length() / 500) * 100L;
        }
        if (spec.taskType() != null) {
            base = switch (spec.taskType().toLowerCase(Locale.ROOT)) {
                case "explore", "explore_unknown_repo", "survey", "research" -> base / 2;
                case "implement_feature", "implement", "build" -> base * 2;
                case "refactor" -> (long) (base * 1.5);
                default -> base;
            };
        }
        return Math.min(base, MAX_CU_ESTIMATE);
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public CodexCliRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list. Wire shape:
     * {@code codex exec "<description>" --json [--provider <p>] [extra flags]}.
     * The api-key value never appears in argv — it travels via env.
     *
     * <p>Pre-2026-05 attempts to use {@code codex run} or {@code --api-key}
     * are wrong: the non-interactive subcommand is {@code exec}, and there
     * is no API-key flag (the binary reads {@code OPENAI_API_KEY} /
     * {@code CODEX_API_KEY} from env).</p>
     */
    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("exec");

        // Wyrdsekai items-as-tools contract — prepend the OpenHands
        // preamble (canonical source) so Codex CLI emits the same
        // single-{@code .js}-with-{@code exports.manifest} shape every
        // backend must produce. See OpenHandsBackend's
        // ITEMS_AS_TOOLS_PREAMBLE for the full rationale.
        var description = spec != null ? spec.description() : null;
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        args.add(OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE
            + "\n\n--- TASK ---\n" + promptBody);

        args.add("--json");

        if (config.provider() != null && !config.provider().isBlank()) {
            args.add("--provider");
            args.add(config.provider());
        }

        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Build the env for the subprocess. On {@link AuthMode.ApiKey} we
     * inject {@code OPENAI_API_KEY} (and the {@code CODEX_API_KEY}
     * alias upstream honours inside {@code codex exec}). OAuth path
     * leaves env empty so the upstream CLI reads its own
     * {@code ~/.codex/auth.json}.
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            env.put("OPENAI_API_KEY", key.value());
            // codex also honours CODEX_API_KEY inside `codex exec` per
            // upstream docs; setting both is harmless and survives a
            // future contract tightening.
            env.put("CODEX_API_KEY", key.value());
        }
        return env;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Codex's stdout into {@link CodingArtifact}s. JSON-line
     * format keyed by {@code type}; the final event carries
     * {@code result} or {@code summary}. We accumulate file paths from
     * any event mentioning them.
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var files = new ArrayList<String>();
        var workspace = spec != null && spec.workspaceHint() != null
            ? spec.workspaceHint()
            : System.getProperty("user.dir", ".");
        String finalSummary = null;

        var stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            for (var line : stdout.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    String type = node.path("type").asText("");
                    if ("result".equals(type) || "summary".equals(type)
                            || "complete".equals(type) || "finished".equals(type)) {
                        var sumText = node.path("summary").asText(
                            node.path("result").asText(""));
                        if (!sumText.isBlank()) finalSummary = sumText;
                    }
                    extractFiles(node, files);
                } catch (Exception _) {
                    // Not every line is valid JSON; tolerate gracefully.
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
        metadata.put("source", "codex");
        metadata.put("backend", NAME);
        if (config.provider() != null) metadata.put("provider", config.provider());
        if (finalSummary != null) metadata.put("final_summary", finalSummary);
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
            null,
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
        if (artifacts.isEmpty() || files == 0) {
            return "Codex completed the " + taskType + " (no file artifacts captured).";
        }
        return "Codex completed the " + taskType + ", touching "
            + files + " file(s).";
    }

    // -- ProcessRunner indirection ----------------------------------------

    public record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut) {}

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessResult run(List<String> args, Map<String, String> env,
                          Duration timeout) throws IOException, InterruptedException;
    }

    /** Default {@link ProcessRunner} — spawns the real subprocess. */
    public static class DefaultProcessRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  Duration timeout) throws IOException, InterruptedException {
            // route env through the shared egress gate
            // (scrubs SSH_AUTH_SOCK/ambient keys; enforcing by default).
            var pb = EgressGate.gatedProcessBuilder(args, env);
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
