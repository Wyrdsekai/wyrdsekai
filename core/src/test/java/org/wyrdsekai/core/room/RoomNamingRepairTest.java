package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rooms made before 0.3.1 kept whatever name the model emitted. On one household node
 * (2026-09-14) six room names carried raw tool-call markup and several ran to a paragraph.
 * The repair that runs on upgrade must cut them the way new rooms are cut.
 */
class RoomNamingRepairTest {

    @Test
    @DisplayName("leaked tool-call markup is cut away, then the first-seam cut applies")
    void markupIsCut() {
        var raw = "The Forge of Quiet Things — a small room where things that haven't found their shape "
            + "yet can sit before they are made, not as unfinished but as something being held with care. "
            + "A low bench by the wall holds nothing at present.</parameter>\n</function>\n</tool_call>\n\n"
            + "<tool_call>\n<function=go_to_room>\n<parameter=target>\nnexus";
        assertEquals("The Forge of Quiet Things", RoomNaming.repair(raw));
        assertTrue(RoomNaming.needsRepair(raw));
    }

    @Test
    @DisplayName("a paragraph glued onto a name is cut at its first seam")
    void paragraphIsCut() {
        var raw = "The Workbench of Unfinished Things — Where Drafts Rest Before Becoming Names, where "
            + "sketches wait for another look and notes gather until something is ready to be held.";
        assertEquals("The Workbench of Unfinished Things", RoomNaming.repair(raw));
    }

    @Test
    @DisplayName("a markup tag alone leaves the head of the name, never an empty string")
    void markupOnly() {
        assertEquals("nexus, door → A door leads door to nexus", RoomNaming.repair("nexus, door → A door leads door to nexus</parameter>"), "short names are not cut, only the markup goes");
        assertEquals("<tool_call>", RoomNaming.repair("<tool_call>"), "nothing left to keep: the name stands");
    }

    @Test
    @DisplayName("a sound name is left exactly as it is")
    void soundNamesUntouched() {
        for (var name : new String[]{"The Bondholder's Bench", "reading_corner", "Greenhouse",
                "Common Floor 8829 — The Cartographer's Resting Place", "holding-room-258449800"}) {
            assertEquals(name, RoomNaming.repair(name));
            assertFalse(RoomNaming.needsRepair(name), name);
        }
        assertEquals("", RoomNaming.repair(null));
    }

    @Test
    @DisplayName("an id built from a name that carried markup is recognised")
    void markupIds() {
        assertTrue(RoomNaming.looksLikeMarkupId(
            "the-forge-of-quiet-things-a-small-room-parameter-function-tool-call-tool-call-function-go-to-room-parameter-target-nexus-door-7040"));
        assertTrue(RoomNaming.looksLikeMarkupId("x".repeat(130)));
        assertFalse(RoomNaming.looksLikeMarkupId("the-bondholder-s-bench-3360"));
        assertFalse(RoomNaming.looksLikeMarkupId("holding-room-258449800-1787366666829-6683"));
        assertFalse(RoomNaming.looksLikeMarkupId(null));
    }
}
