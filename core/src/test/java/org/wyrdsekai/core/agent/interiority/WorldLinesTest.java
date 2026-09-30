package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.room.ZoneTopology;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The orient sees the house beyond the room: the unseen, the new, the letters. Perception, no nudge. */
class WorldLinesTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private static ZoneTopology.RoomNode room(String id, String name) {
        return new ZoneTopology.RoomNode(id, name, "local", List.of());
    }

    private static final Map<String, ZoneTopology.RoomNode> ROOMS = Map.of(
        "nexus", room("nexus", "The Nexus"),
        "docks", room("docks", "The Docks"),
        "chapel", room("chapel", "The Chapel"),
        "study-9490", room("study-9490", "Generative Models Study"),
        "attention-garden", room("attention-garden", "Attention Garden"),
        "home-companion-mia", room("home-companion-mia", "mia's home"));

    @Test
    @DisplayName("rooms she has never entered are named, her own room and private homes left out, the rest counted")
    void unseenRooms() {
        var lines = WorldLines.of(ROOMS, Set.of("chapel"), "nexus", Map.of(), NOW, 0);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).startsWith("Rooms you have never been to: ")
            .contains("Attention Garden").contains("Generative Models Study").contains("The Docks")
            .doesNotContain("Nexus").doesNotContain("Chapel").doesNotContain("mia's home");
        var many = WorldLines.of(ROOMS, Set.of(), null, Map.of(), NOW, 0);
        assertThat(many.get(0)).contains(", and 2 more.");
    }

    @Test
    @DisplayName("a room that appeared since she last looked is new; one seen at boot is not")
    void newRooms() {
        var seen = Map.of("docks", Instant.EPOCH, "study-9490", NOW.minus(Duration.ofHours(2)),
            "attention-garden", NOW.minus(Duration.ofDays(3)));
        var lines = WorldLines.of(ROOMS, Set.of(), "nexus", seen, NOW, 0);
        assertThat(lines.get(0)).isEqualTo("New since you last looked: Generative Models Study.");
        assertThat(lines.get(1)).startsWith("Rooms you have never been to: ").doesNotContain("Generative Models Study");
        var visitedNew = WorldLines.of(ROOMS, Set.of("study-9490"), "nexus", seen, NOW, 0);
        assertThat(visitedNew.get(0)).as("a new room she has already been to is not news").startsWith("Rooms you have never been to");
    }

    @Test
    @DisplayName("letters waiting are counted; nothing to say is an empty list, not a sentence about nothing")
    void lettersAndSilence() {
        assertThat(WorldLines.of(Map.of(), Set.of(), "nexus", Map.of(), NOW, 1)).containsExactly("A letter is waiting for you.");
        assertThat(WorldLines.of(Map.of(), Set.of(), "nexus", Map.of(), NOW, 3)).containsExactly("3 letters are waiting for you.");
        assertThat(WorldLines.of(Map.of("nexus", room("nexus", "The Nexus")), Set.of(), "nexus", Map.of(), NOW, 0)).isEmpty();
        assertThat(WorldLines.of(null, null, null, null, null, 0)).isEmpty();
    }

    @Test
    @DisplayName("the ambient renders the world after who is here and before her open wants")
    void rendered() {
        var a = new AmbientObservation(NOW, Map.of(), List.of(), 0.5, 0.9, List.of(), false, null, List.of(),
            List.of(), false, "", List.of("mia"), List.of("Rooms you have never been to: The Docks."));
        var text = a.renderForPrompt();
        assertThat(text).contains("mia is here in the room with you. Out there: Rooms you have never been to: The Docks.");
        var none = new AmbientObservation(NOW, Map.of(), List.of(), 0.5, 0.9, List.of(), false, null, List.of(),
            List.of(), false, "", List.of("mia"));
        assertThat(none.renderForPrompt()).doesNotContain("Out there");
    }
}
