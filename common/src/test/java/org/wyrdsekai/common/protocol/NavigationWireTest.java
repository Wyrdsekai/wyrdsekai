package org.wyrdsekai.common.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.model.TopologySnapshot;
import org.wyrdsekai.common.model.TopologySnapshot.MapEdge;
import org.wyrdsekai.common.model.TopologySnapshot.MapNode;
import org.wyrdsekai.common.util.Json;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wire protocol roundtrip tests for navigation messages (§N6).
 * Tests JSON serialization/deserialization of TopologyChanged, MapData, MapRequest.
 */
class NavigationWireTest {

    private static final ObjectMapper mapper = Json.mapper();

    // ── S2C: TopologyChanged ──

    @Test
    void topology_changed_roundtrip() throws Exception {
        var msg = new S2CMessage.TopologyChanged(
            42, "exit_opened", "nexus", "east", "garden",
            "A new passage has opened to the east, leading to The Garden.");

        var json = mapper.writeValueAsString(msg);
        var parsed = mapper.readValue(json, S2CMessage.class);

        assertInstanceOf(S2CMessage.TopologyChanged.class, parsed);
        var tc = (S2CMessage.TopologyChanged) parsed;
        assertEquals(42, tc.seq());
        assertEquals("exit_opened", tc.changeType());
        assertEquals("nexus", tc.roomId());
        assertEquals("east", tc.direction());
        assertEquals("garden", tc.targetRoomId());
        assertTrue(tc.description().contains("Garden"));
    }

    @Test
    void topology_changed_exit_closed() throws Exception {
        var msg = new S2CMessage.TopologyChanged(
            43, "exit_closed", "nexus", "south", "pit",
            "The passage south has collapsed.");

        var json = mapper.writeValueAsString(msg);
        var parsed = mapper.readValue(json, S2CMessage.class);

        assertInstanceOf(S2CMessage.TopologyChanged.class, parsed);
        assertEquals("exit_closed", ((S2CMessage.TopologyChanged) parsed).changeType());
    }

    @Test
    void topology_changed_null_direction() throws Exception {
        var msg = new S2CMessage.TopologyChanged(
            44, "room_created", "garden", null, null,
            "A new room has appeared: The Garden.");

        var json = mapper.writeValueAsString(msg);
        var parsed = (S2CMessage.TopologyChanged) mapper.readValue(json, S2CMessage.class);
        assertNull(parsed.direction());
        assertNull(parsed.targetRoomId());
    }

    // ── S2C: MapData ──

    @Test
    void map_data_with_topology_roundtrip() throws Exception {
        var snapshot = new TopologySnapshot("nexus",
            List.of(
                new MapNode("nexus", "The Nexus", "foundation", true, true, 0),
                new MapNode("terminal", "The Terminal", "foundation", false, true, 1),
                new MapNode("docks", "?", "foundation", false, false, 1)
            ),
            List.of(
                new MapEdge("nexus", "terminal", "north", "A corridor leads north", true),
                new MapEdge("nexus", "docks", "east", "An archway opens east", false)
            ));

        var msg = new S2CMessage.MapData(10, "map",
            "[* Nexus]\n├── north--[Terminal]\n└── east->[?]",
            snapshot, null);

        var json = mapper.writeValueAsString(msg);
        var parsed = mapper.readValue(json, S2CMessage.class);

        assertInstanceOf(S2CMessage.MapData.class, parsed);
        var md = (S2CMessage.MapData) parsed;
        assertEquals(10, md.seq());
        assertEquals("map", md.command());
        assertTrue(md.textMap().contains("Nexus"));
        assertNotNull(md.topology());
        assertEquals("nexus", md.topology().centerRoomId());
        assertEquals(3, md.topology().nodes().size());
        assertEquals(2, md.topology().edges().size());
        assertNull(md.path());
    }

    @Test
    void map_data_edge_has_return_flag() throws Exception {
        var edge = new MapEdge("a", "b", "north", "North to B", true);
        var json = mapper.writeValueAsString(edge);
        var parsed = mapper.readValue(json, MapEdge.class);
        assertTrue(parsed.hasReturn());

        var oneWay = new MapEdge("a", "b", "down", "Fall into pit", false);
        var json2 = mapper.writeValueAsString(oneWay);
        var parsed2 = mapper.readValue(json2, MapEdge.class);
        assertFalse(parsed2.hasReturn());
    }

    @Test
    void map_data_path_command() throws Exception {
        var msg = new S2CMessage.MapData(11, "path",
            "Nexus → north → Terminal → east → Workshop",
            null, List.of("nexus", "terminal", "workshop"));

        var json = mapper.writeValueAsString(msg);
        var parsed = (S2CMessage.MapData) mapper.readValue(json, S2CMessage.class);
        assertEquals("path", parsed.command());
        assertNotNull(parsed.path());
        assertEquals(3, parsed.path().size());
        assertEquals("nexus", parsed.path().getFirst());
        assertEquals("workshop", parsed.path().getLast());
    }

    @Test
    void map_data_where_command() throws Exception {
        var msg = new S2CMessage.MapData(12, "where",
            "You are in The Nexus (Foundation zone). 10 exits available.",
            null, null);

        var json = mapper.writeValueAsString(msg);
        var parsed = (S2CMessage.MapData) mapper.readValue(json, S2CMessage.class);
        assertEquals("where", parsed.command());
        assertTrue(parsed.textMap().contains("Nexus"));
    }

    // ── S2C: TopologySnapshot ──

    @Test
    void topology_snapshot_empty() throws Exception {
        var snapshot = TopologySnapshot.empty("unknown");
        var json = mapper.writeValueAsString(snapshot);
        var parsed = mapper.readValue(json, TopologySnapshot.class);
        assertEquals("unknown", parsed.centerRoomId());
        assertTrue(parsed.nodes().isEmpty());
        assertTrue(parsed.edges().isEmpty());
    }

    @Test
    void map_node_roundtrip() throws Exception {
        var node = new MapNode("nexus", "The Nexus", "foundation", true, true, 0);
        var json = mapper.writeValueAsString(node);
        var parsed = mapper.readValue(json, MapNode.class);
        assertEquals("nexus", parsed.roomId());
        assertEquals("The Nexus", parsed.name());
        assertTrue(parsed.current());
        assertTrue(parsed.visited());
        assertEquals(0, parsed.hopsFromCenter());
    }

    // ── C2S: MapRequest ──

    @Test
    void map_request_roundtrip() throws Exception {
        var msg = new C2SMessage.MapRequest("req-1", "map", 3, null);
        var json = mapper.writeValueAsString(msg);
        var parsed = mapper.readValue(json, C2SMessage.class);

        assertInstanceOf(C2SMessage.MapRequest.class, parsed);
        var mr = (C2SMessage.MapRequest) parsed;
        assertEquals("req-1", mr.id());
        assertEquals("map", mr.command());
        assertEquals(3, mr.radius());
        assertNull(mr.target());
    }

    @Test
    void map_request_path_command() throws Exception {
        var msg = new C2SMessage.MapRequest("req-2", "path", 0, "The Library");
        var json = mapper.writeValueAsString(msg);
        var parsed = (C2SMessage.MapRequest) mapper.readValue(json, C2SMessage.class);
        assertEquals("path", parsed.command());
        assertEquals("The Library", parsed.target());
    }
}
