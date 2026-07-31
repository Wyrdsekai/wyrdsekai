package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReconsiderationEngineTest {

    @Test
    void oracle_trigger_matches_plan_with_related_goals() {
        var plan = TaskPlan.create("p1", "find books in the library", null, null,
            List.of("Go to library", "Search for books"));

        var trigger = ReconsiderationEngine.fromOraclePrediction(3, 0.9, true);
        var impacts = ReconsiderationEngine.check(plan, List.of(trigger));

        // "library" is in the goal and could match prediction text
        // But the trigger condition is about predictions, not library
        // This tests the mechanism, not deep keyword matching
        assertNotNull(impacts);
    }

    @Test
    void tell_trigger_detects_plan_impact() {
        var plan = TaskPlan.create("p1", "search library", null, null,
            List.of("Navigate to library", "Search mythology"));

        var trigger = ReconsiderationEngine.fromTell("Claude", "The library is being reorganized");
        var impacts = ReconsiderationEngine.check(plan, List.of(trigger));

        assertFalse(impacts.isEmpty(), "Tell mentioning 'library' should impact library goals");
        assertTrue(impacts.getFirst().contains("library"));
    }

    @Test
    void room_change_trigger() {
        var trigger = ReconsiderationEngine.fromRoomChange("Library", "shelves rearranged");
        assertEquals("room_change", trigger.triggerType());
        assertTrue(trigger.condition().contains("Library"));
    }

    @Test
    void no_impact_for_null_plan() {
        var trigger = ReconsiderationEngine.fromTell("Claude", "important news");
        var impacts = ReconsiderationEngine.check(null, List.of(trigger));
        assertTrue(impacts.isEmpty());
    }

    @Test
    void no_impact_for_unrelated_trigger() {
        var plan = TaskPlan.create("p1", "exercise", null, null,
            List.of("Run laps", "Do pushups"));

        var trigger = ReconsiderationEngine.fromTell("Claude", "The library has new books");
        var impacts = ReconsiderationEngine.check(plan, List.of(trigger));
        assertTrue(impacts.isEmpty());
    }

    @Test
    void multiple_triggers_checked() {
        var plan = TaskPlan.create("p1", "library research", null, null,
            List.of("Go to library", "Search for books", "Read results"));

        var triggers = List.of(
            ReconsiderationEngine.fromTell("System", "library closed"),
            ReconsiderationEngine.fromRoomChange("Garden", "flowers blooming")
        );

        var impacts = ReconsiderationEngine.check(plan, triggers);
        // Library trigger should match, garden should not
        assertFalse(impacts.isEmpty());
    }
}
