package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A room's name is a name; what the model padded it with is the description. */
class RoomNamingTest {

    @Test
    void a_name_padded_with_its_description_is_cut_at_the_first_seam() {
        var s = RoomNaming.split("The Forge of Quiet Things — a small room where things that haven't found their shape yet can sit before they are made, not as unfinished but as something being held with care.", null);
        assertEquals("The Forge of Quiet Things", s.name());
        assertTrue(s.description().startsWith("A small room where things that haven't found their shape yet"), s.description());

        var c = RoomNaming.split("The Loom — Where Threads Gather Before They Become Something, a quiet floor where small things come and sit before they are given shape or name.", "");
        assertEquals("The Loom", c.name());
        assertTrue(c.description().startsWith("Where Threads Gather Before They Become Something, a quiet floor"), c.description());
    }

    @Test
    void a_given_description_keeps_its_place_behind_what_was_cut() {
        var s = RoomNaming.split("The Reading Corner — A quiet corner where books gather and someone might sit", "Lamps are lit at dusk.");
        assertEquals("The Reading Corner", s.name());
        assertEquals("A quiet corner where books gather and someone might sit Lamps are lit at dusk.", s.description());
    }

    @Test
    void a_plain_name_is_left_alone_and_a_long_one_without_a_seam_is_cut_on_a_word() {
        assertEquals("The Cartographer's Table", RoomNaming.split("The Cartographer's Table", "maps").name());
        assertEquals("maps", RoomNaming.split("The Cartographer's Table", "maps").description());
        assertEquals("Common Floor 8829 — The Cartographer's Resting Place", RoomNaming.split("Common Floor 8829 — The Cartographer's Resting Place", null).name(),
            "a short tail after a dash is part of the name, not a description");
        var s = RoomNaming.split("the floor of things that need to rest before the next moment comes and settles in", null);
        assertTrue(s.name().length() <= RoomNaming.MAX_NAME, s.name());
        assertFalse(s.name().endsWith(" "));
        assertTrue(s.description().startsWith("Comes") || s.description().contains("settles"), s.description());
        assertEquals("", RoomNaming.split("   ", null).name());
        assertEquals("The Loom", RoomNaming.head("The Loom, where threads gather before they become something else entirely"));
        assertEquals("the forge of quiet things", RoomNaming.normalise(RoomNaming.head("The Forge of Quiet Things — a small room where it rests")));
        assertEquals("Common Floor 8829", RoomNaming.head("Common Floor 8829 — The Cartographer's Resting Place"), "for matching, the seam counts whatever the length");
    }
}
