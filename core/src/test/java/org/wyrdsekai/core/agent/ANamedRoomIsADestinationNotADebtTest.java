package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.ZoneTopology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that names a room that already EXISTS is naming a destination,
 * not asking for a new room.
 *
 * <h2>What went wrong</h2>
 * Battery cpB5 (2026-08-23 17:27): "please build a working weather tool and
 * put it in the room weather-attic-1325…" — the attic stood, furnished, one
 * exit away. {@code asksForARoomThatActs} read "room" + "so anyone" as a
 * room-that-acts ask, the dispatch registered a room DEBT, and the loop's
 * second step called {@code create_room_from_template name='weather-tool'}:
 * a second room beside the first, while the named one stayed empty.
 */
class ANamedRoomIsADestinationNotADebtTest {

    private static final String CPB5 =
        "please build a working weather tool and put it in the room weather-attic-1325 "
        + "so anyone in the weather attic can ask for a city and state and hear the weather";

    @BeforeEach
    void seedTopology() {
        ZoneTopology.setShared(ZoneTopology.build(List.of(
            new ZoneTopology.RoomSeed("nexus", "The Nexus", "hearth",
                List.of(new Exit("up", "weather-attic-1325", "The Weather Attic"))),
            new ZoneTopology.RoomSeed("weather-attic-1325", "weather-attic", "hearth",
                List.of(new Exit("down", "nexus", "The Nexus"))),
            new ZoneTopology.RoomSeed("library", "The Library", "hearth", List.of()))));
    }

    @AfterEach
    void reset() {
        ZoneTopology.resetForTests();
    }

    @Test
    @DisplayName("the cpB5 phrasing resolves to the standing room")
    void theNamedRoomIsFound() {
        assertThat(CompanionActor.existingRoomNamedIn(CPB5))
            .contains("weather-attic-1325");
    }

    @Test
    @DisplayName("a passing mention of a seeded room name binds to nothing")
    void aPassingMentionDoesNotBind() {
        // "library" is a real room id, but short and undistinctive — the ask is
        // ABOUT the library, not addressed to it as a destination.
        assertThat(CompanionActor.existingRoomNamedIn(
            "make me a tool that queries the library and tells the room a story"))
            .isEmpty();
    }

    @Test
    @DisplayName("a room nobody has made yet stays a debt")
    void anUnmadeRoomStaysOwed() {
        assertThat(CompanionActor.existingRoomNamedIn(
            "please make me a room called signal-loft off the nexus and build "
            + "a working weather tool into that room")).isEmpty();
        // ...and the room-that-acts detector still fires for it.
        assertThat(CompanionActor.asksForARoomThatActs(
            "please make me a room called signal-loft off the nexus and build "
            + "a working weather tool into that room, so I can walk in and ask it "
            + "the current weather")).isTrue();
    }

    @Test
    @DisplayName("the dispatch site skips the debt and places into the named room")
    void theDispatchSiteHonoursTheDestination() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Path.of("..", rel);
        var src = Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
        // Debt registration consults the named-existing-room check…
        assertThat(src).contains("namedExistingRoom.isEmpty()");
        // …and the placement path adopts the named room when the model left
        // the room param blank.
        assertThat(src).contains(
            "if (askedRoom.isBlank() && namedExistingRoom.isPresent())");
        // The owed-room gate asks requestOwesARoom, which excludes standing rooms.
        assertThat(src).contains("private static boolean requestOwesARoom(String text)");
        assertThat(src).contains(
            "asksForARoomThatActs(text) && existingRoomNamedIn(text).isEmpty()");
    }
}
