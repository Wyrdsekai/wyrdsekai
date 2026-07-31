package org.wyrdsekai.core.coding;

import java.time.Instant;
import java.util.UUID;

/**
 * A backend-agnostic artifact produced by a coding task.
 *
 * <p>. Two concrete variants:</p>
 * <ul>
 *   <li>{@link SourceArtifact} — a body of source code (workspace + files + git ref).</li>
 *   <li>{@link BuildArtifact} — a build/test/deploy output derived from a SourceArtifact.</li>
 * </ul>
 *
 * <p>The legacy CodePlane-specific shims ({@code CodexItem} /
 * {@code ArtifactItem}) were removed in the Phase 2 cleanup pass — the
 * canonical shape is now this sealed family. CodePlane-specific extras
 * (board ID, host node, language) live under
 * {@link SourceArtifact#backendMetadata() backendMetadata}.</p>
 */
public sealed interface CodingArtifact permits SourceArtifact, BuildArtifact {

    /** UUID assigned when the artifact was minted. */
    UUID artifactId();

    /** {@link CodingTaskBackend#name() backend.name()} that produced this artifact. */
    String backend();

    /** {@link TaskSpec#taskId taskId} this artifact was produced for. Stringly-typed for backends that don't use UUIDs natively. */
    String taskId();

    /** When the artifact was minted on the host that holds it. */
    Instant createdAt();
}
