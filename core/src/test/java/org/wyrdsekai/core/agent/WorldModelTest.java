package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldModelTest {

    private static RoomSnapshot makeRoom(String roomId, int entityCount, String... objects) {
        var entities = new ArrayList<Entity>();
        for (int i = 0; i < entityCount; i++) {
            entities.add(new Entity("e" + i, "Entity" + i, "agent", ""));
        }
        var objs = new ArrayList<RoomObject>();
        for (var name : objects) {
            objs.add(new RoomObject(name, name, "An object.", false));
        }
        return new RoomSnapshot(roomId, "Room " + roomId, "Description", "test",
            List.of(new Exit("north", "other", "Go north")),
            entities, objs, List.of());
    }

    @Test
    void recordAndPredict() {
        var wm = new WorldModel();
        var nexus = makeRoom("nexus", 2, "crystal");
        var library = makeRoom("library", 1, "catalog", "desk");

        // No prediction yet
        assertThat(wm.predict(nexus, "go_to_room", "southeast")).isNull();

        // Record a transition
        wm.recordTransition(nexus, "go_to_room", "southeast", library, true, "Arrived in library");

        // Now we can predict
        var pred = wm.predict(nexus, "go_to_room", "southeast");
        assertThat(pred).isNotNull();
        assertThat(pred.stateWillChange()).isTrue();
        assertThat(pred.likelySuccess()).isTrue();
        assertThat(pred.observationCount()).isEqualTo(1);
    }

    @Test
    void detectNoEffect() {
        var wm = new WorldModel();
        var nexus = makeRoom("nexus", 2, "crystal");

        // Record an action that had no effect (same state before and after)
        wm.recordTransition(nexus, "examine", "room", nexus, true, "Looked around");
        wm.recordTransition(nexus, "examine", "room", nexus, true, "Looked around");

        var pred = wm.predict(nexus, "examine", "room");
        assertThat(pred).isNotNull();
        assertThat(pred.stateWillChange()).isFalse(); // no state change
        assertThat(pred.confidence()).isGreaterThan(0.3);
    }

    @Test
    void detectActionLoop() {
        var wm = new WorldModel();

        // Simulate repeated actions
        var nexus = makeRoom("nexus", 2, "crystal");
        wm.recordTransition(nexus, "go_to_room", "library", nexus, false, "Failed");
        wm.recordTransition(nexus, "go_to_room", "library", nexus, false, "Failed");

        assertThat(wm.isActionLoop("go_to_room", "library")).isTrue();
        assertThat(wm.isActionLoop("go_to_room", "boiler")).isFalse();
    }

    @Test
    void suggestAlternative_noEffect() {
        var wm = new WorldModel();
        var nexus = makeRoom("nexus", 2, "crystal");

        wm.recordTransition(nexus, "examine", "room", nexus, true, "Looked around");
        wm.recordTransition(nexus, "examine", "room", nexus, true, "Looked around");
        wm.recordTransition(nexus, "examine", "room", nexus, true, "Looked around");

        var hint = wm.suggestAlternative(nexus, "examine", "room");
        assertThat(hint).isNotNull();
        assertThat(hint).contains("no effect");
    }

    @Test
    void suggestAlternative_loop() {
        var wm = new WorldModel();
        var nexus = makeRoom("nexus", 2, "crystal");

        wm.recordTransition(nexus, "go_to_room", "library", nexus, true, "went");
        wm.recordTransition(nexus, "go_to_room", "library", nexus, true, "went");

        var hint = wm.suggestAlternative(nexus, "go_to_room", "library");
        assertThat(hint).isNotNull();
        assertThat(hint).contains("already tried");
    }

    @Test
    void noSuggestion_forNewAction() {
        var wm = new WorldModel();
        var nexus = makeRoom("nexus", 2, "crystal");

        var hint = wm.suggestAlternative(nexus, "searching_glass", "Pekko");
        assertThat(hint).isNull(); // no data, no suggestion
    }

    @Test
    void stateKey_differsByRoom() {
        var nexus = makeRoom("nexus", 2, "crystal");
        var library = makeRoom("library", 1, "catalog");

        assertThat(WorldModel.stateKey(nexus)).isNotEqualTo(WorldModel.stateKey(library));
    }

    @Test
    void stateKey_sameForSameState() {
        var room1 = makeRoom("nexus", 2, "crystal");
        var room2 = makeRoom("nexus", 2, "crystal");

        assertThat(WorldModel.stateKey(room1)).isEqualTo(WorldModel.stateKey(room2));
    }
}
