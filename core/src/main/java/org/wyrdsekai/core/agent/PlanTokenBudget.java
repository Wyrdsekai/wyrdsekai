package org.wyrdsekai.core.agent;

/**
 * Per-plan token budget that tracks estimated inference cost.
 *
 * <p>Informs GoalExecutor decisions: when budget is tight, prefer skip/delegate
 * over retry. When exhausted, escalate immediately.</p>
 *
 * @param totalTokens      total budget for this plan (estimated)
 * @param usedTokens       tokens consumed so far
 * @param estimatedPerGoal average tokens per goal attempt
 */
public record PlanTokenBudget(
    int totalTokens,
    int usedTokens,
    int estimatedPerGoal
) {
    private static final int CHARS_PER_TOKEN = 4;

    /** Tokens remaining in the budget. */
    public int remaining() {
        return totalTokens - usedTokens;
    }

    /** Fraction of budget consumed (0.0 to 1.0+). */
    public double utilizationFraction() {
        return totalTokens > 0 ? (double) usedTokens / totalTokens : 1.0;
    }

    /** Check if there are enough tokens for an estimated cost. */
    public boolean canAfford(int estimatedCost) {
        return remaining() >= estimatedCost;
    }

    /** Return a new budget with additional tokens consumed. */
    public PlanTokenBudget withUsed(int additionalTokens) {
        return new PlanTokenBudget(totalTokens, usedTokens + additionalTokens, estimatedPerGoal);
    }

    /**
     * Create a budget for a plan based on context window size and goal count.
     * Budget = contextWindow * goals * 2 (allow retries).
     */
    public static PlanTokenBudget forPlan(TaskPlan plan, int contextWindowTokens) {
        int goalsCount = Math.max(1, plan.goals().size());
        int perGoal = contextWindowTokens;
        return new PlanTokenBudget(perGoal * goalsCount * 2, 0, perGoal);
    }

    /**
     * Estimate tokens from prompt + response character lengths.
     */
    public static int estimateFromChars(int promptChars, int responseChars) {
        return (promptChars + responseChars) / CHARS_PER_TOKEN;
    }

    /**
     * Build a brief status string for injection into plan prompt context.
     */
    public String buildPromptContext() {
        int pct = (int) (utilizationFraction() * 100);
        return "Token budget: " + remaining() + " remaining of " + totalTokens
            + " (" + pct + "% used)";
    }
}
