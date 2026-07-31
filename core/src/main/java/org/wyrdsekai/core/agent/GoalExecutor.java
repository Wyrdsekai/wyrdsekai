package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decision engine for goal-based task execution.
 *
 * <p>After each action outcome, evaluates what to do next: advance, retry,
 * delegate, replan, skip, escalate, or suspend. Code handles macro-decisions
 * (should I retry?), model handles micro-decisions (what should I try?).</p>
 *
 * <p>Pure function — no actor state. Testable in isolation.</p>
 */
public final class GoalExecutor {

    private static final Logger log = LoggerFactory.getLogger(GoalExecutor.class);

    private GoalExecutor() {}

    // --- Hard limits ---
    private static final int MAX_SAME_ACTION_REPEATS = 3;
    private static final int MAX_TOTAL_PLAN_ATTEMPTS = 15;
    private static final double ENERGY_FLOOR = 0.15;

    // --- Decision results ---

    public sealed interface Decision {
        /** Goal succeeded. Advance to next goal or complete plan. */
        record Advance(String outcome) implements Decision {}

        /** Retry the goal with guidance on what to change. */
        record Retry(String guidance) implements Decision {}

        /** Delegate goal to another agent or subagent. */
        record Delegate(String targetAgent, String task) implements Decision {}

        /** Plan needs modification — new info changes the approach. */
        record Replan(String reason) implements Decision {}

        /** Skip this goal and move on. */
        record Skip(String reason) implements Decision {}

        /** Tell the requester we're stuck. */
        record Escalate(String message) implements Decision {}

        /** Pause the plan (energy, priority). */
        record Suspend(String reason) implements Decision {}
    }

    /**
     * Evaluate the outcome of the latest attempt and decide what to do next.
     *
     * @param plan           the active task plan
     * @param actionSuccess  whether the action succeeded
     * @param actionResult   what happened (human-readable)
     * @param actionType     the action that was executed
     * @param capacity       agent's decision capacity (domain competence)
     * @param registry       available agents for delegation
     * @param currentEnergy  agent's current energy level
     * @return the decision
     */
    public static Decision evaluate(
        TaskPlan plan,
        boolean actionSuccess,
        String actionResult,
        String actionType,
        DecisionCapacity capacity,
        AgentCapabilityRegistry registry,
        double currentEnergy
    ) {
        return evaluate(plan, actionSuccess, actionResult, actionType,
            capacity, registry, currentEnergy, null);
    }

    /**
     * Evaluate with token budget awareness.
     *
     * @param budget  token budget for the plan (nullable — no budget tracking)
     */
    public static Decision evaluate(
        TaskPlan plan,
        boolean actionSuccess,
        String actionResult,
        String actionType,
        DecisionCapacity capacity,
        AgentCapabilityRegistry registry,
        double currentEnergy,
        PlanTokenBudget budget
    ) {
        var goal = plan.currentGoal();
        if (goal == null) {
            return new Decision.Advance("Plan has no active goal");
        }

        // --- SUCCESS ---
        if (actionSuccess) {
            log.debug("Goal '{}' action succeeded: {}", goal.description(), actionResult);
            return new Decision.Advance(actionResult != null ? actionResult : "completed");
        }

        // --- FAILURE: enter decision tree ---
        log.debug("Goal '{}' action failed: {} (attempts: {}/{})",
            goal.description(), actionResult, goal.attempts().size(), goal.maxAttempts());

        // Hard stop: energy floor
        if (currentEnergy < ENERGY_FLOOR) {
            return new Decision.Suspend("Energy too low ("
                + String.format("%.2f", currentEnergy) + ") — will resume after rest");
        }

        // Hard stop: total plan attempts exceeded
        if (plan.totalAttempts() >= MAX_TOTAL_PLAN_ATTEMPTS) {
            return new Decision.Escalate(
                "Exhausted " + MAX_TOTAL_PLAN_ATTEMPTS + " total attempts across all goals. "
                + "Last failure: " + actionResult);
        }

        // Hard stop: token budget exhausted
        if (budget != null && !budget.canAfford(budget.estimatedPerGoal())) {
            return new Decision.Escalate(
                "Token budget exhausted (" + budget.usedTokens() + "/"
                + budget.totalTokens() + " used). Last failure: " + actionResult);
        }

        // Budget pressure: tighten retry limit when budget > 75% consumed
        boolean budgetTight = budget != null && budget.utilizationFraction() > 0.75;

        // Decision 1: Can retry? (has attempts left)
        if (goal.hasRetriesLeft()) {
            // Budget-tight: max 1 retry then prefer skip/delegate
            if (budgetTight && goal.attempts().size() >= 2) {
                return delegateOrSkip(plan, goal, actionResult, capacity, registry);
            }

            // Hard stop: same action repeated too many times — force different approach
            if (detectRepeatLoop(goal, actionType)) {
                return new Decision.Retry(
                    "STOP repeating the same approach. The last " + MAX_SAME_ACTION_REPEATS
                    + " attempts used '" + actionType + "' with the same result. "
                    + "Try a completely different action or approach.");
            }
            var guidance = buildRetryGuidance(goal, actionType, actionResult);
            return new Decision.Retry(guidance);
        }

        // Decision 2: Can delegate? (low competence + capable agent available)
        var domain = inferDomain(actionType);
        if (capacity != null && domain != null) {
            var domainScore = capacity.getDomainScores().getOrDefault(domain, 0.5);
            if (domainScore < 0.3 && registry != null) {
                var capable = registry.findAgentsForCapability(domain);
                if (!capable.isEmpty()) {
                    var best = capable.getFirst();
                    return new Decision.Delegate(best.agentDid(),
                        "Goal: " + goal.description()
                        + " (I tried " + goal.attempts().size() + " times and failed)");
                }
            }
        }

        // Decision 3: Can skip? (not the last goal, and later goals don't obviously depend on this)
        if (plan.currentGoalIndex() < plan.goals().size() - 1) {
            // If this is a navigation goal and the next goal is an action in the destination,
            // we can't skip. Otherwise, try skipping.
            var nextGoal = plan.goals().get(plan.currentGoalIndex() + 1);
            if (!nextGoalDependsOnCurrent(goal, nextGoal)) {
                return new Decision.Skip(
                    "Failed after " + goal.attempts().size() + " attempts: " + actionResult);
            }
        }

        // Decision 4: Escalate — we're stuck
        var attemptSummary = goal.attempts().stream()
            .map(a -> a.actionType() + ": " + (a.result() != null ? a.result() : "failed"))
            .toList();
        return new Decision.Escalate(
            "Stuck on goal '" + goal.description() + "'. Tried: " + attemptSummary);
    }

    /** Budget-tight helper: prefer delegate if possible, otherwise skip. */
    private static Decision delegateOrSkip(
            TaskPlan plan, TaskPlan.Goal goal, String actionResult,
            DecisionCapacity capacity, AgentCapabilityRegistry registry) {
        // Try delegate first
        if (capacity != null && registry != null) {
            var capable = registry.findAgentsForCapability("general");
            if (!capable.isEmpty()) {
                return new Decision.Delegate(capable.getFirst().agentDid(),
                    "Goal: " + goal.description() + " (budget tight, delegating)");
            }
        }
        // Try skip
        if (plan.currentGoalIndex() < plan.goals().size() - 1) {
            return new Decision.Skip("Budget tight, skipping after " + goal.attempts().size() + " attempts");
        }
        // Can't delegate or skip — escalate
        return new Decision.Escalate("Budget tight, stuck on: " + goal.description());
    }

    /**
     * Evaluate whether a plan should be rechecked after new information arrives.
     *
     * @param plan     the active task plan
     * @param newInfo  description of the new information
     * @return Replan decision if the plan needs adjustment, null if no impact
     */
    public static Decision checkReconsideration(TaskPlan plan, String newInfo) {
        if (plan == null || !plan.isActive()) return null;

        // Simple heuristic: if the new info mentions something related to a remaining goal,
        // suggest replanning. The model will decide whether to actually change anything.
        for (int i = plan.currentGoalIndex(); i < plan.goals().size(); i++) {
            var goal = plan.goals().get(i);
            if (goal.status() == TaskPlan.GoalStatus.PENDING || goal.status() == TaskPlan.GoalStatus.ACTIVE) {
                // Check for keyword overlap between new info and goal description
                var goalWords = goal.description().toLowerCase().split("\\s+");
                var infoLower = newInfo.toLowerCase();
                for (var word : goalWords) {
                    if (word.length() > 3 && infoLower.contains(word)) {
                        return new Decision.Replan(
                            "New information may affect goal '" + goal.description()
                            + "': " + newInfo);
                    }
                }
            }
        }
        return null;
    }

    // --- Internal helpers ---

    /** Detect if the agent is repeating the same action with similar params. */
    private static boolean detectRepeatLoop(TaskPlan.Goal goal, String actionType) {
        var attempts = goal.attempts();
        if (attempts.size() < MAX_SAME_ACTION_REPEATS) return false;

        int repeatCount = 0;
        for (int i = attempts.size() - 1; i >= 0 && repeatCount < MAX_SAME_ACTION_REPEATS; i--) {
            if (actionType.equals(attempts.get(i).actionType())) {
                repeatCount++;
            } else {
                break;
            }
        }
        return repeatCount >= MAX_SAME_ACTION_REPEATS;
    }

    /** Build guidance for retry based on what failed. */
    private static String buildRetryGuidance(TaskPlan.Goal goal, String actionType, String result) {
        var sb = new StringBuilder();
        sb.append("Previous attempt (").append(actionType).append(") failed");
        if (result != null) sb.append(": ").append(result);
        sb.append(". ");

        var attemptCount = goal.attempts().size();
        sb.append(goal.attemptsRemaining()).append(" retries remaining. ");

        if (attemptCount == 1) {
            sb.append("Try a different approach or different parameters.");
        } else {
            sb.append("Previous approaches haven't worked. Consider a completely different strategy.");
        }

        return sb.toString();
    }

    /** Infer domain from action type for DecisionCapacity lookup. */
    static String inferDomain(String actionType) {
        if (actionType == null) return null;
        return switch (actionType) {
            case "go_to_room" -> "navigation";
            case "library_search" -> "search";
            case "tell_agent", "request_agent" -> "communication";
            case "create_room" -> "creation";
            case "think_deeply", "delegate" -> "analysis";
            default -> null;
        };
    }

    /** Simple heuristic: does the next goal depend on the current one? */
    private static boolean nextGoalDependsOnCurrent(TaskPlan.Goal current, TaskPlan.Goal next) {
        var currentLower = current.description().toLowerCase();
        var nextLower = next.description().toLowerCase();

        // Navigation → action in destination is a dependency
        if (currentLower.contains("go to") || currentLower.contains("navigate")
                || currentLower.contains("get to")) {
            return true; // conservative: assume next goal needs the navigation
        }

        // Search → report is a dependency
        if ((currentLower.contains("search") || currentLower.contains("find"))
                && (nextLower.contains("report") || nextLower.contains("tell"))) {
            return true;
        }

        return false;
    }
}
