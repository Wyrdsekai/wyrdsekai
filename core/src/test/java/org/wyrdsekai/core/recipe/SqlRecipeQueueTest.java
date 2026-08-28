package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track-C C1 — SqlRecipeQueue round-trip against a fresh SQLite file.
 *
 * <p>Covers the contract the scheduler (C2) leans on: enqueue → peek →
 * markAttempted (CAS) → markCompleted (atomic outcome + cadence write) →
 * list / find / findByRecipe. Each test gets its own temp DB so there's
 * no state bleed.</p>
 */
class SqlRecipeQueueTest {

    @TempDir
    Path tmp;

    private SqlRecipeQueue queue;

    @BeforeEach
    void setUp() {
        queue = new SqlRecipeQueue("jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath());
    }

    @Test
    void enqueue_and_find_round_trips_every_field() {
        var params = new LinkedHashMap<String, Object>();
        params.put("head", "task_present");
        params.put("min_accuracy", 0.8);
        var entry = QueuedRecipe.newEntry(
            UUID.randomUUID().toString(),
            "retrain-classifier-head",
            params,
            "settling cadence tick",
            QueuedRecipe.TriggerSource.CRON,
            "did:wyrd:companion-wyrd",
            CadenceTier.SETTLING,
            2);

        queue.enqueue(entry);

        var found = queue.find(entry.id()).orElseThrow();
        assertThat(found.id()).isEqualTo(entry.id());
        assertThat(found.recipeId()).isEqualTo("retrain-classifier-head");
        assertThat(found.params())
            .containsEntry("head", "task_present")
            .containsEntry("min_accuracy", 0.8);
        assertThat(found.triggerReason()).isEqualTo("settling cadence tick");
        assertThat(found.triggerSource()).isEqualTo(QueuedRecipe.TriggerSource.CRON);
        assertThat(found.agentDid()).isEqualTo("did:wyrd:companion-wyrd");
        assertThat(found.cadenceTier()).isEqualTo(CadenceTier.SETTLING);
        assertThat(found.consecutiveSuccesses()).isEqualTo(2);
        assertThat(found.status()).isEqualTo(QueuedRecipe.Status.PENDING);
        assertThat(found.attemptedAt()).isNull();
        assertThat(found.completedAt()).isNull();
    }

    @Test
    void peek_returns_oldest_pending_first() {
        var older = newPending("recipe-A", Instant.now().minus(Duration.ofMinutes(5)));
        var newer = newPending("recipe-B", Instant.now());
        queue.enqueue(newer);   // insert order should not matter
        queue.enqueue(older);

        var head = queue.peekNextPending().orElseThrow();
        assertThat(head.id()).isEqualTo(older.id());
    }

    @Test
    void peek_skips_in_progress_and_terminal_rows() {
        var attempted = newPending("recipe-A", Instant.now().minus(Duration.ofMinutes(10)));
        var pending   = newPending("recipe-B", Instant.now().minus(Duration.ofMinutes(1)));
        queue.enqueue(attempted);
        queue.enqueue(pending);

        // attempted moves to IN_PROGRESS, then SUCCEEDED — peek must skip it.
        assertThat(queue.markAttempted(attempted.id(), Instant.now())).isTrue();
        assertThat(queue.markCompleted(attempted.id(),
            QueuedRecipe.Status.SUCCEEDED, Instant.now(),
            CadenceTier.SETTLING, 3, "run-xyz", "ok")).isTrue();

        var head = queue.peekNextPending().orElseThrow();
        assertThat(head.id()).isEqualTo(pending.id());
    }

    @Test
    void markAttempted_is_idempotent_CAS() {
        var entry = newPending("recipe-A", Instant.now());
        queue.enqueue(entry);

        // First call wins.
        assertThat(queue.markAttempted(entry.id(), Instant.now())).isTrue();
        // Second call must NOT touch the row — status drifted to IN_PROGRESS.
        assertThat(queue.markAttempted(entry.id(), Instant.now())).isFalse();

        var after = queue.find(entry.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(QueuedRecipe.Status.IN_PROGRESS);
        assertThat(after.attemptedAt()).isNotNull();
    }

    @Test
    void markCompleted_writes_cadence_and_run_outcome_atomically() {
        var entry = newPending("recipe-A", Instant.now());
        queue.enqueue(entry);
        queue.markAttempted(entry.id(), Instant.now());

        var completedAt = Instant.now();
        assertThat(queue.markCompleted(entry.id(),
            QueuedRecipe.Status.SUCCEEDED, completedAt,
            CadenceTier.SETTLING, 3, "run-12345", "all gates passed"))
            .isTrue();

        var after = queue.find(entry.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(QueuedRecipe.Status.SUCCEEDED);
        assertThat(after.completedAt()).isEqualTo(
            Instant.ofEpochMilli(completedAt.toEpochMilli()));
        assertThat(after.cadenceTier()).isEqualTo(CadenceTier.SETTLING);
        assertThat(after.consecutiveSuccesses()).isEqualTo(3);
        assertThat(after.runId()).isEqualTo("run-12345");
        assertThat(after.message()).isEqualTo("all gates passed");
    }

    @Test
    void markCompleted_rejects_non_terminal_status() {
        var entry = newPending("recipe-A", Instant.now());
        queue.enqueue(entry);
        try {
            queue.markCompleted(entry.id(),
                QueuedRecipe.Status.IN_PROGRESS, Instant.now(),
                CadenceTier.WARMUP, 0, null, null);
            // unreachable
            assertThat(true).as("expected IllegalArgumentException").isFalse();
        } catch (IllegalArgumentException expected) {
            // Assert on the status that was REJECTED rather than the list of accepted
            // ones: the accepted set grew when SKIPPED arrived (a run that never
            // started still has to leave the queue), and pinning the prose meant this
            // test failed for a wording change while the behaviour was correct.
            assertThat(expected.getMessage()).contains("IN_PROGRESS");
        }
    }

    @Test
    void findByRecipe_scopes_to_recipe_and_agent_pair() {
        var aliceA = newPending("recipe-A", Instant.now());
        var aliceA2 = newPending("recipe-A", Instant.now().plusMillis(1));
        var bobA = newPending("recipe-A", Instant.now().plusMillis(2));
        var aliceB = newPending("recipe-B", Instant.now().plusMillis(3));
        // override agents — newPending bakes did:test:alice by default.
        aliceA  = withAgent(aliceA,  "did:test:alice");
        aliceA2 = withAgent(aliceA2, "did:test:alice");
        bobA    = withAgent(bobA,    "did:test:bob");
        aliceB  = withAgent(aliceB,  "did:test:alice");

        queue.enqueue(aliceA);
        queue.enqueue(aliceA2);
        queue.enqueue(bobA);
        queue.enqueue(aliceB);

        var alicesRecipeA = queue.findByRecipe("recipe-A", "did:test:alice");
        assertThat(alicesRecipeA).hasSize(2)
            .extracting(QueuedRecipe::agentDid)
            .containsOnly("did:test:alice");
        assertThat(alicesRecipeA)
            .extracting(QueuedRecipe::recipeId)
            .containsOnly("recipe-A");
    }

    @Test
    void listByStatus_returns_matching_rows_newest_first() {
        var early = newPending("recipe-A", Instant.now().minus(Duration.ofMinutes(10)));
        var late  = newPending("recipe-B", Instant.now());
        queue.enqueue(early);
        queue.enqueue(late);

        var pending = queue.listByStatus(QueuedRecipe.Status.PENDING);
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).id()).isEqualTo(late.id());
        assertThat(pending.get(1).id()).isEqualTo(early.id());

        // Mark one terminal and re-check status partitioning.
        queue.markAttempted(early.id(), Instant.now());
        queue.markCompleted(early.id(),
            QueuedRecipe.Status.FAILED, Instant.now(),
            CadenceTier.WARMUP, 0, null, "gate-fail");

        assertThat(queue.listByStatus(QueuedRecipe.Status.PENDING))
            .extracting(QueuedRecipe::id)
            .containsExactly(late.id());
        assertThat(queue.listByStatus(QueuedRecipe.Status.FAILED))
            .extracting(QueuedRecipe::id)
            .containsExactly(early.id());
    }

    @Test
    void enqueue_is_idempotent_on_id() {
        var id = UUID.randomUUID().toString();
        var first  = newWithIdAndAgent(id, "recipe-A", "did:test:alice");
        var base   = newWithIdAndAgent(id, "recipe-A", "did:test:alice");
        var second = new QueuedRecipe(base.id(), base.recipeId(), base.params(),
            base.triggerReason(), base.triggerSource(), base.enqueuedAt(),
            base.attemptedAt(), base.completedAt(), base.status(),
            base.agentDid(), base.cadenceTier(), 7,
            base.runId(), base.message());

        queue.enqueue(first);
        queue.enqueue(second);

        assertThat(queue.size()).isEqualTo(1);
        var after = queue.find(id).orElseThrow();
        // upsert merged the new consecutive_successes.
        assertThat(after.consecutiveSuccesses()).isEqualTo(7);
    }

    // -- hasOpenForRecipe (G4 C4 cron-wire idempotency probe, #1016) ----

    @Test
    void hasOpenForRecipe_true_for_PENDING_and_IN_PROGRESS_false_after_terminal() {
        var queue = new SqlRecipeQueue(
            "jdbc:sqlite:" + tmp.resolve("rq-hasopen.db").toAbsolutePath());
        // Empty store: no row → false.
        assertThat(queue.hasOpenForRecipe("recipe-X", "did:test:alice")).isFalse();

        // PENDING row → true.
        var pending = newWithIdAndAgent(UUID.randomUUID().toString(),
            "recipe-X", "did:test:alice");
        queue.enqueue(pending);
        assertThat(queue.hasOpenForRecipe("recipe-X", "did:test:alice")).isTrue();

        // IN_PROGRESS row (after markAttempted) → still true.
        queue.markAttempted(pending.id(), Instant.now());
        assertThat(queue.hasOpenForRecipe("recipe-X", "did:test:alice")).isTrue();

        // Terminal (SUCCEEDED) → false.
        queue.markCompleted(pending.id(), QueuedRecipe.Status.SUCCEEDED,
            Instant.now(), CadenceTier.WARMUP, 1, "run-1", "ok");
        assertThat(queue.hasOpenForRecipe("recipe-X", "did:test:alice")).isFalse();

        // FAILED terminal also clears the gate.
        var pending2 = newWithIdAndAgent(UUID.randomUUID().toString(),
            "recipe-X", "did:test:alice");
        queue.enqueue(pending2);
        queue.markAttempted(pending2.id(), Instant.now());
        queue.markCompleted(pending2.id(), QueuedRecipe.Status.FAILED,
            Instant.now(), CadenceTier.WARMUP, 0, null, "boom");
        assertThat(queue.hasOpenForRecipe("recipe-X", "did:test:alice")).isFalse();
    }

    @Test
    void hasOpenForRecipe_scopes_by_recipeId_and_agentDid() {
        // Different recipe → independent; different agent → independent;
        // null agent matches null agent only (steward scope).
        var queue = new SqlRecipeQueue(
            "jdbc:sqlite:" + tmp.resolve("rq-hasopen-scope.db").toAbsolutePath());
        queue.enqueue(newWithIdAndAgent(UUID.randomUUID().toString(),
            "recipe-A", "did:test:alice"));
        queue.enqueue(newWithIdAndAgent(UUID.randomUUID().toString(),
            "recipe-A", "did:test:bob"));
        var steward = newWithIdAndAgent(UUID.randomUUID().toString(),
            "recipe-A", null);
        queue.enqueue(steward);

        assertThat(queue.hasOpenForRecipe("recipe-A", "did:test:alice")).isTrue();
        assertThat(queue.hasOpenForRecipe("recipe-A", "did:test:bob")).isTrue();
        assertThat(queue.hasOpenForRecipe("recipe-A", null)).isTrue();
        // Different recipe → no rows.
        assertThat(queue.hasOpenForRecipe("recipe-B", "did:test:alice")).isFalse();
        // Different agent on same recipe → no rows for that agent.
        assertThat(queue.hasOpenForRecipe("recipe-A", "did:test:carol")).isFalse();
    }

    @Test
    void migration_is_idempotent_across_constructions() {
        // Second instance against the same DB must not fail; CREATE TABLE
        // IF NOT EXISTS is the production invariant.
        new SqlRecipeQueue("jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath())
            .enqueue(newPending("recipe-A", Instant.now()));
        new SqlRecipeQueue("jdbc:sqlite:" + tmp.resolve("rq.db").toAbsolutePath())
            .enqueue(newPending("recipe-B", Instant.now()));
        assertThat(queue.size()).isEqualTo(2);
    }

    // -- helpers ---------------------------------------------------------

    private static QueuedRecipe newPending(String recipeId, Instant enqueuedAt) {
        return new QueuedRecipe(
            UUID.randomUUID().toString(), recipeId, Map.of(),
            null, QueuedRecipe.TriggerSource.AGENT, enqueuedAt,
            null, null, QueuedRecipe.Status.PENDING,
            "did:test:alice", CadenceTier.WARMUP, 0, null, null);
    }

    private static QueuedRecipe newWithIdAndAgent(String id, String recipeId, String did) {
        return new QueuedRecipe(id, recipeId, Map.of(), null,
            QueuedRecipe.TriggerSource.AGENT, Instant.now(),
            null, null, QueuedRecipe.Status.PENDING,
            did, CadenceTier.WARMUP, 0, null, null);
    }

    private static QueuedRecipe withAgent(QueuedRecipe e, String did) {
        return new QueuedRecipe(e.id(), e.recipeId(), e.params(),
            e.triggerReason(), e.triggerSource(), e.enqueuedAt(),
            e.attemptedAt(), e.completedAt(), e.status(), did,
            e.cadenceTier(), e.consecutiveSuccesses(),
            e.runId(), e.message());
    }
}
