package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalExecutorBudgetTest {

    private TaskPlan makePlan(String... goals) {
        return TaskPlan.create("test", "test plan", "user1", "mas",
            List.of(goals));
    }

    @Test
    void exhausted_budget_escalates() {
        var plan = makePlan("Search", "Report");
        plan.recordAttempt("library_search", "myth", "no results", false);

        // Budget with 0 remaining
        var budget = new PlanTokenBudget(10000, 10000, 5000);

        var decision = GoalExecutor.evaluate(
            plan, false, "no results", "library_search",
            null, null, 0.8, budget);

        assertInstanceOf(GoalExecutor.Decision.Escalate.class, decision);
        assertTrue(((GoalExecutor.Decision.Escalate) decision).message().contains("budget"));
    }

    @Test
    void tight_budget_reduces_retries() {
        var plan = makePlan("Search", "Navigate");
        // Record 2 attempts (normally would retry, but budget is tight)
        plan.recordAttempt("library_search", "a", "fail", false);
        plan.recordAttempt("library_search", "b", "fail", false);

        // Budget at 80% utilization
        var budget = new PlanTokenBudget(10000, 8000, 2000);

        var decision = GoalExecutor.evaluate(
            plan, false, "still failing", "library_search",
            null, null, 0.8, budget);

        // Should skip or escalate instead of retry (budget-tight path)
        assertFalse(decision instanceof GoalExecutor.Decision.Retry,
            "Budget-tight should not retry after 2 attempts");
    }

    @Test
    void normal_budget_retries_as_usual() {
        var plan = makePlan("Search");
        plan.recordAttempt("library_search", "myth", "no results", false);

        // Budget with plenty remaining
        var budget = new PlanTokenBudget(10000, 2000, 2000);

        var decision = GoalExecutor.evaluate(
            plan, false, "no results", "library_search",
            null, null, 0.8, budget);

        assertInstanceOf(GoalExecutor.Decision.Retry.class, decision);
    }

    @Test
    void null_budget_behaves_like_original() {
        var plan = makePlan("Step 1");
        var decision = GoalExecutor.evaluate(
            plan, true, "done", "go_to_room",
            null, null, 0.8, null);

        assertInstanceOf(GoalExecutor.Decision.Advance.class, decision);
    }

    @Test
    void success_ignores_budget() {
        // Even with exhausted budget, success should advance
        var budget = new PlanTokenBudget(100, 100, 100);
        var plan = makePlan("Step 1", "Step 2");

        var decision = GoalExecutor.evaluate(
            plan, true, "done", "go_to_room",
            null, null, 0.8, budget);

        assertInstanceOf(GoalExecutor.Decision.Advance.class, decision);
    }
}
