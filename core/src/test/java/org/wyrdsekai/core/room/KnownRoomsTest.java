package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The way back to a room she made, from anywhere in the zone. */
class KnownRoomsTest {

    @BeforeEach
    void zone() {
        RoomRegistry.get().clear();
        ZoneTopology.setShared(ZoneTopology.build(List.of(
            new ZoneTopology.RoomSeed("nexus", "The Nexus", "hearth", List.of(
                new Exit("to-the-forge-of-quiet-things-2171", "the-forge-of-quiet-things-2171", "A path to The Forge of Quiet Things"),
                new Exit("east", "library", "The Library"))),
            new ZoneTopology.RoomSeed("library", "The Library", "hearth", List.of(new Exit("west", "nexus", "The Nexus"))),
            new ZoneTopology.RoomSeed("the-forge-of-quiet-things-2171", "The Forge of Quiet Things", "companion-created", List.of(new Exit("out", "nexus", "Back to nexus"))))));
        RoomRegistry.get().registerAliases("the-forge-of-quiet-things-2171", List.of("The Forge of Quiet Things"));
        RoomRegistry.get().registerAliases("library", List.of("The Library"));
        RoomRegistry.get().registerAliases("nexus", List.of("The Nexus"));
    }

    @AfterEach
    void reset() { RoomRegistry.get().clear(); ZoneTopology.resetForTests(); }

    @Test
    void a_room_two_doors_away_is_found_by_name_with_the_doors_between() {
        var f = KnownRooms.find("The Forge of Quiet Things", "library", null).orElseThrow();
        assertEquals("the-forge-of-quiet-things-2171", f.roomId());
        assertEquals("The Forge of Quiet Things", f.name());
        assertEquals(List.of("library", "nexus", "the-forge-of-quiet-things-2171"), f.path());
        assertEquals(2, f.hops());
    }

    @Test
    void the_name_she_remembers_may_carry_the_description_she_gave_it() {
        var f = KnownRooms.find("the forge of quiet things — a small room where things that haven't found their shape yet can sit before they are made", "library", null);
        assertTrue(f.isPresent(), "the head of the name resolves when the whole does not");
        assertEquals("the-forge-of-quiet-things-2171", f.get().roomId());
    }

    @Test
    void the_room_she_is_in_and_a_name_nobody_holds_are_not_found_but_her_last_room_is() {
        assertTrue(KnownRooms.find("The Forge of Quiet Things", "the-forge-of-quiet-things-2171", null).isEmpty());
        assertTrue(KnownRooms.find("the greenhouse", "library", null).isEmpty());
        assertTrue(KnownRooms.find("  ", "library", null).isEmpty());
        // an ambiguous part of a name resolves to nothing rather than a guess…
        RoomRegistry.get().registerAliases("the-forge-of-loud-things-9", List.of("The Forge of Loud Things"));
        assertTrue(KnownRooms.find("the forge", "library", null).isEmpty());
        // …unless it is the room she made last, whose name the target names
        var f = KnownRooms.find("the forge of quiet things, where I left the drafts", "library", "the-forge-of-quiet-things-2171");
        assertTrue(f.isPresent());
    }

    @Test
    void a_same_named_room_is_recognised_before_she_makes_it_again() {
        assertEquals("the-forge-of-quiet-things-2171", KnownRooms.sameNamed("The Forge of Quiet Things", "library").orElseThrow());
        assertEquals("the-forge-of-quiet-things-2171", KnownRooms.sameNamed("the forge of quiet things — a small room where things rest", "library").orElseThrow());
        assertTrue(KnownRooms.sameNamed("The Forge", "library").isEmpty(), "a part of a name is a different room");
        assertTrue(KnownRooms.sameNamed("The Forge of Quiet Things", "the-forge-of-quiet-things-2171").isEmpty(), "she is standing in it");
    }
}
