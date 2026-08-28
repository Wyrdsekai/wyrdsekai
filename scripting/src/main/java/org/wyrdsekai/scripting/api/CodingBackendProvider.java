package org.wyrdsekai.scripting.api;

/**
 * Provides coding-backend selection for room scripts via
 * {@code world.codingBackendFor(...)}.
 *
 * <p> / Phase 1a step 17. Defined in the
 * scripting module to avoid a circular dependency (scripting cannot
 * reference core); core supplies the implementation that wraps the
 * {@code BackendRegistry}.</p>
 *
 * <p>Phase 1a returns a fixed default ({@code "codezaiku"} when healthy,
 * else {@code null}). Phase 1b promotes the policy to a live-tunable
 * GraalJS script ( question 6).</p>
 */
public interface CodingBackendProvider {

    /**
     * Pick a backend for the given task.
     *
     * @param entityId        DID of the companion (or player) submitting
     *                        the task; may be null for system tasks.
     * @param taskType        free-form short tag — {@code "code"},
     *                        {@code "test"}, {@code "review"}, etc.
     * @param taskDescription natural-language statement of what the task
     *                        is. Used by richer Phase 1b policies.
     * @return the {@link org.wyrdsekai.core.coding.CodingTaskBackend#name()}
     *         string of the chosen backend, or {@code null} if no backend
     *         is currently available / allowed.
     */
    String backendFor(String entityId, String taskType, String taskDescription);

    /**
     * Quick "is this backend currently registered + healthy?" probe used
     * by room scripts (Phase 2b — Workshop narration uses it to decide
     * which boards to surface). Default returns {@code false}; the core
     * implementation overrides with a real {@code BackendRegistry} +
     * health-check probe.
     */
    default boolean backendAvailable(String name) {
        return false;
    }
}
