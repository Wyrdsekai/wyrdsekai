package org.wyrdsekai.core.coding;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Build / test / deployable artifact derived from a {@link SourceArtifact}.
 *
 * <p>. Concrete examples: a packaged jar
 * a Docker image, a stripped binary, the output of {@code aider --test},
 * an OpenHands run trace.</p>
 *
 * @param artifactId        unique ID assigned at minting.
 * @param backend           {@link CodingTaskBackend#name()} value.
 * @param taskId            originating task ID.
 * @param sourceArtifactId  ID of the {@link SourceArtifact} this build came
 *                           from. Free-form string (not enforced UUID) so
 *                           legacy CodeZaiku codex IDs (8-hex) still fit.
 * @param status            {@code "success"}, {@code "failed"},
 *                           {@code "untested"}, or backend-specific value.
 * @param testsPassed       number of tests that passed (0 if no test step).
 * @param testsFailed       number of tests that failed (0 if no test step).
 * @param backendMetadata   free-form per-backend extras (artifact path,
 *                           build status string, host node, etc.).
 */
public record BuildArtifact(
    UUID artifactId,
    String backend,
    String taskId,
    String sourceArtifactId,
    String status,
    int testsPassed,
    int testsFailed,
    Instant createdAt,
    Map<String, Object> backendMetadata
) implements CodingArtifact {

    public BuildArtifact {
        if (artifactId == null) artifactId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (backendMetadata == null) backendMetadata = Map.of();
    }
}
