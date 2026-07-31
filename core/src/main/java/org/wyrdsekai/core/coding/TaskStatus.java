package org.wyrdsekai.core.coding;

/**
 * Outcome status of a {@link CodingTaskBackend#submitTask submitted} coding task.
 *
 * <p>. Backends translate their native exit
 * codes / status fields into these four values.</p>
 */
public enum TaskStatus {
    /** Task ran to completion with the backend reporting success. */
    SUCCEEDED,
    /** Task ran but the backend reported failure (compile error, test fail, agent gave up). */
    FAILED,
    /** Caller (or a watchdog) cancelled the task before completion. */
    CANCELLED,
    /** Task exceeded its wallclock deadline and was killed. */
    TIMED_OUT
}
