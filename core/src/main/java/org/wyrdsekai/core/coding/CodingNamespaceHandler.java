package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.NamespaceHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The single namespace handler that owns every verb for one coding
 * backend (e.g. {@code openhands.*}, {@code opencode.*},
 * {@code codeplane.*}). One instance is registered per backend in
 * {@link CodingBackendBootstrap}; the {@link
 * org.wyrdsekai.core.agent.LocalCommandRouter} keys them by backend
 * name.
 *
 * <p>. The handler:</p>
 * <ol>
 *   <li>looks up the backend in {@link BackendRegistry};</li>
 *   <li>dispatches the verb to the matching backend method
 *       (Phase B: {@code create}, {@code examine}; Phase C extends
 *       to {@code run}, {@code diff}, {@code log}, {@code test},
 *       {@code deploy});</li>
 *   <li>for {@code create}, awaits the future and on completion
 *       publishes a synthetic terminal {@link
 *       org.wyrdsekai.common.protocol.S2CMessage.ZoneResponse} via
 *       {@link AgentEventStream#publishZoneBroadcast} — the format
 *       {@link OpenHandsEventAdapter} / {@link OpenCodeEventAdapter}
 *       already parse — so {@link CodingTaskItemBridge} picks it up
 *       and places the codex/artifact items in the originating room.</li>
 * </ol>
 *
 * <p>Acks are immediate: on {@code create} the caller gets a {@code
 * Prose} "submitted" line back so player narration / agent feedback
 * doesn't block on the agent loop. The terminal {@code Prose} arrives
 * later through the same router-respond callback when the future
 * completes.</p>
 */
public final class CodingNamespaceHandler implements NamespaceHandler {

    private static final Logger log = LoggerFactory.getLogger(CodingNamespaceHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String backendName;
    private final BackendRegistry registry;

    public CodingNamespaceHandler(String backendName, BackendRegistry registry) {
        this.backendName = backendName;
        this.registry = registry != null ? registry : BackendRegistry.get();
    }

    /** Test seam: package-visible accessor for the backend name. */
    String backendName() { return backendName; }

    @Override
    public void dispatch(String entityId, String verb, List<String> args,
                         Map<String, String> payload, Consumer<S2CMessage> respond) {
        var backend = registry.backendFor(backendName).orElse(null);
        if (backend == null) {
            respond.accept(error("backend_unavailable",
                "no backend registered for '" + backendName + "'"));
            return;
        }

        switch (verb == null ? "" : verb.toLowerCase()) {
            case "create" -> handleCreate(entityId, payload, respond, backend);
            case "examine" -> handleExamine(args, payload, respond, backend);
            case "run" -> handleRun(args, payload, respond, backend);
            case "test" -> handleTest(args, payload, respond, backend);
            case "destroy" -> handleDestroy(args, payload, respond, backend);
            // Remaining verbs (diff, log, deploy, copy, move, apply,
            // build) land in Phase C extensions when more backends opt
            // in. Until then we surface "unsupported_verb" so callers
            // can degrade without mistaking it for a routing failure.
            default -> respond.accept(error("unsupported_verb",
                backendName + "." + verb + " is not yet wired"));
        }
    }

    // ─── Verb: create ────────────────────────────────────────────

    private void handleCreate(String entityId, Map<String, String> payload,
                              Consumer<S2CMessage> respond,
                              CodingTaskBackend backend) {
        var description = payload.getOrDefault("description",
            payload.getOrDefault("task", ""));
        if (description.isBlank()) {
            respond.accept(error("missing_field",
                "create requires 'description' (or legacy 'task') in payload"));
            return;
        }
        var taskType = payload.getOrDefault("taskType",
            payload.getOrDefault("intent", "code"));
        var roomId = payload.getOrDefault("roomId", "");

        var spec = TaskSpec.create(entityId, taskType, description);
        var taskId = spec.taskId().toString();

        // Immediate ack — narration uses this to tell the player work
        // is in flight. The terminal Prose arrives via the same
        // respond callback once the future completes.
        respond.accept(prose(backendName + " accepted task " + taskId
            + " — working on '" + truncate(description, 60) + "'"));

        backend.submitTask(spec).whenComplete((result, err) -> {
            try {
                if (err != null) {
                    log.warn("[CodingNamespaceHandler] {} task {} failed before completion: {}",
                        backendName, taskId, err.toString());
                    respond.accept(error("task_failed",
                        backendName + " task threw: " + err.getMessage()));
                    return;
                }
                publishTerminal(roomId, spec, result, backend);
                respond.accept(prose(backendName + " task " + taskId
                    + " completed (" + result.status() + "): "
                    + truncate(safe(result.summary()), 200)));
            } catch (Exception e) {
                log.warn("[CodingNamespaceHandler] post-completion handling failed: {}",
                    e.toString());
            }
        });
    }

    // ─── Verb: examine ───────────────────────────────────────────
    //
    // Two modes:
    //   1. examine <artifactId>  → backend.examineArtifact(uuid) — file
    //      previews (Phase C). Distinguish artifactId from taskId by
    //      attempting UUID parse first.
    //   2. examine <taskId>      → list-only summary from cached
    //      artifactsFor(taskId). Always available.

    private void handleExamine(List<String> args, Map<String, String> payload,
                               Consumer<S2CMessage> respond,
                               CodingTaskBackend backend) {
        var raw = !args.isEmpty() ? args.get(0)
            : payload.getOrDefault("artifactId",
                payload.getOrDefault("taskId", ""));
        if (raw.isBlank()) {
            respond.accept(error("missing_field",
                "examine requires an artifactId or taskId argument"));
            return;
        }
        var asUuid = tryParseUuid(raw);
        if (asUuid != null) {
            backend.examineArtifact(asUuid).whenComplete((res, err) -> {
                if (err != null) {
                    respond.accept(error("examine_failed",
                        backendName + ".examine threw: " + err.getMessage()));
                    return;
                }
                if (res == null || res.isUnsupported()) {
                    // Fallback: treat raw as taskId-shaped string for
                    // legacy callers that pass a UUID-formatted taskId.
                    examineByTaskId(raw, backend, respond);
                    return;
                }
                respond.accept(prose(formatExamine(res)));
            });
            return;
        }
        examineByTaskId(raw, backend, respond);
    }

    private void examineByTaskId(String taskId, CodingTaskBackend backend,
                                 Consumer<S2CMessage> respond) {
        var artifacts = backend.artifactsFor(taskId).toList();
        if (artifacts.isEmpty()) {
            respond.accept(prose("No artifacts known for " + backendName
                + " task " + taskId));
            return;
        }
        var lines = new ArrayList<String>();
        lines.add("Artifacts for " + backendName + " task " + taskId + ":");
        for (var a : artifacts) {
            switch (a) {
                case SourceArtifact s -> lines.add("  codex "
                    + s.artifactId().toString().substring(0, 8)
                    + " — " + (s.files() == null ? 0 : s.files().size()) + " file(s)");
                case BuildArtifact b -> lines.add("  artifact "
                    + b.artifactId().toString().substring(0, 8)
                    + " — " + b.status() + " (tests "
                    + b.testsPassed() + "/"
                    + (b.testsPassed() + b.testsFailed()) + ")");
            }
        }
        respond.accept(prose(String.join("\n", lines)));
    }

    // ─── Verb: run ───────────────────────────────────────────────

    private void handleRun(List<String> args, Map<String, String> payload,
                            Consumer<S2CMessage> respond,
                            CodingTaskBackend backend) {
        var artifactId = parseArtifactId(args, payload);
        if (artifactId == null) {
            respond.accept(error("missing_field",
                "run requires an artifactId argument or payload field"));
            return;
        }
        // Tail of args is forwarded as argv to the entrypoint.
        var tail = args.size() > 1 ? args.subList(1, args.size()) : List.<String>of();
        backend.runArtifact(artifactId, tail, Map.of()).whenComplete((res, err) -> {
            if (err != null) {
                respond.accept(error("run_failed",
                    backendName + ".run threw: " + err.getMessage()));
                return;
            }
            respond.accept(prose(formatExec(res, "run")));
        });
    }

    // ─── Verb: test ──────────────────────────────────────────────

    private void handleTest(List<String> args, Map<String, String> payload,
                             Consumer<S2CMessage> respond,
                             CodingTaskBackend backend) {
        var artifactId = parseArtifactId(args, payload);
        if (artifactId == null) {
            respond.accept(error("missing_field",
                "test requires an artifactId argument or payload field"));
            return;
        }
        backend.testArtifact(artifactId).whenComplete((res, err) -> {
            if (err != null) {
                respond.accept(error("test_failed",
                    backendName + ".test threw: " + err.getMessage()));
                return;
            }
            respond.accept(prose(formatExec(res, "test")));
        });
    }

    // ─── Verb: destroy ───────────────────────────────────────────

    private void handleDestroy(List<String> args, Map<String, String> payload,
                                Consumer<S2CMessage> respond,
                                CodingTaskBackend backend) {
        var artifactId = parseArtifactId(args, payload);
        if (artifactId == null) {
            respond.accept(error("missing_field",
                "destroy requires an artifactId argument or payload field"));
            return;
        }
        backend.destroyArtifact(artifactId).whenComplete((ok, err) -> {
            if (err != null) {
                respond.accept(error("destroy_failed",
                    backendName + ".destroy threw: " + err.getMessage()));
                return;
            }
            respond.accept(prose(Boolean.TRUE.equals(ok)
                ? "Destroyed " + backendName + " artifact " + artifactId
                : backendName + " could not destroy artifact "
                    + artifactId + " (unsupported or unknown)"));
        });
    }

    // ─── Internals ──────────────────────────────────────────────

    /**
     * Build the synthetic terminal payload that {@link
     * OpenHandsEventAdapter#translateEvent} (and friends) parse, then
     * publish it as a {@link AgentEventStream#publishZoneBroadcast}.
     * Bridge code already knows how to translate this into a {@link
     * SourceArtifact} (+ optional {@link BuildArtifact} sibling) and
     * place {@link org.wyrdsekai.common.model.RoomObject}s in {@code
     * roomId}.
     *
     * <p>The shape here MUST stay aligned with the adapter's
     * {@code isTerminalShape} contract — V1 agent server uses
     * {@code kind=ConversationStateUpdateEvent + value=finished}, but
     * the legacy MCP-era flatten {@code event=task_completed} also
     * works and is what we emit (it covers all backends, not just
     * OpenHands V1). Adapters that aren't OpenHands ignore the V1
     * fields cleanly.</p>
     */
    private void publishTerminal(String roomId, TaskSpec spec,
                                 TaskResult result, CodingTaskBackend backend) {
        if (roomId == null || roomId.isBlank()) {
            log.debug("[CodingNamespaceHandler] no roomId for {} task {}; "
                + "skipping ZoneBroadcast (caller didn't pass roomId in payload)",
                backendName, result.taskId());
            return;
        }
        var stream = AgentEventStream.get();
        if (stream == null) {
            log.debug("[CodingNamespaceHandler] AgentEventStream not initialised; "
                + "skipping ZoneBroadcast for {} task {}",
                backendName, result.taskId());
            return;
        }

        var data = MAPPER.createObjectNode();
        data.put("event", result.status() == TaskStatus.SUCCEEDED
            ? "task_completed" : "task_failed");
        data.put("taskId", spec.taskId().toString());
        data.put("status", result.status().name().toLowerCase());

        // Pull file list + workspace from the cached SourceArtifact.
        var artifacts = backend.artifactsFor(spec.taskId().toString()).toList();
        var src = artifacts.stream()
            .filter(a -> a instanceof SourceArtifact)
            .map(a -> (SourceArtifact) a)
            .findFirst().orElse(null);
        if (src != null) {
            data.put("workspace", safe(src.workspacePath()));
            var files = data.putArray("files");
            if (src.files() != null) src.files().forEach(files::add);
            if (src.backendMetadata() != null) {
                var av = src.backendMetadata().get("agent_version");
                if (av != null) data.put("agentVersion", String.valueOf(av));
            }
        } else {
            // No SourceArtifact captured — synthesise minimal fields so
            // the adapter can still parse and produce a stub codex.
            data.put("workspace", "");
            data.putArray("files");
        }

        // Sibling build payload, if a BuildArtifact was emitted.
        var build = artifacts.stream()
            .filter(a -> a instanceof BuildArtifact)
            .map(a -> (BuildArtifact) a)
            .findFirst().orElse(null);
        if (build != null) {
            ObjectNode b = data.putObject("build");
            b.put("status", safe(build.status()));
            b.put("testsPassed", build.testsPassed());
            b.put("testsFailed", build.testsFailed());
        }

        var msg = new S2CMessage.ZoneResponse(0, spec.taskId().toString(),
            backendName, "task " + result.status().name().toLowerCase(),
            (JsonNode) data, List.of());
        stream.publishZoneBroadcast(backendName, roomId, msg);
        log.debug("[CodingNamespaceHandler] published terminal ZoneBroadcast "
            + "(ns={}, room={}, taskId={}, files={})",
            backendName, roomId, result.taskId(),
            src == null || src.files() == null ? 0 : src.files().size());
    }

    private static S2CMessage.Prose prose(String text) {
        return new S2CMessage.Prose(0, "system", text, List.of(), null, "normal", null);
    }

    private static S2CMessage.Error error(String code, String message) {
        return new S2CMessage.Error(0, code, message, null);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null) return null;
        try { return UUID.fromString(raw.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Pull a UUID artifact id from args[0] or payload. Returns null
     * when neither is a valid UUID — handlers narrate this as
     * missing_field.
     */
    private static UUID parseArtifactId(List<String> args, Map<String, String> payload) {
        if (args != null && !args.isEmpty()) {
            var u = tryParseUuid(args.get(0));
            if (u != null) return u;
        }
        var fromPayload = payload.getOrDefault("artifactId", "");
        if (!fromPayload.isBlank()) return tryParseUuid(fromPayload);
        return null;
    }

    private static String formatExamine(ExamineResult res) {
        var sb = new StringBuilder();
        sb.append("Codex ").append(res.artifactId() == null ? "?"
            : res.artifactId().toString().substring(0, 8))
          .append(" (").append(res.backend()).append(")");
        if (res.workspacePath() != null && !res.workspacePath().isBlank()) {
            sb.append("\nWorkspace: ").append(res.workspacePath());
        }
        if (!res.notes().isEmpty()) {
            sb.append("\nNotes: ").append(String.join("; ", res.notes()));
        }
        sb.append("\nFiles (").append(res.files().size()).append("):");
        for (var f : res.files()) {
            sb.append("\n  ").append(f);
            var preview = res.filePreviews().get(f);
            if (preview != null && !preview.isBlank()) {
                var firstLines = preview.lines().limit(3)
                    .reduce((a, b) -> a + "\n      " + b)
                    .orElse(preview);
                sb.append("\n      ").append(truncate(firstLines, 240));
            }
        }
        return sb.toString();
    }

    private static String formatExec(ExecResult res, String verbLabel) {
        if (res == null) return "no result";
        if (res.isUnsupported()) {
            return verbLabel + " unsupported: " + res.unsupportedReason();
        }
        var sb = new StringBuilder();
        sb.append(res.success() ? "Ran " : "Failed ").append("`")
          .append(res.entrypoint()).append("` (exit=")
          .append(res.exitCode()).append(", ")
          .append(res.duration().toMillis()).append("ms)");
        if (!res.stdout().isBlank()) {
            sb.append("\nstdout:\n").append(truncate(res.stdout(), 2048));
        }
        if (!res.stderr().isBlank()) {
            sb.append("\nstderr:\n").append(truncate(res.stderr(), 1024));
        }
        return sb.toString();
    }
}
