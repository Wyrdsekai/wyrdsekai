package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.agent.EntityRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map shows who is where. Everyone by name in public rooms; a private room says the
 * kind of place and never its name (2026-09-13: "I'd like to go find them and talk to them").
 */
class MapShowsWhoIsWhereTest {

    @Test
    @DisplayName("private rooms are known by kind, public rooms by nothing")
    void privacyKinds() {
        assertEquals("at home", RoomPrivacy.whereabouts("home-companion-wisp"));
        assertEquals("in their Study", RoomPrivacy.whereabouts("study-1f56a2d4"));
        assertEquals("resting", RoomPrivacy.whereabouts("sanctuary"));
        assertNull(RoomPrivacy.whereabouts("nexus"));
        assertNull(RoomPrivacy.whereabouts("the-garden-4421"));
        assertNull(RoomPrivacy.whereabouts("parlor"));
        assertFalse(RoomPrivacy.isPrivate("docks"));
    }

    @Test
    @DisplayName("the registry answers who stands in each room")
    void registryOccupants() {
        EntityRegistry.init();
        var r = EntityRegistry.get();
        r.enter("companion-wisp", "Wisp", "agent", "the-garden");
        r.enter("u-steward", "Steward", "player", "the-garden");
        r.enter("u-guest", "Guest (visitor, MCP)", "visitor", "parlor");
        r.enter("companion-ember", "Ember", "agent", "home-companion-ember");
        var by = r.occupantsByRoom();
        assertEquals(2, by.get("the-garden").size());
        assertTrue(by.get("the-garden").stream().anyMatch(o -> o.name().equals("Wisp") && o.isAgent()));
        assertTrue(by.get("parlor").getFirst().isVisitor());
        assertEquals("visitor", r.typeOf("u-guest"));
        r.remove("companion-wisp"); r.remove("u-steward"); r.remove("u-guest"); r.remove("companion-ember");
    }

    @Test
    @DisplayName("the rendered map carries names beside public rooms and a footer for private ones")
    void renderedMap() {
        var topo = ZoneTopology.build(Map.of(
            "nexus", new ZoneTopology.RoomNode("nexus", "The Nexus", "home", List.of(new Exit("north", "the-garden", "to the garden"))),
            "the-garden", new ZoneTopology.RoomNode("the-garden", "The Garden", "home", List.of(new Exit("south", "nexus", "back")))));
        Function<String, String> occupants = id -> switch (id) {
            case "the-garden" -> "Wisp, Steward";
            case "*elsewhere*" -> "Elsewhere: Ember (at home)";
            default -> null;
        };
        var text = topo.renderTextMap("nexus", 2, Set.of("nexus", "the-garden"), occupants);
        assertTrue(text.contains("[The Garden]  — Wisp, Steward"), text);
        assertTrue(text.endsWith("Elsewhere: Ember (at home)"), text);
        assertFalse(text.contains("home-companion"), "a private room is never named: " + text);
        var plain = topo.renderTextMap("nexus", 2, Set.of("nexus", "the-garden"));
        assertFalse(plain.contains("—"), "the plain render is unchanged: " + plain);
    }
}
