package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Agent Cognition Engine.
 * Tests full pipelines across TaskPlan, GoalExecutor, ObservationBuffer,
 * OrientationEngine, HeuristicExtractor, and OutcomeTracker.
 */
class CognitionIntegrationTest {

    // ── ActionParser: task_plan / modify_plan / goal_done ──

    @Test
    void parse_task_plan_action() {
        var input = """
            I'll make a plan for that.
            ```json
            {"action": "task_plan", "description": "find mythology books", "goals": ["Go to Library", "Search for mythology", "Report back to mas"]}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertNotNull(result.primaryAction());
        assertInstanceOf(ActionParser.AgentAction.CreateTaskPlan.class, result.primaryAction());
        var plan = (ActionParser.AgentAction.CreateTaskPlan) result.primaryAction();
        assertEquals("find mythology books", plan.description());
        assertEquals(3, plan.goals().size());
        assertEquals("Go to Library", plan.goals().get(0));
    }

    @Test
    void parse_modify_plan_action() {
        var input = """
            ```json
            {"action": "modify_plan", "operation": "add_goal", "index": 1, "goal": "Check the Vault too"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertInstanceOf(ActionParser.AgentAction.ModifyPlan.class, result.primaryAction());
        var mod = (ActionParser.AgentAction.ModifyPlan) result.primaryAction();
        assertEquals("add_goal", mod.operation());
        assertEquals(1, mod.index());
        assertEquals("Check the Vault too", mod.goal());
    }

    @Test
    void parse_goal_done_action() {
        var input = """
            ```json
            {"action": "goal_done", "outcome": "Found 3 books about Norse mythology"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertInstanceOf(ActionParser.AgentAction.GoalDone.class, result.primaryAction());
        assertEquals("Found 3 books about Norse mythology",
            ((ActionParser.AgentAction.GoalDone) result.primaryAction()).outcome());
    }

    @Test
    void parse_task_plan_missing_goals_returns_null() {
        var input = """
            ```json
            {"action": "task_plan", "description": "do something"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertNull(result.primaryAction()); // missing goals array
    }

    // ── GoalExecutor + TaskPlan full cycle ──

    @Test
    void full_plan_lifecycle_success() {
        var plan = TaskPlan.create("p1", "find books", "user1", "mas",
            List.of("Go to Library", "Search mythology", "Report back"));

        // Goal 1: navigate succeeds
        plan.recordAttempt("go_to_room", "library", "arrived", true);
        var d1 = GoalExecutor.evaluate(plan, true, "arrived", "go_to_room",
            null, null, 0.8);
        assertInstanceOf(GoalExecutor.Decision.Advance.class, d1);
        plan.advanceGoal(((GoalExecutor.Decision.Advance) d1).outcome());

        // Goal 2: search fails first, succeeds on retry
        plan.recordAttempt("library_search", "myth", "no results", false);
        var d2 = GoalExecutor.evaluate(plan, false, "no results", "library_search",
            null, null, 0.7);
        assertInstanceOf(GoalExecutor.Decision.Retry.class, d2);

        plan.recordAttempt("library_search", "mythology legends", "3 results", true);
        var d3 = GoalExecutor.evaluate(plan, true, "3 results", "library_search",
            null, null, 0.65);
        assertInstanceOf(GoalExecutor.Decision.Advance.class, d3);
        plan.advanceGoal(((GoalExecutor.Decision.Advance) d3).outcome());

        // Goal 3: tell succeeds
        plan.recordAttempt("tell_agent", "mas", "told", true);
        var d4 = GoalExecutor.evaluate(plan, true, "told", "tell_agent",
            null, null, 0.6);
        assertInstanceOf(GoalExecutor.Decision.Advance.class, d4);
        assertFalse(plan.advanceGoal(((GoalExecutor.Decision.Advance) d4).outcome()));

        plan.complete("Found 3 mythology books and told mas");
        assertEquals(TaskPlan.PlanStatus.COMPLETED, plan.status());
        assertEquals(4, plan.totalAttempts());
    }

    @Test
    void plan_suspends_on_low_energy_resumes_later() {
        var plan = TaskPlan.create("p1", "research", null, null,
            List.of("Go somewhere", "Do something"));

        plan.recordAttempt("go_to_room", "library", "failed", false);
        var d = GoalExecutor.evaluate(plan, false, "failed", "go_to_room",
            null, null, 0.10); // below energy floor

        assertInstanceOf(GoalExecutor.Decision.Suspend.class, d);
        plan.suspend(((GoalExecutor.Decision.Suspend) d).reason());
        assertEquals(TaskPlan.PlanStatus.SUSPENDED, plan.status());

        // Resume later
        plan.resume();
        assertTrue(plan.isActive());
        assertEquals(0, plan.currentGoalIndex()); // still on goal 1
    }

    @Test
    void plan_escalates_when_all_retries_exhausted() {
        var plan = TaskPlan.create("p1", "impossible task", "u1", "mas",
            List.of("Do the impossible"));

        for (int i = 0; i < 3; i++) {
            plan.recordAttempt("action", null, "nope", false);
        }
        var d = GoalExecutor.evaluate(plan, false, "nope", "action",
            null, null, 0.8);

        assertInstanceOf(GoalExecutor.Decision.Escalate.class, d);
        assertTrue(((GoalExecutor.Decision.Escalate) d).message().contains("Stuck"));
    }

    // ── ObservationBuffer + OrientationEngine ──

    @Test
    void observations_contextualized_with_active_plan() {
        var buf = new ObservationBuffer();
        buf.observe("tell", "Claude says: the library has new mythology books", 0.9);
        buf.observe("room", "Ember entered the room", 0.5);
        buf.observe("system", "Inference backend healthy", 0.3);

        var plan = TaskPlan.create("p1", "find mythology books in library", null, null,
            List.of("Search for mythology"));

        var observations = buf.top(10);
        var contextualized = OrientationEngine.orient(observations, plan, null);

        assertFalse(contextualized.isEmpty());
        // The tell about mythology+library should be ranked highest (plan relevant + high base)
        var top = contextualized.getFirst();
        assertEquals("tell", top.observation().source());
        assertNotNull(top.planImpact()); // should detect plan impact
        assertTrue(top.planImpact().contains("mythology"));
    }

    @Test
    void observations_without_plan_ranked_by_source_priority() {
        var buf = new ObservationBuffer();
        buf.observe("room", "Background noise", 0.5);
        buf.observe("tell", "Direct message", 0.9);
        buf.observe("system", "Status update", 0.7);

        var contextualized = OrientationEngine.orient(buf.top(10), null, null);
        // Tell should be highest confidence
        assertEquals("tell", contextualized.getFirst().observation().source());
    }

    // ── HeuristicExtractor end-to-end ──

    @Test
    void failed_plan_produces_failure_heuristics_for_prompt() {
        // Create a plan that fails
        var plan = TaskPlan.create("p1", "search library for books", null, null,
            List.of("Search for mythology"));
        plan.recordAttempt("library_search", "myth", "no results", false);
        plan.recordAttempt("library_search", "mythology", "no results", false);
        plan.currentGoal().markFailed("exhausted retries");
        plan.fail("goal failed");

        // Extract heuristics
        var heuristics = HeuristicExtractor.extract(plan);
        assertFalse(heuristics.isEmpty());
        assertTrue(heuristics.stream().allMatch(h ->
            h.type() == Heuristic.HeuristicType.FAILURE_AVOIDANCE));

        // Build prompt context for a similar future task
        var promptCtx = HeuristicExtractor.buildPromptContext(
            heuristics, "search for books about legends", 5);
        assertNotNull(promptCtx);
        assertTrue(promptCtx.contains("Learned Patterns"));
        assertTrue(promptCtx.contains("Avoid"));
    }

    @Test
    void successful_plan_produces_skill_and_retry_heuristic() {
        var plan = TaskPlan.create("p1", "find books in library", null, null,
            List.of("Navigate to library", "Search for books", "Report results"));

        // Goal 1: clean success
        plan.recordAttempt("go_to_room", "library", "arrived", true);
        plan.advanceGoal("navigated");

        // Goal 2: failed first, succeeded on retry
        plan.recordAttempt("library_search", "myth", "no results", false);
        plan.recordAttempt("library_search", "mythology legends", "found 3", true);
        plan.advanceGoal("found books");

        // Goal 3: clean success
        plan.recordAttempt("tell_agent", "mas", "told", true);
        plan.advanceGoal("reported");
        plan.complete("done");

        // Heuristics from goal 2 (retry pattern)
        var heuristics = HeuristicExtractor.extract(plan);
        assertFalse(heuristics.isEmpty());
        var successPattern = heuristics.stream()
            .filter(h -> h.type() == Heuristic.HeuristicType.SUCCESS_PATTERN)
            .findFirst();
        assertTrue(successPattern.isPresent());

        // Skill from the completed plan
        var skill = HeuristicExtractor.extractSkill(plan);
        assertNotNull(skill);
        assertEquals(3, skill.goalTemplates().size());
    }

    // ── OutcomeTracker calibration ──

    @Test
    void outcome_tracker_detects_overconfidence_pattern() {
        var tracker = new OutcomeTracker();

        // Agent predicts success every time but only succeeds 40%
        for (int i = 0; i < 20; i++) {
            tracker.record("p1", "goal", "search",
                true, i % 5 < 2); // 8/20 = 40% actual
        }

        assertTrue(tracker.isOverconfident());
        assertTrue(tracker.predictedSuccessRate() > tracker.actualSuccessRate() + 0.2);
        assertTrue(tracker.calibrationScore() < 0.7);
    }

    // ── ReasoningDepthRouter with plan context ──

    @Test
    void reasoning_depth_elevates_with_active_plan() {
        // Without plan: greeting → ROUTINE
        assertEquals(ReasoningDepthRouter.Depth.ROUTINE,
            ReasoningDepthRouter.route("ok", false, false, 0.8));

        // With active plan: same input → SIMPLE (needs to advance goal)
        assertEquals(ReasoningDepthRouter.Depth.SIMPLE,
            ReasoningDepthRouter.route("ok", true, false, 0.8));

        // With failed goal: → COMPLEX (needs replanning)
        assertEquals(ReasoningDepthRouter.Depth.COMPLEX,
            ReasoningDepthRouter.route("ok", true, true, 0.8));
    }

    // ── ReconsiderationEngine with real triggers ──

    @Test
    void tell_message_triggers_reconsideration_of_related_plan() {
        var plan = TaskPlan.create("p1", "library research", null, null,
            List.of("Go to library", "Search for books", "Report findings"));

        var trigger = ReconsiderationEngine.fromTell("System",
            "The library is temporarily closed for maintenance");
        var impacts = ReconsiderationEngine.check(plan, List.of(trigger));

        assertFalse(impacts.isEmpty(), "Library closure should affect library-related plan");
        assertTrue(impacts.getFirst().contains("library"));
    }
}
