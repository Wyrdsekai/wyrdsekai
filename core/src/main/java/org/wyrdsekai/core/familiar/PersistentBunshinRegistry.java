package org.wyrdsekai.core.familiar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Tracks persistent bunshin tasks across sessions.
 *
 * <p>Primary check-in API (§18.2): {@link #status}, {@link #nudge},
 * {@link #pause}, {@link #cancel}, {@link #kill}. All mutate state and
 * return the updated task so callers can narrate in their own voice.</p>
 *
 * <p>Overnight behavior (§18.3): the runtime calls
 * {@link #maybeRecordProgress} on a cadence; if {@link
 * PersistentBunshinTask#isReportDue} fires, a note is appended. On primary
 * user re-emergence, {@link #pendingReturnsForPrimary} surfaces any
 * completed-overnight tasks so the primary can weave them into reconnect
 * narration.</p>
 *
 * <p>Like {@link SummonKeyRegistry}, in-memory today; persistence piggy-backs
 * on the broader Vault pass .</p>
 */
public final class PersistentBunshinRegistry {

    private static final Logger log = LoggerFactory.getLogger(PersistentBunshinRegistry.class);

    /** Keyed by task id. */
    private final ConcurrentMap<String, PersistentBunshinTask> tasks = new ConcurrentHashMap<>();

    // ── Dispatch ───────────────────────────────────────────────────────────

    /** Register a freshly dispatched task. Returns the stored record. */
    public PersistentBunshinTask register(PersistentBunshinTask task) {
        if (task == null) throw new IllegalArgumentException("task required");
        tasks.put(task.id(), task);
        log.info("PersistentBunshinRegistry: registered {} for {}", task.id(), task.primaryAgentDid());
        return task;
    }

    public Optional<PersistentBunshinTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** List tasks owned by a given primary, newest-dispatched first. */
    public List<PersistentBunshinTask> listForPrimary(String primaryDid) {
        return tasks.values().stream()
            .filter(t -> primaryDid.equals(t.primaryAgentDid()))
            .sorted(Comparator.comparing(PersistentBunshinTask::dispatchedAt).reversed())
            .toList();
    }

    /** Live tasks (RUNNING or YIELDED) for a primary. */
    public List<PersistentBunshinTask> aliveForPrimary(String primaryDid) {
        return listForPrimary(primaryDid).stream()
            .filter(PersistentBunshinTask::isAlive)
            .toList();
    }

    // ── Primary check-in API (§18.2) ───────────────────────────────────────

    public Optional<PersistentBunshinTask> status(String taskId) {
        return get(taskId);
    }

    /** Inject guidance — next inference turn sees it. */
    public Optional<PersistentBunshinTask> nudge(String taskId, String hint) {
        return mutate(taskId, t ->
            t.withProgressNote("[nudge from primary] " + (hint == null ? "" : hint)));
    }

    /** Pause a running task; idempotent. */
    public Optional<PersistentBunshinTask> pause(String taskId) {
        return mutate(taskId, t -> t.status() == PersistentBunshinTask.Status.RUNNING
            ? t.withStatus(PersistentBunshinTask.Status.YIELDED)
            : t);
    }

    /** Resume a paused task. */
    public Optional<PersistentBunshinTask> resume(String taskId) {
        return mutate(taskId, t -> t.status() == PersistentBunshinTask.Status.YIELDED
            ? t.withStatus(PersistentBunshinTask.Status.RUNNING)
            : t);
    }

    /** Graceful cancel — returns partial result, marks CANCELLED. */
    public Optional<PersistentBunshinTask> cancel(String taskId, String partialSummary) {
        return mutate(taskId, t -> t.isTerminal() ? t : t
            .withPartialResult(partialSummary)
            .withStatus(PersistentBunshinTask.Status.CANCELLED));
    }

    /** Hard stop — logged as intervention, used rarely. */
    public Optional<PersistentBunshinTask> kill(String taskId) {
        return mutate(taskId, t -> t.isTerminal() ? t :
            t.withProgressNote("[killed — intervention]")
             .withStatus(PersistentBunshinTask.Status.CANCELLED));
    }

    /** Mark a task complete (caller supplies final result). */
    public Optional<PersistentBunshinTask> complete(String taskId, String finalResult) {
        return mutate(taskId, t -> t.isTerminal() ? t : t
            .withPartialResult(finalResult)
            .withStatus(PersistentBunshinTask.Status.COMPLETED));
    }

    /** Mark a task failed (non-recoverable). */
    public Optional<PersistentBunshinTask> fail(String taskId, String reason) {
        return mutate(taskId, t -> t.isTerminal() ? t : t
            .withProgressNote("[failed: " + reason + "]")
            .withStatus(PersistentBunshinTask.Status.FAILED));
    }

    // ── Cadence-based progress (§18.3) ─────────────────────────────────────

    /**
     * If the task's report cadence is due, record a progress note and return
     * the updated task. Otherwise returns the existing record unchanged.
     */
    public Optional<PersistentBunshinTask> maybeRecordProgress(
            String taskId, String content, Instant now) {
        return mutate(taskId, t -> {
            if (!t.isAlive()) return t;
            if (!t.isReportDue(now)) return t;
            return t.withProgressNote(content);
        });
    }

    /**
     * Expire tasks whose wall-clock has run out. Returns the list of tasks
     * that transitioned to EXPIRED. Safe to call periodically.
     */
    public List<PersistentBunshinTask> expireOverdue(Instant now) {
        var expired = new ArrayList<PersistentBunshinTask>();
        for (var t : tasks.values()) {
            if (t.isAlive() && t.isExpired(now)) {
                var updated = t.withProgressNote("[expired — wall-clock ceiling reached]")
                    .withStatus(PersistentBunshinTask.Status.EXPIRED);
                tasks.put(t.id(), updated);
                expired.add(updated);
            }
        }
        return List.copyOf(expired);
    }

    // ── Reconnect surfacing (§18.3) ────────────────────────────────────────

    /**
     * Tasks that terminated while the primary was away — to be woven into
     * the reconnect narration when the primary returns. Returns tasks that
     * completed, expired, failed, or cancelled and are younger than the
     * cutoff.
     */
    public List<PersistentBunshinTask> pendingReturnsForPrimary(
            String primaryDid, Instant since) {
        return tasks.values().stream()
            .filter(t -> primaryDid.equals(t.primaryAgentDid()))
            .filter(PersistentBunshinTask::isTerminal)
            .filter(t -> t.dispatchedAt().isAfter(since)
                || t.lastCheckInAt().map(i -> i.isAfter(since)).orElse(false))
            .sorted(Comparator.comparing(PersistentBunshinTask::dispatchedAt))
            .toList();
    }

    /** Drop terminal tasks older than a cutoff — garbage collection for the in-memory store. */
    public int purgeTerminalOlderThan(Instant cutoff) {
        var count = 0;
        for (var t : List.copyOf(tasks.values())) {
            if (t.isTerminal() && t.dispatchedAt().isBefore(cutoff)) {
                tasks.remove(t.id());
                count++;
            }
        }
        return count;
    }

    public int size() { return tasks.size(); }

    // ── Process-wide singleton (§18) ───────────────────────────────────────
    // One registry per JVM; CompanionActor instances share it so that
    // persistence survives individual actor restarts, and so a primary
    // reconnecting after sleep sees bunshins dispatched by her prior session.

    private static final PersistentBunshinRegistry INSTANCE = new PersistentBunshinRegistry();

    public static PersistentBunshinRegistry get() { return INSTANCE; }

    /** Test support — drop all state so the next test starts clean. */
    public static void resetForTests() {
        INSTANCE.tasks.clear();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Optional<PersistentBunshinTask> mutate(
            String taskId, Function<PersistentBunshinTask, PersistentBunshinTask> fn) {
        var t = tasks.get(taskId);
        if (t == null) return Optional.empty();
        var next = fn.apply(t);
        if (next != t) tasks.put(taskId, next);
        return Optional.of(next);
    }
}
