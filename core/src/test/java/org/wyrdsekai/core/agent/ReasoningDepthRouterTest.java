package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReasoningDepthRouterTest {

    @Test
    void greeting_routes_to_routine() {
        var depth = ReasoningDepthRouter.route("hello", false, false, 0.8);
        assertEquals(ReasoningDepthRouter.Depth.ROUTINE, depth);
    }

    @Test
    void simple_question_routes_to_simple() {
        var depth = ReasoningDepthRouter.route("how are you?", false, false, 0.8);
        assertEquals(ReasoningDepthRouter.Depth.SIMPLE, depth);
    }

    @Test
    void complex_input_routes_to_complex() {
        var depth = ReasoningDepthRouter.route(
            "explain the architecture of the prediction engine in detail",
            false, false, 0.8);
        assertEquals(ReasoningDepthRouter.Depth.COMPLEX, depth);
    }

    @Test
    void active_plan_elevates_to_simple() {
        var depth = ReasoningDepthRouter.route("ok", true, false, 0.8);
        assertEquals(ReasoningDepthRouter.Depth.SIMPLE, depth);
    }

    @Test
    void failed_goal_elevates_to_complex() {
        var depth = ReasoningDepthRouter.route("ok", true, true, 0.8);
        assertEquals(ReasoningDepthRouter.Depth.COMPLEX, depth);
    }

    @Test
    void low_energy_forces_routine() {
        var depth = ReasoningDepthRouter.route(
            "explain quantum physics", false, false, 0.1);
        assertEquals(ReasoningDepthRouter.Depth.ROUTINE, depth);
    }

    @Test
    void elevate_increases_depth() {
        assertEquals(ReasoningDepthRouter.Depth.SIMPLE,
            ReasoningDepthRouter.elevate(ReasoningDepthRouter.Depth.ROUTINE));
        assertEquals(ReasoningDepthRouter.Depth.COMPLEX,
            ReasoningDepthRouter.elevate(ReasoningDepthRouter.Depth.SIMPLE));
        assertEquals(ReasoningDepthRouter.Depth.CRITICAL,
            ReasoningDepthRouter.elevate(ReasoningDepthRouter.Depth.COMPLEX));
        assertEquals(ReasoningDepthRouter.Depth.CRITICAL,
            ReasoningDepthRouter.elevate(ReasoningDepthRouter.Depth.CRITICAL));
    }

    @Test
    void token_budgets_increase_with_depth() {
        assertTrue(ReasoningDepthRouter.Depth.ROUTINE.tokenBudget()
            < ReasoningDepthRouter.Depth.SIMPLE.tokenBudget());
        assertTrue(ReasoningDepthRouter.Depth.SIMPLE.tokenBudget()
            < ReasoningDepthRouter.Depth.COMPLEX.tokenBudget());
        assertTrue(ReasoningDepthRouter.Depth.COMPLEX.tokenBudget()
            < ReasoningDepthRouter.Depth.CRITICAL.tokenBudget());
    }
}
