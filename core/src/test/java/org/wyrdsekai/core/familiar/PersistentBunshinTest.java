package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — persistent bunshin tasks, primary check-in
 * API, cadence reporting, wall-clock expiry, reconnect surfacing.
 */
class PersistentBunshinTest {

    private static final String PRIMARY = "did:wyrd:zA:wyrd";

    private PersistentBunshinRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PersistentBunshinRegistry();
    }

    private PersistentBunshinTask dispatch(String goal) {
        return registry.register(PersistentBunshinTask.dispatch(
            PRIMARY, goal, "result contains 3 sources",
            false, Duration.ofHours(1), Duration.ofMinutes(10)));
    }

    // ── task record invariants ─────────────────────────────────────────────

    @Test
    void dispatch_factory_sets_defaults() {
        var t = PersistentBunshinTask.dispatch(PRIMARY, "find historical sources",
            null, false, null, null);
        assertEquals(PersistentBunshinTask.DEFAULT_MAX_WALL_CLOCK, t.maxWallClock());
        assertEquals(PersistentBunshinTask.DEFAULT_REPORT_CADENCE, t.reportCadence());
        assertEquals(PersistentBunshinTask.Status.RUNNING, t.status());
        assertTrue(t.progressNotes().isEmpty());
    }

    @Test
    void progress_notes_are_bounded() {
        var t = PersistentBunshinTask.dispatch(PRIMARY, "g", null, false, null, null);
        for (int i = 0; i < PersistentBunshinTask.MAX_NOTES + 10; i++) {
            t = t.withProgressNote("note-" + i);
        }
        assertEquals(PersistentBunshinTask.MAX_NOTES, t.progressNotes().size());
        // Most recent preserved
        var last = t.progressNotes().get(t.progressNotes().size() - 1);
        assertTrue(last.content().endsWith("-" + (PersistentBunshinTask.MAX_NOTES + 9)));
    }

    @Test
    void isExpired_respects_wall_clock() {
        var t = PersistentBunshinTask.dispatch(PRIMARY, "g", null, false,
            Duration.ofSeconds(10), null);
        assertFalse(t.isExpired(Instant.now()));
        assertTrue(t.isExpired(Instant.now().plusSeconds(60)));
    }

    @Test
    void isReportDue_fires_on_cadence_boundary() {
        var t = PersistentBunshinTask.dispatch(PRIMARY, "g", null, false,
            null, Duration.ofMinutes(10));
        assertFalse(t.isReportDue(t.dispatchedAt().plusSeconds(300)));
        assertTrue(t.isReportDue(t.dispatchedAt().plusSeconds(700)));
    }

    // ── registry: check-in API ─────────────────────────────────────────────

    @Test
    void status_returns_current_task() {
        var t = dispatch("keep going");
        var queried = registry.status(t.id());
        assertTrue(queried.isPresent());
        assertEquals(t.id(), queried.get().id());
    }

    @Test
    void nudge_appends_progress_note() {
        var t = dispatch("research X");
        var after = registry.nudge(t.id(), "focus on primary sources").orElseThrow();
        assertEquals(1, after.progressNotes().size());
        assertTrue(after.progressNotes().get(0).content().contains("focus on primary sources"));
    }

    @Test
    void pause_then_resume_round_trip() {
        var t = dispatch("long task");
        var paused = registry.pause(t.id()).orElseThrow();
        assertEquals(PersistentBunshinTask.Status.YIELDED, paused.status());
        var resumed = registry.resume(t.id()).orElseThrow();
        assertEquals(PersistentBunshinTask.Status.RUNNING, resumed.status());
    }

    @Test
    void cancel_sets_partial_result_and_terminates() {
        var t = dispatch("mid-task");
        var cancelled = registry.cancel(t.id(), "mid-progress summary").orElseThrow();
        assertEquals(PersistentBunshinTask.Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.partialResult().isPresent());
        assertTrue(cancelled.isTerminal());
    }

    @Test
    void kill_records_intervention_note() {
        var t = dispatch("runaway");
        var killed = registry.kill(t.id()).orElseThrow();
        assertEquals(PersistentBunshinTask.Status.CANCELLED, killed.status());
        assertTrue(killed.progressNotes().stream()
            .anyMatch(n -> n.content().contains("killed")));
    }

    @Test
    void complete_and_fail_are_idempotent_on_terminal() {
        var t = dispatch("done task");
        var completed = registry.complete(t.id(), "the answer").orElseThrow();
        assertEquals(PersistentBunshinTask.Status.COMPLETED, completed.status());
        // Try to fail after completion — no effect
        var stillCompleted = registry.fail(t.id(), "too late").orElseThrow();
        assertEquals(PersistentBunshinTask.Status.COMPLETED, stillCompleted.status());
    }

    // ── cadence + expiry ───────────────────────────────────────────────────

    @Test
    void maybeRecordProgress_only_writes_when_due() {
        var t = dispatch("slow task");
        var notYet = registry.maybeRecordProgress(t.id(), "not due",
            t.dispatchedAt().plusSeconds(60)).orElseThrow();
        assertTrue(notYet.progressNotes().isEmpty());

        var later = registry.maybeRecordProgress(t.id(), "progress",
            t.dispatchedAt().plusSeconds(700)).orElseThrow();
        assertEquals(1, later.progressNotes().size());
    }

    @Test
    void expireOverdue_transitions_overdue_tasks() {
        var t = registry.register(PersistentBunshinTask.dispatch(
            PRIMARY, "short task", null, false,
            Duration.ofSeconds(10), null));
        var expired = registry.expireOverdue(Instant.now().plusSeconds(60));
        assertEquals(1, expired.size());
        assertEquals(PersistentBunshinTask.Status.EXPIRED, expired.get(0).status());
    }

    // ── listing + reconnect surfacing ──────────────────────────────────────

    @Test
    void listForPrimary_filters_by_did() {
        dispatch("a");
        dispatch("b");
        registry.register(PersistentBunshinTask.dispatch(
            "did:wyrd:zA:other", "c", null, false, null, null));
        assertEquals(2, registry.listForPrimary(PRIMARY).size());
    }

    @Test
    void pendingReturns_surfaces_overnight_completions() throws InterruptedException {
        var before = Instant.now();
        var t1 = dispatch("overnight work");
        Thread.sleep(10);
        registry.complete(t1.id(), "result");
        var pending = registry.pendingReturnsForPrimary(PRIMARY, before);
        assertEquals(1, pending.size());
        assertEquals(PersistentBunshinTask.Status.COMPLETED, pending.get(0).status());
    }

    @Test
    void aliveForPrimary_only_returns_running_or_yielded() {
        var t1 = dispatch("work-1");
        var t2 = dispatch("work-2");
        registry.complete(t1.id(), "done");
        assertEquals(1, registry.aliveForPrimary(PRIMARY).size());
    }

    @Test
    void purgeTerminalOlderThan_cleans_up() {
        var t = dispatch("x");
        registry.complete(t.id(), "done");
        var purged = registry.purgeTerminalOlderThan(Instant.now().plusSeconds(60));
        assertEquals(1, purged);
        assertEquals(0, registry.size());
    }

    @Test
    void missing_task_returns_empty_optional_gracefully() {
        assertTrue(registry.status("nonexistent").isEmpty());
        assertTrue(registry.nudge("nonexistent", "hi").isEmpty());
        assertTrue(registry.cancel("nonexistent", "x").isEmpty());
    }
}
