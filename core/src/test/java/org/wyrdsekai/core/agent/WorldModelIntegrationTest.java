package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WorldModel — tests realistic multi-room navigation
 * scenarios and verifies loop detection works for common companion patterns.
 */
class WorldModelIntegrationTest {

    private static RoomSnapshot room(String id, List<String> entities, List<String> objects, List<String> exits) {
        return new RoomSnapshot(id, "Room " + id, "Description of " + id, "test",
            exits.stream().map(e -> {
                var parts = e.split("→");
                return new Exit(parts[0].trim(), parts[1].trim(), "Go " + parts[0].trim());
            }).toList(),
            entities.stream().map(n -> new Entity(n.toLowerCase(), n, "agent", "")).toList(),
            objects.stream().map(n -> new RoomObject(n, n, "An object.", true)).toList(),
            List.of());
    }

    @Test
    void multiRoomNavigation_learnsTransitions() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd", "Claude"), List.of("crystal"),
            List.of("southeast → library", "down → boiler-room"));
        var library = room("library", List.of("Wyrd"), List.of("catalog", "desk"),
            List.of("northwest → nexus"));
        var boiler = room("boiler-room", List.of("Wyrd", "Chief"), List.of("computer"),
            List.of("up → nexus"));

        // Navigate nexus → library
        wm.recordTransition(nexus, "go_to_room", "southeast", library, true, "Arrived in library");
        // Navigate library → nexus
        wm.recordTransition(library, "go_to_room", "northwest", nexus, true, "Arrived in nexus");
        // Navigate nexus → boiler
        wm.recordTransition(nexus, "go_to_room", "down", boiler, true, "Arrived in boiler-room");

        // Predictions
        var toLibrary = wm.predict(nexus, "go_to_room", "southeast");
        assertThat(toLibrary).isNotNull();
        assertThat(toLibrary.stateWillChange()).isTrue();
        assertThat(toLibrary.likelySuccess()).isTrue();

        var toBoiler = wm.predict(nexus, "go_to_room", "down");
        assertThat(toBoiler).isNotNull();
        assertThat(toBoiler.stateWillChange()).isTrue();

        // No prediction for unseen route
        assertThat(wm.predict(library, "go_to_room", "down")).isNull();
    }

    @Test
    void examineLoop_detectedAndBlocked() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"), List.of());

        // Companion examines room 3 times (typical loop pattern)
        wm.recordTransition(nexus, "examine", "room", nexus, true, "You see the room");
        wm.recordTransition(nexus, "examine", "room", nexus, true, "You see the room");
        wm.recordTransition(nexus, "examine", "room", nexus, true, "You see the room");

        // Should suggest alternative
        var hint = wm.suggestAlternative(nexus, "examine", "room");
        assertThat(hint).isNotNull();
        assertThat(hint).containsAnyOf("no effect", "already tried");

        // Different examine target should be fine
        assertThat(wm.suggestAlternative(nexus, "examine", "crystal")).isNull();
    }

    @Test
    void goToRoomLoop_detectedAndBlocked() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"),
            List.of("southeast → library"));

        // Companion tries go_to_room(library) but stays in nexus (failed navigation)
        wm.recordTransition(nexus, "go_to_room", "library", nexus, true, "Already in nexus");
        wm.recordTransition(nexus, "go_to_room", "library", nexus, true, "Already in nexus");

        // Should detect loop
        assertThat(wm.isActionLoop("go_to_room", "library")).isTrue();

        var hint = wm.suggestAlternative(nexus, "go_to_room", "library");
        assertThat(hint).isNotNull();
    }

    @Test
    void searchAction_allowedWithoutPriorData() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"), List.of());

        // First-time search — no prediction, no blocking
        assertThat(wm.predict(nexus, "searching_glass", "Pekko")).isNull();
        assertThat(wm.suggestAlternative(nexus, "searching_glass", "Pekko")).isNull();
    }

    @Test
    void failedAction_predictedAsLikelyFailure() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"), List.of());

        // Record repeated failures
        wm.recordTransition(nexus, "tell_agent", "Bob", nexus, false, "Agent not found");
        wm.recordTransition(nexus, "tell_agent", "Bob", nexus, false, "Agent not found");
        wm.recordTransition(nexus, "tell_agent", "Bob", nexus, false, "Agent not found");

        var pred = wm.predict(nexus, "tell_agent", "Bob");
        assertThat(pred).isNotNull();
        assertThat(pred.likelySuccess()).isFalse();

        var hint = wm.suggestAlternative(nexus, "tell_agent", "Bob");
        assertThat(hint).isNotNull();
        // May return loop hint or failure hint — both are correct
        assertThat(hint).containsAnyOf("failed before", "no effect", "already tried");
    }

    @Test
    void successfulAction_noBlocking() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"), List.of());
        var library = room("library", List.of("Wyrd"), List.of("catalog"), List.of());

        // Record a successful action with state change
        wm.recordTransition(nexus, "go_to_room", "southeast", library, true, "Arrived");

        // Should NOT block — action succeeds and changes state
        assertThat(wm.suggestAlternative(nexus, "go_to_room", "southeast")).isNull();
    }

    @Test
    void stats_reportCorrectly() {
        var wm = new WorldModel();
        var nexus = room("nexus", List.of("Wyrd"), List.of("crystal"), List.of());
        var library = room("library", List.of("Wyrd"), List.of("catalog"), List.of());

        wm.recordTransition(nexus, "go_to_room", "se", library, true, "ok");
        wm.recordTransition(nexus, "examine", "crystal", nexus, true, "ok");

        var stats = wm.stats();
        assertThat((int) stats.get("uniqueStateActions")).isEqualTo(2);
        assertThat((int) stats.get("totalTransitions")).isEqualTo(2);
    }
}
