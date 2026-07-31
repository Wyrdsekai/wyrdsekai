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
 * {@link BackendAdapter} for the OpenCode backend.
 *
 * <p>Translates OpenCode-shaped {@link AgentEvent.ZoneBroadcast} events
 * (emitted by the in-process {@link OpenCodeBackend} once a task
 * completes) into generic {@link CodingArtifact}s, and turns
 * Workshop-room player commands into {@link TaskSpec}s the backend
 * can submit.</p>
 *
 * <p>OpenCode runs as an in-process subprocess (no zone bridge required —
 * unlike CodePlane). Events still flow through the same
 * {@link AgentEvent.ZoneBroadcast} channel so the {@link CodingTaskItemBridge}
 * can place the resulting room objects without caring about the backend's
 * transport.</p>
 */
public final class OpenCodeEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenCodeEventAdapter.class);

    @Override public String namespace() { return OpenCodeBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!OpenCodeBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;

        // Phase 2b: the event payload mirrors the SourceArtifact's
        // metadata block so the bridge can re-hydrate it. CodePlane uses
        // a board_completed event sentinel; OpenCode uses task_completed.
        var eventName = data.path("event").asText("");
        if (!"task_completed".equals(eventName)) return null;

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var model = textOrNull(data, "model");
        var provider = textOrNull(data, "provider");
        var status = textOrNull(data, "status");

        if (taskId == null || workspace == null) {
            log.warn("OpenCode: incomplete task_completed metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", "opencode");
        if (model != null) srcMetadata.put("model", model);
        if (provider != null) srcMetadata.put("provider", provider);
        if (status != null) srcMetadata.put("status", status);

        // OpenCode emits a build summary as a sibling event (testsPassed
        // / testsFailed) when the agent ran a test step. Stash it under
        // __sibling_build so the bridge can place both items together,
        // matching the CodePlane pattern.
        if (data.has("buildStatus") && !data.get("buildStatus").isNull()) {
            int testsPassed = data.path("testsPassed").asInt(0);
            int testsFailed = data.path("testsFailed").asInt(0);
            String buildStatus = data.get("buildStatus").asText();

            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("source", "opencode");
            buildMetadata.put("workspace", workspace);
            if (model != null) buildMetadata.put("model", model);

            var build = new BuildArtifact(
                UUID.randomUUID(),
                OpenCodeBackend.NAME,
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
            OpenCodeBackend.NAME,
            taskId,
            workspace,
            List.copyOf(files),
            null, // OpenCode doesn't surface git ref directly
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        // The Workshop room exposes commands like `code <task>`,
        // `test <target>`, `review <target>`, etc. We forward each as a
        // TaskSpec with the verb as the taskType so the policy script
        // can route per-type if the household's preferences say so.
        if (command == null || command.isBlank()) return null;

        var verb = command.trim().toLowerCase();
        var description = args == null ? "" : args.trim();

        // Normalize the few aliases the Workshop dispatches: `code` and
        // `create` are equivalent for OpenCode's autonomous loop.
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
