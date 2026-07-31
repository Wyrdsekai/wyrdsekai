package org.wyrdsekai.core.recipe;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.soul.RepairMode;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C2 — actor-level integration: full dispatch cycle
 * lands the cadence write back to {@link SqlRecipeQueue}.
 *
 * <p>Uses a stub {@link RecipeService} that returns a canned
 * {@link RecipeRunner.RecipeRun} — the scheduler under test is the
 * actor-mailbox + persistence wiring, not the runner itself
 * (that's already covered by tier3 live tests).</p>
 */
class RecipeSchedulerTest {

    private ActorTestKit testKit;
    private SqlRecipeQueue queue;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("RecipeSchedulerTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        queue = new SqlRecipeQueue("jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath());
    }

    @AfterEach
    void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test
    void enqueue_pollNow_dispatch_completion_writes_cadence_promotion() {
        var dispatched = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = stubDispatcher(
            r -> RecipeRunner.Status.SUCCESS, "ok", dispatched);

        // Tight poll so a test poll-tick won't fire between operations.
        // We drive ticks via PollNow; the timer is essentially a watchdog.
        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 1)));

        var entry = RecipeScheduler.newEnqueue("retrain-classifier-head",
            "did:test:companion-A", CadenceTier.WARMUP, 2,
            QueuedRecipe.TriggerSource.CRON,
            "settling-arc third success", Map.of("head", "task_present"));
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        // Wait for: dispatch fired AND row transitioned to SUCCEEDED with
        // promoted tier (WARMUP@2 + SUCCESS → SETTLING@0).
        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            assertThat(dispatched.get())
                .as("RecipeService.run should have been called once")
                .isEqualTo(1);
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.SUCCEEDED);
            assertThat(after.cadenceTier()).isEqualTo(CadenceTier.SETTLING);
            assertThat(after.consecutiveSuccesses()).isZero();
            assertThat(after.message()).isEqualTo("ok");
        });
    }

    @Test
    void gate_failure_demotes_to_warmup_and_marks_failed() {
        var dispatched = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = stubDispatcher(
            r -> RecipeRunner.Status.GATE_FAILED, "metric below 0.80", dispatched);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 1)));

        var entry = RecipeScheduler.newEnqueue("retrain-classifier-head",
            "did:test:companion-A", CadenceTier.MATURE, 7,
            QueuedRecipe.TriggerSource.CRON, "mature weekly tick", Map.of());
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.FAILED);
            // MATURE → WARMUP/0 on any non-SUCCESS outcome.
            assertThat(after.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            assertThat(after.consecutiveSuccesses()).isZero();
        });
    }

    @Test
    void rollback_path_in_outcomes_maps_to_ROLLBACK_FIRED() {
        // Stub returns STEP_FAILED with a rollback outcome in the list —
        // the scheduler's mapOutcome should recognise this and pick
        // ROLLBACK_FIRED. Cadence outcome is the same (demote to WARMUP)
        // but the audit-trail outcome is more informative.
        RecipeScheduler.Dispatcher dispatcher = (did, name, params) ->
            new RecipeService.StartedRun(UUID.randomUUID().toString(),
                new RecipeRunner.RecipeRun(
                    RecipeRunner.Status.STEP_FAILED,
                    "smoke failed; rolled back",
                    List.of(
                        new RecipeRunner.StepOutcome("deploy", StepKind.SHELL, true, "ok"),
                        new RecipeRunner.StepOutcome("smoke", StepKind.BACKEND, false, "fail"),
                        new RecipeRunner.StepOutcome("rollback", StepKind.SHELL, true, "restored")),
                    new RecipeContext(Map.of())));
        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 1)));

        var entry = RecipeScheduler.newEnqueue("retrain-classifier-head",
            "did:test:companion-A", CadenceTier.SETTLING, 4,
            QueuedRecipe.TriggerSource.CRON, "test", Map.of());
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.FAILED);
            assertThat(after.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            assertThat(after.message()).contains("rolled back");
        });
    }

    @Test
    void empty_queue_pollNow_is_a_noop() {
        var dispatched = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = stubDispatcher(
            r -> RecipeRunner.Status.SUCCESS, "ok", dispatched);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 1)));

        scheduler.tell(new RecipeScheduler.PollNow());

        // Give the actor a chance to process and confirm nothing was called.
        try { Thread.sleep(500); } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        assertThat(dispatched.get()).isZero();
        assertThat(queue.size()).isZero();
    }

    @Test
    void unknown_did_routes_to_ERROR_demote() {
        // Dispatcher returns null → scheduler maps to ERROR.
        RecipeScheduler.Dispatcher dispatcher = (did, name, params) -> null;
        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 1)));

        var entry = RecipeScheduler.newEnqueue("retrain-classifier-head",
            "did:test:nobody", CadenceTier.SETTLING, 4,
            QueuedRecipe.TriggerSource.AGENT, "trigger", Map.of());
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.FAILED);
            assertThat(after.cadenceTier()).isEqualTo(CadenceTier.WARMUP);
            assertThat(after.message()).contains("no RecipeService");
        });
    }

    // ── G4 C4 cron-tick wire (task #1016, 2026-05-25) ──────────────────

    @Test
    void cron_ticker_enqueues_due_enrollments_on_each_tick() {
        // Wire a CronTicker that returns one due CRON entry. After PollNow,
        // the queue must hold the row (proves cron-tick is invoked before
        // drain) AND it must transition to SUCCEEDED (proves the drain also
        // picked it up — same tick handles both).
        var dispatched = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = stubDispatcher(
            r -> RecipeRunner.Status.SUCCESS, "ok", dispatched);

        var dueEntryId = UUID.randomUUID().toString();
        AtomicInteger plannerCalls =
            new AtomicInteger(0);
        RecipeScheduler.CronTicker ticker = now -> {
            int call = plannerCalls.incrementAndGet();
            if (call > 1) return List.of();   // only plan on the first tick
            return List.of(new QueuedRecipe(dueEntryId,
                "retrain-classifier-head", Map.of("head", "task_present"),
                "cron tick (tier=WARMUP)", QueuedRecipe.TriggerSource.CRON,
                now, null, null, QueuedRecipe.Status.PENDING,
                "did:test:companion-A", CadenceTier.WARMUP, 0, null, null));
        };

        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5),
            /* welfare */ null, ticker));

        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            assertThat(plannerCalls.get())
                .as("cron planner must be called at least once on PollNow")
                .isGreaterThanOrEqualTo(1);
            var row = queue.find(dueEntryId).orElseThrow();
            assertThat(row.triggerSource())
                .isEqualTo(QueuedRecipe.TriggerSource.CRON);
            assertThat(row.status()).isEqualTo(QueuedRecipe.Status.SUCCEEDED);
            assertThat(dispatched.get())
                .as("dispatcher must run for the cron-enqueued row")
                .isEqualTo(1);
        });
    }

    @Test
    void cron_ticker_idempotency_skip_when_open_row_already_pending() {
        // The same (recipeId, agentDid) pair gets planned twice. The first
        // tick enqueues; the second tick must SKIP because the first row is
        // still PENDING (welfare gate deferred it). Proves hasOpenForRecipe
        // gate works — without this, repeated ticks would compound rows
        // until the gate cleared.
        RecipeScheduler.Dispatcher dispatcher = (did, name, params) -> null;  // always defers

        var firstId = UUID.randomUUID().toString();
        AtomicInteger plannerCalls =
            new AtomicInteger(0);
        RecipeScheduler.CronTicker ticker = now -> List.of(new QueuedRecipe(
            // Same params + tier; but each plan call returns a fresh row id
            // (mirrors real plan() which mints a UUID per call).
            plannerCalls.getAndIncrement() == 0
                ? firstId : UUID.randomUUID().toString(),
            "retrain-classifier-head", Map.of(),
            "cron tick", QueuedRecipe.TriggerSource.CRON,
            now, null, null, QueuedRecipe.Status.PENDING,
            "did:test:companion-B", CadenceTier.WARMUP, 0, null, null));

        // Welfare gate that always denies (puts dispatcher behind a defer).
        // Causes the first row to stay PENDING after the tick — exactly the
        // state we want hasOpenForRecipe to detect on the second tick.
        // Any-agent-in-repair is the simplest trip — set a non-NONE mode.
        RecipeScheduler.WelfareSupplier denyAll = peeked -> new WelfareGate.Inputs(
            Set.of(RepairMode.SELF),
            /* substratePressureSustained */ false,
            Duration.ZERO, Duration.ofHours(6),
            /* runsThisMonth */ 0, /* monthlyRunCap */ 100,
            /* lastTerminalAt */ null, CadenceTier.WARMUP,
            /* consecutiveDeployFailures */ 0,
            Instant.now());

        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5),
            denyAll, ticker));

        // First poll: planner fires, row enqueued, welfare denies dispatch
        // → row stays PENDING.
        scheduler.tell(new RecipeScheduler.PollNow());
        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            assertThat(queue.find(firstId).map(QueuedRecipe::status))
                .contains(QueuedRecipe.Status.PENDING);
        });
        int rowsAfterFirstTick = queue.findByRecipe(
            "retrain-classifier-head", "did:test:companion-B").size();
        assertThat(rowsAfterFirstTick).isEqualTo(1);

        // Second poll: planner returns a fresh row, but hasOpenForRecipe
        // sees the prior PENDING and the scheduler must SKIP — total stays
        // at 1, not 2.
        scheduler.tell(new RecipeScheduler.PollNow());
        // Give cron tick time to fire its planner + skip; nothing should
        // land in the queue.
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        int rowsAfterSecondTick = queue.findByRecipe(
            "retrain-classifier-head", "did:test:companion-B").size();
        assertThat(rowsAfterSecondTick)
            .as("idempotency: open PENDING row must block duplicate cron enqueue")
            .isEqualTo(1);
        assertThat(plannerCalls.get())
            .as("planner must have been called twice — proves we exercised the gate")
            .isGreaterThanOrEqualTo(2);
    }

    @Test
    void cron_ticker_exception_is_caught_drain_still_proceeds() {
        // A buggy ticker must not crash the scheduler. The drain after the
        // throwing tick must still process any pre-existing rows.
        var dispatched = new AtomicInteger(0);
        RecipeScheduler.Dispatcher dispatcher = stubDispatcher(
            r -> RecipeRunner.Status.SUCCESS, "ok", dispatched);

        RecipeScheduler.CronTicker buggyTicker = now -> {
            throw new RuntimeException("planner exploded");
        };

        var scheduler = testKit.spawn(RecipeScheduler.create(queue, dispatcher,
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5),
            /* welfare */ null, buggyTicker));

        // Pre-existing row in the queue (came from some other trigger).
        var entry = RecipeScheduler.newEnqueue("retrain-classifier-head",
            "did:test:companion-C", CadenceTier.WARMUP, 0,
            QueuedRecipe.TriggerSource.AGENT, "manual",
            Map.of("head", "task_present"));
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(10), () -> {
            // Drain still proceeded despite the cron-tick exception.
            var after = queue.find(entry.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(QueuedRecipe.Status.SUCCEEDED);
        });
    }

    // ── helpers ────────────────────────────────────────────────────────

    /**
     * Tiny Awaitility replacement: poll the assertion every 100ms until it
     * either passes or the deadline trips, in which case the most recent
     * failure is re-thrown for AssertionError diagnostics.
     */
    private static void awaitUntilAsserted(Duration timeout, Runnable check) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadlineNanos) {
            try {
                check.run();
                return;
            } catch (AssertionError | RuntimeException e) {
                // RuntimeException covers Optional.orElseThrow's
                // NoSuchElementException (row not yet persisted) without
                // swallowing the AssertionError diagnostics we re-throw at
                // the end. Anything more exotic is a real bug — let it fly.
                last = e;
            }
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (last instanceof AssertionError ae) throw ae;
        if (last instanceof RuntimeException re) throw re;
        throw new AssertionError("awaitUntilAsserted timed out", last);
    }

    /**
     * Dispatcher stub that always returns a canned outcome. The
     * {@code statusFn} lambda receives the recipe name in case tests want
     * to vary status by recipe; ignored for the simple cases here.
     */
    private static RecipeScheduler.Dispatcher stubDispatcher(
            Function<String, RecipeRunner.Status> statusFn,
            String message, AtomicInteger counter) {
        return (did, name, params) -> {
            counter.incrementAndGet();
            return new RecipeService.StartedRun(UUID.randomUUID().toString(),
                new RecipeRunner.RecipeRun(statusFn.apply(name),
                    message, List.of(), new RecipeContext(Map.of())));
        };
    }
}
