package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.item.ToolItemStarterKit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The escape hatch reaches what she names: a placed item by its object name, a fixture by
 * looking at it, and a forced "act or decline" surface that actually offers decline
 * (household node, 2026-09-10: 63 refusals in a week, none of them about permission).
 */
class UseItemHatchTest {

    private static final List<String> TOOLS = List.of("key_chest", "chest", "journal", "workbench", "go_to_room");

    @Test
    void a_placed_copy_named_with_the_placement_suffix_resolves_to_its_tool() {
        assertEquals("chest", CompanionActor.hatchNameFor("chest-2", TOOLS));
        assertEquals("journal", CompanionActor.hatchNameFor("journal-2", TOOLS));
        assertEquals("workbench", CompanionActor.hatchNameFor("workbench_2", TOOLS));
        assertEquals("key_chest", CompanionActor.hatchNameFor("key chest", TOOLS));
        assertEquals("key_chest", CompanionActor.hatchNameFor("Key-Chest", TOOLS));
    }

    @Test
    void a_name_that_is_no_tool_stays_unresolved() {
        assertNull(CompanionActor.hatchNameFor("Mirror", TOOLS));
        assertNull(CompanionActor.hatchNameFor("workbench-hammer-258449800-1786308520639", TOOLS));
        assertNull(CompanionActor.hatchNameFor("", TOOLS));
        assertNull(CompanionActor.hatchNameFor(null, TOOLS));
    }

    @Test
    void a_fixture_of_the_room_is_found_by_name_or_id() {
        var objects = List.of(
            new RoomObject("mirror", "Mirror", "A full-length enchanted mirror.", false),
            new RoomObject("memory-chest", "Memory Chest", "A brass-bound chest.", false),
            new RoomObject("codex-d484253b", "chest-2", "The household's backup snapshots.", true));
        assertEquals("Mirror", CompanionActor.fixtureFor("mirror", objects));
        assertEquals("Memory Chest", CompanionActor.fixtureFor("Memory Chest", objects));
        assertEquals("Memory Chest", CompanionActor.fixtureFor("memory-chest", objects));
        assertEquals("chest-2", CompanionActor.fixtureFor("chest-2", objects));
        assertNull(CompanionActor.fixtureFor("dream journal", objects));
        assertNull(CompanionActor.fixtureFor("mirror", null));
    }

    @Test
    void the_refusal_names_the_nearest_tools() {
        var near = CompanionActor.nearestToolNames("workbench_submit", TOOLS);
        assertEquals(List.of("workbench"), near);
        assertTrue(CompanionActor.nearestToolNames("zzz", TOOLS).isEmpty());
    }

    @Test
    void a_forced_surface_gains_decline_once_and_only_when_missing() {
        var tools = new ArrayList<InferenceClient.ToolDefinition>();
        for (var a : ToolItemStarterKit.inherentActions()) {
            var def = a.toToolDefinition();
            if ("go_to_room".equals(def.function().name())) tools.add(def);
        }
        assertEquals(1, tools.size());
        CompanionActor.addDeclineIfMissing(tools);
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(t -> "decline_with_reason".equals(t.function().name())));
        CompanionActor.addDeclineIfMissing(tools);
        assertEquals(2, tools.size(), "decline is added once");
        CompanionActor.addDeclineIfMissing(null);
    }
}
