package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that never started is not a failed run.
 *
 * <p>{@code NEEDS_BACKEND} means the node has no familiar to do the work;
 * {@code RESOURCE_DENIED} means the box cannot satisfy the recipe's declared hardware
 * needs. Both were mapped to {@code Outcome.ERROR} and stored as FAILED, so three ticks
 * on a node without a coding backend tripped the consecutive-deploy-failure ceiling and
 * paused the recipe — and {@link ShipDefaultEnrollmentProvisioner} documents that such a
 * node "will just NEEDS_BACKEND every run", so this was the default fate of any install
 * shipped without goose.
 *
 * <p>Same principle as the missing-required-param case: a welfare ceiling must only
 * count work that actually ran. {@code GATE_FAILED} is pointedly excluded — that work
 * ran and did not clear the bar, which is precisely what the ceiling is for.
 */
class NeverRanIsNotAFailureTest {

    private static RecipeRunner.RecipeRun run(RecipeRunner.Status status) {
        return new RecipeRunner.RecipeRun(status, status.name(), List.of(), null, null);
    }

    @Test
    void a_node_without_a_backend_has_not_failed_anything() {
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.NEEDS_BACKEND))).isTrue();
    }

    @Test
    void a_box_that_cannot_meet_the_requisites_has_not_failed_anything() {
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.RESOURCE_DENIED))).isTrue();
    }

    @Test
    void work_that_ran_and_missed_the_bar_still_counts() {
        // The ceiling must keep noticing genuine regressions — that is its whole job.
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.GATE_FAILED))).isFalse();
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.STEP_FAILED))).isFalse();
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.ERROR))).isFalse();
        assertThat(RecipeScheduler.neverRan(run(RecipeRunner.Status.SUCCESS))).isFalse();
    }

    @Test
    void a_missing_run_is_not_treated_as_never_ran() {
        // A null run means the dispatcher itself broke — that IS an error worth counting.
        assertThat(RecipeScheduler.neverRan(null)).isFalse();
    }

    @Test
    void skipped_can_be_written_as_a_terminal_and_does_not_feed_the_ceiling() throws Exception {
        // Regression guard: markCompleted used to reject anything but SUCCEEDED/FAILED,
        // so the skip path would have thrown at runtime while compiling perfectly.
        var db = Files.createTempFile("never-ran", ".db");
        Files.delete(db);
        var jdbc = "jdbc:sqlite:" + db;
        try {
            var queue = new SqlRecipeQueue(jdbc);
            var budget = new RecipeBudgetTracker(jdbc);
            for (int i = 0; i < 4; i++) {
                var id = "nb-" + i;
                queue.enqueue(QueuedRecipe.newEntry(id, "retrain-classifier-head",
                    Map.of("head", "task_present"), "cron",
                    QueuedRecipe.TriggerSource.CRON, "did:key:z6Mktest",
                    CadenceTier.WARMUP, 0));
                queue.markAttempted(id, Instant.now());
                queue.markCompleted(id, QueuedRecipe.Status.SKIPPED, Instant.now(),
                    CadenceTier.WARMUP, 0, "run-" + id, "NEEDS_BACKEND");
            }
            assertThat(budget.consecutiveDeployFailures(
                "retrain-classifier-head", "did:key:z6Mktest")).isZero();
            assertThat(queue.peekNextPending()).isEmpty();
        } finally {
            Files.deleteIfExists(db);
        }
    }

    @Test
    void completing_into_a_non_terminal_state_is_still_rejected() throws Exception {
        var db = Files.createTempFile("never-ran2", ".db");
        Files.delete(db);
        try {
            var queue = new SqlRecipeQueue("jdbc:sqlite:" + db);
            queue.enqueue(QueuedRecipe.newEntry("x", "r", Map.of(), "cron",
                QueuedRecipe.TriggerSource.CRON, null, CadenceTier.WARMUP, 0));
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                queue.markCompleted("x", QueuedRecipe.Status.PENDING, Instant.now(),
                    CadenceTier.WARMUP, 0, null, "nope"))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            Files.deleteIfExists(db);
        }
    }
}
