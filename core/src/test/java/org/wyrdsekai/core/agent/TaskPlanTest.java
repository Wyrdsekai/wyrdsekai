package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskPlanTest {

    @Test
    void create_initializes_with_first_goal_active() {
        var plan = TaskPlan.create("p1", "find books", "user1", "mas",
            List.of("Go to Library", "Search mythology", "Report back"));

        assertEquals(3, plan.goals().size());
        assertEquals(TaskPlan.PlanStatus.ACTIVE, plan.status());
        assertEquals(0, plan.currentGoalIndex());
        assertEquals(TaskPlan.GoalStatus.ACTIVE, plan.goals().get(0).status());
        assertEquals(TaskPlan.GoalStatus.PENDING, plan.goals().get(1).status());
    }

    @Test
    void advanceGoal_marks_done_and_activates_next() {
        var plan = TaskPlan.create("p1", "test", null, null,
            List.of("Step 1", "Step 2", "Step 3"));

        assertTrue(plan.advanceGoal("done with step 1"));
        assertEquals(1, plan.currentGoalIndex());
        assertEquals(TaskPlan.GoalStatus.DONE, plan.goals().get(0).status());
        assertEquals(TaskPlan.GoalStatus.ACTIVE, plan.goals().get(1).status());
    }

    @Test
    void advanceGoal_returns_false_on_last_goal() {
        var plan = TaskPlan.create("p1", "test", null, null, List.of("Only step"));

        assertFalse(plan.advanceGoal("done"));
        assertEquals(TaskPlan.GoalStatus.DONE, plan.goals().get(0).status());
    }

    @Test
    void complete_sets_status_and_outcome() {
        var plan = TaskPlan.create("p1", "test", null, null, List.of("Step 1"));
        plan.advanceGoal("done");
        plan.complete("All done");

        assertEquals(TaskPlan.PlanStatus.COMPLETED, plan.status());
        assertEquals("All done", plan.finalOutcome());
        assertTrue(plan.isTerminal());
    }

    @Test
    void suspend_and_resume() {
        var plan = TaskPlan.create("p1", "test", null, null, List.of("Step 1"));
        plan.suspend("Low energy");

        assertEquals(TaskPlan.PlanStatus.SUSPENDED, plan.status());
        assertFalse(plan.isActive());

        plan.resume();
        assertEquals(TaskPlan.PlanStatus.ACTIVE, plan.status());
        assertTrue(plan.isActive());
    }

    @Test
    void recordAttempt_tracks_attempts() {
        var plan = TaskPlan.create("p1", "test", null, null, List.of("Search"));

        plan.recordAttempt("library_search", "mythology", "no results", false);
        plan.recordAttempt("library_search", "myths legends", "3 results", true);

        var goal = plan.currentGoal();
        assertEquals(2, goal.attempts().size());
        assertFalse(goal.attempts().get(0).success());
        assertTrue(goal.attempts().get(1).success());
        assertEquals(2, plan.totalAttempts());
    }

    @Test
    void goal_retries_tracking() {
        var goal = new TaskPlan.Goal("Test goal");
        assertEquals(3, goal.maxAttempts());
        assertTrue(goal.hasRetriesLeft());
        assertEquals(3, goal.attemptsRemaining());

        goal.recordAttempt(new TaskPlan.Attempt("test", null, "fail", false, Instant.now()));
        goal.recordAttempt(new TaskPlan.Attempt("test", null, "fail", false, Instant.now()));
        assertEquals(1, goal.attemptsRemaining());
        assertTrue(goal.hasRetriesLeft());

        goal.recordAttempt(new TaskPlan.Attempt("test", null, "fail", false, Instant.now()));
        assertFalse(goal.hasRetriesLeft());
    }

    @Test
    void insertGoal_adds_after_index() {
        var plan = TaskPlan.create("p1", "test", null, null,
            List.of("Step 1", "Step 3"));

        plan.insertGoal(0, "Step 2");

        assertEquals(3, plan.goals().size());
        assertEquals("Step 2", plan.goals().get(1).description());
        assertEquals("Step 3", plan.goals().get(2).description());
    }

    @Test
    void skipCurrentGoal_advances() {
        var plan = TaskPlan.create("p1", "test", null, null,
            List.of("Skip me", "Do me"));

        plan.skipCurrentGoal("not needed");

        assertEquals(TaskPlan.GoalStatus.SKIPPED, plan.goals().get(0).status());
        assertEquals(1, plan.currentGoalIndex());
        assertEquals(TaskPlan.GoalStatus.ACTIVE, plan.goals().get(1).status());
    }

    @Test
    void buildPromptContext_includes_all_goals() {
        var plan = TaskPlan.create("p1", "find books", "u1", "mas",
            List.of("Go to Library", "Search", "Report back"));
        plan.advanceGoal("navigated");

        var context = plan.buildPromptContext();

        assertTrue(context.contains("Active Task"));
        assertTrue(context.contains("find books"));
        assertTrue(context.contains("for mas"));
        assertTrue(context.contains("DONE"));
        assertTrue(context.contains("ACTIVE"));
        assertTrue(context.contains("PENDING"));
        assertTrue(context.contains("report back to mas") || context.contains("tell_agent"));
    }

    @Test
    void buildPromptContext_shows_attempts_for_active_goal() {
        var plan = TaskPlan.create("p1", "test", null, null, List.of("Search"));
        plan.recordAttempt("library_search", "myth", "no results", false);

        var context = plan.buildPromptContext();
        assertTrue(context.contains("library_search"));
        assertTrue(context.contains("FAILED"));
        assertTrue(context.contains("Retries remaining: 2"));
    }
}
