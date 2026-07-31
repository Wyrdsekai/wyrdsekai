package org.wyrdsekai.core.coding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A coding task as expressed by a companion (or a player).
 *
 * <p>Backends consume {@code TaskSpec}s and return {@link TaskResult}s.
 * </p>
 *
 * @param taskId        UUID assigned by the caller; used to correlate
 *                      events, artifacts, and result.
 * @param companionDid  DID of the companion (or player) submitting the
 *                      task; null for system-initiated work.
 * @param taskType      free-form short tag — {@code "code"},
 *                      {@code "test"}, {@code "review"},
 *                      {@code "experiment"}, etc. The selection policy
 *                      uses this to bias which backend is preferred.
 * @param description   natural-language statement of what the task is.
 *                      Forwarded into the backend's prompt / instructions.
 * @param workspaceHint optional pre-existing workspace path or repo URL.
 *                      Backends may use this verbatim or treat it as a
 *                      hint and create a fresh workspace.
 * @param files         file paths relative to the workspace that the
 *                      task should focus on. Empty list = whole workspace.
 * @param maxCu         soft compute-unit ceiling for the task. Backends
 *                      that meter (Cloud, OpenHands) abort once exceeded;
 *                      backends that don't meter (Aider, CodePlane local)
 *                      treat this as advisory.
 * @param deadline      optional wallclock cutoff. Backends with a
 *                      timeout knob honor this; otherwise the caller's
 *                      watchdog enforces it.
 */
public record TaskSpec(
    UUID taskId,
    String companionDid,
    String taskType,
    String description,
    String workspaceHint,
    List<String> files,
    long maxCu,
    Instant deadline
) {
    /** Convenience: build with a freshly-generated taskId. */
    public static TaskSpec create(String companionDid, String taskType, String description) {
        return new TaskSpec(UUID.randomUUID(), companionDid, taskType, description,
            null, List.of(), 0L, null);
    }
}
