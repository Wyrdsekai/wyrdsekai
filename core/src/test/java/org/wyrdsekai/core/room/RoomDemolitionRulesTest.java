package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.persistence.RoomMetadataService;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a steward may take down, and what stays no matter who asks. */
class RoomDemolitionRulesTest {

    private static RoomMetadataService.RoomInfo made(String id, String by) {
        return new RoomMetadataService.RoomInfo(id, id, "home", by, 1L);
    }

    @Test
    @DisplayName("a made room may go; founding rooms, Homes and unrecorded rooms stay")
    void rules() {
        var founding = Set.of("nexus", "docks", "vault");
        assertNull(RoomDemolition.refusal("the-garden-4421", made("the-garden-4421", "system"), founding));
        assertNull(RoomDemolition.refusal("holding-room-258449800", made("holding-room-258449800", "companion-mia"), founding));
        assertNotNull(RoomDemolition.refusal("nexus", made("nexus", "system"), founding), "seeded");
        assertNotNull(RoomDemolition.refusal("home-companion-mia", made("home-companion-mia", "system"), founding), "her Home");
        assertNotNull(RoomDemolition.refusal("study-1f56", made("study-1f56", "system"), founding), "a person's Study");
        assertNotNull(RoomDemolition.refusal("workshop-codeplane-1f56", made("workshop-codeplane-1f56", "system"), founding), "a person's Workshop");
        assertNotNull(RoomDemolition.refusal("the-loft-12", null, founding), "no record");
        assertNotNull(RoomDemolition.refusal("the-garden-4421", made("the-garden-4421", null), founding), "no maker recorded");
        assertNotNull(RoomDemolition.refusal(null, null, founding));
        assertNotNull(RoomDemolition.refusal(" ", null, founding));
    }

    @Test
    @DisplayName("a room with things in it is not demolished blind; --with-objects takes them down with it")
    void objects() {
        var thing = new org.wyrdsekai.common.model.RoomObject("workbench", "workbench", "hers", false, true, false, java.util.List.of(), java.util.Map.of());
        var full = new org.wyrdsekai.common.model.RoomSnapshot("the-hub", "the hub", "", "home", java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(thing, thing), java.util.List.of());
        var empty = new org.wyrdsekai.common.model.RoomSnapshot("the-hub", "the hub", "", "home", java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertNotNull(RoomDemolition.objectsRefusal(full, false));
        assertEquals("It holds 2 objects — take them out first, or pass --with-objects to demolish them with it.",
            RoomDemolition.objectsRefusal(full, false));
        assertNull(RoomDemolition.objectsRefusal(full, true));
        assertNull(RoomDemolition.objectsRefusal(empty, false));
        assertNull(RoomDemolition.objectsRefusal(null, false));
    }

    @Test
    @DisplayName("an occupied room names who is in it")
    void occupants() {
        var snap = new RoomSnapshot("the-garden-4421", "The Garden", "", "home", List.of(),
            List.of(new Entity("companion-mia", "Mia", "companion", "", null, List.of(), null)), List.of(), List.of());
        assertEquals(List.of("Mia"), RoomDemolition.occupants(snap));
        assertTrue(RoomDemolition.occupants(new RoomSnapshot("x", "x", "", "home", List.of(), List.of(), List.of(), List.of())).isEmpty());
        assertTrue(RoomDemolition.occupants(null).isEmpty());
    }
}
