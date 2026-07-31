package org.wyrdsekai.core.coding;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Source-code artifact produced by a coding task.
 *
 * <p>. Backend-specific extras (CodePlane
 * boardId, Aider session id, OpenHands trace id, etc.) live in
 * {@code backendMetadata} so the wire shape stays uniform across backends.</p>
 *
 * @param artifactId       unique ID assigned at minting.
 * @param backend          {@link CodingTaskBackend#name()} value.
 * @param taskId           originating task ID.
 * @param workspacePath    path on disk on the {@code hostNode} where the
 *                          source lives.
 * @param files            files relative to {@code workspacePath} that
 *                          the task touched.
 * @param gitRef           current git ref ({@code main}, a SHA, a branch);
 *                          null for non-git workspaces.
 * @param backendMetadata  free-form per-backend extras. Keep keys
 *                          lowercase-with-underscores; values must be
 *                          JSON-serialisable scalars/lists/maps.
 */
public record SourceArtifact(
    UUID artifactId,
    String backend,
    String taskId,
    String workspacePath,
    List<String> files,
    String gitRef,
    Instant createdAt,
    Map<String, Object> backendMetadata
) implements CodingArtifact {

    public SourceArtifact {
        if (artifactId == null) artifactId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (files == null) files = List.of();
        if (backendMetadata == null) backendMetadata = Map.of();
    }
}
