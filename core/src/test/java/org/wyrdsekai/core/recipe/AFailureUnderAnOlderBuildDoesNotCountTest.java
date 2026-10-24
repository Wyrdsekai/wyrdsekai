package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deploy ceiling counts a failure streak; an update of Wyrdsekai ends it.
 *
 * <p>On a household node the retrain recipe failed thirteen times in a row on
 * one cause (its probe could not run on an installed node). The fix shipped in
 * an update, but the streak lived in the queue history and the breaker kept
 * the recipe waiting a week for one more try. Every finished row now carries
 * the build that wrote it, and the streak stops at the first row written by
 * another build — or by a build from before the column existed.
 */
class AFailureUnderAnOlderBuildDoesNotCountTest {

    private static final String RECIPE = "retrain-classifier-head";
    private static final String DID = "did:key:z6Mkstreak";

    @Test
    void failures_written_by_this_build_count_and_an_older_builds_do_not(@TempDir Path tmp) throws Exception {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("rq.db");
        var queue = new SqlRecipeQueue(jdbc);
        var budget = new RecipeBudgetTracker(jdbc);

        for (int i = 0; i < 3; i++) fail(queue, "now-" + i, Instant.now().plusSeconds(i));
        assertThat(budget.consecutiveDeployFailures(RECIPE, DID))
                .as("three failures by the running build").isEqualTo(3);

        // The same history seen by a later build: not its failures.
        assertThat(budget.consecutiveDeployFailures(RECIPE, DID, "0.9.9 (deadbee)")).isZero();
    }

    @Test
    void history_from_before_the_column_existed_ends_the_streak(@TempDir Path tmp) throws Exception {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("rq.db");
        var queue = new SqlRecipeQueue(jdbc);
        var budget = new RecipeBudgetTracker(jdbc);

        for (int i = 0; i < 4; i++) fail(queue, "old-" + i, Instant.now().minusSeconds(100 - i));
        // Rows written by a build that did not record itself (pre-column history).
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.executeUpdate("UPDATE recipe_queue SET node_version = NULL");
        }
        assertThat(budget.consecutiveDeployFailures(RECIPE, DID)).isZero();

        // One fresh failure by this build counts as one, not five.
        fail(queue, "fresh", Instant.now());
        assertThat(budget.consecutiveDeployFailures(RECIPE, DID)).isEqualTo(1);
    }

    @Test
    void a_queue_table_from_before_the_column_gains_it_on_first_use(@TempDir Path tmp) throws Exception {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("old.db");
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE recipe_queue(
                  id TEXT PRIMARY KEY, recipe_id TEXT NOT NULL, params_json TEXT NOT NULL DEFAULT '{}',
                  trigger_reason TEXT, trigger_source TEXT NOT NULL, enqueued_at INTEGER NOT NULL,
                  attempted_at INTEGER, completed_at INTEGER, status TEXT NOT NULL DEFAULT 'PENDING',
                  agent_did TEXT, cadence_tier TEXT NOT NULL DEFAULT 'WARMUP',
                  consecutive_successes INTEGER NOT NULL DEFAULT 0, run_id TEXT, message TEXT)
                """);
        }
        var queue = new SqlRecipeQueue(jdbc);
        fail(queue, "after-upgrade", Instant.now());
        try (var conn = DriverManager.getConnection(jdbc);
             var rs = conn.createStatement().executeQuery(
                     "SELECT node_version FROM recipe_queue WHERE id = 'after-upgrade'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(SqlRecipeQueue.nodeVersion());
        }
        assertThat(new RecipeBudgetTracker(jdbc).consecutiveDeployFailures(RECIPE, DID)).isEqualTo(1);
    }

    private static void fail(SqlRecipeQueue queue, String id, Instant at) {
        queue.enqueue(QueuedRecipe.newEntry(id, RECIPE, Map.of("head", "task_present"), "cron",
                QueuedRecipe.TriggerSource.CRON, DID, CadenceTier.WARMUP, 0));
        queue.markAttempted(id, at);
        queue.markCompleted(id, QueuedRecipe.Status.FAILED, at, CadenceTier.WARMUP, 0,
                "run-" + id, "shell step 'regression-probe' exit 1");
    }
}
