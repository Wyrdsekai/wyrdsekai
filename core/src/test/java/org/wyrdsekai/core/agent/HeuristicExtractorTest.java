package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeuristicExtractorTest {

    @Test
    void extract_failure_heuristics_from_failed_goal() {
        var plan = TaskPlan.create("p1", "find books", null, null,
            List.of("Search for mythology"));
        plan.recordAttempt("library_search", "myth", "no results", false);
        plan.currentGoal().markFailed("no results after retries");
        plan.fail("goal failed");

        var heuristics = HeuristicExtractor.extract(plan);

        assertFalse(heuristics.isEmpty());
        assertEquals(Heuristic.HeuristicType.FAILURE_AVOIDANCE, heuristics.getFirst().type());
        assertTrue(heuristics.getFirst().guidance().contains("Avoid"));
    }

    @Test
    void extract_retry_heuristics_from_multi_attempt_success() {
        var plan = TaskPlan.create("p1", "find books", null, null,
            List.of("Search for mythology"));
        plan.recordAttempt("library_search", "myth", "no results", false);
        plan.recordAttempt("library_search", "mythology legends", "3 results", true);
        plan.currentGoal().markDone("found 3 books");
        plan.complete("done");

        var heuristics = HeuristicExtractor.extract(plan);

        assertFalse(heuristics.isEmpty());
        var successPattern = heuristics.stream()
            .filter(h -> h.type() == Heuristic.HeuristicType.SUCCESS_PATTERN)
            .findFirst();
        assertTrue(successPattern.isPresent());
        assertTrue(successPattern.get().guidance().contains("What worked"));
    }

    @Test
    void no_heuristics_from_clean_success() {
        var plan = TaskPlan.create("p1", "greet", null, null, List.of("Say hello"));
        plan.recordAttempt("say", "hello", "said hello", true);
        plan.currentGoal().markDone("said hello");
        plan.complete("done");

        var heuristics = HeuristicExtractor.extract(plan);
        assertTrue(heuristics.isEmpty()); // single-attempt success → nothing to learn
    }

    @Test
    void extract_skill_from_completed_plan() {
        var plan = TaskPlan.create("p1", "find books in library", null, null,
            List.of("Navigate to Library", "Search for topic", "Report results"));
        plan.advanceGoal("navigated");
        plan.advanceGoal("found 3 books");
        plan.advanceGoal("told user");
        plan.complete("done");

        var skill = HeuristicExtractor.extractSkill(plan);
        assertNotNull(skill);
        assertEquals(3, skill.goalTemplates().size());
        assertEquals("search", skill.domain());
        assertEquals(1, skill.successCount());
    }

    @Test
    void no_skill_from_single_goal_plan() {
        var plan = TaskPlan.create("p1", "say hi", null, null, List.of("Say hello"));
        plan.advanceGoal("done");
        plan.complete("done");

        assertNull(HeuristicExtractor.extractSkill(plan));
    }

    @Test
    void no_skill_from_failed_plan() {
        var plan = TaskPlan.create("p1", "broken", null, null, List.of("A", "B"));
        plan.fail("failed");

        assertNull(HeuristicExtractor.extractSkill(plan));
    }

    @Test
    void buildPromptContext_matches_relevant_heuristics() {
        var heuristics = List.of(
            new Heuristic("search", "library_search failed", "Try broader terms", Heuristic.HeuristicType.FAILURE_AVOIDANCE, 0.8, 0),
            new Heuristic("navigation", "go_to_room failed", "Check exits list", Heuristic.HeuristicType.FAILURE_AVOIDANCE, 0.7, 0),
            new Heuristic("cooking", "recipe failed", "Add more salt", Heuristic.HeuristicType.SUCCESS_PATTERN, 0.6, 0)
        );

        var context = HeuristicExtractor.buildPromptContext(heuristics, "search for books in library", 5);
        assertNotNull(context);
        assertTrue(context.contains("broader terms")); // search domain matches
        assertTrue(context.contains("Learned Patterns"));
    }

    @Test
    void buildPromptContext_returns_null_when_no_match() {
        var heuristics = List.of(
            new Heuristic("cooking", "recipe failed", "Add salt", Heuristic.HeuristicType.FAILURE_AVOIDANCE, 0.8, 0)
        );

        var context = HeuristicExtractor.buildPromptContext(heuristics, "navigate to library", 5);
        assertNull(context);
    }
}
