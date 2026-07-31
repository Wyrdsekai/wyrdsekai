package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@link BackendAdapter} for the Devin backend (Phase 2e).
 *
 * <p>Translates Devin-shaped {@link AgentEvent.ZoneBroadcast} events
 * (emitted by {@link DevinBackend} once a session reaches a terminal
 * state) into generic {@link CodingArtifact}s, and turns Workshop-room
 * player commands into {@link TaskSpec}s the backend can submit.</p>
 *
 * <p>Devin only meaningfully handles {@code create}-flavoured tasks
 * (it's an async cloud session, not a per-edit tool); refactor /
 * explore commands are still routed to a {@link TaskSpec} but the
 * companion's policy script is responsible for picking a more local
 * backend for narrow tasks.</p>
 */
public final class DevinEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(DevinEventAdapter.class);

    @Override public String namespace() { return DevinBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!DevinBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;

        var eventName = data.path("event").asText("");
        if (!"task_completed".equals(eventName)) return null;

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var sessionId = textOrNull(data, "session_id");
        var prUrl = textOrNull(data, "pull_request_url");
        var prTitle = textOrNull(data, "pull_request_title");
        var statusEnum = textOrNull(data, "status_enum");

        if (taskId == null || workspace == null) {
            log.warn("Devin: incomplete task_completed metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", "devin");
        if (sessionId != null) srcMetadata.put("session_id", sessionId);
        if (prUrl != null) srcMetadata.put("pull_request_url", prUrl);
        if (prTitle != null) srcMetadata.put("pull_request_title", prTitle);
        if (statusEnum != null) srcMetadata.put("status_enum", statusEnum);

        return new SourceArtifact(
            UUID.randomUUID(),
            DevinBackend.NAME,
            taskId,
            workspace,
            List.copyOf(files),
            null,
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        if (command == null || command.isBlank()) return null;

        var verb = command.trim().toLowerCase(Locale.ROOT);
        var description = args == null ? "" : args.trim();

        // Devin's primary mode is "create" — a long-running session.
        // We accept create / code / implement here; refactor / explore
        // still flow through but the policy script SHOULD steer them
        // to a cheaper backend.
        if ("create".equals(verb)) verb = "code";

        return new TaskSpec(
            UUID.randomUUID(),
            null,
            verb,
            description,
            null,
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
