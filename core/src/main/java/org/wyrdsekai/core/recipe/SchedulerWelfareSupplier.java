package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.soul.RepairMode;
import org.wyrdsekai.core.soul.RepairModeTracker;
import org.wyrdsekai.core.soul.ResilienceSession;
import org.wyrdsekai.core.soul.SustainedSubstratePatternDetector;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Track-C C9 — production {@link RecipeScheduler.WelfareSupplier}.
 *
 * <p>Builds {@link WelfareGate.Inputs} from the live trackers each
 * dispatch tick. Same posture as the §23 substrate floor: every input
 * is read fresh at dispatch time, so a repair-mode transition between
 * enqueue and dispatch is honored (no stale snapshots).</p>
 *
 * <p>Decisions: nullable trackers degrade to OPEN_GATE rather than
 * deny — partial wiring (tests, OSS first-boot) should not freeze the
 * scheduler. The gate itself is defensive in the same direction.</p>
 */
public final class SchedulerWelfareSupplier implements RecipeScheduler.WelfareSupplier {

    private final RecipeBudgetTracker budget;
    private final Duration gpuDailyBudget;
    private final int monthlyRunCap;
    private final RepairModeTracker repairTracker;
    private final Function<String, ResilienceSession> resilienceLookup;
    private final ZoneId zone;

    public SchedulerWelfareSupplier(RecipeBudgetTracker budget,
                                    Duration gpuDailyBudget,
                                    int monthlyRunCap,
                                    RepairModeTracker repairTracker,
                                    Function<String, ResilienceSession> resilienceLookup,
                                    ZoneId zone) {
        this.budget = budget;
        this.gpuDailyBudget = gpuDailyBudget != null
            ? gpuDailyBudget : WelfareGate.DEFAULT_DAILY_GPU_BUDGET;
        this.monthlyRunCap = monthlyRunCap > 0
            ? monthlyRunCap : WelfareGate.DEFAULT_MONTHLY_RUN_CAP;
        this.repairTracker = repairTracker;
        this.resilienceLookup = resilienceLookup;
        this.zone = zone != null ? zone : ZoneId.systemDefault();
    }

    @Override
    public WelfareGate.Inputs inputsFor(QueuedRecipe peeked) {
        if (peeked == null) return null;
        var now = Instant.now();

        // (a) — repair-mode + sustained substrate pressure for the
        // queued recipe's agent. We only check the requesting agent
        // (not zone-wide) because that's the agent the recipe's
        // outcome will alter via Forge ingestion — other companions'
        // repair state is between them and their bondholders.
        Set<RepairMode> modes = new HashSet<>();
        if (repairTracker != null && peeked.agentDid() != null) {
            var m = repairTracker.currentMode(peeked.agentDid());
            if (m != null) modes.add(m);
        }
        boolean substratePressure = false;
        if (resilienceLookup != null && peeked.agentDid() != null) {
            try {
                var session = resilienceLookup.apply(peeked.agentDid());
                if (session != null) {
                    var findings = SustainedSubstratePatternDetector.detect(session);
                    substratePressure = findings != null && !findings.isEmpty();
                }
            } catch (Exception ignored) {}
        }

        // (b) — budget. Read live every dispatch.
        Duration gpuUsed = budget != null
            ? budget.gpuUsedToday(now, zone) : Duration.ZERO;
        int runsThisMonth = budget != null
            ? budget.runsThisMonth(now, zone) : 0;

        // (c) — cooldown anchor. lastTerminalAt across the
        // (recipe, agent) pair; current tier is what the queue row
        // captured at enqueue (post-completion the cadence ladder
        // pulls this back to WARMUP on failure).
        Instant lastTerminal = budget != null
            ? budget.lastTerminalAt(peeked.recipeId(), peeked.agentDid())
            : null;

        // (d) — deploy ceiling. Consecutive deploy failures for the
        // (recipe, agent) pair.
        int deployFailures = budget != null
            ? budget.consecutiveDeployFailures(peeked.recipeId(), peeked.agentDid())
            : 0;

        return new WelfareGate.Inputs(
            modes,
            substratePressure,
            gpuUsed,
            gpuDailyBudget,
            runsThisMonth,
            monthlyRunCap,
            lastTerminal,
            peeked.cadenceTier(),
            deployFailures,
            now);
    }
}
