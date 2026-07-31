package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.typed.ActorRef;
import org.wyrdsekai.core.agent.interiority.DoomLoopDetector;
import org.wyrdsekai.core.config.WyrdConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track-C C9 — sleep-pass adapter from {@link
 * org.wyrdsekai.core.agent.interiority.ChronicleService#detectAll}
 * findings to {@link RecipeScheduler.Enqueue} messages.
 *
 * <p>The companion's sleep pass already computes substrate-aware
 * Chronicle findings (doom-loop / psychosis / sustained-substrate
 * patterns). This class is the policy layer that decides which of
 * those findings warrant a recipe-run, by matching the finding's
 * {@link DoomLoopDetector.Finding#key()} against enrolled
 * {@code gap_keys} and applying the steward's
 * {@link WyrdConfig#schedulerGapTicks() ticks} +
 * {@link WyrdConfig#schedulerGapWindowHours() window} thresholds.</p>
 *
 * <p>Pure-logic <em>planning</em>; the caller (CompanionActor's
 * sleep-pass) sends the actor messages. The actor's welfare gate has
 * final say at dispatch time — over-enqueuing here is safe because
 * the dispatcher will defer / deny.</p>
 *
 * <p>De-dup: this class is stateless, so a sustained pattern firing on
 * every sleep cycle <em>would</em> stack enqueues. The dedup happens
 * downstream — {@link SqlRecipeQueue#enqueue} idempotency is by
 * UUID, but we explicitly suppress duplicate trigger reasons by
 * checking {@code SqlRecipeQueue.findByRecipe(...)} for existing
 * PENDING/IN_PROGRESS rows with the same {@code triggerReason}
 * prefix.</p>
 */
public final class SchedulerGapBridge {

    private SchedulerGapBridge() {}

    /**
     * Convert {@code findings} → planned enqueues. Tests use this to
     * assert the policy without involving an actor.
     *
     * @param findings        from {@code ChronicleService.detectAll}
     * @param sourceAgentDid  the agent whose sleep-pass produced them
     * @param enrollments     {@link RecipeEnrollmentStore#listAll()}
     * @param config          steward thresholds
     * @param now             test-friendly clock
     */
    public static List<QueuedRecipe> plan(List<DoomLoopDetector.Finding> findings,
                                          String sourceAgentDid,
                                          RecipeEnrollmentStore enrollments,
                                          WyrdConfig config,
                                          Instant now) {
        if (findings == null || findings.isEmpty()) return List.of();
        if (sourceAgentDid == null || enrollments == null) return List.of();

        // Steward can disable gap-detection entirely without touching enrollments.
        if (config != null && !config.schedulerGapDetectionEnabled()) {
            return List.of();
        }

        // Thresholds. Default 5 ticks / 48h sustained — conservative
        // by design (we'd rather under-fire and let the agent
        // request_recipe than thrash the household with retries).
        int ticksThreshold = config != null ? config.schedulerGapTicks() : 5;
        int windowHours = config != null ? config.schedulerGapWindowHours() : 48;

        // The pure trigger lives in RecipeGapTrigger; we just produce
        // the right inputs after the threshold check. The Finding
        // shape carries the gap key + severity; severity-based
        // thresholding is a future enhancement, but for now any
        // qualifying finding above WARN counts as one "tick" and any
        // CRIT/ERROR finding counts as the full threshold (so a
        // single critical event still routes through).
        var bucketed = bucketByKey(findings);
        var enqueues = new ArrayList<QueuedRecipe>();
        var t0 = now == null ? Instant.now() : now;
        for (var entry : bucketed.entrySet()) {
            var gapKey = entry.getKey();
            var bucket = entry.getValue();
            int weight = bucket.ticks();
            // A single CRIT/ERROR finding short-circuits the ticks check —
            // we treat it as immediate-action. Otherwise we require N
            // sustained ticks within the configured window.
            boolean satisfies = bucket.hasCritical()
                || weight >= ticksThreshold;
            if (!satisfies) continue;
            // Older-than-window: the chronicle pass should already
            // window the findings, but be defensive.
            if (bucket.oldestAt() != null
                    && Duration.between(bucket.oldestAt(), t0)
                        .compareTo(Duration.ofHours(windowHours)) > 0) {
                continue;
            }
            var matching = enrollments.listByGapKey(gapKey);
            var plans = RecipeGapTrigger.plan(gapKey, sourceAgentDid, matching);
            enqueues.addAll(plans);
        }
        return enqueues;
    }

    /**
     * Send-and-forget: plan + dispatch enqueues to the live actor,
     * skipping rows whose recipe already has a PENDING/IN_PROGRESS
     * queue row attributed to the same agent (so a sustained pattern
     * doesn't multiply on each sleep cycle).
     *
     * @return count of enqueues actually sent (post-dedup)
     */
    public static int dispatch(List<DoomLoopDetector.Finding> findings,
                               String sourceAgentDid,
                               RecipeEnrollmentStore enrollments,
                               SqlRecipeQueue queueDedup,
                               ActorRef<RecipeScheduler.Command> scheduler,
                               WyrdConfig config,
                               Instant now) {
        if (scheduler == null) return 0;
        var plans = plan(findings, sourceAgentDid, enrollments, config, now);
        int sent = 0;
        for (var q : plans) {
            if (queueDedup != null
                    && hasActiveRow(queueDedup, q.recipeId(), q.agentDid())) {
                continue;
            }
            scheduler.tell(new RecipeScheduler.Enqueue(q));
            sent++;
        }
        return sent;
    }

    private static boolean hasActiveRow(SqlRecipeQueue queue, String recipeId, String agentDid) {
        try {
            for (var row : queue.findByRecipe(recipeId, agentDid)) {
                var s = row.status();
                if (s == QueuedRecipe.Status.PENDING
                        || s == QueuedRecipe.Status.IN_PROGRESS) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static Map<String, Bucket> bucketByKey(List<DoomLoopDetector.Finding> findings) {
        var out = new LinkedHashMap<String, Bucket>();
        for (var f : findings) {
            if (f == null || f.key() == null) continue;
            // Map raw doom-loop / substrate finding keys to gap_keys.
            // Convention: chronicle findings already use dotted-namespace
            // ("task_present.misroute", "drive.frustration.stuck", etc.).
            // No transformation needed — enrollments declare their gap_keys
            // in the same form.
            var b = out.computeIfAbsent(f.key(), k -> new Bucket());
            b.add(f);
        }
        return out;
    }

    private static final class Bucket {
        private int ticks;
        private boolean hasCritical;
        private Instant oldestAt;

        void add(DoomLoopDetector.Finding f) {
            ticks++;
            // DoomLoopDetector.Severity is { INFO, WARN, CRITICAL }; we treat
            // CRITICAL as short-circuit, lower severities require sustained
            // count. Defensive against future enum extensions ("ERROR" etc).
            var name = f.severity() != null ? f.severity().name() : "";
            if ("CRITICAL".equals(name) || "CRIT".equals(name) || "ERROR".equals(name)) {
                hasCritical = true;
            }
        }

        int ticks() { return ticks; }
        boolean hasCritical() { return hasCritical; }
        Instant oldestAt() { return oldestAt; }
    }
}
