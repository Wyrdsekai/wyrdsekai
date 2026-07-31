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
 * {@link BackendAdapter} for the Gemini CLI backend (Phase 2e).
 *
 * <p>Translates Gemini-shaped {@link AgentEvent.ZoneBroadcast} events
 * (emitted by {@link GeminiCliBackend} once a task completes) into
 * generic {@link CodingArtifact}s, and turns Workshop-room player
 * commands into {@link TaskSpec}s the backend can submit.</p>
 */
public final class GeminiCliEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(GeminiCliEventAdapter.class);

    @Override public String namespace() { return GeminiCliBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!GeminiCliBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;

        var eventName = data.path("event").asText("");
        if (!"task_completed".equals(eventName)) return null;

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var model = textOrNull(data, "model");
        var status = textOrNull(data, "status");

        if (taskId == null || workspace == null) {
            log.warn("Gemini CLI: incomplete task_completed metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", "gemini-cli");
        if (model != null) srcMetadata.put("model", model);
        if (status != null) srcMetadata.put("status", status);

        if (data.has("buildStatus") && !data.get("buildStatus").isNull()) {
            int testsPassed = data.path("testsPassed").asInt(0);
            int testsFailed = data.path("testsFailed").asInt(0);
            String buildStatus = data.get("buildStatus").asText();

            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("source", "gemini-cli");
            buildMetadata.put("workspace", workspace);
            if (model != null) buildMetadata.put("model", model);

            var build = new BuildArtifact(
                UUID.randomUUID(),
                GeminiCliBackend.NAME,
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
            GeminiCliBackend.NAME,
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
