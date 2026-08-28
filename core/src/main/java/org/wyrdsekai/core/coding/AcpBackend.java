package org.wyrdsekai.core.coding;

import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.core.coding.acp.AcpClient;
import org.wyrdsekai.core.coding.acp.AcpConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

/**
 * A {@link CodingTaskBackend} over any ACP v1 agent — one adapter for the
 * five (and counting) backends that speak the protocol, replacing five
 * bespoke stdout parsers with one schema'd wire.
 *
 * <p>Lifecycle is spawn-per-task: start the agent process
 * ({@code goose acp}, {@code codezaiku acp}, …), initialize (negotiated
 * version), one session, one prompt turn, collect updates, terminate.
 * That matches {@link CodingTaskBackend}'s one-shot semantics and leaves
 * no long-lived subprocess to babysit.</p>
 *
 * <p><b>Artifacts are typed, twice over:</b> file paths come from
 * {@code tool_call}/{@code tool_call_update} {@code locations[].path}
 * entries (the schema'd follow-along field), and when the agent's final
 * message carries the shared result-JSON block (the CodeZaiku contract
 * shape), {@link CodeZaikuBackend#parseResultJson} promotes it to a full
 * source+build pair — the same parser as the CLI path, written once.</p>
 */
public final class AcpBackend implements CodingTaskBackend {

    private static final Logger log = LoggerFactory.getLogger(AcpBackend.class);
    private static final Duration DEFAULT_TURN_TIMEOUT = Duration.ofMinutes(30);

    /** Seam for tests: produce a connected {@link AcpConnection}. */
    @FunctionalInterface
    public interface TransportFactory {
        Transport open() throws IOException;
    }

    /** Default registry name — the generic ACP surface. */
    public static final String NAME = "acp";

    /** A live connection plus how to dispose of it. */
    public record Transport(AcpConnection connection, Runnable terminate) {}

    /** How long a run pauses for the steward's consent before the refusal
     *  that silence always meant. Well under CodeZaiku's own 600s window. */
    public static final Duration DEFAULT_CONSENT_WAIT = Duration.ofSeconds(120);

    private final String name;
    private final List<String> agentCommand;
    private final Duration turnTimeout;
    private final TransportFactory transportFactory;
    private volatile Duration consentWait = DEFAULT_CONSENT_WAIT;
    private final Map<String, List<CodingArtifact>> artifactCache = new ConcurrentHashMap<>();

    /** Production: spawn {@code agentCommand} with an EgressGate-scrubbed env. */
    public AcpBackend(String name, List<String> agentCommand, Map<String, String> env,
                      Duration turnTimeout) {
        this(name, agentCommand, turnTimeout, () -> spawn(agentCommand, env));
    }

    /** Test seam: any transport (piped streams, fake agent). */
    public AcpBackend(String name, List<String> agentCommand,
                      Duration turnTimeout, TransportFactory transportFactory) {
        this.name = name != null ? name : "acp";
        this.agentCommand = agentCommand != null ? List.copyOf(agentCommand) : List.of();
        this.turnTimeout = turnTimeout != null ? turnTimeout : DEFAULT_TURN_TIMEOUT;
        this.transportFactory = transportFactory;
    }

    /** Override the steward-consent wait (bootstrap passes the config value). */
    public AcpBackend withConsentWait(Duration wait) {
        if (wait != null && !wait.isNegative() && !wait.isZero()) {
            this.consentWait = wait;
        }
        return this;
    }

    private static Transport spawn(List<String> command, Map<String, String> env)
            throws IOException {
        var pb = new ProcessBuilder(command);
        EgressGate.defaultInstance().applyEnv(pb.environment(),
            env != null ? env : Map.of());
        pb.redirectErrorStream(false);
        var process = pb.start();
        // stderr drained so a chatty agent can't block on a full pipe
        Thread.ofVirtual().name("acp-stderr").start(() -> {
            try (var err = process.getErrorStream()) {
                err.readAllBytes();
            } catch (Exception ignored) { }
        });
        var conn = new AcpConnection(process.getInputStream(), process.getOutputStream());
        return new Transport(conn, process::destroy);
    }

    @Override public String name() { return name; }

    @Override public BackendTier tier() { return BackendTier.LOCAL_HEAVY; }

    @Override
    public CompletableFuture<TaskResult> submitTask(TaskSpec spec) {
        var future = new CompletableFuture<TaskResult>();
        var started = System.currentTimeMillis();
        var taskId = spec != null && spec.taskId() != null ? spec.taskId() : UUID.randomUUID();

        Thread.ofVirtual().name("acp-task-" + taskId).start(() -> {
            Transport transport = null;
            try {
                transport = transportFactory.open();
                // Steward-consent policy (2026-08-16): git-state writes route
                // to the steward with a bounded wait; silence/timeout falls
                // back to reject_once. With no notifier wired (headless,
                // tests) the wait resolves to refusal — behaviorally the old
                // HOUSE_POLICY, plus a visible pending entry while it waits.
                var policy = AcpClient.stewardConsent(
                    ConsentBroker.get(), consentWait, name, taskId.toString());
                try (var client = new AcpClient(transport.connection(), policy)) {
                    client.initialize("wyrdsekai", "0.1.6");
                    // Never the JVM cwd — that is the install root on a packaged node.
                    var cwd = CodingWorkspace.pathFor(
                        spec != null ? spec.workspaceHint() : null,
                        taskId == null ? null : taskId.toString());
                    var sessionId = client.newSession(cwd);
                    // Items-as-tools contract. Every OTHER backend prepends this; ACP
                    // sent the raw description, so an item authored over ACP had never
                    // been told the manifest shape, the embodiment block, the commands
                    // block or the invoke() entrypoint — and the bridge would refuse it
                    // for certain. ACP is the permission-GATED route and its default
                    // agent is `codezaiku acp`, so this is the path a steward-consented
                    // build takes. Same preamble, same bridge, same requirements.
                    var body = spec != null && spec.description() != null
                        ? spec.description() : "";
                    var description = OpenHandsBackend.itemsAsToolsPreambleCwd(ItemCapabilitySet.craftedDefault())
                        + "\n\n--- TASK ---\n" + body;

                    var response = client.prompt(sessionId, description, turnTimeout);
                    var stopReason = response.path("stopReason").asText("unknown");

                    var artifacts = toArtifacts(taskId, cwd, client.updates(),
                        resultMeta(response));

                    // CONTRACT REPAIR — the session is still open, so re-prompting it is
                    // the cheapest reprompt of any backend: no new process, same context.
                    ItemContractRepair.repairRun(artifacts,
                        cwd == null || cwd.isBlank() ? null : Path.of(cwd),
                        taskId.toString(), Instant.ofEpochMilli(started),
                        repairPrompt -> {
                            try {
                                var r = client.prompt(sessionId, repairPrompt, turnTimeout);
                                return "end_turn".equals(
                                    r.path("stopReason").asText("unknown"));
                            } catch (Exception e) {
                                return false;
                            }
                        },
                    spec == null ? null : spec.description());
                    artifacts = toArtifacts(taskId, cwd, client.updates(),
                        resultMeta(response));
                    artifactCache.put(taskId.toString(), artifacts);
                    var ids = new ArrayList<UUID>();
                    for (var a : artifacts) ids.add(a.artifactId());

                    var status = switch (stopReason) {
                        case "end_turn" -> TaskStatus.SUCCEEDED;
                        // CodeZaiku 01de82d2 shares one status function across run and
                        // ACP: a prompt that ends unfinished or is cancelled now reports
                        // `incomplete`, not `failed`. Running out of room is not failing;
                        // what it wrote is real, and the bridge's disk check decides
                        // whether it is worth placing.
                        case "max_turn_requests", "max_tokens", "incomplete", "cancelled" ->
                            TaskStatus.SUCCEEDED;
                        case "refusal" -> TaskStatus.FAILED;
                        default -> TaskStatus.FAILED; // unknown
                    };
                    future.complete(new TaskResult(taskId, name, status,
                        "ACP turn ended: " + stopReason + " ("
                            + artifacts.size() + " artifact(s))",
                        List.copyOf(ids), 0L,
                        System.currentTimeMillis() - started));
                }
            } catch (Exception e) {
                future.complete(new TaskResult(taskId, name, TaskStatus.FAILED,
                    "ACP task error: " + e.getMessage(), List.of(), 0L,
                    System.currentTimeMillis() - started));
            } finally {
                if (transport != null) {
                    try { transport.terminate().run(); } catch (Exception ignored) { }
                }
            }
        });
        return future;
    }

    @Override
    public Stream<CodingArtifact> artifactsFor(String taskId) {
        var list = taskId == null ? null : artifactCache.get(taskId);
        return list == null ? Stream.empty() : list.stream();
    }

    /**
     * Health: the agent binary answers {@code --version}. Same availability
     * rule as every CLI backend; no session is opened.
     */
    @Override
    public CompletableFuture<Boolean> healthCheck() {
        return CompletableFuture.supplyAsync(() -> {
            if (agentCommand.isEmpty()) return false;
            try {
                var probe = new ArrayList<>(List.of(agentCommand.get(0), "--version"));
                var pb = new ProcessBuilder(probe);
                EgressGate.defaultInstance().applyEnv(pb.environment(), Map.of());
                var p = pb.start();
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return false;
                }
                return p.exitValue() == 0;
            } catch (Exception e) {
                log.info("ACP agent '{}' not available: {}",
                    agentCommand.get(0), e.getMessage());
                return false;
            }
        });
    }

    @Override
    public long estimatedCu(TaskSpec spec) { return 0L; }

    // -- update → artifact translation ------------------------------------

    /**
     * The shared result document out of the prompt response's {@code _meta}.
     *
     * <p>CodeZaiku emits it under {@code codezaiku}; before the rename it was
     * {@code codezaiku}. That key is NOT covered by the environment-variable
     * aliases, and {@link JsonNode#path} answers a missing key with a
     * MissingNode rather than throwing — so reading only one spelling against
     * the other binary loses every typed artifact in silence and falls back to
     * scraping the message text. Read the new name first, then the old, so both
     * binaries work until the aliases go at 1.0.</p>
     */
    private static JsonNode resultMeta(JsonNode response) {
        var meta = response.path("_meta");
        var current = meta.path("codezaiku");
        return current.isObject() ? current : meta.path("codeplane");
    }

    private List<CodingArtifact> toArtifacts(UUID taskId, String cwd,
                                              List<JsonNode> updates, JsonNode metaResult) {
        // 1. Typed follow-along paths from tool calls.
        var files = new LinkedHashSet<String>();
        var messageText = new StringBuilder();
        for (var params : updates) {
            var update = params.path("update");
            var kind = update.path("sessionUpdate").asText("");
            if ("tool_call".equals(kind) || "tool_call_update".equals(kind)) {
                for (var loc : update.path("locations")) {
                    var path = loc.path("path").asText(null);
                    if (path != null && !path.isBlank()) files.add(path);
                }
            } else if ("agent_message_chunk".equals(kind)) {
                messageText.append(update.path("content").path("text").asText(""));
            }
        }

        // 2. The shared result document — preferred home is the prompt
        //    response's _meta.codezaiku (CodeZaiku's ACP contract); fall back
        //    to a JSON block in the final message text. One shape either way.
        var parsed = metaResult != null && metaResult.isObject() && metaResult.has("files")
            ? metaResult
            : CodeZaikuBackend.parseResultJson(messageText.toString());
        if (parsed != null && parsed.has("files")) {
            var meta = new HashMap<String, Object>();
            meta.put("source", name + "-acp");
            var resultFiles = new ArrayList<String>();
            for (var f : parsed.get("files")) resultFiles.add(f.asText());
            files.addAll(resultFiles);

            var build = new BuildArtifact(UUID.randomUUID(), name, taskId.toString(),
                null, parsed.path("status").asText("untested"),
                parsed.path("testsPassed").asInt(0),
                parsed.path("testsFailed").asInt(0),
                Instant.now(), Map.copyOf(meta));
            var srcMeta = new HashMap<String, Object>();
            srcMeta.put("source", name + "-acp");
            srcMeta.put("__sibling_build", build);
            var src = new SourceArtifact(UUID.randomUUID(), name, taskId.toString(),
                parsed.hasNonNull("workspacePath")
                    ? parsed.get("workspacePath").asText() : cwd,
                List.copyOf(files),
                parsed.hasNonNull("gitRef") ? parsed.get("gitRef").asText() : null,
                Instant.now(), Map.copyOf(srcMeta));
            return List.of(src, build);
        }

        // 3. Locations-only: still typed — the schema'd field, not stdout
        //    scraping. No files at all yields an EMPTY artifact list, which
        //    callers can see plainly (never a fabricated success payload).
        if (files.isEmpty()) return List.of();
        var srcMeta = new HashMap<String, Object>();
        srcMeta.put("source", name + "-acp");
        return List.of(new SourceArtifact(UUID.randomUUID(), name, taskId.toString(),
            cwd, List.copyOf(files), null, Instant.now(), Map.copyOf(srcMeta)));
    }
}
