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
import java.util.Map;
import java.util.UUID;

/**
 * {@link BackendAdapter} for the Goose backend.
 *
 * <p>Translates Goose-shaped {@link AgentEvent.ZoneBroadcast} events
 * (emitted by {@link GooseBackend} once a task completes) into generic
 * {@link CodingArtifact}s, and turns Workshop-room player commands into
 * {@link TaskSpec}s the backend can submit.</p>
 *
 * <p>Mirrors {@link OpenCodeEventAdapter} in shape; the only material
 * difference is the {@code source = "goose"} provenance tag and the
 * Goose-specific provider field on the metadata.</p>
 */
public final class GooseEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(GooseEventAdapter.class);

    @Override public String namespace() { return GooseBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!GooseBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;

        var eventName = data.path("event").asText("");
        if (!"task_completed".equals(eventName)) return null;

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var provider = textOrNull(data, "provider");
        var status = textOrNull(data, "status");

        if (taskId == null || workspace == null) {
            log.warn("Goose: incomplete task_completed metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", "goose");
        if (provider != null) srcMetadata.put("provider", provider);
        if (status != null) srcMetadata.put("status", status);

        if (data.has("buildStatus") && !data.get("buildStatus").isNull()) {
            int testsPassed = data.path("testsPassed").asInt(0);
            int testsFailed = data.path("testsFailed").asInt(0);
            String buildStatus = data.get("buildStatus").asText();

            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("source", "goose");
            buildMetadata.put("workspace", workspace);
            if (provider != null) buildMetadata.put("provider", provider);

            var build = new BuildArtifact(
                UUID.randomUUID(),
                GooseBackend.NAME,
                taskId,
                taskId,
                buildStatus,
                testsPassed,
                testsFailed,
                Instant.now(),
                Map.copyOf(buildMetadata)
            );
            srcMetadata.put("__sibling_build", build);
        }

        return new SourceArtifact(
            UUID.randomUUID(),
            GooseBackend.NAME,
            taskId,
            workspace,
            List.copyOf(files),
            null, // Goose doesn't surface a git ref directly
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        if (command == null || command.isBlank()) return null;

        var verb = command.trim().toLowerCase();
        var description = args == null ? "" : args.trim();

        if ("create".equals(verb)) verb = "code";

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
