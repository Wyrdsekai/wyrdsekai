package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.soul.RepairMode;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Track-C C3 — pre-dispatch welfare floor.
 *
 * <p>Same posture as the §23 substrate floor (see
 * {@code session-2026-05-20-s23-teeth-and-924}): a recipe run consumes
 * the same household substrate the agents are made of, so the scheduler
 * must check the household isn't already under load before firing.
 * Four gates, evaluated in order; first deny short-circuits.</p>
 *
 * <h2>Gates</h2>
 * <ol>
 *   <li><b>Repair-mode</b>: any companion in the zone is currently in a
 *       {@link RepairMode} other than {@code NONE}, OR sustained
 *       substrate-pressure pattern detected within the last 24h. Recipe
 *       training is a load on the same shared zone — running it while a
 *       companion is in active repair would compound that load.</li>
 *   <li><b>Budget</b>: per-day GPU time + per-month run count.
 *       Defaults: 6h GPU / day, 100 runs / month. Deferred runs stay
 *       PENDING in the queue — next tick re-evaluates.</li>
 *   <li><b>Cooldown</b>: a (recipe, agent) pair can't fire twice within
 *       its current cadence-tier interval. Belt-and-suspenders against
 *       a buggy trigger spamming the queue.</li>
 *   <li><b>Deploy-ceiling</b>: 3 consecutive deploy failures (or
 *       rollbacks) for a (recipe, agent) pair → pause + steward
 *       notification. Don't keep retrying something the runtime gates
 *       are killing every time — that's noise, not learning.</li>
 * </ol>
 *
 * <p>{@code evaluate} is a pure function: the scheduler fetches the
 * inputs (repair modes from {@link
 * org.wyrdsekai.core.soul.RepairModeTracker}, budget usage from
 * recipe_queue history, etc), passes them in, and acts on the
 * {@link Decision}. Steward override is
 * {@link RecipeScheduler#tellForceFire} — bypasses this gate
 * entirely.</p>
 */
public final class WelfareGate {

    /** Daily GPU-time cap (sum of recipe wall-clock across the day). */
    public static final Duration DEFAULT_DAILY_GPU_BUDGET = Duration.ofHours(6);

    /** Monthly run-count cap (count of completed terminal-state runs). */
    public static final int DEFAULT_MONTHLY_RUN_CAP = 100;

    /** Deploy-attempt ceiling — at this count, the recipe pauses for steward. */
    public static final int DEPLOY_CEILING = 3;

    private WelfareGate() {}

    /**
     * Pure-logic gate. Returns {@link Decision#allow()} or the first
     * {@link DenyReason} that trips, with a human-legible detail string
     * the scheduler can log + the steward CLI can render.
     */
    public static Decision evaluate(Inputs in) {
        if (in == null) {
            return Decision.allowed();
        }

        // (a) — repair-mode / substrate-pressure. We accept both signals
        // because the substrate-pressure window catches sustained load
        // even before the agent has declared repair-mode.
        if (in.anyAgentInRepair()) {
            return Decision.deny(DenyReason.REPAIR_MODE_ACTIVE,
                "one or more zone companions are in active repair-mode "
                + "(modes=" + in.activeRepairModes() + ")");
        }
        if (in.substratePressureSustained()) {
            return Decision.deny(DenyReason.SUBSTRATE_PRESSURE_SUSTAINED,
                "sustained substrate-pressure pattern in last 24h "
                + "(suppression/dissociation classifier window)");
        }

        // (b) — budget. Daily GPU first, then monthly run cap. Long
        // recipes can wedge the daily cap before the monthly count
        // notices, so check both.
        if (in.gpuDailyBudget() != null
                && in.gpuUsedToday().compareTo(in.gpuDailyBudget()) >= 0) {
            return Decision.deny(DenyReason.GPU_DAILY_BUDGET_EXCEEDED,
                "daily GPU budget hit: used="
                    + format(in.gpuUsedToday()) + " cap="
                    + format(in.gpuDailyBudget()));
        }
        if (in.monthlyRunCap() > 0 && in.runsThisMonth() >= in.monthlyRunCap()) {
            return Decision.deny(DenyReason.MONTHLY_RUN_CAP_EXCEEDED,
                "monthly run cap hit: ran=" + in.runsThisMonth()
                    + " cap=" + in.monthlyRunCap());
        }

        // (c) — cooldown. If the recipe completed within the current
        // cadence-tier interval, defer. {@code lastTerminalAt} is the
        // most recent SUCCEEDED/FAILED for this (recipe, agent) pair —
        // use that as the cooldown anchor so a long-failing recipe
        // still respects the period of *its current tier* (which
        // post-failure should be WARMUP, so 1d).
        if (in.lastTerminalAt() != null && in.currentTier() != null
                && in.now() != null) {
            var elapsed = Duration.between(in.lastTerminalAt(), in.now());
            var required = in.currentTier().period();
            if (elapsed.compareTo(required) < 0) {
                return Decision.deny(DenyReason.COOLDOWN_NOT_ELAPSED,
                    "cooldown not elapsed: " + format(elapsed) + " <"
                        + " " + format(required) + " (tier="
                        + in.currentTier() + ")");
            }
        }

        // (d) — deploy-ceiling. The scheduler should pause + page the
        // steward instead of grinding the agent down with rollbacks.
        if (in.consecutiveDeployFailures() >= DEPLOY_CEILING) {
            return Decision.deny(DenyReason.DEPLOY_CEILING_HIT,
                "deploy-attempt ceiling hit: "
                    + in.consecutiveDeployFailures()
                    + " consecutive deploy failures (limit "
                    + DEPLOY_CEILING + ")");
        }

        return Decision.allowed();
    }

    // ── data shapes ─────────────────────────────────────────────────────

    /**
     * Snapshot of the inputs the gate considers. Built by the scheduler
     * from {@link org.wyrdsekai.core.soul.RepairModeTracker} + budget
     * tracker + {@link SqlRecipeQueue} history. Defensive: nulls and
     * missing data degrade to {@code ALLOW} for that gate so a partial
     * snapshot doesn't reject everything.
     */
    public record Inputs(
        Set<RepairMode> activeRepairModes,
        boolean substratePressureSustained,
        Duration gpuUsedToday,
        Duration gpuDailyBudget,
        int runsThisMonth,
        int monthlyRunCap,
        Instant lastTerminalAt,
        CadenceTier currentTier,
        int consecutiveDeployFailures,
        Instant now) {

        public Inputs {
            if (activeRepairModes == null) activeRepairModes = Set.of();
            if (gpuUsedToday == null) gpuUsedToday = Duration.ZERO;
            if (now == null) now = Instant.now();
            if (consecutiveDeployFailures < 0) consecutiveDeployFailures = 0;
        }

        /** True if any agent is in a repair-mode other than {@link RepairMode#NONE}. */
        public boolean anyAgentInRepair() {
            for (var m : activeRepairModes) {
                if (m != null && m != RepairMode.NONE) return true;
            }
            return false;
        }
    }

    public record Decision(boolean allow, DenyReason reason, String detail) {
        public static Decision allowed() {
            return new Decision(true, DenyReason.ALLOW, null);
        }
        public static Decision deny(DenyReason reason, String detail) {
            return new Decision(false, reason, detail);
        }
    }

    public enum DenyReason {
        ALLOW,
        REPAIR_MODE_ACTIVE,
        SUBSTRATE_PRESSURE_SUSTAINED,
        GPU_DAILY_BUDGET_EXCEEDED,
        MONTHLY_RUN_CAP_EXCEEDED,
        COOLDOWN_NOT_ELAPSED,
        DEPLOY_CEILING_HIT
    }

    private static String format(Duration d) {
        if (d == null) return "?";
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours > 0) return hours + "h" + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return d.toSecondsPart() + "s";
    }
}
