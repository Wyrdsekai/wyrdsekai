package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a scheduled run of {@code retrain-classifier-head} should work on.
 *
 * <p>The gap path reads the head off the gap key — {@code "<head>.misroute"} names the
 * thing that misrouted. Cron has no equivalent signal, so the scheduled path supplied no
 * {@code head} at all and every cadence tick died on "missing required params": the
 * recipe's own "cron when stale" intent had never once worked (found live 2026-08-18).
 *
 * <p>Selection is by STALENESS rather than by score, and from a DECLARED candidate set,
 * because both of the obvious alternatives are actively harmful here — see the tests
 * below for the evidence each one encodes.
 */
class CronPicksStalestHeadTest {

    private static final String DID = "did:key:z6MkExampleCompanion";
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");

    // ── the candidate set is declared, and excludes the heads that must not be picked ──

    @Test
    void the_shipped_candidate_set_is_the_two_heads_retraining_actually_improves() throws Exception {
        var yaml = Files.readString(Path.of(
            "src/main/resources/recipes/retrain-classifier-head.recipe.yaml"));
        var manifest = RecipeParser.parseManifest(yaml);
        var cronHeads = manifest.params().get("cron_heads");

        assertThat(cronHeads).as("scheduled runs need a declared candidate set").isNotNull();
        var heads = Set.of(String.valueOf(cronHeads.defaultValue()).split(","));

        // Proven end-to-end by the 2026-07-21 release-bake study.
        assertThat(heads).contains("task_present", "cleanliness");

        // substrate_present: baseline already optimal (0/90); BOTH LR (6/90) and MLP
        // (12/90) retrains REGRESS it. Auto-selecting it could only make her worse.
        assertThat(heads).doesNotContain("substrate_present");

        // request_type: 8-way head at its capability ceiling (~0.58 < the 0.60 gate).
        // It can only FAIL the accuracy gate — and unlike a missing param, a real gate
        // failure legitimately counts toward the deploy ceiling, so auto-selecting it
        // would pause the recipe for a reason that is nobody's bug.
        assertThat(heads).doesNotContain("request_type");
    }

    // ── staleness, because the recorded accuracies are not comparable ──────────────

    @Test
    void per_head_success_history_is_reported_separately() throws Exception {
        withQueue((queue, budget) -> {
            succeed(queue, "a", "task_present", NOW.minus(Duration.ofDays(9)));
            succeed(queue, "b", "cleanliness",  NOW.minus(Duration.ofDays(2)));

            var byHead = budget.lastSuccessByParam("retrain-classifier-head", DID, "head");
            assertThat(byHead).containsOnlyKeys("task_present", "cleanliness");
            assertThat(byHead.get("task_present"))
                .isBefore(byHead.get("cleanliness"));
        });
    }

    @Test
    void a_failed_run_does_not_count_as_having_retrained_that_head() throws Exception {
        withQueue((queue, budget) -> {
            var id = "f1";
            queue.enqueue(QueuedRecipe.newEntry(id, "retrain-classifier-head",
                Map.of("head", "cleanliness"), "cron", QueuedRecipe.TriggerSource.CRON,
                DID, CadenceTier.WARMUP, 0));
            queue.markAttempted(id, NOW);
            queue.markCompleted(id, QueuedRecipe.Status.FAILED, NOW,
                CadenceTier.WARMUP, 0, "run-f1", "val_accuracy below min_accuracy");

            assertThat(budget.lastSuccessByParam("retrain-classifier-head", DID, "head"))
                .doesNotContainKey("cleanliness");
        });
    }

    // ── the choice itself ─────────────────────────────────────────────────────────

    @Test
    void the_stalest_candidate_wins() {
        var chosen = CronHeadSelection.stalest(
            List.of("task_present", "cleanliness"),
            Map.of("task_present", NOW.minus(Duration.ofDays(9)),
                   "cleanliness",  NOW.minus(Duration.ofDays(2))));
        assertThat(chosen).contains("task_present");
    }

    @Test
    void a_head_never_retrained_outranks_one_merely_old() {
        var chosen = CronHeadSelection.stalest(
            List.of("task_present", "cleanliness"),
            Map.of("task_present", NOW.minus(Duration.ofDays(400))));
        assertThat(chosen).contains("cleanliness");
    }

    @Test
    void selection_rotates_rather_than_pinning_one_head() {
        // After task_present is retrained it becomes the freshest, so the next tick
        // must move on. A selector that kept returning the same head would starve the
        // rest of the set forever.
        var first = CronHeadSelection.stalest(List.of("task_present", "cleanliness"),
            Map.of("task_present", NOW.minus(Duration.ofDays(9)),
                   "cleanliness",  NOW.minus(Duration.ofDays(2)))).orElseThrow();
        var second = CronHeadSelection.stalest(List.of("task_present", "cleanliness"),
            Map.of("task_present", NOW,
                   "cleanliness",  NOW.minus(Duration.ofDays(2)))).orElseThrow();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void ties_resolve_deterministically_to_declaration_order() {
        var at = NOW.minus(Duration.ofDays(3));
        assertThat(CronHeadSelection.stalest(List.of("task_present", "cleanliness"),
            Map.of("task_present", at, "cleanliness", at))).contains("task_present");
    }

    @Test
    void an_empty_or_blank_set_disables_scheduled_runs() {
        assertThat(CronHeadSelection.parseCandidates("")).isEmpty();
        assertThat(CronHeadSelection.parseCandidates("  ,  ")).isEmpty();
        assertThat(CronHeadSelection.parseCandidates(null)).isEmpty();
        assertThat(CronHeadSelection.stalest(List.of(), Map.of())).isEmpty();
    }

    @Test
    void the_candidate_list_tolerates_spacing_and_duplicates() {
        assertThat(CronHeadSelection.parseCandidates(" task_present , cleanliness ,task_present"))
            .containsExactly("task_present", "cleanliness");
    }

    // ── the planner carries the choice ────────────────────────────────────────────

    @Test
    void a_scheduled_run_carries_the_head_it_should_retrain() {
        var planned = planWith((recipeId, agentDid) -> Map.of("head", "task_present"));
        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).params())
            .containsEntry("head", "task_present")
            .containsEntry("agent_did", DID);
    }

    @Test
    void a_recipe_needing_no_extra_params_is_unaffected() {
        var planned = planWith(RecipeCronTrigger.CronParamsLookup.NONE);
        assertThat(planned).hasSize(1);
        assertThat(planned.get(0).params()).containsOnlyKeys("agent_did");
    }

    @Test
    void a_lookup_that_declines_leaves_the_run_unparameterised() {
        // Empty means "no scheduled runs" — the scheduler's required-param guard then
        // retires the row as SKIPPED rather than failing it.
        var planned = planWith((recipeId, agentDid) -> Map.of());
        assertThat(planned.get(0).params()).doesNotContainKey("head");
    }

    private static List<QueuedRecipe> planWith(RecipeCronTrigger.CronParamsLookup lookup) {
        var enrollment = new RecipeEnrollment("retrain-classifier-head", DID,
            CadenceTier.WARMUP, 0, NOW.minus(Duration.ofDays(30)), true, Set.of());
        return RecipeCronTrigger.plan(
            List.of(enrollment),
            (recipeId, agentDid) -> null,          // never run → due now
            RecipeCronTrigger.PrefersHoursLookup.ANYTIME,
            Clock.fixed(NOW, ZoneId.of("UTC")),
            NOW,
            lookup);
    }

    private static void succeed(SqlRecipeQueue queue, String id, String head, Instant at) {
        queue.enqueue(QueuedRecipe.newEntry(id, "retrain-classifier-head",
            Map.of("head", head), "cron", QueuedRecipe.TriggerSource.CRON,
            DID, CadenceTier.WARMUP, 0));
        queue.markAttempted(id, at);
        queue.markCompleted(id, QueuedRecipe.Status.SUCCEEDED, at,
            CadenceTier.WARMUP, 1, "run-" + id, "ok");
    }

    private interface QueueTest {
        void run(SqlRecipeQueue queue, RecipeBudgetTracker budget) throws Exception;
    }

    private static void withQueue(QueueTest body) throws Exception {
        var db = Files.createTempFile("cron-head", ".db");
        Files.delete(db);
        try {
            var jdbc = "jdbc:sqlite:" + db;
            body.run(new SqlRecipeQueue(jdbc), new RecipeBudgetTracker(jdbc));
        } finally {
            Files.deleteIfExists(db);
        }
    }
}
