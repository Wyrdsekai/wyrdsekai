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
import java.nio.file.Path;
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
 * Anthropic Claude Code SDK ({@code @anthropic-ai/claude-code}) implementation
 * of {@link CodingTaskBackend}. Targets the headless mode documented at
 * <a href="https://code.claude.com/docs/en/headless">code.claude.com/docs/en/headless</a>.
 *
 * <p><b>Wire shape (Phase 2e, May 2026)</b>:
 * {@code claude -p "<prompt>" --output-format json --model <alias>
 * [--bare] [--system-prompt …]}. The {@code -p} flag puts the SDK in
 * print/headless mode; {@code --output-format json} returns a single
 * JSON object on stdout with shape
 * {@code {result, session_id, usage:{input_tokens,output_tokens},
 * total_cost_usd, structured_output?}}.</p>
 *
 * <p><b>Auth</b> — dual-path per SPEC §9.2:
 * <ul>
 *   <li>{@link AuthMode.OAuthSession} → DO NOT pass {@code --bare}; the
 *       upstream CLI reads {@code ~/.config/claude/} for the user's
 *       subscription credentials. Cost on the household: zero direct $$
 *       (the subscription absorbs it).</li>
 *   <li>{@link AuthMode.ApiKey} → pass {@code --bare} and inject
 *       {@code ANTHROPIC_API_KEY} on the subprocess env. Bare mode skips
 *       OAuth/keychain reads, which is exactly what we want when the
 *       household has explicitly opted into pay-as-you-go API keys.</li>
 *   <li>{@link AuthMode.AuthMissing} → return
 *       {@code TaskStatus.FAILED} with a {@code LOGIN_REQUIRED} prefix
 *       and the resolver's recovery hint.</li>
 * </ul>
 *
 * <p><b>Tier</b>: {@link BackendTier#CLOUD_PAID}. CU estimate: 200 baseline,
 * scaled by description length. {@link org.wyrdsekai.core.protection.ActionPolicy}
 * gates the actual budget; this estimate is what the policy script sees.</p>
 *
 * <p><b>Reference</b>: see {@code core/src/main/java/org/wyrdsekai/core/inference/ClaudeCliInference.java}
 * for the chat-completion flavour of this same subprocess pattern (used
 * by the {@code wyrd-cloud} inference backend). Phase 2e does not share
 * code with it because the input/output shapes are different (chat
 * messages vs single prompt + structured output), but the auth-detection
 * heuristics (claude --version, claude auth status) are the same.</p>
 *
 * <p>Configuration is read via {@link ClaudeSdkRuntimeConfig}.</p>
 */
public final class ClaudeSdkBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link ClaudeSdkEventAdapter#namespace()}. */
    public static final String NAME = "claude-sdk";

    private static final Logger log = LoggerFactory.getLogger(ClaudeSdkBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock so we don't stall the policy script. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Hard cap on Claude-SDK CU estimate per SPEC §9 cost-policy guard rails. */
    private static final long MAX_CU_ESTIMATE = 5000L;

    /** Baseline CU per task before length scaling. */
    private static final long BASELINE_CU = 200L;

    private final ClaudeSdkRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public ClaudeSdkBackend(ClaudeSdkRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public ClaudeSdkBackend(ClaudeSdkRuntimeConfig config,
                            AuthResolver authResolver,
                            ProcessRunner runner) {
        this.config = config != null ? config : ClaudeSdkRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "wyrd coding login claude-sdk",
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
                "Claude Code SDK backend is disabled in config", started));
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
            args = buildArgs(spec, auth);
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct Claude SDK invocation: " + e.getMessage(), started));
            return future;
        }

        Map<String, String> env = buildEnv(auth);

        Thread.ofVirtual().name("claude-sdk-task-" + taskId).start(() -> {
            try {
                // Wyrdsekai items-as-tools contract — wrap the user
                // description with the OpenHands preamble (canonical
                // source) so Claude SDK emits the single-.js-with-
                // exports.manifest shape every backend must produce.
                // See OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault()).
                var rawDescription = spec != null ? spec.description() : null;
                var promptBody = (rawDescription != null && !rawDescription.isBlank())
                    ? rawDescription : "";
                var description = OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
                    + "\n\n--- TASK ---\n" + promptBody;
                // Honor spec.workspaceHint() as subprocess CWD so claude's
                // Write/Edit/Bash tools land artifacts where the caller
                // can scan them — matches the contract pi already follows.
                // Never the process's own directory: on a packaged node the JVM cwd is
                // the INSTALL ROOT. CodingWorkspace gives each task a private scratch dir
                // and still honours an explicit hint.
                var workspaceHint = CodingWorkspace.pathFor(
                    spec != null ? spec.workspaceHint() : null, taskId.toString());
                var result = runner.run(args, env, description, workspaceHint,
                    config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Claude SDK task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Claude SDK exited with code " + result.exitCode()
                            + (result.stderr().isBlank() ? ""
                                : ": " + result.stderr().trim()),
                        List.of(), 0L, durationMs));
                    return;
                }

                var parsed = parseClaudeResponse(taskId, spec, result);

                // CONTRACT REPAIR. The prompt is the `description` argument here, so the
                // Reprompt is supplied directly (see PiCodingBackend for the same shape).
                ItemContractRepair.repairRun(parsed.artifacts,
                    workspaceHint == null || workspaceHint.isBlank()
                        ? null : Path.of(workspaceHint),
                    taskId.toString(), Instant.ofEpochMilli(started),
                    repairPrompt -> {
                        try {
                            var r = runner.run(args, env, repairPrompt, workspaceHint,
                                config.maxWallclock());
                            return !r.timedOut() && r.exitCode() == 0;
                        } catch (Exception e) {
                            return false;
                        }
                    },
                    spec == null ? null : spec.description());
                parsed = parseClaudeResponse(taskId, spec, result);
                artifactCache.put(taskId.toString(), parsed.artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : parsed.artifacts) ids.add(a.artifactId());

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, parsed),
                    List.copyOf(ids), parsed.cuConsumed, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Claude SDK subprocess error: " + e.getMessage(), started));
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
                    log.debug("Claude SDK --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Claude SDK binary not found at '{}' — "
                    + "run `wyrd coding install claude-sdk` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Claude SDK health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        if (spec == null) return Math.min(BASELINE_CU, MAX_CU_ESTIMATE);
        // Length scaling: every ~500 chars of description adds ~100 CU,
        // capped at MAX_CU_ESTIMATE. Descriptions are usually short, so
        // most tasks land near the baseline.
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
    public ClaudeSdkRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list passed to {@link ProcessRunner}. Wire shape:
     * {@code claude -p --output-format json --model <alias> [--bare]
     * [--system-prompt …] [extra flags]}.
     *
     * <p>The actual prompt is fed via stdin (matches
     * {@code ClaudeCliInference}'s pattern; --bare mode also reads from
     * stdin when no positional prompt is given). The api-key value never
     * appears in argv — it travels via env.</p>
     */
    public List<String> buildArgs(TaskSpec spec, AuthMode auth) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("-p");
        args.add("--output-format");
        args.add("json");
        args.add("--model");
        args.add(config.model());
        // --no-session-persistence keeps each task self-contained (mirrors
        // ClaudeCliInference's chat path); the SDK headless mode honours
        // this flag the same way as the inference path.
        args.add("--no-session-persistence");

        // --bare ONLY when running with a Key Chest API key. With an
        // OAuth session, --bare would skip the keychain read and fail.
        if (auth instanceof AuthMode.ApiKey && config.useBare()) {
            args.add("--bare");
        }

        // When the caller has named a workspace, allow claude to actually
        // touch it — without an --allowedTools whitelist, claude -p
        // refuses to run Write/Edit and prose-pastes the would-be file
        // body into chat instead. Scoping by workspace dir + named-tool
        // list is much narrower than --dangerously-skip-permissions.
        var workspaceHint = spec != null ? spec.workspaceHint() : null;
        if (workspaceHint != null && !workspaceHint.isBlank()) {
            args.add("--add-dir");
            args.add(workspaceHint);
            args.add("--allowedTools");
            args.add("Edit");
            args.add("Write");
            args.add("Bash");
        }

        args.addAll(config.extraFlags());

        return List.copyOf(args);
    }

    /**
     * Build the env for the subprocess. On {@link AuthMode.ApiKey} we
     * inject {@code ANTHROPIC_API_KEY}. On {@link AuthMode.OAuthSession}
     * we leave the env empty so the upstream CLI reads its own
     * credentials.
     */
    public Map<String, String> buildEnv(AuthMode auth) {
        var env = new HashMap<String, String>();
        if (auth instanceof AuthMode.ApiKey key && key.value() != null && !key.value().isBlank()) {
            env.put("ANTHROPIC_API_KEY", key.value());
        }
        return env;
    }

    // -- output parsing ---------------------------------------------------

    /** Container for parsed Claude SDK response — separate from artifacts. */
    private record ClaudeResponse(List<CodingArtifact> artifacts, long cuConsumed,
                                   String resultText) {}

    /**
     * Translate Claude SDK's stdout JSON into {@link CodingArtifact}s.
     *
     * <p>Headless JSON shape: {@code {result, session_id,
     * usage:{input_tokens,output_tokens}, total_cost_usd,
     * structured_output?}}. We extract files mentioned in
     * {@code structured_output.files} or via best-effort scan of
     * {@code result} text.</p>
     */
    private ClaudeResponse parseClaudeResponse(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());
        var files = new ArrayList<String>();
        long cuConsumed = 0L;
        String resultText = "";
        String sessionId = null;
        Double totalCostUsd = null;

        if (result.stdout() != null && !result.stdout().isBlank()) {
            try {
                JsonNode root = MAPPER.readTree(result.stdout());
                resultText = root.path("result").asText("");
                sessionId = textOrNull(root, "session_id");

                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    int input = usage.path("input_tokens").asInt(0);
                    int output = usage.path("output_tokens").asInt(0);
                    // CU heuristic: 1 input token = 1 CU, 1 output token = 4 CU.
                    // (Output is typically priced 4-5x input across providers.)
                    cuConsumed = input + (output * 4L);
                }
                if (root.has("total_cost_usd") && root.get("total_cost_usd").isNumber()) {
                    totalCostUsd = root.get("total_cost_usd").asDouble();
                }

                JsonNode structured = root.path("structured_output");
                if (!structured.isMissingNode()) {
                    extractFiles(structured, files);
                }
            } catch (Exception e) {
                log.debug("[Claude SDK] failed to parse JSON response: {}", e.getMessage());
            }
        }

        // Fallback: if structured_output didn't surface files, regex-scan
        // the result text for common path hints (e.g. "Edited foo.java",
        // "Created src/Bar.java"). Best-effort, case-insensitive.
        if (files.isEmpty() && !resultText.isBlank()) {
            var pattern = Pattern.compile(
                "(?:edited|editing|created|modified|wrote)\\s+([\\w./_-]+)",
                Pattern.CASE_INSENSITIVE);
            var m = pattern.matcher(resultText);
            while (m.find()) {
                String path = m.group(1);
                if (path != null && !path.isBlank()) files.add(path);
            }
        }

        var seen = new LinkedHashSet<>(files);
        var dedupedFiles = new ArrayList<>(seen);

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "claude-sdk");
        metadata.put("backend", NAME);
        metadata.put("model", config.model());
        if (sessionId != null) metadata.put("session_id", sessionId);
        if (totalCostUsd != null) metadata.put("total_cost_usd", totalCostUsd);
        if (cuConsumed > 0) metadata.put("cu_consumed", cuConsumed);

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
        return new ClaudeResponse(List.of(src), cuConsumed, resultText);
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

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        var t = node.get(field).asText();
        return t.isBlank() ? null : t;
    }

    private TaskResult failed(UUID taskId, String summary, long startedMs) {
        return new TaskResult(taskId, NAME, TaskStatus.FAILED, summary,
            List.of(), 0L, System.currentTimeMillis() - startedMs);
    }

    private static String summarise(TaskSpec spec, ClaudeResponse parsed) {
        // Always surface the model's reply text when present — the
        // workshop narration + downstream shape checks both need to
        // see what claude actually said. Bookkeeping fallback only
        // when claude emitted nothing.
        if (parsed.resultText != null && !parsed.resultText.isBlank()) {
            return parsed.resultText;
        }
        int files = 0;
        for (var a : parsed.artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        if (files > 0) {
            return "Claude SDK completed the " + taskType + ", touching "
                + files + " file(s).";
        }
        return "Claude SDK completed the " + taskType + " (no file artifacts captured).";
    }

    // -- ProcessRunner indirection ----------------------------------------

    /** Result of a subprocess invocation. */
    public record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut) {}

    /**
     * Indirection seam — {@code (args, env, stdin, timeout)} with an
     * optional {@code workspace} dir as subprocess CWD. The Claude CLI
     * reads {@code -p} prompts from stdin when no positional is given;
     * the workspace dir is honored so file-write tools land artifacts
     * where the caller scans (matches the
     * {@link PiCodingBackend#runWithWorkspace} contract).
     */
    @FunctionalInterface
    public interface ProcessRunner {
        /**
         * Legacy 4-arg shape — abstract for backward compatibility with
         * existing test lambdas {@code (args, env, stdin, t) -> …}. The
         * workspace-aware overload below defaults to discarding workspace
         * and calling this; {@link DefaultProcessRunner} overrides both.
         */
        ProcessResult run(List<String> args, Map<String, String> env,
                          String stdin, Duration timeout)
            throws IOException, InterruptedException;

        /**
         * Workspace-aware overload — sets the subprocess CWD when the
         * caller has a workspace dir. Default impl drops workspace and
         * delegates to the 4-arg path, which is fine for stub runners
         * in unit tests; the {@link DefaultProcessRunner} actually
         * honors it.
         */
        default ProcessResult run(List<String> args, Map<String, String> env,
                                   String stdin, String workspace, Duration timeout)
                throws IOException, InterruptedException {
            return run(args, env, stdin, timeout);
        }
    }

    /** Default {@link ProcessRunner} — spawns the real subprocess. */
    public static class DefaultProcessRunner implements ProcessRunner {
        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  String stdin, Duration timeout)
                throws IOException, InterruptedException {
            return run(args, env, stdin, null, timeout);
        }

        @Override
        public ProcessResult run(List<String> args, Map<String, String> env,
                                  String stdin, String workspace, Duration timeout)
                throws IOException, InterruptedException {
            // env routed through the shared egress gate
            // (scrubs SSH_AUTH_SOCK/ambient keys; enforcing by default).
            var pb = EgressGate.gatedProcessBuilder(args, env);
            if (workspace != null && !workspace.isBlank()) {
                var dir = new File(workspace);
                if (dir.isDirectory()) pb.directory(dir);
            }
            // Mirror ClaudeCliInference: clear CLAUDECODE so the SDK
            // doesn't think it's running inside another Claude session.
            pb.environment().remove("CLAUDECODE");
            pb.redirectErrorStream(false);
            var process = pb.start();

            if (stdin != null && !stdin.isEmpty()) {
                try (var out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception _) { /* swallow */ }
            } else {
                // Close stdin so the SDK doesn't wait forever for input.
                try { process.getOutputStream().close(); }
                catch (Exception _) { /* swallow */ }
            }

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
