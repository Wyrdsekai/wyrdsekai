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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C4 — tier-2 integration. All three trigger
 * sources (cron, gap, agent-initiated) write into the real
 * {@link SqlRecipeQueue}; the live {@link RecipeScheduler} actor picks
 * them up; welfare gates ({@link WelfareGate}) correctly defer the
 * ones they should and let the rest through.
 *
 * <p>This is the load-bearing check that the three pure-logic trigger
 * classes + the queue + the actor + the gate all line up at the seams
 * the production wiring relies on.</p>
 */
class RecipeTriggerIntegrationTest {

    private ActorTestKit testKit;
    private SqlRecipeQueue queue;
    private RecipeEnrollmentStore enrollments;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("RecipeTriggerIntegrationTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = "jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath();
        queue = new SqlRecipeQueue(jdbc);
        enrollments = new RecipeEnrollmentStore(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test
    void cron_path_enqueues_and_scheduler_dispatches_under_open_gate() {
        // Enroll a recipe + companion. No prior terminal — cron sees due.
        var enrollment = new RecipeEnrollment("retrain-classifier-head",
            "did:test:alice", CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of("task_present.misroute"));
        enrollments.upsert(enrollment);

        var planned = RecipeCronTrigger.plan(enrollments.listEnabled(),
            (r, d) -> null, Instant.now());
        assertThat(planned).hasSize(1);

        var dispatched = new AtomicInteger(0);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue,
            stubDispatcher(RecipeRunner.Status.SUCCESS, dispatched),
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5)));

        // Enqueue the cron-planned row via the scheduler.
        for (var row : planned) scheduler.tell(new RecipeScheduler.Enqueue(row));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            assertThat(dispatched.get()).isEqualTo(1);
            var after = queue.findByRecipe("retrain-classifier-head", "did:test:alice");
            assertThat(after).hasSize(1);
            assertThat(after.get(0).status())
                .isEqualTo(QueuedRecipe.Status.SUCCEEDED);
            assertThat(after.get(0).triggerSource())
                .isEqualTo(QueuedRecipe.TriggerSource.CRON);
        });
    }

    @Test
    void gap_path_enqueues_with_gap_reason() {
        var enrollment = new RecipeEnrollment("retrain-task-present",
            "did:test:alice", CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of("task_present.misroute"));
        enrollments.upsert(enrollment);

        // Chronicle detected this gap on Alice's classifier.
        var planned = RecipeGapTrigger.plan(
            "task_present.misroute", "did:test:alice",
            enrollments.listByGapKey("task_present.misroute"));
        assertThat(planned).hasSize(1);

        var dispatched = new AtomicInteger(0);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue,
            stubDispatcher(RecipeRunner.Status.SUCCESS, dispatched),
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5)));

        for (var row : planned) scheduler.tell(new RecipeScheduler.Enqueue(row));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            var rows = queue.findByRecipe("retrain-task-present", "did:test:alice");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).triggerSource())
                .isEqualTo(QueuedRecipe.TriggerSource.GAP);
            assertThat(rows.get(0).triggerReason())
                .contains("gap:task_present.misroute");
            assertThat(rows.get(0).status())
                .isEqualTo(QueuedRecipe.Status.SUCCEEDED);
        });
    }

    @Test
    void agent_initiated_request_passes_gate_and_lands_in_queue() {
        var enrollment = new RecipeEnrollment("retrain-classifier-head",
            "did:test:alice", CadenceTier.WARMUP, 0,
            Instant.now(), true, Set.of());
        enrollments.upsert(enrollment);

        // Agent gate evaluates inputs.
        var inputs = new RecipeRequestGate.Inputs(
            "retrain-classifier-head", "did:test:alice",
            enrollments.find("retrain-classifier-head", "did:test:alice").isPresent(),
            RepairMode.NONE, false);
        var d = RecipeRequestGate.evaluate(inputs);
        assertThat(d.allow()).isTrue();

        var entry = QueuedRecipe.newEntry(UUID.randomUUID().toString(),
            "retrain-classifier-head", Map.of(),
            "agent-initiated probe", QueuedRecipe.TriggerSource.AGENT,
            "did:test:alice", CadenceTier.WARMUP, 0);

        var dispatched = new AtomicInteger(0);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue,
            stubDispatcher(RecipeRunner.Status.SUCCESS, dispatched),
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5)));
        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            var rows = queue.findByRecipe("retrain-classifier-head", "did:test:alice");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).triggerSource())
                .isEqualTo(QueuedRecipe.TriggerSource.AGENT);
        });
    }

    @Test
    void agent_request_denied_when_not_enrolled_never_reaches_queue() {
        // No enrollment seeded.
        var inputs = new RecipeRequestGate.Inputs(
            "retrain-classifier-head", "did:test:alice",
            enrollments.find("retrain-classifier-head", "did:test:alice").isPresent(),
            RepairMode.NONE, false);
        var d = RecipeRequestGate.evaluate(inputs);
        assertThat(d.allow()).isFalse();
        assertThat(d.reason()).isEqualTo(RecipeRequestGate.DenyReason.NOT_ENROLLED);

        // Caller must NOT enqueue when denied — verify queue stays empty.
        assertThat(queue.size()).isZero();
    }

    @Test
    void welfare_gate_repair_mode_defers_dispatch_row_stays_pending() {
        // All three triggers fire and write rows; the welfare gate keeps
        // them PENDING because companion-A is in repair-mode. Once we
        // clear the supplier's repair signal, the next poll dispatches.
        seedEnrollment("recipe-A", "did:test:alice",
            Set.of("task_present.misroute"));

        var cronRows = RecipeCronTrigger.plan(enrollments.listEnabled(),
            (r, d) -> null, Instant.now());
        var gapRows = RecipeGapTrigger.plan("task_present.misroute",
            "did:test:alice",
            enrollments.listByGapKey("task_present.misroute"));
        var agentRow = QueuedRecipe.newEntry(UUID.randomUUID().toString(),
            "recipe-A", Map.of(), "agent test",
            QueuedRecipe.TriggerSource.AGENT, "did:test:alice",
            CadenceTier.WARMUP, 0);

        var repairMode = new AtomicReference<>(RepairMode.SELF);
        RecipeScheduler.WelfareSupplier supplier = peeked ->
            new WelfareGate.Inputs(
                EnumSet.of(repairMode.get()),
                false,
                Duration.ZERO, Duration.ofHours(6),
                0, 100,
                null, peeked.cadenceTier(),
                0, Instant.now());

        var dispatched = new AtomicInteger(0);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue,
            stubDispatcher(RecipeRunner.Status.SUCCESS, dispatched),
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5),
            supplier));

        for (var row : cronRows) scheduler.tell(new RecipeScheduler.Enqueue(row));
        for (var row : gapRows) scheduler.tell(new RecipeScheduler.Enqueue(row));
        scheduler.tell(new RecipeScheduler.Enqueue(agentRow));
        scheduler.tell(new RecipeScheduler.PollNow());

        // After the poll, all three rows must still be PENDING because
        // the welfare gate denied each peek attempt.
        awaitUntilAsserted(Duration.ofSeconds(3), () -> {
            assertThat(dispatched.get())
                .as("welfare gate must have denied every dispatch attempt")
                .isZero();
            var pending = queue.listByStatus(QueuedRecipe.Status.PENDING);
            assertThat(pending).hasSize(3);
            assertThat(pending)
                .extracting(QueuedRecipe::triggerSource)
                .containsExactlyInAnyOrder(
                    QueuedRecipe.TriggerSource.CRON,
                    QueuedRecipe.TriggerSource.GAP,
                    QueuedRecipe.TriggerSource.AGENT);
        });

        // Lift the welfare denial — repair-mode clears. Next poll dispatches.
        repairMode.set(RepairMode.NONE);
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            assertThat(dispatched.get())
                .as("after repair-mode clears, scheduler should dispatch the first row")
                .isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    void force_fire_bypasses_welfare_gate() {
        seedEnrollment("recipe-A", "did:test:alice", Set.of());

        // Welfare gate ALWAYS denies (repair-mode active forever).
        RecipeScheduler.WelfareSupplier supplier = peeked ->
            new WelfareGate.Inputs(
                EnumSet.of(RepairMode.STEWARD),
                false, Duration.ZERO, Duration.ofHours(6),
                0, 100, null, peeked.cadenceTier(),
                0, Instant.now());

        var entry = QueuedRecipe.newEntry(UUID.randomUUID().toString(),
            "recipe-A", Map.of(), "test",
            QueuedRecipe.TriggerSource.STEWARD, "did:test:alice",
            CadenceTier.WARMUP, 0);

        var dispatched = new AtomicInteger(0);
        var scheduler = testKit.spawn(RecipeScheduler.create(queue,
            stubDispatcher(RecipeRunner.Status.SUCCESS, dispatched),
            new RecipeScheduler.Config(Duration.ofSeconds(30), 5),
            supplier));

        scheduler.tell(new RecipeScheduler.Enqueue(entry));
        // Steward force-fires for this pair.
        scheduler.tell(new RecipeScheduler.ForceFire("recipe-A", "did:test:alice"));
        scheduler.tell(new RecipeScheduler.PollNow());

        awaitUntilAsserted(Duration.ofSeconds(5), () -> {
            assertThat(dispatched.get())
                .as("force-fire should bypass the welfare gate")
                .isEqualTo(1);
        });
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private void seedEnrollment(String recipeId, String agentDid, Set<String> gapKeys) {
        enrollments.upsert(new RecipeEnrollment(recipeId, agentDid,
            CadenceTier.WARMUP, 0, Instant.now(), true, gapKeys));
    }

    private static RecipeScheduler.Dispatcher stubDispatcher(
            RecipeRunner.Status status, AtomicInteger counter) {
        return (did, name, params) -> {
            counter.incrementAndGet();
            return new RecipeService.StartedRun(UUID.randomUUID().toString(),
                new RecipeRunner.RecipeRun(status, "ok",
                    List.of(), new RecipeContext(Map.of())));
        };
    }

    private static void awaitUntilAsserted(Duration timeout, Runnable check) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try { check.run(); return; }
            catch (AssertionError | RuntimeException e) { last = e; }
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (last instanceof AssertionError ae) throw ae;
        if (last instanceof RuntimeException re) throw re;
        throw new AssertionError("awaitUntilAsserted timed out", last);
    }
}
