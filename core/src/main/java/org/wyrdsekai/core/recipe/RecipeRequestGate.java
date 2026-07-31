package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.soul.RepairMode;

/**
 * Track-C C4 — gate for agent-initiated recipe requests.
 *
 * <p>Distinct from {@link WelfareGate}, which fires pre-dispatch on
 * every queue row. This gate fires <i>pre-enqueue</i> on agent-action
 * paths only: the goal is to refuse the request <i>before</i> it
 * pollutes the queue, so a denied agent gets immediate
 * {@link Decision#detail() structured-denial detail} via {@code speak}
 * rather than a silent never-fires queue row.</p>
 *
 * <h2>Three checks</h2>
 * <ol>
 *   <li>{@link DenyReason#NOT_ENROLLED} — agent isn't enrolled in this
 *       recipe. Conservative: agents can't request runs they're not
 *       configured for. Steward enrolls via C6 CLI.</li>
 *   <li>{@link DenyReason#REPAIR_MODE_ACTIVE} — agent is in a
 *       {@link RepairMode} other than NONE. Initiating training while
 *       in repair is exactly what the substrate floor is meant to
 *       prevent.</li>
 *   <li>{@link DenyReason#BUDGET_NO_HEADROOM} — daily GPU or monthly
 *       count would tip over if this run actually fired. Same as
 *       {@link WelfareGate}'s (b) but checked at request-time so the
 *       agent knows immediately.</li>
 * </ol>
 *
 * <p>Pure function. Caller wires inputs from
 * {@link org.wyrdsekai.core.soul.RepairModeTracker} +
 * {@link RecipeBudgetTracker} + {@link RecipeEnrollmentStore}.</p>
 */
public final class RecipeRequestGate {

    private RecipeRequestGate() {}

    public static Decision evaluate(Inputs in) {
        if (in == null) {
            return Decision.allowed();
        }
        if (!in.enrolled()) {
            return Decision.deny(DenyReason.NOT_ENROLLED,
                "agent " + in.agentDid() + " is not enrolled in recipe '"
                    + in.recipeId() + "' — ask steward to enroll first");
        }
        if (in.agentRepairMode() != null && in.agentRepairMode() != RepairMode.NONE) {
            return Decision.deny(DenyReason.REPAIR_MODE_ACTIVE,
                "you're in repair-mode " + in.agentRepairMode()
                    + " — substrate floor refuses recipe runs while you're "
                    + "in active repair");
        }
        if (in.budgetExceeded()) {
            return Decision.deny(DenyReason.BUDGET_NO_HEADROOM,
                "no GPU/run budget headroom right now — try again "
                    + "tomorrow (daily) or next month (monthly cap)");
        }
        return Decision.allowed();
    }

    public record Inputs(
            String recipeId,
            String agentDid,
            boolean enrolled,
            RepairMode agentRepairMode,
            boolean budgetExceeded) {}

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
        NOT_ENROLLED,
        REPAIR_MODE_ACTIVE,
        BUDGET_NO_HEADROOM
    }
}
