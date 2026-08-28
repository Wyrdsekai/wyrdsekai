package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
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
 * Pi (pi.dev, {@code @mariozechner/pi-coding-agent}) implementation of
 * {@link CodingTaskBackend}. Pi is a minimal terminal harness for AI
 * coding that fans out to 15+ LLM providers (Anthropic, OpenAI, Google,
 * Azure, Bedrock, Mistral, Groq, …) behind a single CLI — so this one
 * adapter covers the long tail without us writing per-provider
 * subclasses.
 *
 * <p><b>Wire shape (Phase 2f, May 2026)</b>: {@code pi -p "<prompt>"
 * --mode json --model <alias> [--provider <p>] [--no-session]
 * [--api-key <k>]}. {@code -p} is print/headless mode;
 * {@code --mode json} emits NDJSON (newline-delimited JSON) events on
 * stdout. Final assistant content lands in {@code message_end} events
 * under {@code message.content}.</p>
 *
 * <p><b>Auth</b> — dual-path per SPEC §9.2:
 * <ul>
 *   <li>{@link AuthMode.OAuthSession} → no flag changes; pi reads
 *       provider OAuth credentials from its config dir. No env injection.</li>
 *   <li>{@link AuthMode.ApiKey} → pass {@code --api-key <value>} on the
 *       CLI. Pi forwards the key to whichever provider the model alias
 *       resolves to. We do <em>not</em> set provider env vars
 *       ({@code ANTHROPIC_API_KEY} etc.) — passing the key once via
 *       {@code --api-key} is enough and keeps us provider-agnostic.</li>
 *   <li>{@link AuthMode.AuthMissing} → {@code TaskStatus.FAILED} with
 *       {@code LOGIN_REQUIRED}.</li>
 * </ul>
 *
 * <p><b>Tier</b>: {@link BackendTier#CLOUD_PAID}. CU estimate mirrors
 * the Claude SDK adapter (200 baseline + length scaling).</p>
 *
 * <p>Configuration is read via {@link PiCodingRuntimeConfig}. Subprocess
 * indirection reuses {@link ClaudeSdkBackend.ProcessRunner} /
 * {@link ClaudeSdkBackend.DefaultProcessRunner} — same shape, no
 * duplication.</p>
 */
public final class PiCodingBackend implements CodingTaskBackend {

    /** Stable backend name. */
    public static final String NAME = "pi";

    private static final Logger log = LoggerFactory.getLogger(PiCodingBackend.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_CU_ESTIMATE = 5000L;
    private static final long BASELINE_CU = 200L;

    private final PiCodingRuntimeConfig config;
    private final AuthResolver authResolver;
    private final ClaudeSdkBackend.ProcessRunner runner;

    private final Map<String, List<CodingArtifact>> artifactCache =
        new ConcurrentHashMap<>();

    /** Production constructor — uses the default subprocess runner. */
    public PiCodingBackend(PiCodingRuntimeConfig config, AuthResolver authResolver) {
        this(config, authResolver, new ClaudeSdkBackend.DefaultProcessRunner());
    }

    /** Test constructor — pluggable {@link ClaudeSdkBackend.ProcessRunner}. */
    public PiCodingBackend(PiCodingRuntimeConfig config,
                           AuthResolver authResolver,
                           ClaudeSdkBackend.ProcessRunner runner) {
        this.config = config != null ? config : PiCodingRuntimeConfig.defaults();
        this.authResolver = authResolver != null ? authResolver
            : (name -> new AuthMode.AuthMissing(name,
                "wyrd coding login pi",
                "AuthResolver not wired"));
        this.runner = runner != null ? runner : new ClaudeSdkBackend.DefaultProcessRunner();
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
                "Pi coding backend is disabled in config", started));
            return future;
        }

        LocalPi localPi = null;
        var auth = authResolver.resolveAuth(NAME);
        if (auth instanceof AuthMode.AuthMissing missing) {
            // No cloud key is not the end: pi supports LOCAL OpenAI-compatible
            // providers through ~/.pi/agent/models.json ("apiKey is required
            // but any value works" -- their docs, verbatim). This adapter used
            // to refuse here unconditionally, which made a local-llama-capable
            // tool read as LOGIN_REQUIRED against the household's own drive.
            // If the node has a drive, wire it as a managed provider entry and
            // proceed keyless; only when there is no drive either is this a
            // real refusal.
            var local = LocalPiProvider.ensure();
            if (local == null) {
                future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                    "LOGIN_REQUIRED: " + missing.reason()
                        + " (recovery: " + missing.recoveryCommand()
                        + " -- or start a local drive; pi runs keyless against it)",
                    List.of(), 0L, System.currentTimeMillis() - started));
                return future;
            }
            auth = local.auth();
            localPi = local.selection();
        }

        List<String> args;
        try {
            args = buildArgs(spec, auth, localPi);
        } catch (Exception e) {
            future.complete(failed(taskId,
                "Failed to construct pi invocation: " + e.getMessage(), started));
            return future;
        }

        Thread.ofVirtual().name("pi-task-" + taskId).start(() -> {
            try {
                // Items-as-tools preamble is the canonical contract (defined
                // in OpenHandsBackend) — same as ClaudeSdkBackend.
                var rawDescription = spec != null ? spec.description() : null;
                var promptBody = (rawDescription != null && !rawDescription.isBlank())
                    ? rawDescription : "";
                var description = OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
                    + "\n\n--- TASK ---\n" + promptBody;
                // Pi reads the prompt from stdin when -p has no positional
                // (mirrors claude's headless mode); see pi.dev/docs/usage.
                // Honor spec.workspaceHint() as the subprocess CWD so pi's
                // write/edit/bash tools land artifacts in a place the
                // caller can scan after — matches the OpenHands V1 Agent
                // Server's bind-mount workspace contract.
                // Never the process's own directory: on a packaged node the JVM cwd is
                // the INSTALL ROOT. CodingWorkspace gives each task a private scratch dir
                // and still honours an explicit hint.
                var workspaceHint = CodingWorkspace.pathFor(
                    spec != null ? spec.workspaceHint() : null, taskId.toString());
                var result = runWithWorkspace(args, description,
                    workspaceHint, config.maxWallclock());
                long durationMs = System.currentTimeMillis() - started;

                if (result.timedOut()) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.TIMED_OUT,
                        "Pi task exceeded wallclock cap of "
                            + config.maxWallclock().toMinutes() + " min",
                        List.of(), 0L, durationMs));
                    return;
                }
                if (result.exitCode() != 0) {
                    future.complete(new TaskResult(taskId, NAME, TaskStatus.FAILED,
                        "Pi exited with code " + result.exitCode()
                            + (result.stderr().isBlank() ? ""
                                : ": " + result.stderr().trim()),
                        List.of(), 0L, durationMs));
                    return;
                }

                var parsed = parsePiResponse(taskId, spec, result);

                // CONTRACT REPAIR. Pi takes its prompt on STDIN, not as a trailing
                // argument, so it supplies the Reprompt directly rather than through
                // rerunWithPrompt — which is the point of Reprompt being a one-method
                // seam: the repair is shared, only "run yourself again" is per-backend.
                ItemContractRepair.repairRun(parsed.artifacts,
                    workspaceHint == null || workspaceHint.isBlank()
                        ? null : Path.of(workspaceHint),
                    taskId.toString(), Instant.ofEpochMilli(started),
                    repairPrompt -> {
                        try {
                            var r = runWithWorkspace(args, repairPrompt,
                                workspaceHint, config.maxWallclock());
                            return !r.timedOut() && r.exitCode() == 0;
                        } catch (Exception e) {
                            return false;
                        }
                    },
                    spec == null ? null : spec.description());
                parsed = parsePiResponse(taskId, spec, result);
                artifactCache.put(taskId.toString(), parsed.artifacts);
                var ids = new ArrayList<UUID>();
                for (var a : parsed.artifacts) ids.add(a.artifactId());

                future.complete(new TaskResult(taskId, NAME, TaskStatus.SUCCEEDED,
                    summarise(spec, parsed),
                    List.copyOf(ids), parsed.cuConsumed, durationMs));
            } catch (Exception e) {
                future.complete(failed(taskId,
                    "Pi subprocess error: " + e.getMessage(), started));
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
                    log.debug("Pi --version probe failed: timedOut={} exit={} stderr={}",
                        result.timedOut(), result.exitCode(), result.stderr());
                    return false;
                }
                return true;
            } catch (UncheckedIOException | IOException e) {
                log.info("Pi binary not found at '{}' — install with "
                    + "`npm i -g @mariozechner/pi-coding-agent`. ({})",
                    config.executablePath(), e.getMessage());
                return false;
            } catch (Exception e) {
                log.debug("Pi health probe error: {}", e.getMessage());
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
    public PiCodingRuntimeConfig config() { return config; }

    // -- subprocess with explicit CWD ------------------------------------

    /**
     * Spawn the pi subprocess with an explicit working directory so its
     * write/edit/bash tools touch the requested workspace. The shared
     * {@link ClaudeSdkBackend.ProcessRunner} doesn't expose CWD; this is
     * the minimal local equivalent that does.
     */
    private ClaudeSdkBackend.ProcessResult runWithWorkspace(
            List<String> args, String stdin, String workspace, Duration timeout)
            throws IOException, InterruptedException {
        // Pi previously spawned with NO env handling
        // (full inherit). Route through the shared egress gate: scrubs
        // SSH_AUTH_SOCK/ambient creds, keeps PATH/HOME + OPENAI_* (local llama).
        var pb = EgressGate.gatedProcessBuilder(args, null);
        if (workspace != null && !workspace.isBlank()) {
            var dir = new File(workspace);
            if (dir.isDirectory()) pb.directory(dir);
        }
        pb.redirectErrorStream(false);
        var process = pb.start();

        if (stdin != null && !stdin.isEmpty()) {
            try (var out = process.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception _) { /* swallow */ }
        } else {
            try { process.getOutputStream().close(); }
            catch (Exception _) { /* swallow */ }
        }

        var stdoutBuf = new StringBuilder();
        var stderrBuf = new StringBuilder();
        var stdoutThread = Thread.ofVirtual().start(() -> {
            try (var in = process.getInputStream()) {
                stdoutBuf.append(new String(in.readAllBytes(),
                    StandardCharsets.UTF_8));
            } catch (Exception _) { /* swallow */ }
        });
        var stderrThread = Thread.ofVirtual().start(() -> {
            try (var in = process.getErrorStream()) {
                stderrBuf.append(new String(in.readAllBytes(),
                    StandardCharsets.UTF_8));
            } catch (Exception _) { /* swallow */ }
        });

        boolean finished = process.waitFor(timeout.toMillis(),
            TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutThread.join(500);
            stderrThread.join(500);
            return new ClaudeSdkBackend.ProcessResult(-1,
                stdoutBuf.toString(), stderrBuf.toString(), true);
        }
        stdoutThread.join();
        stderrThread.join();
        return new ClaudeSdkBackend.ProcessResult(process.exitValue(),
            stdoutBuf.toString(), stderrBuf.toString(), false);
    }

    // -- argv construction (unit-testable) --------------------------------

    /**
     * Build the argv passed to the subprocess. Wire shape:
     * {@code pi -p --mode json --model <alias> [--provider <p>]
     * [--no-session] [--api-key <k>] [extra flags]}. Prompt is fed via
     * stdin (pi accepts either positional or stdin in {@code -p} mode).
     */
    /** Cloud-mode shape, kept for existing callers/tests. */
    public List<String> buildArgs(TaskSpec spec, AuthMode auth) {
        return buildArgs(spec, auth, null);
    }

    List<String> buildArgs(TaskSpec spec, AuthMode auth, LocalPi localPi) {
        var args = new ArrayList<String>();
        args.add(config.executablePath());
        args.add("-p");
        args.add("--mode");
        args.add("json");
        args.add("--model");
        args.add(localPi != null ? localPi.model() : config.model());
        if (localPi != null) {
            // Local mode selects the managed models.json provider and MUST be
            // the only --provider on the line: the comment here used to say
            // config.provider() "is skipped" while the code below still added
            // it, so pi received two --provider flags and last-wins handed it
            // the config default — "Unknown provider" on the very box the
            // local mode was built for. A comment describing a skip is not a
            // skip.
            args.add("--provider");
            args.add(localPi.provider());
        } else if (config.provider() != null && !config.provider().isBlank()) {
            args.add("--provider");
            args.add(config.provider());
        }
        if (config.noSession()) {
            args.add("--no-session");
        }
        // ApiKey path → pass via CLI (provider-agnostic); OAuth path
        // falls back to pi's own credential resolution.
        if (auth instanceof AuthMode.ApiKey k && k.value() != null && !k.value().isBlank()) {
            args.add("--api-key");
            args.add(k.value());
        }
        args.addAll(config.extraFlags());
        return List.copyOf(args);
    }


    /**
     * Local-drive mode: pi as the household's keyless coding hand.
     *
     * <p>pi reads custom providers from {@code ~/.pi/agent/models.json}
     * (openai-completions API, any apiKey accepted for local servers). We
     * MANAGE ONE ENTRY in that file — provider {@code wyrd-local} — and merge
     * around whatever else the person has configured: their file, their other
     * providers, untouched. Returns an {@link AuthMode.ApiKey} whose value is
     * the literal {@code "local"} plus the provider/model to select, or null
     * when no local drive answers.</p>
     */
    record LocalPi(String provider, String model) { }

    /** What local mode yields: the keyless auth plus the provider/model to select. */
    record LocalWiring(AuthMode.ApiKey auth, LocalPi selection) { }

    static final class LocalPiProvider {
        private LocalPiProvider() { }

        static LocalWiring ensure() {
            var ep = LocalInferenceEndpoint.resolve().orElse(null);
            if (ep == null) return null;
            try {
                var dir = Path.of(System.getProperty("user.home"), ".pi", "agent");
                Files.createDirectories(dir);
                var file = dir.resolve("models.json");
                var mapper = new ObjectMapper();
                ObjectNode root;
                if (Files.exists(file)) {
                    var parsed = mapper.readTree(Files.readString(file));
                    root = parsed != null && parsed.isObject()
                        ? (ObjectNode) parsed : mapper.createObjectNode();
                } else {
                    root = mapper.createObjectNode();
                }
                var providers = root.has("providers") && root.get("providers").isObject()
                    ? (ObjectNode) root.get("providers")
                    : root.putObject("providers");
                var p = providers.putObject("wyrd-local");
                var base = ep.url().endsWith("/v1") ? ep.url() : ep.url() + "/v1";
                p.put("baseUrl", base);
                p.put("api", "openai-completions");
                p.put("apiKey", "local");
                var compat = p.putObject("compat");
                compat.put("supportsDeveloperRole", false);
                compat.put("supportsReasoningEffort", false);
                p.putArray("models").addObject().put("id", ep.modelId());
                Files.writeString(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                return new LocalWiring(new AuthMode.ApiKey("local"),
                    new LocalPi("wyrd-local", ep.modelId()));
            } catch (Exception e) {
                return null;
            }
        }
    }

    // -- output parsing ---------------------------------------------------

    /** Container for parsed pi response — separate from artifacts. */
    private record PiResponse(List<CodingArtifact> artifacts, long cuConsumed,
                               String resultText) {}

    /**
     * Translate pi's NDJSON event stream into {@link CodingArtifact}s.
     * Strategy: scan every line as a JSON object, accumulate the last
     * {@code message_end.message.content} text as the final reply, and
     * harvest any file paths mentioned (best-effort regex on the
     * accumulated reply). Token usage is rolled up if any event carries
     * an {@code input_tokens}/{@code output_tokens} pair.
     */
    private PiResponse parsePiResponse(UUID taskId, TaskSpec spec,
                                       ClaudeSdkBackend.ProcessResult result) {
        // The workspace REPORTED on the artifact is what CodingTaskItemBridge scans for
        // the item's .js. Falling back to the process directory pointed that scan at the
        // install root on a packaged node — the same defect as running there.
        var workspace = CodingWorkspace.pathFor(
            spec != null ? spec.workspaceHint() : null,
            taskId == null ? null : taskId.toString());
        var files = new ArrayList<String>();
        long cuConsumed = 0L;
        String resultText = "";
        String sessionId = null;

        if (result.stdout() != null && !result.stdout().isBlank()) {
            for (var line : result.stdout().split("\\R")) {
                if (line.isBlank()) continue;
                try {
                    JsonNode evt = MAPPER.readTree(line);
                    var type = evt.path("type").asText("");
                    if ("session".equals(type)) {
                        sessionId = textOrNull(evt, "id");
                    } else if ("message_end".equals(type)) {
                        var msg = evt.path("message");
                        var content = extractContentText(msg);
                        if (!content.isBlank()) {
                            // Last message_end wins — pi may emit
                            // multiple turns in one session.
                            resultText = content;
                        }
                    }
                    var usage = evt.path("usage");
                    if (usage.isObject()) {
                        int input = usage.path("input_tokens").asInt(0);
                        int output = usage.path("output_tokens").asInt(0);
                        if (input > 0 || output > 0) {
                            cuConsumed += input + (output * 4L);
                        }
                    }
                } catch (Exception _) {
                    // Skip malformed lines — pi may interleave
                    // human-readable lines depending on version.
                }
            }
        }

        // Best-effort file extraction from accumulated reply text.
        if (!resultText.isBlank()) {
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
        metadata.put("source", "pi");
        metadata.put("backend", NAME);
        metadata.put("model", config.model());
        if (config.provider() != null) metadata.put("provider", config.provider());
        if (sessionId != null) metadata.put("session_id", sessionId);
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
        return new PiResponse(List.of(src), cuConsumed, resultText);
    }

    /**
     * Extract a {@code String} reply from a pi {@code message} object.
     * Pi's content can be a plain string OR an array of blocks
     * ({@code [{"type":"text","text":"…"}, …]}); handle both.
     */
    private static String extractContentText(JsonNode msg) {
        if (msg == null || msg.isMissingNode() || msg.isNull()) return "";
        var content = msg.path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            var sb = new StringBuilder();
            for (var block : content) {
                if (block.isTextual()) {
                    sb.append(block.asText());
                } else if (block.has("text") && block.get("text").isTextual()) {
                    sb.append(block.get("text").asText());
                }
            }
            return sb.toString();
        }
        // Some pi versions surface text directly on the message.
        if (msg.has("text") && msg.get("text").isTextual()) {
            return msg.get("text").asText();
        }
        return "";
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

    private static String summarise(TaskSpec spec, PiResponse parsed) {
        // Always surface the model's reply text when present — that's
        // what the items-as-tools shape check + policy narration both
        // need to see. The bookkeeping fallback is only used when pi
        // emitted no reply (rare, usually a wire/parsing failure).
        if (parsed.resultText != null && !parsed.resultText.isBlank()) {
            return parsed.resultText;
        }
        var taskType = spec != null && spec.taskType() != null ? spec.taskType() : "task";
        int files = 0;
        for (var a : parsed.artifacts) {
            if (a instanceof SourceArtifact s) files += s.files().size();
        }
        if (files > 0) {
            return "Pi completed the " + taskType + ", touching " + files + " file(s).";
        }
        return "Pi completed the " + taskType + " (no file artifacts captured).";
    }
}
