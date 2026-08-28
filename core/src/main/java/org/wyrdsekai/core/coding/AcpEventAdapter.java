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
import java.util.UUID;

/**
 * {@link BackendAdapter} for the generic ACP backend.
 *
 * <p>{@link CodingNamespaceHandler} publishes the same generic
 * {@code task_completed} / {@code task_failed} ZoneBroadcast for every
 * backend, built from {@link CodingTaskBackend#artifactsFor}; this adapter
 * parses that shape under the {@code acp} namespace — structurally the
 * {@link GooseEventAdapter} translation with ACP provenance stamped on the
 * artifacts. (The interesting ACP-specific work — typed artifact paths from
 * {@code tool_call} updates, {@code _meta.codezaiku} result documents —
 * already happened inside {@link AcpBackend} before the cache was filled.)</p>
 */
public final class AcpEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(AcpEventAdapter.class);

    @Override public String namespace() { return AcpBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!AcpBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null) return null;
        if (!"task_completed".equals(data.path("event").asText(""))) return null;

        var taskId = textOrNull(data, "taskId");
        var workspace = textOrNull(data, "workspace");
        var status = textOrNull(data, "status");
        if (taskId == null || workspace == null) {
            log.warn("acp: incomplete task_completed metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }

        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("source", AcpBackend.NAME);
        if (status != null) srcMetadata.put("status", status);

        var sourceId = UUID.randomUUID();
        if (data.has("build") && data.get("build").isObject()) {
            var b = data.get("build");
            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("source", AcpBackend.NAME);
            buildMetadata.put("workspace", workspace);
            var build = new BuildArtifact(
                UUID.randomUUID(), AcpBackend.NAME, taskId, sourceId.toString(),
                textOrNull(b, "status"),
                b.path("testsPassed").asInt(0), b.path("testsFailed").asInt(0),
                Instant.now(), buildMetadata);
            srcMetadata.put("__sibling_build", build);
        }

        // gitRef always null: the house rule (only the steward commits)
        // extends to backends — our workspaces are never committed in.
        return new SourceArtifact(sourceId, AcpBackend.NAME, taskId,
            workspace, files, null, Instant.now(), srcMetadata);
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        if (command == null || command.isBlank()) return null;
        var verb = command.trim().toLowerCase();
        if ("create".equals(verb)) verb = "code";
        return new TaskSpec(
            UUID.randomUUID(),
            null,                     // companionDid filled in by caller
            verb,
            args == null ? "" : args.trim(),
            null,                     // workspaceHint set by Workshop later
            List.of(),
            0L,
            null);
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        var v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }
}
