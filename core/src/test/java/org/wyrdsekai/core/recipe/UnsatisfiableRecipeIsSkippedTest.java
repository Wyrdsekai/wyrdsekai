package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Set;

/**
 * A recipe that cannot run as configured must be skipped, not failed.
 *
 * <p>Live on a household node (2026-08-18): {@code retrain-classifier-head} declares
 * {@code head} required with no default. {@link RecipeGapTrigger} enqueued it with an
 * empty param map even though the gap key it fired on — {@code "task_present.misroute"}
 * — names the head. Every run therefore died in {@link RecipeRunner} on "missing
 * required params: head", and after three of them the consecutive-deploy-failure ceiling
 * paused the recipe. Fourteen failed runs later it was still paused, waiting for a
 * steward, and the welfare mechanism that exists to stop a recipe grinding a companion
 * down had been spent on a missing string.
 *
 * <p>Two defects, fixed separately because either alone leaves a hole: the trigger now
 * carries the head, and the scheduler no longer reports an outcome for work that never
 * ran.
 */
class UnsatisfiableRecipeIsSkippedTest {

    // ── the gap key carries the head ────────────────────────────────────────────

    @Test
    void a_misroute_gap_names_the_head_to_retrain() {
        assertThat(RecipeGapTrigger.paramsFor("task_present.misroute"))
            .isEqualTo(Map.of("head", "task_present"));
    }

    @Test
    void a_head_whose_name_contains_dots_keeps_all_of_it() {
        assertThat(RecipeGapTrigger.paramsFor("request_type.v2.misroute"))
            .isEqualTo(Map.of("head", "request_type.v2"));
    }

    @Test
    void a_key_that_names_no_head_yields_no_params_rather_than_a_guess() {
        assertThat(RecipeGapTrigger.paramsFor("misroute")).isEmpty();
        assertThat(RecipeGapTrigger.paramsFor(".misroute")).isEmpty();
    }

    @Test
    void planning_a_gap_run_supplies_the_required_param() {
        var enrollment = new RecipeEnrollment(
            "retrain-classifier-head", "did:key:z6Mktest", CadenceTier.WARMUP, 0,
            Instant.parse("2026-08-18T00:00:00Z"), true,
            Set.of("cleanliness.misroute"));

        var planned = RecipeGapTrigger.plan(
            "cleanliness.misroute", "did:key:z6Mktest", List.of(enrollment));

        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).params()).containsEntry("head", "cleanliness");
    }

    // ── the queue distinguishes "did not run" from "ran and failed" ──────────────

    @Test
    void skipped_is_terminal_but_is_not_an_outcome() {
        // The deploy-failure ceiling and the cadence ladder both read exactly the
        // SUCCEEDED/FAILED rows. SKIPPED must be neither, so a configuration gap can
        // neither break a success streak nor consume a welfare ceiling.
        assertThat(QueuedRecipe.Status.valueOf("SKIPPED"))
            .isNotIn(QueuedRecipe.Status.SUCCEEDED, QueuedRecipe.Status.FAILED,
                     QueuedRecipe.Status.PENDING, QueuedRecipe.Status.IN_PROGRESS);
    }

    @Test
    void marking_a_row_skipped_retires_it_without_a_failure() throws Exception {
        var db = Files.createTempFile("recipe-queue-skip", ".db");
        Files.delete(db);
        var jdbc = "jdbc:sqlite:" + db;
        try {
            var queue = new SqlRecipeQueue(jdbc);
            var budget = new RecipeBudgetTracker(jdbc);
            var row = QueuedRecipe.newEntry(
                "row-1", "retrain-classifier-head", Map.of(), "cron",
                QueuedRecipe.TriggerSource.CRON, "did:key:z6Mktest",
                CadenceTier.WARMUP, 0);
            queue.enqueue(row);

            assertThat(queue.markSkipped("row-1", Instant.now(),
                "missing required param(s): head")).isTrue();

            // Gone from the queue — it cannot block the head forever.
            assertThat(queue.peekNextPending()).isEmpty();
            // And invisible to the ceiling that paused it before.
            assertThat(budget.consecutiveDeployFailures(
                "retrain-classifier-head", "did:key:z6Mktest")).isZero();
        } finally {
            Files.deleteIfExists(db);
        }
    }

    @Test
    void history_of_runs_that_never_started_stops_holding_the_ceiling_down() throws Exception {
        // Correcting the cause is not enough on an install that already accumulated
        // these: the FAILED rows are permanent, so the ceiling stays tripped and the
        // recipe stays paused forever. The live node had exactly three — the limit.
        var db = Files.createTempFile("recipe-queue-heal", ".db");
        Files.delete(db);
        var jdbc = "jdbc:sqlite:" + db;
        try {
            var seed = new SqlRecipeQueue(jdbc);
            var budget = new RecipeBudgetTracker(jdbc);
            for (int i = 0; i < 3; i++) {
                var id = "old-" + i;
                seed.enqueue(QueuedRecipe.newEntry(
                    id, "retrain-classifier-head", Map.of(), "gap:task_present.misroute",
                    QueuedRecipe.TriggerSource.GAP, "did:key:z6Mktest",
                    CadenceTier.WARMUP, 0));
                seed.markAttempted(id, Instant.now());
                seed.markCompleted(id, QueuedRecipe.Status.FAILED,
                    Instant.now(), CadenceTier.WARMUP, 0, null,
                    "missing required params: head");
            }
            assertThat(budget.consecutiveDeployFailures(
                "retrain-classifier-head", "did:key:z6Mktest")).isEqualTo(3);

            // A fresh store runs the migration, as a restart on an upgraded node does.
            new SqlRecipeQueue(jdbc).size();

            assertThat(budget.consecutiveDeployFailures(
                "retrain-classifier-head", "did:key:z6Mktest")).isZero();
        } finally {
            Files.deleteIfExists(db);
        }
    }

    @Test
    void a_run_that_genuinely_failed_still_counts() throws Exception {
        // The repair must not launder real failures — only rows that provably never
        // started, matched on the exact message the runner writes before step one.
        var db = Files.createTempFile("recipe-queue-keep", ".db");
        Files.delete(db);
        var jdbc = "jdbc:sqlite:" + db;
        try {
            var seed = new SqlRecipeQueue(jdbc);
            var budget = new RecipeBudgetTracker(jdbc);
            seed.enqueue(QueuedRecipe.newEntry(
                "real-1", "retrain-classifier-head", Map.of("head", "task_present"),
                "gap", QueuedRecipe.TriggerSource.GAP, "did:key:z6Mktest",
                CadenceTier.WARMUP, 0));
            seed.markAttempted("real-1", Instant.now());
            seed.markCompleted("real-1", QueuedRecipe.Status.FAILED,
                Instant.now(), CadenceTier.WARMUP, 0, "run-1",
                "val_accuracy 0.61 below min_accuracy 0.80");

            new SqlRecipeQueue(jdbc).size();

            assertThat(budget.consecutiveDeployFailures(
                "retrain-classifier-head", "did:key:z6Mktest")).isEqualTo(1);
        } finally {
            Files.deleteIfExists(db);
        }
    }

    @Test
    void a_row_already_taken_by_a_runner_is_not_skipped_out_from_under_it() throws Exception {
        var db = Files.createTempFile("recipe-queue-skip2", ".db");
        Files.delete(db);
        var jdbc = "jdbc:sqlite:" + db;
        try {
            var queue = new SqlRecipeQueue(jdbc);
            queue.enqueue(QueuedRecipe.newEntry(
                "row-2", "retrain-classifier-head", Map.of(), "cron",
                QueuedRecipe.TriggerSource.CRON, "did:key:z6Mktest",
                CadenceTier.WARMUP, 0));
            assertThat(queue.markAttempted("row-2", Instant.now())).isTrue();

            assertThat(queue.markSkipped("row-2", Instant.now(), "too late"))
                .isFalse();
        } finally {
            Files.deleteIfExists(db);
        }
    }
}
