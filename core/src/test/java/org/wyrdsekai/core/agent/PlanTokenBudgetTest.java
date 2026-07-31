package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanTokenBudgetTest {

    @Test
    void remaining_calculates_correctly() {
        var budget = new PlanTokenBudget(10000, 3000, 2000);
        assertEquals(7000, budget.remaining());
    }

    @Test
    void utilization_fraction() {
        var budget = new PlanTokenBudget(10000, 7500, 2000);
        assertEquals(0.75, budget.utilizationFraction(), 0.001);
    }

    @Test
    void can_afford() {
        var budget = new PlanTokenBudget(10000, 8000, 2000);
        assertTrue(budget.canAfford(2000));
        assertFalse(budget.canAfford(2001));
    }

    @Test
    void with_used_adds_tokens() {
        var budget = new PlanTokenBudget(10000, 3000, 2000);
        var updated = budget.withUsed(1000);
        assertEquals(4000, updated.usedTokens());
        assertEquals(3000, budget.usedTokens()); // original unchanged (records are immutable)
    }

    @Test
    void for_plan_creates_reasonable_budget() {
        var plan = TaskPlan.create("test", "test plan", "user1", "mas",
            List.of("Goal 1", "Goal 2", "Goal 3"));
        var budget = PlanTokenBudget.forPlan(plan, 4096);

        // 3 goals * 4096 * 2 = 24576
        assertEquals(24576, budget.totalTokens());
        assertEquals(0, budget.usedTokens());
        assertEquals(4096, budget.estimatedPerGoal());
    }

    @Test
    void estimate_from_chars() {
        // 400 chars prompt + 200 chars response = 150 tokens (at 4 chars/token)
        assertEquals(150, PlanTokenBudget.estimateFromChars(400, 200));
    }

    @Test
    void build_prompt_context() {
        var budget = new PlanTokenBudget(10000, 5000, 2000);
        var ctx = budget.buildPromptContext();
        assertTrue(ctx.contains("5000 remaining"));
        assertTrue(ctx.contains("10000"));
        assertTrue(ctx.contains("50% used"));
    }

    @Test
    void zero_total_returns_full_utilization() {
        var budget = new PlanTokenBudget(0, 0, 0);
        assertEquals(1.0, budget.utilizationFraction());
    }
}
