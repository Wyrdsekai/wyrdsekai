package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A demolished room leaves the map with every doorway that led to it. */
class ZoneTopologyForgetRoomTest {

    @AfterEach
    void tearDown() { ZoneTopology.resetForTests(); }

    private static final String LONG = "to-the-forge-of-quiet-things-a-small-room-where-things-that-haven-t-found-their-shape-7040";

    @Test
    @DisplayName("forgetRoom drops the node and the exits into it, and nothing else")
    void forget() {
        ZoneTopology.setShared(ZoneTopology.build(Map.of(
            "nexus", new ZoneTopology.RoomNode("nexus", "The Nexus", "foundation", List.of(
                new Exit("east", "docks", "The Docks"), new Exit(LONG, "junk-7040", "junk"))),
            "docks", new ZoneTopology.RoomNode("docks", "The Docks", "foundation", List.of(
                new Exit("to-junk", "junk-7040", "junk"))),
            "junk-7040", new ZoneTopology.RoomNode("junk-7040", "junk", "foundation", List.of(
                new Exit("back", "nexus", "The Nexus"))))));
        var before = ZoneTopology.getShared();
        assertEquals(2, before.exitsInto("junk-7040").size());
        ZoneTopology.forgetRoom("junk-7040");
        var after = ZoneTopology.getShared();
        assertTrue(after.room("junk-7040").isEmpty());
        assertEquals(0, after.exitsInto("junk-7040").size());
        assertEquals(1, after.room("nexus").get().exits().size());
        assertEquals(0, after.room("docks").get().exits().size());
        assertTrue(after.room("docks").isPresent());
        ZoneTopology.forgetRoom("never-existed");
        assertEquals(after, ZoneTopology.getShared(), "nothing to forget, nothing rebuilt");
    }

    @Test
    @DisplayName("auto-generated to-<id> keys are shown by their head on the map")
    void longKeysShortened() {
        assertEquals("to-the-forge-of-quiet-things-…", ZoneTopology.displayDirection(LONG));
        assertEquals("to-reading-corner-3866", ZoneTopology.displayDirection("to-reading-corner-3866"));
        assertEquals("north", ZoneTopology.displayDirection("north"));
        var topo = ZoneTopology.build(Map.of(
            "nexus", new ZoneTopology.RoomNode("nexus", "The Nexus", "foundation", List.of(new Exit(LONG, "junk-7040", "junk"))),
            "junk-7040", new ZoneTopology.RoomNode("junk-7040", "junk", "foundation", List.of())));
        var text = topo.renderTextMap("nexus", 1, topo.rooms().keySet());
        assertTrue(text.contains("to-the-forge-of-quiet-things-…"), text);
        assertFalse(text.contains(LONG), text);
    }
}
