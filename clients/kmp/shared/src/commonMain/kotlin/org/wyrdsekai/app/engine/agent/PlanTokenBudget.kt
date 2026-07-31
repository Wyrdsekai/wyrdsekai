package org.wyrdsekai.app.engine.agent

/**
 * Per-plan token budget that tracks estimated inference cost (KMP port).
 *
 * Informs GoalExecutor decisions: when budget is tight, prefer skip/delegate
 * over retry. When exhausted, escalate immediately.
 */
data class PlanTokenBudget(
    val totalTokens: Int,
    val usedTokens: Int,
    val estimatedPerGoal: Int,
) {
    /** Tokens remaining in the budget. */
    fun remaining(): Int = totalTokens - usedTokens

    /** Fraction of budget consumed (0.0 to 1.0+). */
    fun utilizationFraction(): Double =
        if (totalTokens > 0) usedTokens.toDouble() / totalTokens else 1.0

    /** Check if there are enough tokens for an estimated cost. */
    fun canAfford(estimatedCost: Int): Boolean = remaining() >= estimatedCost

    /** Return a new budget with additional tokens consumed. */
    fun withUsed(additionalTokens: Int): PlanTokenBudget =
        copy(usedTokens = usedTokens + additionalTokens)

    /**
     * Build a brief status string for injection into plan prompt context.
     */
    fun buildPromptContext(): String {
        val pct = (utilizationFraction() * 100).toInt()
        return "Token budget: ${remaining()} remaining of $totalTokens ($pct% used)"
    }

    companion object {
        private const val CHARS_PER_TOKEN = 4

        /**
         * Create a budget for a plan based on context window size and goal count.
         * Budget = contextWindow * goals * 2 (allow retries).
         */
        fun forPlan(plan: TaskPlan, contextWindowTokens: Int): PlanTokenBudget {
            val goalsCount = maxOf(1, plan.goals.size)
            val perGoal = contextWindowTokens
            return PlanTokenBudget(perGoal * goalsCount * 2, 0, perGoal)
        }

        /**
         * Estimate tokens from prompt + response character lengths.
         */
        fun estimateFromChars(promptChars: Int, responseChars: Int): Int =
            (promptChars + responseChars) / CHARS_PER_TOKEN
    }
}
