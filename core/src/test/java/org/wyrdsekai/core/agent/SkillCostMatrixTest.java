package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillCostMatrixTest {

    @Test
    void new_companion_has_all_known_actions() {
        var matrix = SkillCostMatrix.newCompanion();
        assertTrue(matrix.size() > 50, "Should have 50+ known actions");
    }

    @Test
    void new_companion_costs_are_above_floor() {
        var matrix = SkillCostMatrix.newCompanion();
        double navCost = matrix.costFor("go_to_room");
        double navFloor = SkillCostMatrix.floorFor("go_to_room");
        assertTrue(navCost > navFloor, "New companion cost should be above floor");
        assertEquals(0.02, navFloor, 0.001);
    }

    @Test
    void unknown_action_returns_default_cost() {
        var matrix = SkillCostMatrix.newCompanion();
        double cost = matrix.costFor("magic_scroll_of_doom");
        assertEquals(SkillCostMatrix.DEFAULT_NEW_COST, cost, 0.001);
    }

    @Test
    void can_afford_with_sufficient_energy() {
        var matrix = SkillCostMatrix.newCompanion();
        // tell_agent floor is 0.01, new cost is 0.01 + 0.25 = 0.26
        assertTrue(matrix.canAfford("tell_agent", 0.5));
    }

    @Test
    void cannot_afford_with_insufficient_energy() {
        var matrix = SkillCostMatrix.newCompanion();
        // workbench_submit floor is 0.20, new cost is 0.20 + 0.25 = 0.45
        assertFalse(matrix.canAfford("workbench_submit", 0.3));
    }

    @Test
    void affordable_actions_filters_correctly() {
        var matrix = SkillCostMatrix.newCompanion();
        var available = Set.of("tell_agent", "go_to_room", "workbench_submit", "create_room");
        var affordable = matrix.affordableActions(available, 0.30);
        assertTrue(affordable.contains("tell_agent"));
        assertTrue(affordable.contains("go_to_room"));
        assertFalse(affordable.contains("workbench_submit"));
        assertFalse(affordable.contains("create_room"));
    }

    @Test
    void too_costly_returns_complement() {
        var matrix = SkillCostMatrix.newCompanion();
        var available = Set.of("tell_agent", "workbench_submit");
        var costly = matrix.tooCostly(available, 0.30);
        assertFalse(costly.contains("tell_agent"));
        assertTrue(costly.contains("workbench_submit"));
    }

    @Test
    void record_success_adds_novel_action() {
        var matrix = SkillCostMatrix.newCompanion();
        assertEquals(SkillCostMatrix.DEFAULT_NEW_COST, matrix.costFor("weather_globe"));
        matrix.recordSuccess("weather_globe");
        assertEquals(SkillCostMatrix.DEFAULT_NEW_COST, matrix.costFor("weather_globe"));
        // Cost doesn't change until Forge consolidation
    }

    @Test
    void forge_consolidation_reduces_practiced_cost() {
        var matrix = SkillCostMatrix.newCompanion();
        double before = matrix.costFor("go_to_room");

        // Simulate a day of successful navigation
        for (int i = 0; i < 20; i++) {
            matrix.recordSuccess("go_to_room");
        }
        matrix.forgeConsolidate();

        double after = matrix.costFor("go_to_room");
        assertTrue(after < before, "Practiced action should be cheaper after Forge: " + before + " → " + after);
    }

    @Test
    void forge_consolidation_respects_floor() {
        var matrix = SkillCostMatrix.newCompanion();

        // Consolidate many times to try to go below floor
        for (int cycle = 0; cycle < 100; cycle++) {
            for (int i = 0; i < 50; i++) {
                matrix.recordSuccess("tell_agent");
            }
            matrix.forgeConsolidate();
        }

        double cost = matrix.costFor("tell_agent");
        double floor = SkillCostMatrix.floorFor("tell_agent");
        assertTrue(cost >= floor, "Cost should never go below floor: " + cost + " < " + floor);
    }

    @Test
    void forge_consolidation_decays_unused_skills() {
        var matrix = SkillCostMatrix.newCompanion();

        // First practice a lot to reduce cost
        for (int cycle = 0; cycle < 10; cycle++) {
            for (int i = 0; i < 20; i++) {
                matrix.recordSuccess("go_to_room");
            }
            matrix.forgeConsolidate();
        }
        double practiced = matrix.costFor("go_to_room");

        // Now don't use it for several cycles
        for (int cycle = 0; cycle < 10; cycle++) {
            matrix.forgeConsolidate(); // no usage recorded
        }
        double decayed = matrix.costFor("go_to_room");

        assertTrue(decayed > practiced, "Unused skill should decay: " + practiced + " → " + decayed);
    }

    @Test
    void forge_consolidation_failure_ratio_limits_improvement() {
        var matrix = SkillCostMatrix.newCompanion();
        double before = matrix.costFor("workbench_submit");

        // 50% failure rate
        for (int i = 0; i < 10; i++) {
            matrix.recordSuccess("workbench_submit");
            matrix.recordFailure("workbench_submit");
        }
        matrix.forgeConsolidate();

        double after = matrix.costFor("workbench_submit");
        // Should improve less than 100% success rate
        double improvement = before - after;
        assertTrue(improvement > 0, "Should still improve with 50% success");
        assertTrue(improvement < 0.02, "Should improve less than full practice rate");
    }

    @Test
    void genome_serialization_roundtrip() {
        var matrix = SkillCostMatrix.newCompanion();
        for (int i = 0; i < 20; i++) {
            matrix.recordSuccess("go_to_room");
        }
        matrix.forgeConsolidate();

        var genome = matrix.toGenome();
        var restored = SkillCostMatrix.fromGenome(genome);

        assertEquals(matrix.costFor("go_to_room"), restored.costFor("go_to_room"), 0.001);
        assertEquals(matrix.costFor("workbench_submit"), restored.costFor("workbench_submit"), 0.001);
    }

    @Test
    void from_null_genome_creates_empty_matrix() {
        var matrix = SkillCostMatrix.fromGenome(null);
        assertEquals(SkillCostMatrix.DEFAULT_NEW_COST, matrix.costFor("go_to_room"));
    }

    @Test
    void creation_actions_have_high_floors() {
        assertTrue(SkillCostMatrix.floorFor("workbench_submit") >= 0.15);
        assertTrue(SkillCostMatrix.floorFor("create_room") >= 0.15);
        assertTrue(SkillCostMatrix.floorFor("craft_item") >= 0.15);
        assertTrue(SkillCostMatrix.floorFor("add_script") >= 0.15);
    }

    @Test
    void speech_actions_have_low_floors() {
        assertTrue(SkillCostMatrix.floorFor("tell_agent") <= 0.02);
        assertTrue(SkillCostMatrix.floorFor("emote") <= 0.02);
        assertTrue(SkillCostMatrix.floorFor("whisper") <= 0.02);
    }
}
