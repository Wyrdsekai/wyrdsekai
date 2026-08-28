package org.wyrdsekai.core.coding;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a coding task submitted via {@link CodingTaskBackend#submitTask}.
 *
 * <p>. {@code artifactIds} reference items
 * placed into the world via the {@link CodingTaskItemBridge} — a successful
 * task typically produces one {@link SourceArtifact} and optionally one
 * {@link BuildArtifact}.</p>
 *
 * @param taskId      UUID matching the {@link TaskSpec#taskId}.
 * @param backend     {@link CodingTaskBackend#name() backend.name()} that
 *                    handled the task.
 * @param status      outcome status; see {@link TaskStatus}.
 * @param summary     short human-readable description suitable for in-world
 *                    narration ("CodeZaiku built 3 files, all tests pass.").
 * @param artifactIds IDs of {@link CodingArtifact}s produced by the task.
 * @param cuConsumed  metered compute-unit cost; 0 for free-tier backends.
 * @param durationMs  wallclock duration the task occupied the backend.
 */
public record TaskResult(
    UUID taskId,
    String backend,
    TaskStatus status,
    String summary,
    List<UUID> artifactIds,
    long cuConsumed,
    long durationMs
) {}
