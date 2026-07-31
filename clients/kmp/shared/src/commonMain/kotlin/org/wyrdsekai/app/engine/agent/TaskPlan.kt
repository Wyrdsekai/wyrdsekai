package org.wyrdsekai.app.engine.agent

import kotlinx.serialization.Serializable

/**
 * Goal-based task plan for autonomous multi-step execution (KMP port).
 * Mirrors Java TaskPlan. Goals are descriptions, not actions.
 */

@Serializable
enum class PlanStatus { ACTIVE, COMPLETED, FAILED, ABANDONED, SUSPENDED }

@Serializable
enum class GoalStatus { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

@Serializable
data class Attempt(
    val actionType: String,
    val parameters: String? = null,
    val result: String? = null,
    val success: Boolean,
    val timestamp: Long = 0L
)

@Serializable
data class Goal(
    val description: String,
    var status: GoalStatus = GoalStatus.PENDING,
    val attempts: MutableList<Attempt> = mutableListOf(),
    var outcome: String? = null,
    val maxAttempts: Int = 3
) {
    fun hasRetriesLeft(): Boolean = attempts.size < maxAttempts
    fun attemptsRemaining(): Int = (maxAttempts - attempts.size).coerceAtLeast(0)
    fun lastAttempt(): Attempt? = attempts.lastOrNull()

    fun activate() { status = GoalStatus.ACTIVE }
    fun markDone(outcome: String) { this.status = GoalStatus.DONE; this.outcome = outcome }
    fun markFailed(reason: String) { this.status = GoalStatus.FAILED; this.outcome = reason }
    fun markSkipped(reason: String) { this.status = GoalStatus.SKIPPED; this.outcome = reason }
}

@Serializable
data class TaskPlan(
    val planId: String,
    val description: String,
    val requesterId: String? = null,
    val requesterName: String? = null,
    val goals: MutableList<Goal>,
    var currentGoalIndex: Int = 0,
    var status: PlanStatus = PlanStatus.ACTIVE,
    val createdAt: Long = 0L,
    val deadline: Long? = null,
    var totalAttempts: Int = 0,
    var finalOutcome: String? = null
) {
    val isActive get() = status == PlanStatus.ACTIVE
    val isTerminal get() = status in listOf(PlanStatus.COMPLETED, PlanStatus.FAILED, PlanStatus.ABANDONED)

    fun currentGoal(): Goal? =
        if (currentGoalIndex in goals.indices) goals[currentGoalIndex] else null

    fun recordAttempt(actionType: String, params: String?, result: String?, success: Boolean) {
        currentGoal()?.attempts?.add(Attempt(actionType, params, result, success))
        totalAttempts++
    }

    fun advanceGoal(outcome: String): Boolean {
        currentGoal()?.let { if (it.status != GoalStatus.SKIPPED) it.markDone(outcome) }
        currentGoalIndex++
        return if (currentGoalIndex < goals.size) {
            goals[currentGoalIndex].activate()
            true
        } else false
    }

    fun complete(outcome: String) { status = PlanStatus.COMPLETED; finalOutcome = outcome }
    fun fail(reason: String) { status = PlanStatus.FAILED; finalOutcome = reason }
    fun suspend(reason: String) { status = PlanStatus.SUSPENDED; finalOutcome = reason }
    fun resume() { if (status == PlanStatus.SUSPENDED) { status = PlanStatus.ACTIVE; finalOutcome = null } }
    fun abandon(reason: String) { status = PlanStatus.ABANDONED; finalOutcome = reason }

    fun skipCurrentGoal(reason: String): Boolean {
        currentGoal()?.markSkipped(reason)
        return advanceGoal("Skipped: $reason")
    }

    fun insertGoal(afterIndex: Int, description: String) {
        if (afterIndex in goals.indices) goals.add(afterIndex + 1, Goal(description))
    }

    fun buildPromptContext(): String = buildString {
        append("## Active Task\n")
        append(description)
        requesterName?.let { append(" (for $it)") }
        append(" — goal ${currentGoalIndex + 1}/${goals.size}\n\n")

        goals.forEachIndexed { i, goal ->
            val marker = when (goal.status) {
                GoalStatus.DONE -> "DONE"
                GoalStatus.ACTIVE -> "ACTIVE"
                GoalStatus.FAILED -> "FAILED"
                GoalStatus.SKIPPED -> "SKIPPED"
                GoalStatus.PENDING -> "PENDING"
            }
            append("Goal ${i + 1}: ${goal.description} [$marker")
            goal.outcome?.let { append(" — $it") }
            append("]\n")

            if (goal.status == GoalStatus.ACTIVE && goal.attempts.isNotEmpty()) {
                for (attempt in goal.attempts) {
                    append("  Attempt: ${attempt.actionType}")
                    attempt.parameters?.let { append(" $it") }
                    append(" → ${if (attempt.success) "OK" else "FAILED"}")
                    attempt.result?.let { append(": $it") }
                    append("\n")
                }
                append("  Retries remaining: ${goal.attemptsRemaining()}\n")
            }
        }

        if (requesterId != null) {
            append("\nWhen all goals are complete, tell ")
            append(requesterName ?: requesterId)
            append(" what you found.\n")
        }
    }

    companion object {
        fun create(planId: String, description: String, requesterId: String?,
                   requesterName: String?, goalDescriptions: List<String>): TaskPlan {
            val goals = goalDescriptions.map { Goal(it) }.toMutableList()
            if (goals.isNotEmpty()) goals.first().activate()
            return TaskPlan(planId, description, requesterId, requesterName, goals)
        }
    }
}
