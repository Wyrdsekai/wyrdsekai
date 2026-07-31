package org.wyrdsekai.core.recipe;

/**
 * Track-C C2 — pure-logic state machine for the
 * {@linkplain CadenceTier adaptive cadence ladder}.
 *
 * <p>Decides the next tier + consecutive-success count for a (recipe, agent)
 * pair given the run-outcome it just produced. The {@link RecipeScheduler}
 * actor calls this and writes the result back to {@code recipe_queue}
 * atomically with the terminal-state update; nothing in this class touches
 * the database, the actor system, or wall-clock — it's a pure function so
 * the policy is testable in isolation and the scheduler is testable
 * without simulating the policy.</p>
 *
 * <h2>Promotion rules</h2>
 * <ul>
 *   <li><b>WARMUP → SETTLING</b>: 3 consecutive {@link Outcome#SUCCESS}
 *       runs (i.e. count reaches {@link #WARMUP_TO_SETTLING_THRESHOLD}).</li>
 *   <li><b>SETTLING → MATURE</b>: 5 consecutive successes at SETTLING tier
 *       ({@link #SETTLING_TO_MATURE_THRESHOLD} — measured from the SETTLING
 *       starting count of 0).</li>
 *   <li><b>MATURE</b>: no further promotion. Stays mature on success.</li>
 * </ul>
 *
 * <h2>Demotion rules</h2>
 * <p>Any of these reset the pair to {@link CadenceTier#WARMUP} with
 * {@code consecutiveSuccesses = 0}, regardless of current tier:</p>
 * <ul>
 *   <li>{@link Outcome#GATE_FAILED} — recipe-runtime metric/regression gate failed.</li>
 *   <li>{@link Outcome#ROLLBACK_FIRED} — deploy step's compensation ran.</li>
 *   <li>{@link Outcome#STEWARD_OVERRIDE} — explicit human reset (C6 CLI hook).</li>
 *   <li>{@link Outcome#STEP_FAILED} — non-gate step error (shell/backend failure).</li>
 *   <li>{@link Outcome#ERROR} — recipe never reached terminal state (parser
 *       error, backend timeout, etc).</li>
 * </ul>
 */
public final class CadenceLadder {

    /** Consecutive successes at WARMUP that trigger promotion to SETTLING. */
    public static final int WARMUP_TO_SETTLING_THRESHOLD = 3;

    /** Consecutive successes at SETTLING that trigger promotion to MATURE. */
    public static final int SETTLING_TO_MATURE_THRESHOLD = 5;

    private CadenceLadder() {}

    /**
     * Pure transition: given the current tier + consecutive-success count
     * and the outcome of the latest run, return the next state.
     */
    public static State advance(CadenceTier currentTier,
            int currentConsecutiveSuccesses, Outcome outcome) {
        var tier = currentTier == null ? CadenceTier.WARMUP : currentTier;
        var count = Math.max(0, currentConsecutiveSuccesses);

        // Demotion: anything other than a clean SUCCESS resets the pair.
        // GATE_FAILED, ROLLBACK_FIRED, STEWARD_OVERRIDE, STEP_FAILED, ERROR
        // all funnel here so the failure mode is a single line of policy.
        if (outcome != Outcome.SUCCESS) {
            return new State(CadenceTier.WARMUP, 0);
        }

        // SUCCESS branch — increment then evaluate promotion against the
        // tier's threshold.
        var newCount = count + 1;
        return switch (tier) {
            case WARMUP -> newCount >= WARMUP_TO_SETTLING_THRESHOLD
                ? new State(CadenceTier.SETTLING, 0)
                : new State(CadenceTier.WARMUP, newCount);
            case SETTLING -> newCount >= SETTLING_TO_MATURE_THRESHOLD
                ? new State(CadenceTier.MATURE, 0)
                : new State(CadenceTier.SETTLING, newCount);
            case MATURE -> new State(CadenceTier.MATURE, newCount);
        };
    }

    /**
     * Run outcome categories the ladder cares about. {@link RecipeScheduler}
     * maps {@link RecipeRunner.RecipeRun#status()} into one of these — keeping
     * the mapping in the scheduler (not here) means this enum stays a pure
     * policy alphabet and doesn't drift if RecipeRunner adds states.
     */
    public enum Outcome {
        /** All gates passed; nothing rolled back. Promotes (or holds at MATURE). */
        SUCCESS,
        /** A metric or regression GATE step rejected the outcome. Demotes. */
        GATE_FAILED,
        /** A deploy step fired its compensation (reversibility seam triggered). Demotes. */
        ROLLBACK_FIRED,
        /** A non-gate, non-deploy step errored. Demotes. */
        STEP_FAILED,
        /** Recipe couldn't reach terminal state (parser/backend error). Demotes. */
        ERROR,
        /** Human reset (steward CLI / Study override). Demotes. */
        STEWARD_OVERRIDE
    }

    /** Result of one transition. */
    public record State(CadenceTier tier, int consecutiveSuccesses) {
        public State {
            if (tier == null) tier = CadenceTier.WARMUP;
            if (consecutiveSuccesses < 0) consecutiveSuccesses = 0;
        }
    }
}
