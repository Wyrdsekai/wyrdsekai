package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.codezaiku.CodeItemGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link BackendAdapter} for the CodeZaiku backend.
 *
 * <p>Translates CodeZaiku {@code board_completed} {@link
 * AgentEvent.ZoneBroadcast} events into {@link SourceArtifact}s, with the
 * optional sibling {@link BuildArtifact} stashed under
 * {@code backendMetadata.__sibling_build} so {@link CodingTaskItemBridge}
 * can place both items in the same room from a single event.</p>
 *
 * <p>.</p>
 */
public final class CodeZaikuEventAdapter implements BackendAdapter {

    private static final Logger log = LoggerFactory.getLogger(CodeZaikuEventAdapter.class);

    private final CodeItemGenerator generator;

    /**
     * @param generator CodeZaiku's existing item generator. Persists the
     *                  {@link SourceArtifact} / {@link BuildArtifact} pair
     *                  to the legacy {@code codex_items} / {@code artifact_items}
     *                  tables as a side effect — keeps the existing storage
     *                  path untouched. May be {@code null} for tests that
     *                  don't need persistence.
     */
    public CodeZaikuEventAdapter(CodeItemGenerator generator) {
        this.generator = generator;
    }

    @Override public String namespace() { return CodeZaikuBackend.NAME; }

    @Override
    public CodingArtifact translateEvent(AgentEvent.ZoneBroadcast event) {
        if (event == null) return null;
        if (!CodeZaikuBackend.NAME.equals(event.namespace())) return null;
        if (!(event.message() instanceof S2CMessage.ZoneResponse zoneResp)) return null;

        var data = zoneResp.data();
        if (data == null || !data.has("event")) return null;
        // The CLI contract reports in the generic terminal shape; the board protocol is
        // the other door. A backend reached through `codezaiku run` never sends a board.
        if ("task_completed".equals(data.path("event").asText())) {
            return TerminalTaskArtifact.from(data, CodeZaikuBackend.NAME).orElse(null);
        }
        if (!"board_completed".equals(data.path("event").asText())) return null;

        var workspace = textOrNull(data, "workspace");
        var hostNode = textOrNull(data, "hostNode");
        var boardId = textOrNull(data, "boardId");
        var language = textOrNull(data, "language");
        var createdBy = textOrNull(data, "createdBy");
        var buildStatus = textOrNull(data, "buildStatus");

        if (workspace == null || boardId == null || hostNode == null) {
            log.warn("CodeZaiku: incomplete board completion metadata: {}", data);
            return null;
        }

        var files = new ArrayList<String>();
        if (data.has("files") && data.get("files").isArray()) {
            for (JsonNode fileNode : data.get("files")) {
                files.add(fileNode.asText());
            }
        }
        int testsPassed = data.path("testsPassed").asInt(0);
        int testsFailed = data.path("testsFailed").asInt(0);

        // Persist via the existing generator. Returns a freshly-minted
        // SourceArtifact (and optional BuildArtifact) — same shape we hand
        // back below, just with persisted IDs.
        SourceArtifact persistedSource = null;
        BuildArtifact persistedBuild = null;
        if (generator != null) {
            var pair = generator.generateFromBoardCompletion(
                boardId, workspace, hostNode, language, files, createdBy,
                testsPassed, testsFailed, buildStatus);
            persistedSource = pair.source();
            persistedBuild = pair.build();
        }

        // Build the SourceArtifact we hand back to the bridge. Re-use the
        // generator-persisted instance when available so IDs stay
        // consistent across the persistence + room-placement paths.
        if (persistedSource != null) {
            // Stash sibling build (if any) under the magic key for the
            // bridge to extract and render.
            if (persistedBuild != null) {
                var srcMetadata = new LinkedHashMap<String, Object>(
                    persistedSource.backendMetadata() != null
                        ? persistedSource.backendMetadata()
                        : Map.of());
                srcMetadata.put("__sibling_build", persistedBuild);
                persistedSource = new SourceArtifact(
                    persistedSource.artifactId(),
                    persistedSource.backend(),
                    persistedSource.taskId(),
                    persistedSource.workspacePath(),
                    persistedSource.files(),
                    persistedSource.gitRef(),
                    persistedSource.createdAt(),
                    Map.copyOf(srcMetadata)
                );
            }
            return persistedSource;
        }

        // Generator-less path (test fixtures): mint records inline so the
        // bridge still gets a complete source + sibling-build pair.
        var srcMetadata = new HashMap<String, Object>();
        srcMetadata.put("boardId", boardId);
        srcMetadata.put("hostNode", hostNode);
        if (language != null) srcMetadata.put("language", language);
        if (createdBy != null) srcMetadata.put("createdBy", createdBy);

        if (buildStatus != null) {
            var buildMetadata = new HashMap<String, Object>();
            buildMetadata.put("boardId", boardId);
            buildMetadata.put("hostNode", hostNode);
            buildMetadata.put("artifactType", "script");
            buildMetadata.put("artifactPath", workspace + "/build");

            var build = new BuildArtifact(
                UUID.randomUUID(),
                CodeZaikuBackend.NAME,
                boardId,
                boardId,
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
            CodeZaikuBackend.NAME,
            boardId,
            workspace,
            List.copyOf(files),
            null,
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
    }

    @Override
    public TaskSpec parsePlayerCommand(String command, String args) {
        // The Workshop room currently dispatches commands through the
        // legacy zone-command path (codezaiku.create with structured
        // payload). Phase 1b will route player commands through the
        // generic TaskSpec path; for Phase 1a this method is a stub that
        // wraps the args as a description.
        if (command == null || command.isBlank()) return null;
        return new TaskSpec(
            UUID.randomUUID(),
            null,
            command,
            args != null ? args : "",
            null,
            List.of(),
            0L,
            null
        );
    }

    private static String textOrNull(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        var text = node.get(field).asText();
        return text.isBlank() ? null : text;
    }
}
