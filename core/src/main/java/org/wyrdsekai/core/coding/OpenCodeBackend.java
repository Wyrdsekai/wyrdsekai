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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * OpenCode (sst/opencode) implementation of {@link CodingTaskBackend}.
 *
 * <p>Spawns the {@code opencode} binary as a subprocess in non-interactive
 * mode ({@code opencode run --format json --model …}) configured to point
 * at the household's local llama-server via the {@code @ai-sdk/openai-compatible}
 * provider shim. The provider config is materialised on-demand into the
 * OpenCode JSON config file so every task uses the household's chosen
 * model + endpoint without polluting the user's global OpenCode config.</p>
 *
 * <p>: this is the default-on backend
 * intended to make "complex items work out of the box" — local, free,
 * autonomous-loop shape (#2). Phase 2b ships the adapter; Phase 2a
 * (manifest infrastructure + binary bundling) will land the actual
 * binary at install time.</p>
 *
 * <p>Configuration is read via {@link OpenCodeRuntimeConfig}; see
 * {@code application.conf} block at {@code wyrdsekai.coding.backends.opencode}.</p>
 */
public final class OpenCodeBackend implements CodingTaskBackend {

    /** Stable backend name — must match {@link OpenCodeEventAdapter#namespace()}. */
    public static final String NAME = "opencode";

    private static final Logger log = LoggerFactory.getLogger(OpenCodeBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cap on health-check probe wallclock so we don't stall the policy script. */
    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final OpenCodeRuntimeConfig config;
    private final ProcessRunner runner;

    /**
     * Cache of the most recent task → produced artifacts. OpenCode emits
     * artifacts inline in the JSON output; we don't have a CodeZaiku-style
     * persistent store yet, so this in-memory map serves Phase 2b. Phase
     * 5 will replace with a persistent index.
     */
    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses {@link DefaultProcessRunner}. */
    public OpenCodeBackend(OpenCodeRuntimeConfig config) {
        this(config, new DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ProcessRunner} for unit tests. */
    public OpenCodeBackend(OpenCodeRuntimeConfig config, ProcessRunner runner) {
        this.config = config != null ? config : OpenCodeRuntimeConfig.defaults();
        this.runner = runner != null ? runner : new DefaultProcessRunner();
    }

    @Override public String name() { return NAME; }

    /**
     * OpenCode points at the household's local llama-server, so no
     * per-token billing applies. Tier matches Aider / local-Qwen
     * backends per SPEC §4.1.
     */
    @Override public BackendTier tier() { return BackendTier.LOCAL_FREE; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        if (!config.enabled()) {
            future.complete(failed(taskId, "OpenCode backend is disabled in config",
                started));
            return future;
        }

        // Build the argv the subprocess will run. Construction stays
        // exposed via buildArgs() so unit tests can assert the wire shape
        // without spawning a real process.
        List<String> args;
        try {
            args = buildArgs(spec, taskId.toString());
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct OpenCode invocation: " + e.getMessage(), started));
            return future;
        }

        // Prepare a transient OpenCode config file pointing at the
        // household llama-server. We pass it via OPENCODE_CONFIG so the
        // user's ~/.config/opencode/opencode.json is left untouched.
        Map<String, String> env;
        try {
            env = buildEnv();
        } catch (IOException e) {
            future.complete(failed(taskId,
                "Failed to write transient OpenCode config: " + e.getMessage(), started));
            return future;
        }

        // Run async on a virtual thread — submitTask() must not block.
        Thread.ofVirtual().name("opencode-task-" + taskId).start(() -> {
            try {
                var result = runner.run(args, env, config.maxWallclock(),
                    CodingWorkspace.forTask(
                        spec != null ? spec.workspaceHint() : null,
                        taskId.toString()));
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "OpenCode task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "OpenCode exited with code " + result.exitCode()
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
                    "OpenCode subprocess error: " + e.getMessage(), started));
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
                    log.debug("OpenCode --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                // Binary missing or PATH lookup failed. Phase 2a will
                // bundle the binary into a fixed location; until then
                // surface the install hint exactly once per JVM (the log
                // module rate-limits if needed).
                log.info("OpenCode binary not found at '{}' — "
                    + "run `wyrd coding install opencode` to fetch it. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("OpenCode health probe error: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) {
        // Local model, no per-token billing. Always 0 — same as CodeZaiku.
        return 0L;
    }

    /** Snapshot of the runtime config; useful for tests + diagnostics. */
    public OpenCodeRuntimeConfig config() { return config; }

    // -- argv / env construction (unit-testable) --------------------------

    /**
     * Build the argv list passed to {@link ProcessRunner}. Exposed so
     * tests can assert {@code base_url} / {@code model} / message wiring
     * without spawning a real subprocess.
     */
    public List<String> buildArgs(TaskSpec spec) {
        return buildArgs(spec, null);
    }

    /**
     * @param taskId scopes the per-task scratch directory when no workspace hint is
     *               given. Null only from the arg-shape unit tests, which do not run a
     *               subprocess and so cannot write anywhere.
     */
    public List<String> buildArgs(TaskSpec spec, String taskId) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("run");
        args.add("--format");
        args.add("json");
        args.add("--model");
        args.add(config.providerName() + "/" + config.model());
        args.add("--dangerously-skip-permissions");

        // --dir was passed ONLY when a hint existed; with none, opencode worked in the
        // process's own directory — the install root on a packaged node. Always name a
        // directory, and let CodingWorkspace decide which one.
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null, taskId);
        if (workspace != null && !workspace.isBlank()) {
            args.add("--dir");
            args.add(workspace);
        }

        // Steward-supplied extra flags. Inserted before the message so
        // any flag that takes a value is parsed correctly.
        args.addAll(config.extraFlags());

        // OpenCode reads the prompt from the trailing positional args
        // ("opencode run [message..]"). We pass the description as a
        // single arg so spaces / newlines survive intact.
        //
        // Wyrdsekai items-as-tools contract — prepend the OpenHands
        // preamble (canonical source) so OpenCode emits the same
        // single-{@code .js}-with-{@code exports.manifest} shape every
        // backend must produce. See OpenHandsBackend's
        // ITEMS_AS_TOOLS_PREAMBLE_CWD for the full rationale.
        var description = spec != null ? spec.description() : null;
        var promptBody = (description != null && !description.isBlank())
            ? description : "";
        // Recipe BACKEND steps with tools=[shell] set
        // taskType=CodingBackendDispatcher.TASK_TYPE_SHELL_EXEC; in that
        // mode the recipe author wants raw shell execution, not a
        // scripted-item .js file. Skip the items-as-tools wrap.
        boolean shellExec = spec != null
                && "shell-exec".equalsIgnoreCase(spec.taskType());
        args.add(shellExec
            ? promptBody
            : (OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
                + "\n\n--- TASK ---\n" + promptBody));
        return List.copyOf(args);
    }

    /**
     * Build the environment for the subprocess: writes a transient
     * OpenCode config file pointing at the household's llama-server and
     * returns env vars wiring it in via {@code OPENCODE_CONFIG}.
     */
    public Map<String, String> buildEnv() throws IOException {
        var env = new HashMap<String, String>();

        var tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai-opencode");
        Files.createDirectories(tmpDir);
        var cfg = tmpDir.resolve("opencode.json");

        // Provider config — see https://opencode.ai/docs/providers/.
        // The npm key is the OpenCode-side identifier of the AI-SDK
        // package; @ai-sdk/openai-compatible covers any /v1/chat/completions
        // server (llama-server, vLLM, LM Studio, …).
        var providerJson = MAPPER.createObjectNode();
        providerJson.put("$schema", "https://opencode.ai/config.json");
        var providers = providerJson.putObject("provider");
        var p = providers.putObject(config.providerName());
        p.put("npm", "@ai-sdk/openai-compatible");
        p.put("name", "Wyrdsekai Local LLM");
        p.putObject("options").put("baseURL", config.effectiveBaseUrl());
        var models = p.putObject("models");
        models.putObject(config.model())
            .put("name", config.model());

        Files.writeString(cfg, MAPPER.writerWithDefaultPrettyPrinter()
            .writeValueAsString(providerJson), StandardCharsets.UTF_8);

        env.put("OPENCODE_CONFIG", cfg.toAbsolutePath().toString());
        // OpenAI-compatible providers require *some* key string even for
        // local servers; llama-server ignores it but the npm package
        // throws if blank.
        env.put("OPENAI_API_KEY",
            config.apiKey() == null || config.apiKey().isBlank()
                ? "not-required" : config.apiKey());
        return env;
    }

    // -- output parsing ---------------------------------------------------

    /**
     * Translate OpenCode's stdout JSON into {@link CodingArtifact}s.
     *
     * <p>OpenCode emits a stream of JSON event records when run with
     * {@code --format json}; the shape isn't fully documented upstream
     * (verified gap, see WebFetch in adapter notes). We take a tolerant
     * approach: every {@code touched_file} / {@code file_edited} / similar
     * key contributes a file path; we collapse them into one
     * {@link SourceArtifact}. If the output is opaque JSON we still
     * surface a SourceArtifact with no files (caller sees the task
     * succeeded but no file inventory was extractable).</p>
     */
    private List<CodingArtifact> parseArtifacts(
            UUID taskId, TaskSpec spec, ProcessResult result) {
        var files = new ArrayList<String>();
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());

        var stdout = result.stdout();
        if (stdout != null && !stdout.isBlank()) {
            // OpenCode emits either a single JSON object or NDJSON.
            // Both shapes parse line-by-line via Jackson; malformed
            // lines are skipped silently.
            for (var line : stdout.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                try {
                    var node = MAPPER.readTree(line);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // ignore — not every line is JSON
                }
            }
            // Final attempt: parse the whole blob as one object.
            if (files.isEmpty()) {
                try {
                    var node = MAPPER.readTree(stdout);
                    extractFiles(node, files);
                } catch (Exception _) {
                    // ignore
                }
            }
        }

        // Deduplicate while preserving order.
        var seen = new LinkedHashSet<>(files);
        var dedupedFiles = new ArrayList<>(seen);

        var metadata = new HashMap<String, Object>();
        metadata.put("source", "opencode");
        metadata.put("backend", NAME);
        metadata.put("model", config.model());
        metadata.put("provider", config.providerName());
        metadata.put("base_url", config.effectiveBaseUrl());
        if (result.stdout() != null && !result.stdout().isBlank()) {
            // Truncate stdout in metadata so a runaway log doesn't blow
            // up the room object.
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
            null, // OpenCode doesn't surface git ref in JSON output
            Instant.now(),
            Map.copyOf(metadata)
        );
        return List.of(src);
    }

    /** Walk a JSON node and accumulate any file paths it mentions. */
    private static void extractFiles(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) return;

        // Common shapes the OpenCode docs hint at + defensive coverage.
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
        // Recurse into object children + array elements.
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
            return "OpenCode completed the " + taskType + " (no artifacts captured).";
        }
        return "OpenCode completed the " + taskType + ", touching "
            + files + " file(s).";
    }

    // -- ProcessRunner indirection ----------------------------------------

    /** Result of a subprocess invocation. */
    public record ProcessResult(int exitCode, String stdout, String stderr,
                                 boolean timedOut) {}

    /**
     * Indirection seam between {@link OpenCodeBackend} and the JDK
     * {@link ProcessBuilder}. Tests substitute a stub so they can
     * deterministically drive the parser without spawning real binaries.
     */
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

    /**
     * Default {@link ProcessRunner} that spawns the real subprocess via
     * {@link ProcessBuilder}.
     */
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
            // Close the child's stdin AT ONCE. opencode also reads prompts
            // from stdin, and an open, empty pipe is not "no input" to it --
            // it is input that has not arrived yet, so the child sat in
            // ep_poll forever and every probe read as a 10-minute hang. The
            // same invocation with stdin at /dev/null (EOF) finished in 60s.
            // The task travels entirely in argv; there is nothing to write.
            process.getOutputStream().close();
            // Drain stdout/stderr in parallel so the subprocess can't
            // block on a full pipe. Virtual threads are cheap; one per
            // stream is fine.
            var stdoutBuf = new StringBuilder();
            var stderrBuf = new StringBuilder();
            var stdoutThread = Thread.ofVirtual().start(() -> {
                try (var in = process.getInputStream()) {
                    var bytes = in.readAllBytes();
                    stdoutBuf.append(new String(bytes, StandardCharsets.UTF_8));
                } catch (Exception _) { /* swallow — process may be killed */ }
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
