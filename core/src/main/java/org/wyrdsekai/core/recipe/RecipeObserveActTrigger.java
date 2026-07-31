package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * observe→act loop (#1140). Turns an observability recipe's
 * finished report into follow-up maintenance enqueues.
 *
 * <p>The cheap, gateless observability recipes ({@code welfare-floor-checkup},
 * {@code research-pack-freshness}) run on a fixed cadence and emit a report whose
 * {@code maintenance_due} / {@code recommendations} arrays name what's actually
 * worth doing now. This trigger reads that merged report context and enqueues the
 * recommended <em>maintenance</em> recipes — so the heavy recipes (re-embed,
 * consolidate) run only when the cheap probe found a reason, instead of on a
 * blind schedule.</p>
 *
 * <p>{@link #plan} is pure logic (mirrors {@link RecipeGapTrigger}): no I/O, no
 * dedup, no welfare gating. {@link #dispatch} adds dedup against open queue rows
 * and tells the scheduler (which re-applies the welfare gate at dispatch time).</p>
 *
 * <p>Not every report signal maps to a runnable recipe. {@code welfare-floor-checkup}'s
 * {@code maintenance_due} entries <em>are</em> recipe names; {@code research-pack-freshness}'s
 * {@code recommendations} (re-acquire / review) are agent/steward actions with no
 * auto-runnable recipe — those are returned as {@link Plan#advisoryOnly()} and
 * surface through the Chronicle, not the queue.</p>
 */
public final class RecipeObserveActTrigger {

    private RecipeObserveActTrigger() {}

    /**
     * Report-signal → recipe id to enqueue. {@code maintenance_due} strings are
     * recipe names (identity mapping); a signal absent here is advisory-only and
     * never auto-enqueued (deliberate: re-acquire-dead-sources /
     * review-stale-unread-packs are agent actions, not recipes).
     */
    static final Map<String, String> SIGNAL_TO_RECIPE = Map.of(
        "reembed-soul-fragments",     "reembed-soul-fragments",
        "consolidate-soul-fragments", "consolidate-soul-fragments",
        "consolidate-memory-graph",   "consolidate-memory-graph"
    );

    /** Result of {@link #plan}: enqueue-able entries + advisory-only signals. */
    public record Plan(List<QueuedRecipe> enqueue, List<String> advisoryOnly) {
        public static Plan empty() { return new Plan(List.of(), List.of()); }
    }

    /**
     * Map an observability recipe's merged report context to follow-up enqueues.
     *
     * @param report         merged step-output context ({@code run.context().snapshot()})
     * @param sourceRecipe   the observability recipe that produced the report
     * @param sourceAgentDid the agent the report is about (nullable)
     * @param tier           cadence tier to stamp on enqueued entries
     * @param recipeExists   predicate: is this recipe id installed? unknowns →
     *                       advisory ({@code null} = assume installed)
     * @param idGen          fresh-id supplier (UUID in prod; deterministic in tests; {@code null} = UUID)
     */
    public static Plan plan(Map<String, Object> report, String sourceRecipe,
                            String sourceAgentDid, CadenceTier tier,
                            Predicate<String> recipeExists, Supplier<String> idGen) {
        if (report == null || report.isEmpty()) return Plan.empty();
        var signals = new LinkedHashSet<String>();
        collectStrings(report.get("maintenance_due"), signals);
        collectStrings(report.get("recommendations"), signals);
        if (signals.isEmpty()) return Plan.empty();

        var enqueue = new ArrayList<QueuedRecipe>();
        var advisory = new ArrayList<String>();
        var seen = new HashSet<String>();   // within-batch dedup by target recipe
        var t = tier == null ? CadenceTier.WARMUP : tier;
        for (var signal : signals) {
            var target = SIGNAL_TO_RECIPE.get(signal);
            if (target == null) { advisory.add(signal); continue; }
            if (recipeExists != null && !recipeExists.test(target)) { advisory.add(signal); continue; }
            if (!seen.add(target)) continue;   // already enqueued this pass
            enqueue.add(QueuedRecipe.newEntry(
                idGen != null ? idGen.get() : UUID.randomUUID().toString(),
                target, Map.of(),
                "observe-act:" + sourceRecipe + ":" + signal,
                QueuedRecipe.TriggerSource.GAP,
                sourceAgentDid, t, 0));
        }
        return new Plan(enqueue, advisory);
    }

    /**
     * Plan + dedup + enqueue for a batch of just-completed runs. Only SUCCESS'd
     * non-deploying (observability) runs are considered. Deduped against open
     * queue rows; the scheduler re-applies the welfare gate at dispatch. Returns
     * the number of enqueue messages actually sent.
     */
    public static int dispatch(List<RecipeForgeIngester.CompletedRun> runs,
                               String sourceAgentDid,
                               SqlRecipeQueue queueDedup,
                               ActorRef<RecipeScheduler.Command> scheduler,
                               Predicate<String> recipeExists,
                               CadenceTier tier) {
        if (scheduler == null || runs == null || runs.isEmpty()) return 0;
        int sent = 0;
        var sentThisBatch = new HashSet<String>();   // cross-run dedup within the batch
        for (var cr : runs) {
            if (cr == null || cr.deploys()) continue;            // observability recipes only
            var run = cr.run();
            if (run == null || run.status() != RecipeRunner.Status.SUCCESS
                    || run.context() == null) continue;
            var plan = plan(run.context().snapshot(), cr.recipeName(),
                sourceAgentDid, tier, recipeExists, null);
            for (var q : plan.enqueue()) {
                String key = q.recipeId() + "|" + (q.agentDid() == null ? "" : q.agentDid());
                if (!sentThisBatch.add(key)) continue;           // two reports recommending the same recipe
                if (queueDedup != null
                        && queueDedup.hasOpenForRecipe(q.recipeId(), q.agentDid())) {
                    continue;                                     // already PENDING/IN_PROGRESS
                }
                scheduler.tell(new RecipeScheduler.Enqueue(q));
                sent++;
            }
        }
        return sent;
    }

    @SuppressWarnings("unchecked")
    private static void collectStrings(Object v, Set<String> out) {
        if (v instanceof List<?> list) {
            for (var e : list) {
                if (e == null) continue;
                var s = String.valueOf(e).trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s.trim());
        }
    }
}
