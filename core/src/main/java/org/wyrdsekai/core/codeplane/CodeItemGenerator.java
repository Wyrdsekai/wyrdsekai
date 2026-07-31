package org.wyrdsekai.core.codeplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.BuildArtifact;
import org.wyrdsekai.core.coding.CodePlaneBackend;
import org.wyrdsekai.core.coding.SourceArtifact;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates {@link SourceArtifact} / {@link BuildArtifact} records from
 * CodePlane board completions and persists them via {@link CodeItemStore}.
 *
 * <p>Called when a {@code board_completed} event arrives via the zone
 * bridge ({@link org.wyrdsekai.core.coding.CodePlaneEventAdapter}). Creates
 * a SourceArtifact from the workspace metadata and an optional
 * BuildArtifact when the board included a build step.</p>
 *
 * <p> — the records are backend-agnostic
 * CodePlane-specific extras (board ID, host node, language) live in
 * {@code backendMetadata}.</p>
 */
public class CodeItemGenerator {

    private static final Logger log = LoggerFactory.getLogger(CodeItemGenerator.class);

    private final CodeItemStore store;

    public CodeItemGenerator(CodeItemStore store) {
        this.store = store;
    }

    /**
     * Generate code-artifact records from a board completion event.
     *
     * @param boardId      the CodePlane board that completed
     * @param workspace    path to the workspace on disk
     * @param hostNode     which node holds the workspace
     * @param language     primary language of the produced code
     * @param files        file paths relative to workspace root
     * @param createdBy    agent or player DID who requested the task
     * @param testsPassed  number of tests passed (0 if no build)
     * @param testsFailed  number of tests failed (0 if no build)
     * @param buildStatus  "success", "failed", "untested", or null if no build step
     * @return the generated source + optional build artifacts
     */
    public Result generateFromBoardCompletion(
            String boardId, String workspace, String hostNode,
            String language, List<String> files, String createdBy,
            int testsPassed, int testsFailed, String buildStatus) {

        var codexId = UUID.randomUUID().toString().substring(0, 8);
        var srcMetadata = new LinkedHashMap<String, Object>();
        srcMetadata.put("codexId", codexId);
        srcMetadata.put("boardId", boardId != null ? boardId : "");
        srcMetadata.put("hostNode", hostNode != null ? hostNode : "");
        srcMetadata.put("language", language != null ? language : "");
        if (createdBy != null) srcMetadata.put("createdBy", createdBy);

        var source = new SourceArtifact(
            UUID.nameUUIDFromBytes(("codeplane-codex-" + codexId).getBytes()),
            CodePlaneBackend.NAME,
            boardId,
            workspace,
            files != null ? List.copyOf(files) : List.of(),
            null,
            Instant.now(),
            Map.copyOf(srcMetadata)
        );
        store.saveSource(source);
        log.info("Generated codex {} for board {} ({} files)",
            codexId, boardId, files != null ? files.size() : 0);

        BuildArtifact build = null;
        if (buildStatus != null) {
            var artifactId = UUID.randomUUID().toString().substring(0, 8);
            var buildMetadata = new LinkedHashMap<String, Object>();
            buildMetadata.put("artifactId", artifactId);
            buildMetadata.put("boardId", boardId != null ? boardId : "");
            buildMetadata.put("hostNode", hostNode != null ? hostNode : "");
            buildMetadata.put("artifactType", "script");
            buildMetadata.put("artifactPath", workspace + "/build");

            build = new BuildArtifact(
                UUID.nameUUIDFromBytes(("codeplane-artifact-" + artifactId).getBytes()),
                CodePlaneBackend.NAME,
                boardId,
                codexId,
                buildStatus,
                testsPassed,
                testsFailed,
                Instant.now(),
                Map.copyOf(buildMetadata)
            );
            store.saveBuild(build);
            log.info("Generated artifact {} for codex {} (status: {})",
                artifactId, codexId, buildStatus);
        }

        return new Result(source, build);
    }

    /**
     * Result pair from item generation. {@code build} may be {@code null}
     * when the originating board produced no build step.
     */
    public record Result(SourceArtifact source, BuildArtifact build) {}
}
