package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one parser for the terminal shape every CLI-contract backend reports in.
 *
 * <h2>Why this exists</h2>
 * {@link CodingTaskBroadcast#publishTerminal} emits one shape for every backend:
 * {@code event: "task_completed", taskId, workspace, files[]}. Each adapter then parsed
 * it with its own copy — OpenCode, Codex, OpenHands — and {@code CodeZaikuEventAdapter}
 * parsed a different shape entirely (the board protocol: {@code event: "board_completed"},
 * {@code boardId}, {@code hostNode}). So on 2026-08-23 09:06 CodeZaiku finished a real
 * item, the broadcast went out with {@code files=1}, the adapter returned null on
 * {@code data.has("event")}, and the bridge dropped it without a word. Two tasks, two
 * working files on disk, nothing registered, nothing placed, nothing logged.
 *
 * <p>A backend that reports through the CLI contract reports in this shape. One parser;
 * every adapter may fall back to it; none has to re-derive it.
 */
public final class TerminalTaskArtifact {

    private static final Logger log = LoggerFactory.getLogger(TerminalTaskArtifact.class);

    private TerminalTaskArtifact() {}

    /** A {@link SourceArtifact} from a {@code task_completed} payload, or empty if it is not one. */
    public static Optional<SourceArtifact> from(JsonNode data, String backendName) {
        if (data == null || !"task_completed".equals(data.path("event").asText(""))) {
            return Optional.empty();
        }
        var taskId = text(data, "taskId");
        var workspace = text(data, "workspace");
        if (taskId == null) {
            log.warn("{}: task_completed without a taskId: {}", backendName, data);
            return Optional.empty();
        }
        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode f : data.get("files")) {
                if (f.isTextual() && !f.asText().isBlank()) files.add(f.asText());
            }
        }
        var meta = new HashMap<String, Object>();
        meta.put("source", backendName);
        for (var k : List.of("status", "model", "provider", "agentVersion")) {
            var v = text(data, k);
            if (v != null) meta.put(k, v);
        }
        return Optional.of(new SourceArtifact(UUID.randomUUID(), backendName, taskId,
            workspace == null ? "" : workspace, files, null, Instant.now(), meta));
    }

    private static String text(JsonNode n, String key) {
        var v = n.path(key);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
