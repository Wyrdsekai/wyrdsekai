package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map's people, shared by every surface. The ssh and telnet handlers rendered maps with
 * no occupants for a day after the web client got them (2026-09-14) — one helper now.
 */
class MapOccupantsTest {

    private static ZoneTopology topo() {
        return ZoneTopology.build(Map.of(
            "nexus", new ZoneTopology.RoomNode("nexus", "The Nexus", "foundation",
                List.of(new Exit("east", "docks", "The Docks"))),
            "docks", new ZoneTopology.RoomNode("docks", "The Docks", "foundation", List.of()),
            "home-companion-wisp", new ZoneTopology.RoomNode("home-companion-wisp", "Wisp's Home", "home", List.of())));
    }

    @Test
    @DisplayName("public rooms name everyone; private rooms go to the footer by kind; the viewer is 'you'")
    void occupantsAndFooter() {
        EntityRegistry.init();
        var r = EntityRegistry.get();
        r.enter("u-kaz", "Kazuo", "player", "nexus");
        r.enter("companion-ember", "Ember", "companion", "nexus");
        r.enter("companion-wisp", "Wisp", "companion", "home-companion-wisp");
        r.enter("u-mei", "Mei", "player", "study-1f56a2d4");

        var f = MapOccupants.forViewer("u-kaz");
        assertEquals("you, Ember", f.apply("nexus"));
        assertNull(f.apply("docks"));
        assertNull(f.apply("home-companion-wisp"), "a private room is never labelled on the map");
        var footer = f.apply(MapOccupants.ELSEWHERE);
        assertTrue(footer.startsWith("Elsewhere: "), footer);
        assertTrue(footer.contains("Wisp (at home)"), footer);
        assertTrue(footer.contains("Mei (in their Study)"), footer);

        var rendered = topo().renderTextMap("nexus", 1, topo().rooms().keySet(), f);
        assertTrue(rendered.contains("you, Ember"), rendered);
        assertTrue(rendered.contains("Elsewhere:"), rendered);
    }

    @Test
    @DisplayName("where <name> answers a public room by name and a private one by kind")
    void whereIs() {
        EntityRegistry.init();
        var r = EntityRegistry.get();
        r.enter("companion-ember", "Ember", "companion", "docks");
        r.enter("companion-wisp", "Wisp", "companion", "home-companion-wisp");
        assertEquals("Ember is in The Docks.", MapOccupants.whereIs("ember", topo()));
        assertEquals("Wisp is at home.", MapOccupants.whereIs("Wisp", topo()));
        assertEquals("Nobody here goes by 'nobody'.", MapOccupants.whereIs("nobody", topo()));
    }
}
