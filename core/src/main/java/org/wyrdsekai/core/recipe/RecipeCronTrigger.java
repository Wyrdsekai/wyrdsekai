package org.wyrdsekai.core.recipe;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Track-C C4 — cron trigger source.
 *
 * <p>Pure-logic decision: given the current enrollment list + a lookup
 * of "last terminal completion for (recipe, agent)", emit a list of
 * fresh {@link QueuedRecipe} rows whose
 * {@link CadenceTier#period()} has elapsed since the last completion.</p>
 *
 * <p>Decoupled from the actor + persistence so the cron tick body is
 * one line: {@code planned = RecipeCronTrigger.plan(...); for r:
 * scheduler.tell(Enqueue(r))}. The actor doesn't care about timing
 * arithmetic; this class doesn't care about Pekko or JDBC.</p>
 *
 * <p>#1023 — optional {@code prefers_hours} filter on the manifest. When a
 * recipe declares {@code prefers_hours: [2,3,4]}, the cron tick only enqueues
 * during those local hours UNLESS the run is "stale-deferred" — defined as
 * {@code now - lastTerminal &gt; 2 × cadenceTier.period()}. Stale-deferral
 * prevents indefinite skip on households whose machine is off during the
 * preferred window; the welfare floor still has final say via gates.</p>
 */
public final class RecipeCronTrigger {

    private RecipeCronTrigger() {}

    /** Lookup signature for "last SUCCEEDED/FAILED completion for this pair". */
    @FunctionalInterface
    public interface LastTerminalLookup {
        Instant lastTerminalAt(String recipeId, String agentDid);
    }

    /** #1023 — Lookup signature for "what hours does this recipe prefer?". */
    @FunctionalInterface
    public interface PrefersHoursLookup {
        /** Returns the recipe's {@code prefers_hours} list, or empty if anytime is fine. */
        List<Integer> prefersHoursFor(String recipeId);

        /** Convenience: "always anytime" — for legacy callers + tests not concerned with quiet hours. */
        PrefersHoursLookup ANYTIME = recipeId -> List.of();
    }

    /**
     * Lookup signature for "what extra params should a scheduled run carry?".
     *
     * <p>The gap path derives its params from the gap key, which names the thing that
     * went wrong. Cron has no such signal, so a recipe with a required param had no way
     * to run on cadence: {@code retrain-classifier-head} declares {@code head} required
     * and the scheduled path supplied nothing, so "cron when stale" could never actually
     * retrain anything. This seam lets production wiring decide, per recipe, what a
     * scheduled run should work on — while the planner stays pure and testable.
     */
    @FunctionalInterface
    public interface CronParamsLookup {
        /** Extra params for a scheduled run; empty when the recipe needs none. */
        Map<String, Object> extraParamsFor(String recipeId, String agentDid);

        /** Convenience: "no extra params" — for legacy callers and tests. */
        CronParamsLookup NONE = (recipeId, agentDid) -> Map.of();
    }

    /**
     * Legacy three-arg overload — preserves pre-#1023 callers (no quiet-hours filter).
     * Equivalent to passing {@link PrefersHoursLookup#ANYTIME} and the system clock.
     */
    public static List<QueuedRecipe> plan(List<RecipeEnrollment> enrollments,
            LastTerminalLookup lookup, Instant now) {
        return plan(enrollments, lookup, PrefersHoursLookup.ANYTIME,
                Clock.systemDefaultZone(), now);
    }

    /**
     * For every enabled enrollment, check whether
     * {@code now - lastTerminal >= cadenceTier.period}. If yes, build a
     * {@link QueuedRecipe} ready to {@link RecipeScheduler#tell}{@code (Enqueue)}.
     * Enrollments with no prior terminal completion (fresh enrollments)
     * always fire on this tick.
     *
     * <p>#1023 — additionally consults {@code prefersHoursLookup} per recipe.
     * If the recipe declares preferred hours and the current local hour
     * isn't in the list, the enrollment is skipped — UNLESS
     * {@code now - lastTerminal > 2 × cadenceTier.period} (stale-deferred),
     * in which case the preference is overridden to prevent indefinite skip.
     * Fresh enrollments (no prior terminal) DO honor the preference window;
     * they fire on the first tick that lands in-window.
     *
     * @param enrollments          enabled enrollments (from {@link RecipeEnrollmentStore#listEnabled}).
     * @param lookup               per-pair last-terminal lookup (typically
     *                             {@link RecipeBudgetTracker#lastTerminalAt}).
     * @param prefersHoursLookup   per-recipe quiet-hours preference lookup; pass
     *                             {@link PrefersHoursLookup#ANYTIME} to disable filtering.
     * @param clock                wall-clock for local-hour resolution; tests use a fixed clock.
     * @param now                  current instant; tests use a fixed instant.
     * @return list of planned enqueues — empty if nothing is due.
     */
    public static List<QueuedRecipe> plan(List<RecipeEnrollment> enrollments,
            LastTerminalLookup lookup,
            PrefersHoursLookup prefersHoursLookup,
            Clock clock,
            Instant now) {
        return plan(enrollments, lookup, prefersHoursLookup, clock, now,
                CronParamsLookup.NONE);
    }

    /**
     * As above, plus a {@link CronParamsLookup} supplying per-recipe run params.
     *
     * @param cronParamsLookup per-recipe extra params for scheduled runs; pass
     *                         {@link CronParamsLookup#NONE} to disable.
     */
    public static List<QueuedRecipe> plan(List<RecipeEnrollment> enrollments,
            LastTerminalLookup lookup,
            PrefersHoursLookup prefersHoursLookup,
            Clock clock,
            Instant now,
            CronParamsLookup cronParamsLookup) {
        if (enrollments == null || enrollments.isEmpty()) return List.of();
        if (cronParamsLookup == null) cronParamsLookup = CronParamsLookup.NONE;
        if (prefersHoursLookup == null) prefersHoursLookup = PrefersHoursLookup.ANYTIME;
        if (clock == null) clock = Clock.systemDefaultZone();
        ZoneId zone = clock.getZone();
        int localHour = ZonedDateTime.ofInstant(now, zone).getHour();

        var out = new ArrayList<QueuedRecipe>();
        for (var e : enrollments) {
            if (!e.enabled()) continue;
            var last = lookup == null ? null
                : lookup.lastTerminalAt(e.recipeId(), e.agentDid());
            Duration period = e.cadenceTier().period();
            boolean due = last == null
                || Duration.between(last, now).compareTo(period) >= 0;
            if (!due) continue;

            // #1023 — quiet-hours preference. If the recipe declares prefers_hours
            // and we're not in that window, skip — unless we're so far past cadence
            // (>2× period) that skipping again would drift the cadence ladder badly.
            var prefers = prefersHoursLookup.prefersHoursFor(e.recipeId());
            if (prefers != null && !prefers.isEmpty()
                    && !prefers.contains(localHour)) {
                boolean staleDeferred = last != null
                    && Duration.between(last, now)
                        .compareTo(period.multipliedBy(2)) > 0;
                if (!staleDeferred) continue;
                // else: fall through, fire even though we're outside the preferred window.
            }

            // Pass agent_did from the enrollment row. Recipes whose first
            // required param is the companion DID (e.g. consolidate-memory-graph)
            // need it; recipes that don't declare agent_did simply leave it
            // sitting in the context unused. The dispatcher fills jdbc_url and
            // other env-driven defaults at run time.
            Map<String, Object> params = new LinkedHashMap<>();
            if (e.agentDid() != null && !e.agentDid().isBlank()) {
                params.put("agent_did", e.agentDid());
            }
            // Whatever this recipe needs to work on this tick (e.g. which classifier
            // head to retrain). Empty for recipes that need nothing.
            var extra = cronParamsLookup.extraParamsFor(e.recipeId(), e.agentDid());
            if (extra != null) params.putAll(extra);
            out.add(QueuedRecipe.newEntry(
                UUID.randomUUID().toString(),
                e.recipeId(), Map.copyOf(params),
                "cron tick (tier=" + e.cadenceTier() + ")",
                QueuedRecipe.TriggerSource.CRON,
                e.agentDid(),
                e.cadenceTier(),
                e.consecutiveSuccesses()));
        }
        return out;
    }
}
