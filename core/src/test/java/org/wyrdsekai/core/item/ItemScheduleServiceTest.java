package org.wyrdsekai.core.item;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * schedule {@code in/cron/cancel/list}
 * round trips through {@link ItemScheduleService}. Owner-scoping is
 * verified — agent A cannot list/cancel agent B's timers.
 */
class ItemScheduleServiceTest {

    private static ActorTestKit testKit;
    private ItemScheduleService svc;

    @BeforeAll static void setUpAll() {
        testKit = ActorTestKit.create("ItemScheduleServiceTest");
    }

    @AfterAll static void tearDownAll() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void setUp() {
        ItemScheduleService.resetForTesting();
        svc = ItemScheduleService.get(testKit.system(), null);  // in-memory
    }

    @AfterEach
    void tearDown() {
        ItemScheduleService.resetForTesting();
    }

    @Test
    void schedule_in_creates_one_shot_that_fires() {
        var fired = new CountDownLatch(1);
        svc.setFireListener((id, s) -> fired.countDown());

        var res = svc.scheduleIn("agent-a", 0, "myHook", Map.of("k", "v"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("timerId")).isInstanceOf(String.class);

        try {
            // 5s window (was 2s) — full-suite parallel test runs make
            // the scheduler latch occasionally miss a 2-second deadline
            // under JVM load. Test isolation passes well under 100ms;
            // the 5s ceiling is generous-but-not-silly per the recurring
            // test below.
            assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // After the one-shot fires, the timer is removed
        pollUntil(() -> svc.size() == 0, 2_000);
    }

    @Test
    void schedule_in_with_negative_seconds_clamps_to_zero() {
        var fired = new CountDownLatch(1);
        svc.setFireListener((id, s) -> fired.countDown());
        svc.scheduleIn("agent-a", -10, "hook", null);
        try {
            // 5s window (was 2s) — full-suite parallel test runs make
            // the scheduler latch occasionally miss a 2-second deadline
            // under JVM load. Test isolation passes well under 100ms;
            // the 5s ceiling is generous-but-not-silly per the recurring
            // test below.
            assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void schedule_every_creates_recurring_that_fires_multiple_times() {
        var count = new AtomicInteger(0);
        svc.setFireListener((id, s) -> count.incrementAndGet());

        var res = svc.scheduleEvery("agent-a", 1, "tick", null);
        assertThat(res.get("ok")).isEqualTo(true);

        pollUntil(() -> count.get() >= 2, 5_000);
        // Cancel before the test ends so the timer doesn't keep firing.
        svc.cancel("agent-a", String.valueOf(res.get("timerId")));
    }

    @Test
    void cron_friendly_form_translates_to_every() {
        var count = new AtomicInteger(0);
        svc.setFireListener((id, s) -> count.incrementAndGet());

        var res = svc.scheduleCron("agent-a", "*/5 seconds", "tick", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("intervalSeconds")).isEqualTo(5L);
        svc.cancel("agent-a", String.valueOf(res.get("timerId")));
    }

    @Test
    void cron_too_short_rejected() {
        var res = svc.scheduleCron("agent-a", "*/1 seconds", "tick", null);
        assertThat(res).containsKey("error");
        assertThat((String) res.get("error")).contains("too short");
    }

    @Test
    void cron_unsupported_form_rejected() {
        var res = svc.scheduleCron("agent-a", "0 5 * * *", "tick", null);
        assertThat(res).containsKey("error");
    }

    @Test
    void list_only_returns_owner_schedules() {
        svc.scheduleIn("agent-a", 60, "hookA", null);
        svc.scheduleIn("agent-b", 60, "hookB", null);
        svc.scheduleIn("agent-a", 60, "hookA2", null);

        var aList = svc.list("agent-a");
        var bList = svc.list("agent-b");
        assertThat(aList).hasSize(2);
        assertThat(bList).hasSize(1);
        assertThat((String) aList.getFirst().get("hookName")).startsWith("hookA");
    }

    @Test
    void cancel_blocks_other_owner() {
        var res = svc.scheduleIn("agent-a", 60, "hook", null);
        var id = String.valueOf(res.get("timerId"));

        var denied = svc.cancel("agent-b", id);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(denied.get("reason")).isEqualTo("not_owner");
        assertThat(svc.exists(id)).isTrue();

        var ok = svc.cancel("agent-a", id);
        assertThat(ok.get("ok")).isEqualTo(true);
        assertThat(svc.exists(id)).isFalse();
    }

    @Test
    void cancel_unknown_id_returns_not_found() {
        var res = svc.cancel("agent-a", "no-such-id");
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("reason")).isEqualTo("not_found");
    }

    @Test
    void list_empty_when_no_schedules() {
        assertThat(svc.list("agent-a")).isEmpty();
    }

    @Test
    void list_includes_recurring_marker() {
        svc.scheduleIn("agent-a", 60, "once", null);
        svc.scheduleEvery("agent-a", 60, "twice", null);
        var entries = svc.list("agent-a");
        assertThat(entries).hasSize(2);
        // One is recurring, one isn't
        long recurringCount = entries.stream()
            .filter(m -> Boolean.TRUE.equals(m.get("recurring"))).count();
        assertThat(recurringCount).isEqualTo(1);
    }

    @Test
    void schedule_persistence_round_trip(@TempDir Path tempDir)
            throws Exception {
        // Use a real H2/SQLite-style URL so the persistence layer engages.
        var dbFile = tempDir.resolve("schedule.db");
        var jdbcUrl = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        ItemScheduleService.resetForTesting();
        var first = ItemScheduleService.get(testKit.system(), jdbcUrl);
        var res = first.scheduleEvery("agent-a", 60, "persistedHook", Map.of("payload", 42));
        var timerId = String.valueOf(res.get("timerId"));
        assertThat(first.exists(timerId)).isTrue();

        // Reset and re-init — re-arm should bring it back.
        ItemScheduleService.resetForTesting();
        var second = ItemScheduleService.get(testKit.system(), jdbcUrl);
        pollUntil(() -> second.exists(timerId), 2_000);
        var entries = second.list("agent-a");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().get("hookName")).isEqualTo("persistedHook");

        second.cancel("agent-a", timerId);
    }

    private static void pollUntil(BooleanSupplier cond, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!cond.getAsBoolean()) {
            throw new AssertionError("condition never became true within " + maxMs + "ms");
        }
    }
}
