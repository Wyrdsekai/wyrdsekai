package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.ZoneTopology;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentNavigator — agent pathfinding using directed ZoneTopology.
 * Exits are DIRECTED: A→B does NOT imply B→A.
 */
class AgentNavigatorTest {

    // --- helpers ---

    private static ZoneTopology.RoomSeed seed(String id, String name, Exit... exits) {
        return new ZoneTopology.RoomSeed(id, name, "test", List.of(exits));
    }

    /**
     * Linear chain with bidirectional exits:
     *   A ←→ B ←→ C ←→ D
     */
    private static AgentNavigator linearNav() {
        var topo = ZoneTopology.build(List.of(
            seed("a", "Room A", new Exit("east", "b", "east to B")),
            seed("b", "Room B", new Exit("west", "a", "west to A"),
                                new Exit("east", "c", "east to C")),
            seed("c", "Room C", new Exit("west", "b", "west to B"),
                                new Exit("east", "d", "east to D")),
            seed("d", "Room D", new Exit("west", "c", "west to C"))
        ));
        return new AgentNavigator(topo);
    }

    /**
     * One-way trap topology:
     *   A → B → C
     *         ↑
     *   (no exit from C back to B, and no exit from B back to A)
     *   C has an exit to D (one-way), D is a dead end.
     */
    private static AgentNavigator oneWayNav() {
        var topo = ZoneTopology.build(List.of(
            seed("a", "Room A", new Exit("north", "b", "north to B")),
            seed("b", "Room B", new Exit("north", "c", "north to C")),
            seed("c", "Room C", new Exit("down", "d", "fall into D")),
            seed("d", "Dead End")
        ));
        return new AgentNavigator(topo);
    }

    // --- nextStep ---

    @Test void nextStep_through_chain() {
        var nav = linearNav();
        var step = nav.nextStep("a", "d");
        assertThat(step).isPresent().hasValue("east");
    }

    @Test void nextStep_already_at_destination() {
        var nav = linearNav();
        var step = nav.nextStep("b", "b");
        assertThat(step).isEmpty();
    }

    @Test void nextStep_unreachable_one_way() {
        var nav = oneWayNav();
        // c→a has no directed path (one-way exits block return)
        var step = nav.nextStep("c", "a");
        assertThat(step).isEmpty();
    }

    // --- fullPath ---

    @Test void fullPath_returns_direction_list() {
        var nav = linearNav();
        var path = nav.fullPath("a", "b");
        assertThat(path).isPresent();
        assertThat(path.get()).containsExactly("east");
    }

    @Test void fullPath_multiple_hops() {
        var nav = linearNav();
        var path = nav.fullPath("a", "d");
        assertThat(path).isPresent();
        assertThat(path.get()).containsExactly("east", "east", "east");
    }

    @Test void fullPath_unreachable_returns_empty() {
        var nav = oneWayNav();
        var path = nav.fullPath("d", "a");
        assertThat(path).isEmpty();
    }

    // --- findNearest ---

    @Test void findNearest_with_predicate() {
        var nav = linearNav();
        // Find the nearest room named "Room D"
        var found = nav.findNearest("a", node -> node.name().equals("Room D"));
        assertThat(found).isPresent().hasValue("d");
    }

    @Test void findNearest_no_match() {
        var nav = linearNav();
        var found = nav.findNearest("a", node -> node.name().equals("Nonexistent"));
        assertThat(found).isEmpty();
    }

    // --- isReachable ---

    @Test void isReachable_true_and_false() {
        var nav = oneWayNav();
        assertThat(nav.isReachable("a", "d")).isTrue();
        assertThat(nav.isReachable("d", "a")).isFalse();
    }

    // --- isOneWay ---

    @Test void isOneWay_bidirectional_exit() {
        var nav = linearNav();
        // A↔B is bidirectional
        assertThat(nav.isOneWay("a", "east")).isFalse();
    }

    @Test void isOneWay_one_way_exit() {
        var nav = oneWayNav();
        // A→B with no return exit from B to A
        assertThat(nav.isOneWay("a", "north")).isTrue();
    }

    // --- distance ---

    @Test void distance_counts_hops() {
        var nav = linearNav();
        assertThat(nav.distance("a", "a")).isEqualTo(0);
        assertThat(nav.distance("a", "b")).isEqualTo(1);
        assertThat(nav.distance("a", "d")).isEqualTo(3);
    }

    @Test void distance_unreachable_returns_negative_one() {
        var nav = oneWayNav();
        assertThat(nav.distance("d", "a")).isEqualTo(-1);
    }
}
