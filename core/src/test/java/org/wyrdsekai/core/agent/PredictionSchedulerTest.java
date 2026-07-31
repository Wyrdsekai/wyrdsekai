package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.oracle.OraclePrediction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionSchedulerTest {

    @Test
    void in_minutes_extracts_relative_fire_time() {
        var p = pred("p1", "topic", "Reminder in 30 minutes about laundry");
        var now = Instant.parse("2026-05-08T10:00:00Z");
        var fire = PredictionScheduler.extractFireTime(p, now);

        assertThat(fire).isPresent();
        assertThat(Duration.between(now, fire.get()).toMinutes()).isEqualTo(30);
    }

    @Test
    void in_hours_extracts_relative_fire_time() {
        var p = pred("p2", "topic", "Check in 2 hours");
        var now = Instant.parse("2026-05-08T10:00:00Z");
        var fire = PredictionScheduler.extractFireTime(p, now);

        assertThat(fire).isPresent();
        assertThat(Duration.between(now, fire.get()).toHours()).isEqualTo(2);
    }

    @Test
    void at_hour_with_pm_resolves_to_next_local_occurrence() {
        var p = pred("p3", "temporal", "User asks about news at 6pm");
        var now = Instant.parse("2026-05-08T05:00:00Z");
        var fire = PredictionScheduler.extractFireTime(p, now);

        assertThat(fire).isPresent();
        var local = fire.get().atZone(ZoneId.systemDefault());
        assertThat(local.getHour()).isEqualTo(18);
    }

    @Test
    void time_of_day_morning_resolves_to_9() {
        var p = pred("p4", "temporal", "User reads news in the morning");
        var now = Instant.parse("2026-05-08T05:00:00Z");
        var fire = PredictionScheduler.extractFireTime(p, now);

        assertThat(fire).isPresent();
        var local = fire.get().atZone(ZoneId.systemDefault());
        assertThat(local.getHour()).isEqualTo(9);
    }

    @Test
    void no_time_hint_returns_empty() {
        var p = pred("p5", "topic", "User likes coffee");
        var fire = PredictionScheduler.extractFireTime(p, Instant.now());

        assertThat(fire).isEmpty();
    }

    @Test
    void schedule_persists_and_replays(@TempDir Path tmp) throws Exception {
        var s1 = new PredictionScheduler(tmp);
        var p = pred("p6", "temporal", "Reminder in 45 minutes");
        var entry = s1.schedule(p, "agent-1");
        assertThat(entry).isPresent();
        assertThat(s1.scheduledCount()).isEqualTo(1);

        var s2 = new PredictionScheduler(tmp);
        assertThat(s2.scheduledCount()).isEqualTo(1);
        assertThat(s2.snapshot().get(0).predictionId()).isEqualTo("p6");
    }

    @Test
    void poll_due_fires_returns_only_due_and_removes_them() {
        var s = new PredictionScheduler(null);
        var pDue = pred("due", "topic", "in 1 minute");
        var pFuture = pred("future", "topic", "in 2 hours");
        s.schedule(pDue, "agent-x");
        s.schedule(pFuture, "agent-x");

        var pollAt = Instant.now().plus(Duration.ofMinutes(2));
        var due = s.pollDueFires("agent-x", pollAt);

        assertThat(due).hasSize(1);
        assertThat(due.get(0).predictionId()).isEqualTo("due");
        assertThat(s.scheduledCount()).isEqualTo(1);
    }

    @Test
    void poll_filters_by_agent_id() {
        var s = new PredictionScheduler(null);
        s.schedule(pred("a-1", "topic", "in 1 minute"), "agent-A");
        s.schedule(pred("b-1", "topic", "in 1 minute"), "agent-B");

        var pollAt = Instant.now().plus(Duration.ofMinutes(2));
        var dueA = s.pollDueFires("agent-A", pollAt);

        assertThat(dueA).hasSize(1);
        assertThat(dueA.get(0).predictionId()).isEqualTo("a-1");
        assertThat(s.scheduledCount()).isEqualTo(1); // b-1 still scheduled
    }

    @Test
    void cancel_removes_a_scheduled_entry() {
        var s = new PredictionScheduler(null);
        s.schedule(pred("c-1", "topic", "in 1 minute"), "agent-C");
        assertThat(s.cancel("c-1")).isTrue();
        assertThat(s.scheduledCount()).isZero();
        assertThat(s.cancel("c-1")).isFalse();
    }

    @Test
    void replay_drops_stale_entries(@TempDir Path tmp) throws Exception {
        var s1 = new PredictionScheduler(tmp);
        // Past fire — should be dropped on replay (stale grace = 15min)
        var pastP = pred("p-stale", "topic", "Reminder in 1 hour");
        s1.schedule(pastP, "agent-S");
        // Manually rewrite the persist file with a past fireAt
        var file = tmp.resolve("m4").resolve("scheduled.jsonl");
        var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        var rewritten = lines.get(0).replaceAll(
            "\"fireAt\":\"[^\"]+\"",
            "\"fireAt\":\"2020-01-01T00:00:00Z\"");
        Files.writeString(file, rewritten + "\n");

        var s2 = new PredictionScheduler(tmp);
        assertThat(s2.scheduledCount()).isZero();
    }

    private static OraclePrediction pred(String id, String category, String text) {
        return new OraclePrediction(id, text, category, 0.7, "key", "evidence", true);
    }
}
