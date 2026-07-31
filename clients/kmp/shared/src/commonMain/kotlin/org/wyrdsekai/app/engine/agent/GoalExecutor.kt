package org.wyrdsekai.app.engine.agent
import org.wyrdsekai.app.platform.formatFixed

/**
 * Decision engine for goal-based task execution (KMP port).
 * Pure functions — no state. Mirrors Java GoalExecutor.
 */
object GoalExecutor {

    private const val MAX_SAME_ACTION_REPEATS = 3
    private const val MAX_TOTAL_PLAN_ATTEMPTS = 15
    private const val ENERGY_FLOOR = 0.15

    sealed interface Decision {
        data class Advance(val outcome: String) : Decision
        data class Retry(val guidance: String) : Decision
        data class Delegate(val targetAgent: String, val task: String) : Decision
        data class Replan(val reason: String) : Decision
        data class Skip(val reason: String) : Decision
        data class Escalate(val message: String) : Decision
        data class Suspend(val reason: String) : Decision
    }

    fun evaluate(
        plan: TaskPlan,
        actionSuccess: Boolean,
        actionResult: String?,
        actionType: String,
        currentEnergy: Double
    ): Decision {
        val goal = plan.currentGoal() ?: return Decision.Advance("No active goal")

        if (actionSuccess) {
            return Decision.Advance(actionResult ?: "completed")
        }

        // Energy floor
        if (currentEnergy < ENERGY_FLOOR) {
            return Decision.Suspend("Energy too low (${formatFixed(currentEnergy, 2)}) — will resume after rest")
        }

        // Total attempts exceeded
        if (plan.totalAttempts >= MAX_TOTAL_PLAN_ATTEMPTS) {
            return Decision.Escalate("Exhausted $MAX_TOTAL_PLAN_ATTEMPTS total attempts. Last: $actionResult")
        }

        // Can retry?
        if (goal.hasRetriesLeft()) {
            if (detectRepeatLoop(goal, actionType)) {
                return Decision.Retry("STOP repeating '$actionType'. Try a completely different approach.")
            }
            val remaining = goal.attemptsRemaining()
            val guidance = if (goal.attempts.size == 1)
                "Try a different approach ($remaining retries remaining)."
            else
                "Previous approaches haven't worked. Try completely different strategy ($remaining retries remaining)."
            return Decision.Retry("$actionType failed: $actionResult. $guidance")
        }

        // Can skip?
        if (plan.currentGoalIndex < plan.goals.size - 1) {
            return Decision.Skip("Failed after ${goal.attempts.size} attempts: $actionResult")
        }

        // Escalate
        val tried = goal.attempts.map { "${it.actionType}: ${it.result ?: "failed"}" }
        return Decision.Escalate("Stuck on '${goal.description}'. Tried: $tried")
    }

    private fun detectRepeatLoop(goal: Goal, actionType: String): Boolean {
        val attempts = goal.attempts
        if (attempts.size < MAX_SAME_ACTION_REPEATS) return false
        var count = 0
        for (i in attempts.indices.reversed()) {
            if (attempts[i].actionType == actionType) count++ else break
            if (count >= MAX_SAME_ACTION_REPEATS) return true
        }
        return false
    }
}
