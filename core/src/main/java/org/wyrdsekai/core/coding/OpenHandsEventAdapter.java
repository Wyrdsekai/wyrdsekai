package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link BackendAdapter} for the OpenHands backend.
 *
 * <p>Translates OpenHands V1 Agent Server events — re-emitted by
 * {@link OpenHandsBackend} as {@link AgentEvent.ZoneBroadcast}s — into
 * generic {@link CodingArtifact}s. Also parses Workshop-room player
 * commands into {@link TaskSpec}s.</p>
 *
 * <p><b>2026-05-05 reconciliation</b>: V1 uses a {@code kind} discriminator
 * (e.g. {@code MessageEvent}, {@code ActionEvent}, {@code ObservationEvent},
 * {@code ConversationStateUpdateEvent}) rather than the legacy {@code event}
 * field. Terminal state is signalled by a {@code ConversationStateUpdateEvent}
 * whose {@code value} is the literal string {@code "finished"} (or
 * {@code "error"} / {@code "stuck"}). The adapter accepts both shapes for
 * back-compat — events emitted by the old fabricated MCP path used
 * {@code event=complete}/{@code task_completed}.</p>
 *
 * <p>Mirrors {@link OpenCodeEventAdapter} in shape; the only material
 * difference is the OpenHands-specific event vocabulary and the
 * "explore-leaning" task-type heuristic in {@link #parsePlayerCommand}.</p>
 */
public final class OpenHandsEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenHandsEventAdapter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override public String namespace() { return OpenHandsBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!OpenHandsBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;

        // Two shapes accepted, in priority order:
        // 1. V1 Agent Server: {kind: "ConversationStateUpdateEvent", value: "finished", ...}
        //    — but adapter callers usually flatten to a synthetic "complete"
        //    payload that carries the task identity. We accept either.
        // 2. Legacy MCP-era flatten: {event: "complete"|"task_completed", ...}.
        if (!isTerminalShape(data)) {
            return null;
        }

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var status = textOrNull(data, "status");
        var agentVersion = textOrNull(data, "agentVersion");

        if (taskId == null || workspace == null) {
            log.warn("OpenHands: incomplete terminal metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", "openhands");
        if (status != null) srcMetadata.put("status", status);
        if (agentVersion != null) srcMetadata.put("agent_version", agentVersion);

        // Sibling build artifact — OpenHands surfaces the test outcome in
        // a nested `build` object on the terminal payload (when the
        // bridge has captured one).
        if (data.has("build") && data.get("build").isObject()) {
            var build = data.get("build");
            int testsPassed = build.path("testsPassed").asInt(0);
            int testsFailed = build.path("testsFailed").asInt(0);
            String buildStatus = build.path("status").asText("untested");

            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("source", "openhands");
            buildMetadata.put("workspace", workspace);
            if (agentVersion != null) buildMetadata.put("agent_version", agentVersion);

            var buildArtifact = new BuildArtifact(
                UUID.randomUUID(),
                OpenHandsBackend.NAME,
                taskId,
                taskId,
                buildStatus,
                testsPassed,
                testsFailed,
                Instant.now(),
                Map.copyOf(buildMetadata)
            );
            srcMetadata.put("__sibling_build", buildArtifact);
        }

        return new SourceArtifact(
            UUID.randomUUID(),
            OpenHandsBackend.NAME,
            taskId,
            workspace,
            List.copyOf(files),
            null, // OpenHands doesn't surface a git ref directly
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
    }

    /**
     * True iff {@code data} represents a terminal trace event the adapter
     * should consume. Accepts:
     * <ul>
     *   <li>V1 shape: {@code kind=ConversationStateUpdateEvent} +
     *       {@code value="finished"} (or {@code "error"}/{@code "stuck"}).</li>
     *   <li>Legacy MCP-era shape: {@code event=complete} or
     *       {@code event=task_completed}.</li>
     * </ul>
     */
    private static boolean isTerminalShape(JsonNode data) {
        // V1: kind discriminator + value string.
        var kind = data.path("kind").asText("");
        if ("ConversationStateUpdateEvent".equals(kind)) {
            var value = data.path("value");
            if (value.isTextual()) {
                String v = value.asText();
                return "finished".equals(v) || "error".equals(v) || "stuck".equals(v);
            }
        }
        // Legacy MCP-era flatten.
        var legacy = data.path("event").asText("");
        return "complete".equals(legacy) || "task_completed".equals(legacy);
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        if (command == null || command.isBlank()) return null;

        var verb = command.trim().toLowerCase();
        var description = args == null ? "" : args.trim();

        // Workshop verb normalisation. `code`/`create` map to a generic
        // task; OpenHands' shine is on explore/refactor/implement, so we
        // surface those verbs as their own task types so the policy
        // script can route them in.
        if ("create".equals(verb)) verb = "code";
        if ("survey".equals(verb) || "research".equals(verb)) verb = "explore";

        return new TaskSpec(
            UUID.randomUUID(),
            null,                     // companionDid filled in by caller
            verb,
            description,
            null,                     // workspaceHint set by Workshop later
            List.of(),
            0L,
            null
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        var t = node.get(field).asText();
        return t.isBlank() ? null : t;
    }
}
