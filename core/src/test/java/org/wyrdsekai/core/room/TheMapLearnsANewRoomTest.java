package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room made after boot appears on the map, by name.
 *
 * <h2>What went wrong</h2>
 * {@code Main} builds the shared topology once, from the hardcoded foundation seeds, and
 * this class has always documented itself as "rebuilt on topology mutations by whoever
 * holds the ref" — nobody ever did. Live on staging 2026-08-22, a room the companion made
 * was walkable, furnished, and had a way back, and {@code map} still rendered
 * {@code ├── to-venture-briefing-room-1931->[?]}: an unnamed destination on a one-way
 * arrow, because the topology had never heard of it.
 */
class TheMapLearnsANewRoomTest {

    @BeforeEach
    void seedFoundation() {
        ZoneTopology.setShared(ZoneTopology.build(List.of(
            new ZoneTopology.RoomSeed("nexus", "The Nexus", "hearth",
                List.of(new Exit("north", "terminal", "The Terminal"))),
            new ZoneTopology.RoomSeed("terminal", "The Terminal", "hearth",
                List.of(new Exit("south", "nexus", "The Nexus"))))));
    }

    @AfterEach
    void reset() {
        ZoneTopology.resetForTests();
    }

    @Test
    @DisplayName("a room created after boot is known by name, not as a question mark")
    void aNewRoomIsLearned() {
        ZoneTopology.learnRoom("venture-briefing-room-1931", "Venture Briefing Room",
            "hearth", List.of(new Exit("out", "nexus", "The Nexus")), null, null);

        var topo = ZoneTopology.getShared();
        assertThat(topo.rooms()).containsKey("venture-briefing-room-1931");
        assertThat(topo.rooms().get("venture-briefing-room-1931").name())
            .isEqualTo("Venture Briefing Room");
    }

    @Test
    @DisplayName("the exit into it is learned too, so the way there is not one-way")
    void theExitIsLearned() {
        ZoneTopology.learnRoom("venture-briefing-room-1931", "Venture Briefing Room",
            "hearth", List.of(new Exit("out", "nexus", "The Nexus")), null, null);
        ZoneTopology.learnExit("nexus", "to-venture-briefing-room-1931",
            "venture-briefing-room-1931", "Venture Briefing Room");

        var nexus = ZoneTopology.getShared().rooms().get("nexus");
        assertThat(nexus.exits())
            .as("the map draws the way in from the source room's exits")
            .anyMatch(e -> "venture-briefing-room-1931".equals(e.targetRoom()));
    }

    @Test
    @DisplayName("a foundation room recovered BEFORE the seeds keeps its learned exits after them")
    void recoveredExitsSurviveTheSeeds() {
        // Boot order on the household node: rooms recover from the DB and announce
        // themselves BEFORE Main builds the seeded topology. The Nexus comes back
        // with its seeded exits plus every exit a person or companion ever added.
        // "Seeds win on conflict" replaced all of that with the seed's canonical
        // list, so each made room became a node with no way in — walkable, and
        // absent from `map` on every boot after the one that made it (2026-09-01:
        // "multiple exits in the Nexus that are not on the map").
        ZoneTopology.resetForTests();
        ZoneTopology.learnRoom("nexus", "The Nexus", "hearth", List.of(
            new Exit("north", "terminal", "The Terminal"),
            new Exit("to-greenhouse-2063", "greenhouse-2063", "Greenhouse")), null, null);
        ZoneTopology.learnRoom("greenhouse-2063", "Greenhouse", "player-created",
            List.of(new Exit("out", "nexus", "The Nexus")), null, null);
        seedFoundation();   // Main's setShared(build(seeds)) arrives last
        var nexus = ZoneTopology.getShared().rooms().get("nexus");
        assertThat(nexus.exits()).as("seeded exit kept, canonical and first")
            .first().extracting(Exit::targetRoom).isEqualTo("terminal");
        assertThat(nexus.exits()).as("the recovered exit survives the seeds")
            .anyMatch(e -> "greenhouse-2063".equals(e.targetRoom()));
        assertThat(ZoneTopology.getShared().renderTextMap("nexus", 2, java.util.Set.of("greenhouse-2063")))
            .as("and the map can draw the way there").contains("Greenhouse");
    }

    @Test
    @DisplayName("learning the same exit twice does not duplicate it")
    void learningIsIdempotent() {
        ZoneTopology.learnExit("nexus", "east", "terminal", "The Terminal");
        ZoneTopology.learnExit("nexus", "east", "terminal", "The Terminal");
        assertThat(ZoneTopology.getShared().rooms().get("nexus").exits())
            .filteredOn(e -> "terminal".equals(e.targetRoom()))
            .hasSize(1);
    }

    @Test
    @DisplayName("a room re-learned after a restart replaces the old entry, exits and all")
    void relearningReplaces() {
        ZoneTopology.learnRoom("venture-briefing-room-1931", "Venture Briefing Room",
            "hearth", List.of(), null, null);
        // A restart: the room comes back up and announces itself again, this time with the
        // exits its own recovered state carries. The map must take the newer answer —
        // otherwise every companion-made room is nameless and one-way after a restart.
        ZoneTopology.learnRoom("venture-briefing-room-1931", "Venture Briefing Room",
            "hearth", List.of(new Exit("out", "nexus", "The Nexus")), null, null);

        var node = ZoneTopology.getShared().rooms().get("venture-briefing-room-1931");
        assertThat(node.exits()).hasSize(1);
        assertThat(node.exits().getFirst().targetRoom()).isEqualTo("nexus");
    }

    @Test
    @DisplayName("before the topology exists, learning is a no-op rather than a crash")
    void beforeBootItIsSafe() {
        ZoneTopology.resetForTests();
        ZoneTopology.learnRoom("x", "X", "hearth", List.of(), null, null);
        ZoneTopology.learnExit("nexus", "north", "x", "X");
        assertThat(ZoneTopology.getShared()).isNull();
    }

    @Test
    @DisplayName("a room that came up before the topology existed is not lost")
    void aRoomThatSpokeTooEarlyIsHeld() {
        // The real boot order: ZoneGuardian starts, every persisted room actor recovers
        // and announces itself, and only THEN does Main publish the topology. Dropping
        // those announcements is why a companion-made room stayed `->[?]` across a
        // restart even after it was taught to register itself.
        ZoneTopology.resetForTests();
        ZoneTopology.learnRoom("greenhouse-7772", "The Greenhouse", "hearth",
            List.of(new Exit("out", "nexus", "The Nexus")), null, null);

        ZoneTopology.setShared(ZoneTopology.build(List.of(
            new ZoneTopology.RoomSeed("nexus", "The Nexus", "hearth", List.of()))));

        var rooms = ZoneTopology.getShared().rooms();
        assertThat(rooms).containsKeys("nexus", "greenhouse-7772");
        assertThat(rooms.get("greenhouse-7772").name()).isEqualTo("The Greenhouse");
    }

    @Test
    @DisplayName("a foundation seed wins over a half-recovered snapshot of the same room")
    void seedsWinOnConflict() {
        ZoneTopology.resetForTests();
        ZoneTopology.learnRoom("nexus", "nexus", "hearth", List.of(), null, null);
        ZoneTopology.setShared(ZoneTopology.build(List.of(
            new ZoneTopology.RoomSeed("nexus", "The Nexus", "hearth",
                List.of(new Exit("north", "terminal", "The Terminal"))))));

        var nexus = ZoneTopology.getShared().rooms().get("nexus");
        assertThat(nexus.name()).isEqualTo("The Nexus");
        assertThat(nexus.exits()).hasSize(1);
    }

    /**
     * The companion's create_room_from_template goes through ZoneGuardian.CreateNewRoom,
     * never RoomCreator — so the caller-side hooks missed it and story_fable stayed
     * `->[?]` on the home node (2026-08-23 07:19). Creation itself is the one place.
     */
    @Test
    @DisplayName("the map is taught inside RoomActor.onCreateRoom, which every room passes through")
    void creationItselfTeachesTheMap() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/room/RoomActor.java";
        var fromCore = java.nio.file.Path.of("..", rel);
        var src = java.nio.file.Files.readString(
            java.nio.file.Files.exists(fromCore) ? fromCore : java.nio.file.Path.of(rel));
        var create = src.indexOf("private Effect<RoomEvent, RoomState> onCreateRoom(");
        var learn = src.indexOf("ZoneTopology.learnRoom(roomId, cmd.name(), cmd.zone()");
        assertThat(learn).isGreaterThan(create);
    }
}
