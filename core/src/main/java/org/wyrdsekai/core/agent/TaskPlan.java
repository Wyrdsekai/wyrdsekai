package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Goal-based task plan for autonomous multi-step execution.
 *
 * <p>The system tracks goals and attempts; the model decides how to execute each goal.
 * Goals are descriptions ("find books about mythology"), not actions ("library_search mythology").
 * The model chooses the action; the system tracks progress and makes macro-decisions
 * (retry, delegate, escalate) via {@link GoalExecutor}.</p>
 *
 * <p>Persisted in SoulManifest. Survives restarts and sleep cycles.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskPlan {

    public enum PlanStatus { ACTIVE, COMPLETED, FAILED, ABANDONED, SUSPENDED }
    public enum GoalStatus { PENDING, ACTIVE, DONE, FAILED, SKIPPED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attempt(
        @JsonProperty("actionType") String actionType,
        @JsonProperty("parameters") String parameters,
        @JsonProperty("result") String result,
        @JsonProperty("success") boolean success,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public Attempt {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Goal {
        @JsonProperty("description") private String description;
        @JsonProperty("status") private GoalStatus status;
        @JsonProperty("attempts") private List<Attempt> attempts;
        @JsonProperty("outcome") private String outcome;
        @JsonProperty("maxAttempts") private int maxAttempts;

        @JsonCreator
        public Goal(
            @JsonProperty("description") String description,
            @JsonProperty("status") GoalStatus status,
            @JsonProperty("attempts") List<Attempt> attempts,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("maxAttempts") int maxAttempts
        ) {
            this.description = description;
            this.status = status != null ? status : GoalStatus.PENDING;
            this.attempts = attempts != null ? new ArrayList<>(attempts) : new ArrayList<>();
            this.outcome = outcome;
            this.maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
        }

        public Goal(String description) {
            this(description, GoalStatus.PENDING, new ArrayList<>(), null, 3);
        }

        public String description() { return description; }
        public GoalStatus status() { return status; }
        public List<Attempt> attempts() { return attempts; }
        public String outcome() { return outcome; }
        public int maxAttempts() { return maxAttempts; }
        public boolean hasRetriesLeft() { return attempts.size() < maxAttempts; }
        public int attemptsRemaining() { return Math.max(0, maxAttempts - attempts.size()); }

        public void activate() { this.status = GoalStatus.ACTIVE; }
        public void recordAttempt(Attempt attempt) { this.attempts.add(attempt); }

        public void markDone(String outcome) {
            this.status = GoalStatus.DONE;
            this.outcome = outcome;
        }

        public void markFailed(String reason) {
            this.status = GoalStatus.FAILED;
            this.outcome = reason;
        }

        public void markSkipped(String reason) {
            this.status = GoalStatus.SKIPPED;
            this.outcome = reason;
        }

        /** Last attempt result, or null. */
        public Attempt lastAttempt() {
            return attempts.isEmpty() ? null : attempts.getLast();
        }
    }

    // --- Plan fields ---

    @JsonProperty("planId") private final String planId;
    @JsonProperty("description") private final String description;
    @JsonProperty("requesterId") private final String requesterId;
    @JsonProperty("requesterName") private final String requesterName;
    @JsonProperty("goals") private final List<Goal> goals;
    @JsonProperty("currentGoalIndex") private int currentGoalIndex;
    @JsonProperty("status") private PlanStatus status;
    @JsonProperty("createdAt") private final Instant createdAt;
    @JsonProperty("deadline") private final Instant deadline;
    @JsonProperty("totalAttempts") private int totalAttempts;
    @JsonProperty("finalOutcome") private String finalOutcome;

    @JsonCreator
    public TaskPlan(
        @JsonProperty("planId") String planId,
        @JsonProperty("description") String description,
        @JsonProperty("requesterId") String requesterId,
        @JsonProperty("requesterName") String requesterName,
        @JsonProperty("goals") List<Goal> goals,
        @JsonProperty("currentGoalIndex") int currentGoalIndex,
        @JsonProperty("status") PlanStatus status,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("deadline") Instant deadline,
        @JsonProperty("totalAttempts") int totalAttempts,
        @JsonProperty("finalOutcome") String finalOutcome
    ) {
        this.planId = planId;
        this.description = description;
        this.requesterId = requesterId;
        this.requesterName = requesterName;
        this.goals = goals != null ? new ArrayList<>(goals) : new ArrayList<>();
        this.currentGoalIndex = currentGoalIndex;
        this.status = status != null ? status : PlanStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.deadline = deadline;
        this.totalAttempts = totalAttempts;
        this.finalOutcome = finalOutcome;
    }

    /** Create a new plan from goal descriptions. */
    public static TaskPlan create(String planId, String description,
                                   String requesterId, String requesterName,
                                   List<String> goalDescriptions) {
        var goals = goalDescriptions.stream()
            .map(Goal::new)
            .collect(Collectors.toCollection(ArrayList::new));
        if (!goals.isEmpty()) {
            goals.getFirst().activate();
        }
        return new TaskPlan(planId, description, requesterId, requesterName,
            goals, 0, PlanStatus.ACTIVE, Instant.now(), null, 0, null);
    }

    // --- Accessors ---

    public String planId() { return planId; }
    public String description() { return description; }
    public String requesterId() { return requesterId; }
    public String requesterName() { return requesterName; }
    public List<Goal> goals() { return goals; }
    public int currentGoalIndex() { return currentGoalIndex; }
    public PlanStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant deadline() { return deadline; }
    public int totalAttempts() { return totalAttempts; }
    public String finalOutcome() { return finalOutcome; }

    /** Current active goal, or null if plan is complete/failed. */
    public Goal currentGoal() {
        if (currentGoalIndex < 0 || currentGoalIndex >= goals.size()) return null;
        return goals.get(currentGoalIndex);
    }

    public boolean isActive() { return status == PlanStatus.ACTIVE; }
    public boolean isTerminal() {
        return status == PlanStatus.COMPLETED || status == PlanStatus.FAILED
            || status == PlanStatus.ABANDONED;
    }

    // --- Mutations ---

    /** Record an attempt on the current goal. */
    public void recordAttempt(String actionType, String params, String result, boolean success) {
        var goal = currentGoal();
        if (goal == null) return;
        goal.recordAttempt(new Attempt(actionType, params, result, success, Instant.now()));
        totalAttempts++;
    }

    /** Advance to the next goal. Returns true if there's a next goal. */
    public boolean advanceGoal(String outcome) {
        var goal = currentGoal();
        if (goal != null && goal.status() != GoalStatus.SKIPPED) {
            goal.markDone(outcome);
        }

        currentGoalIndex++;
        if (currentGoalIndex < goals.size()) {
            goals.get(currentGoalIndex).activate();
            return true;
        }
        return false;
    }

    /** Complete the plan. */
    public void complete(String outcome) {
        this.status = PlanStatus.COMPLETED;
        this.finalOutcome = outcome;
    }

    /** Fail the plan. */
    public void fail(String reason) {
        this.status = PlanStatus.FAILED;
        this.finalOutcome = reason;
    }

    /** Suspend the plan (low energy, higher priority task). */
    public void suspend(String reason) {
        this.status = PlanStatus.SUSPENDED;
        this.finalOutcome = reason;
    }

    /** Resume a suspended plan. */
    public void resume() {
        if (status == PlanStatus.SUSPENDED) {
            this.status = PlanStatus.ACTIVE;
            this.finalOutcome = null;
        }
    }

    /** Abandon the plan. */
    public void abandon(String reason) {
        this.status = PlanStatus.ABANDONED;
        this.finalOutcome = reason;
    }

    /** Skip the current goal and advance. */
    public boolean skipCurrentGoal(String reason) {
        var goal = currentGoal();
        if (goal != null) goal.markSkipped(reason);
        return advanceGoal("Skipped: " + reason);
    }

    /** Add a goal after a specific index. */
    public void insertGoal(int afterIndex, String description) {
        if (afterIndex >= 0 && afterIndex < goals.size()) {
            goals.add(afterIndex + 1, new Goal(description));
        }
    }

    // --- Prompt rendering ---

    /** Build prompt context for injection into Layer 3.5. */
    public String buildPromptContext() {
        var sb = new StringBuilder();
        sb.append("## Active Task (DO NOT create a new task_plan — execute the current ACTIVE goal below)\n");
        sb.append(description);
        if (requesterName != null) sb.append(" (for ").append(requesterName).append(")");
        sb.append(" — goal ").append(currentGoalIndex + 1).append("/").append(goals.size());
        sb.append("\n\n");

        for (int i = 0; i < goals.size(); i++) {
            var goal = goals.get(i);
            var marker = switch (goal.status()) {
                case DONE -> "DONE";
                case ACTIVE -> "ACTIVE";
                case FAILED -> "FAILED";
                case SKIPPED -> "SKIPPED";
                case PENDING -> "PENDING";
            };
            sb.append("Goal ").append(i + 1).append(": ").append(goal.description());
            sb.append(" [").append(marker);
            if (goal.outcome() != null) sb.append(" — ").append(goal.outcome());
            sb.append("]\n");

            // Show attempts for active goal
            if (goal.status() == GoalStatus.ACTIVE && !goal.attempts().isEmpty()) {
                for (var attempt : goal.attempts()) {
                    sb.append("  Attempt: ").append(attempt.actionType());
                    if (attempt.parameters() != null) sb.append(" ").append(attempt.parameters());
                    sb.append(" → ").append(attempt.success() ? "OK" : "FAILED");
                    if (attempt.result() != null) sb.append(": ").append(attempt.result());
                    sb.append("\n");
                }
                sb.append("  Retries remaining: ").append(goal.attemptsRemaining()).append("\n");
            }
        }

        // Directive for current goal
        var activeGoal = currentGoal();
        if (activeGoal != null && activeGoal.status() == GoalStatus.ACTIVE) {
            sb.append("\n→ YOUR NEXT ACTION: Execute goal ").append(currentGoalIndex + 1);
            sb.append(" (").append(activeGoal.description()).append("). ");
            sb.append("Emit ONE action block to advance this goal. ");
            if (!activeGoal.attempts().isEmpty()) {
                sb.append("Previous attempt failed — try a different approach. ");
            }
            sb.append("Use goal_done when this goal is complete.\n");
        }

        // Explicit instructions for reporting back
        if (requesterId != null) {
            var name = requesterName != null ? requesterName : requesterId;
            sb.append("\nTo report back to ").append(name).append(", use one of:\n");
            sb.append("- tell_agent: {\"action\": \"tell_agent\", \"target\": \"").append(name)
              .append("\", \"message\": \"<your findings>\"}\n");
            sb.append("- go_to_room: {\"action\": \"go_to_room\", \"target\": \"").append(name)
              .append("'s Study\"} (if you have a ward)\n");
        }

        return sb.toString();
    }
}
