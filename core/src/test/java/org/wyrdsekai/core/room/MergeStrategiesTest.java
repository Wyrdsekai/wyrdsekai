package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MergeStrategiesTest {

    @Test void mergeEntities_union() {
        var local = Map.of("p1", new Entity("p1", "Alice", "player", ""));
        var remote = Map.of("p2", new Entity("p2", "Bob", "agent", ""));

        var merged = MergeStrategies.mergeEntities(local, remote);
        assertThat(merged).hasSize(2);
        assertThat(merged).containsKey("p1");
        assertThat(merged).containsKey("p2");
    }

    @Test void mergeEntities_remote_wins_conflict() {
        var local = Map.of("p1", new Entity("p1", "Alice-old", "player", ""));
        var remote = Map.of("p1", new Entity("p1", "Alice-new", "player", "updated"));

        var merged = MergeStrategies.mergeEntities(local, remote);
        assertThat(merged.get("p1").name()).isEqualTo("Alice-new");
    }

    @Test void mergeObjects_union() {
        var local = Map.of("o1", new RoomObject("o1", "Sword", "Sharp", true));
        var remote = Map.of("o2", new RoomObject("o2", "Shield", "Sturdy", false));

        var merged = MergeStrategies.mergeObjects(local, remote);
        assertThat(merged).hasSize(2);
    }

    @Test void mergeExits_union() {
        var local = Map.of("north", new Exit("north", "garden", "Path"));
        var remote = Map.of("south", new Exit("south", "cellar", "Stairs"));

        var merged = MergeStrategies.mergeExits(local, remote);
        assertThat(merged).hasSize(2);
        assertThat(merged).containsKey("north");
        assertThat(merged).containsKey("south");
    }

    @Test void mergeProperties_lww_remote_wins() {
        var local = new HashMap<>(Map.of("light", "dim", "mood", "tense"));
        var remote = Map.of("light", "bright", "color", "blue");

        var merged = MergeStrategies.mergeProperties(local, remote);
        assertThat(merged.get("light")).isEqualTo("bright"); // remote wins
        assertThat(merged.get("mood")).isEqualTo("tense");   // local preserved
        assertThat(merged.get("color")).isEqualTo("blue");   // remote added
    }

    @Test void mergeHints_longer_list_wins() {
        var local = List.of(new Hint("A", "a", "a"));
        var remote = List.of(new Hint("A", "a", "a"), new Hint("B", "b", "b"));

        assertThat(MergeStrategies.mergeHints(local, remote)).hasSize(2);
        assertThat(MergeStrategies.mergeHints(remote, local)).hasSize(2);
    }

    @Test void mergeLww_remote_wins_when_present() {
        assertThat(MergeStrategies.mergeLww("old", "new")).isEqualTo("new");
        assertThat(MergeStrategies.mergeLww("old", "")).isEqualTo("old");
        assertThat(MergeStrategies.mergeLww("old", null)).isEqualTo("old");
    }

    @Test void merge_full_room_state() {
        var local = new RoomState("room1", "Hall", "A hall", "zone1",
            Map.of("north", new Exit("north", "garden", "Path")),
            Map.of("p1", new Entity("p1", "Alice", "player", "")),
            Map.of("o1", new RoomObject("o1", "Sword", "Sharp", true)),
            List.of(),
            Map.of("light", "dim"));

        var remote = new RoomState("room1", "Hall", "A dark hall", "zone1",
            Map.of("south", new Exit("south", "cellar", "Stairs")),
            Map.of("p2", new Entity("p2", "Bob", "agent", "")),
            Map.of("o2", new RoomObject("o2", "Shield", "Sturdy", false)),
            List.of(new Hint("Look", "look", "look")),
            Map.of("light", "bright", "mood", "tense"));

        var merged = MergeStrategies.merge(local, remote);
        assertThat(merged.description()).isEqualTo("A dark hall"); // LWW
        assertThat(merged.exits()).hasSize(2);       // OR-Set union
        assertThat(merged.entities()).hasSize(2);     // OR-Set union
        assertThat(merged.objects()).hasSize(2);       // OR-Set union
        assertThat(merged.properties().get("light")).isEqualTo("bright"); // LWW
        assertThat(merged.properties().get("mood")).isEqualTo("tense");   // merged
        assertThat(merged.hints()).hasSize(1);         // remote longer
    }

    @Test void merge_preserves_roomId_and_zone() {
        var local = RoomState.empty("room1");
        var remote = new RoomState("room1", "Hall", "Desc", "zone1",
            Map.of(), Map.of(), Map.of(), List.of(), Map.of());

        var merged = MergeStrategies.merge(local, remote);
        assertThat(merged.roomId()).isEqualTo("room1");
        assertThat(merged.name()).isEqualTo("Hall");
    }
}
