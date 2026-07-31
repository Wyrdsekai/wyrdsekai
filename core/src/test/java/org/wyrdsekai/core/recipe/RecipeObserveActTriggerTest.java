package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * observe→act loop (#1140) — unit coverage for
 * {@link RecipeObserveActTrigger}: the pure report→enqueue mapper and the
 * deploys-filter + queue-dedup dispatch glue.
 */
class RecipeObserveActTriggerTest {

    // ── plan() pure logic ──────────────────────────────────────────────────

    @Test
    void maintenanceDueMapsToRecipeEnqueues() {
        var report = Map.<String, Object>of(
            "checkup_ok", 0,
            "maintenance_due", List.of("reembed-soul-fragments", "consolidate-memory-graph"));
        var plan = RecipeObserveActTrigger.plan(report, "welfare-floor-checkup",
            "did:wyrd:a", CadenceTier.WARMUP, null, det());
        assertThat(plan.enqueue()).hasSize(2);
        assertThat(plan.enqueue()).allSatisfy(q -> {
            assertThat(q.triggerSource()).isEqualTo(QueuedRecipe.TriggerSource.GAP);
            assertThat(q.agentDid()).isEqualTo("did:wyrd:a");
            assertThat(q.triggerReason()).startsWith("observe-act:welfare-floor-checkup:");
        });
        assertThat(plan.enqueue().stream().map(QueuedRecipe::recipeId))
            .containsExactlyInAnyOrder("reembed-soul-fragments", "consolidate-memory-graph");
        assertThat(plan.advisoryOnly()).isEmpty();
    }

    @Test
    void recommendationsWithNoRecipeAreAdvisoryOnly() {
        var report = Map.<String, Object>of(
            "freshness_ok", 0,
            "recommendations", List.of("re-acquire-dead-sources", "review-stale-unread-packs"));
        var plan = RecipeObserveActTrigger.plan(report, "research-pack-freshness",
            "did:wyrd:a", CadenceTier.WARMUP, null, det());
        assertThat(plan.enqueue()).isEmpty();
        assertThat(plan.advisoryOnly())
            .containsExactlyInAnyOrder("re-acquire-dead-sources", "review-stale-unread-packs");
    }

    @Test
    void mixedSignalsSplitActionableFromAdvisory() {
        var report = Map.<String, Object>of(
            "maintenance_due", List.of("consolidate-soul-fragments"),
            "recommendations", List.of("review-stale-unread-packs"));
        var plan = RecipeObserveActTrigger.plan(report, "welfare-floor-checkup",
            "did:wyrd:a", CadenceTier.WARMUP, null, det());
        assertThat(plan.enqueue()).hasSize(1);
        assertThat(plan.enqueue().get(0).recipeId()).isEqualTo("consolidate-soul-fragments");
        assertThat(plan.advisoryOnly()).containsExactly("review-stale-unread-packs");
    }

    @Test
    void recipeExistsPredicateDropsUninstalledTargets() {
        var report = Map.<String, Object>of(
            "maintenance_due", List.of("reembed-soul-fragments", "consolidate-memory-graph"));
        var plan = RecipeObserveActTrigger.plan(report, "welfare-floor-checkup",
            "did:wyrd:a", CadenceTier.WARMUP,
            r -> r.equals("reembed-soul-fragments"), det());   // only one installed
        assertThat(plan.enqueue()).hasSize(1);
        assertThat(plan.enqueue().get(0).recipeId()).isEqualTo("reembed-soul-fragments");
        assertThat(plan.advisoryOnly()).containsExactly("consolidate-memory-graph");
    }

    @Test
    void withinBatchDedupCollapsesRepeatedSignal() {
        var report = Map.<String, Object>of(
            "maintenance_due", List.of("reembed-soul-fragments", "reembed-soul-fragments"));
        var plan = RecipeObserveActTrigger.plan(report, "welfare-floor-checkup",
            "did:wyrd:a", CadenceTier.WARMUP, null, det());
        assertThat(plan.enqueue()).hasSize(1);
    }

    @Test
    void emptyOrNullReportYieldsEmptyPlan() {
        assertThat(RecipeObserveActTrigger.plan(null, "x", "a", CadenceTier.WARMUP, null, det())
            .enqueue()).isEmpty();
        assertThat(RecipeObserveActTrigger.plan(Map.of(), "x", "a", CadenceTier.WARMUP, null, det())
            .enqueue()).isEmpty();
    }

    // ── dispatch() — deploys-filter + queue-dedup + tell ────────────────────

    @Test
    void dispatchEnqueuesObservabilityRunsAndDedups(@TempDir Path tmp) {
        var testKit = ActorTestKit.create("RecipeObserveActTriggerTest");
        try {
            TestProbe<RecipeScheduler.Command> probe =
                testKit.createTestProbe(RecipeScheduler.Command.class);
            var queue = new SqlRecipeQueue(
                "jdbc:sqlite:" + tmp.resolve("oa.db").toAbsolutePath());

            var obsRun = completedRun("welfare-floor-checkup", false,
                Map.of("maintenance_due", List.of("reembed-soul-fragments")));
            // deploys:true run — must be ignored even though it carries a signal.
            var deployingRun = completedRun("reembed-soul-fragments", true,
                Map.of("maintenance_due", List.of("consolidate-memory-graph")));

            int sent = RecipeObserveActTrigger.dispatch(
                List.of(obsRun, deployingRun), "did:wyrd:a", queue, probe.ref(),
                null, CadenceTier.WARMUP);
            assertThat(sent).isEqualTo(1);
            var msg = probe.expectMessageClass(RecipeScheduler.Enqueue.class);
            assertThat(msg.entry().recipeId()).isEqualTo("reembed-soul-fragments");

            // Seed the queue PENDING, re-dispatch → deduped (no new tell).
            queue.enqueue(msg.entry());
            int again = RecipeObserveActTrigger.dispatch(
                List.of(obsRun), "did:wyrd:a", queue, probe.ref(),
                null, CadenceTier.WARMUP);
            assertThat(again).isZero();
            probe.expectNoMessage();
        } finally {
            testKit.shutdownTestKit();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Supplier<String> det() {
        var n = new int[]{0};
        return () -> "id-" + (n[0]++);
    }

    private static RecipeForgeIngester.CompletedRun completedRun(
            String name, boolean deploys, Map<String, Object> ctxVars) {
        var ctx = new RecipeContext(ctxVars);
        var run = new RecipeRunner.RecipeRun(
            RecipeRunner.Status.SUCCESS, "ok", List.of(), ctx);
        return new RecipeForgeIngester.CompletedRun(name, deploys, run);
    }
}
