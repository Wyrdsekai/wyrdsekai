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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Google Gemini CLI implementation of {@link CodingTaskBackend}, targeting
 * the npm package {@code @google/gemini-cli} v0.40.1+ (CVSS-10 RCE floor,
 * patched 2026-04-30). Headless mode per
 * <a href="https://geminicli.com/docs/cli/headless/">geminicli.com/docs/cli/headless</a>.
 *
 * <p><b>Wire shape (Phase 2e, May 2026)</b>:
 * {@code gemini -p "<prompt>" [-m <model>] [--temperature <t>]
 * [--trust] [extra flags]}.</p>
 *
 * <p><b>Auth (May 2026)</b>: API-key only for headless hosts. Upstream
 * OAuth is browser-only as of May 2026 (issue
 * google-gemini/gemini-cli#1696 unresolved); the resolver should never
 * return {@link AuthMode.OAuthSession} for {@code gemini-cli} (the
 * manifest's {@code oauth.headless_supported = false} drives the
 * resolver to skip the OAuth probe and go straight to API key). The
 * adapter still handles all three auth modes defensively.</p>
 *
 * <p><b>Workspace trust</b> (post-RCE patch): headless mode requires
 * explicit acceptance of the workspace folder via {@code --trust}. The
 * adapter only emits this flag when the household has set
 * {@code coding.backends.gemini-cli.trust_workspace = true} — opt-in,
 * deliberate.</p>
 *
 * <p><b>Tier</b>: {@link BackendTier#CLOUD_PAID}. CU estimate weighs
 * prompt length more heavily than the other paid backends — Gemini's
 * 1M-context window can swallow huge prompts and the cost scales
 * linearly with input tokens.</p>
 *
 * <p>Configuration is read via {@link GeminiCliRuntimeConfig}.</p>
 */
public final class GeminiCliBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link GeminiCliEventAdapter#namespace()}. */
    public static final String NAME = "gemini-cli";

    private static final Logger log = LoggerFactory.getLogger(GeminiCliBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Gemini CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 5000L;

    /** Baseline CU per task before length scaling. */
    private static final long BASELINE_CU = 200L;

    private final GeminiCliRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public GeminiCliBackend(GeminiCliRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public GeminiCliBackend(GeminiCliRuntimeConfig config,
                            AuthResolver authResolver,
                            ProcessRunner runner) {
        this.config = config != null ? config : GeminiCliRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "set GEMINI_API_KEY in your Key Chest "
                    + "(no headless OAuth flow as of May 2026)",
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
                "Gemini CLI backend is disabled in config", started));
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
                "Failed to construct Gemini CLI invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);

        Thread.ofVirtual().name("gemini-cli-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Gemini CLI task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Gemini CLI exited with code " + result.exitCode()
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
                    "Gemini CLI subprocess error: " + e.getMessage(), started));
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
                    log.debug("Gemini CLI --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Gemini CLI binary not found at '{}' — "
                    + "run `wyrd coding install gemini-cli` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Gemini CLI health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        if (spec == null) return Math.min(BASELINE_CU, MAX_CU_ESTIMATE);
        // Gemini CLI's distinguishing feature is its 1M-context window;
        // long-prompt jobs are pricier than other backends. Weight prompt
        // length more aggressively than Claude/Codex (each ~250 chars
        // adds 100 CU vs ~500 chars elsewhere).
        long base = BASELINE_CU;
        if (spec.description() != null) {
            base += (spec.description().length() / 250) * 100L;
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
    public GeminiCliRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list. Wire shape:
     * {@code gemini -p "<prompt>" -m <model> [--temperature <t>]
     * [--trust] [extra flags]}.
     *
     * <p>The api-key value never appears in argv — it travels via
     * {@code GEMINI_API_KEY} env.</p>
     */
    public List<String> buildArgs(TaskSpec spec) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("-p");

        // Wyrdsekai items-as-tools contract — prepend the OpenHands
        // preamble (canonical source) so Gemini CLI emits the same
        // single-{@code .js}-with-{@code exports.manifest} shape every
        // backend must produce. See OpenHandsBackend's
        // ITEMS_AS_TOOLS_PREAMBLE for the full rationale.
        var description = spec != null ? spec.description() : null;
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        args.add(OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE
            + "\n\n--- TASK ---\n" + promptBody);

        if (config.model() != null && !config.model().isBlank()) {
            args.add("-m");
            args.add(config.model());
        }

        if (config.temperature() >= 0.0) {
            args.add("--temperature");
            args.add(String.valueOf(config.temperature()));
        }

        if (config.trustWorkspace()) {
            // Post-RCE Gemini CLI requires accept-trust for headless
            // mode; the upstream flag is `--trust` (or the equivalent
            // accept-trust mechanism). Steward opts in deliberately.
            args.add("--trust");
        }

        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Build the env for the subprocess. On {@link AuthMode.ApiKey} we
     * inject {@code GEMINI_API_KEY} (and the {@code GOOGLE_API_KEY}
     * alias upstream also reads). OAuth path is not expected on headless
     * hosts (see class javadoc); we leave env empty as a defensive
     * fall-through.
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            env.put("GEMINI_API_KEY", key.value());
            // Gemini CLI also reads GOOGLE_API_KEY as an alternate; setting
            // both keeps the adapter robust against upstream config-resolution
            // tweaks.
            env.put("GOOGLE_API_KEY", key.value());
        }
        return env;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate Gemini CLI's stdout into {@link CodingArtifact}s. The
     * CLI's output is plain prose by default; we scan for file-mention
     * patterns and stash the full stdout in metadata.
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var files = new ArrayList<String>();
        var workspace = spec != null && spec.workspaceHint() != null
            ? spec.workspaceHint()
            : System.getProperty("user.dir", ".");

        var stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            // Try line-by-line JSON first (gemini --json may emit NDJSON).
            for (var line : stdout.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // Not JSON — fall through to prose scan.
                }
            }
            // Always run a prose scan too — Gemini's default output is
            // natural-language descriptions of edits. Case-insensitive
            // because models inflect verbs both ways.
            var pattern = Pattern.compile(
                "(?:edited|editing|created|modified|wrote)\\s+([\\w./_-]+)",
                Pattern.CASE_INSENSITIVE);
            var m = pattern.matcher(stdout);
            while (m.find()) {
                String path = m.group(1);
                if (path != null && !path.isBlank()) files.add(path);
            }
        }

        var seen = new LinkedHashSet<>(files);
        var dedupedFiles = new ArrayList<>(seen);

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "gemini-cli");
        metadata.put("backend", NAME);
        metadata.put("model", config.model());
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
            return "Gemini CLI completed the " + taskType + " (no file artifacts captured).";
        }
        return "Gemini CLI completed the " + taskType + ", touching "
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
