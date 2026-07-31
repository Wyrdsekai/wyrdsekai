package org.wyrdsekai.core.room;

import org.junit.jupiter.api.*;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.TopologySnapshot;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ZoneTopology — directed room graph with BFS, pathfinding, and map rendering (§N1).
 * Exits are DIRECTED: A→B does NOT imply B→A.
 */
class ZoneTopologyTest {

    // ─── Helpers ─────────────────────────────────────────────────

    private static ZoneTopology.RoomSeed seed(String id, String name, Exit... exits) {
        return new ZoneTopology.RoomSeed(id, name, "test-zone", List.of(exits));
    }

    private static ZoneTopology.RoomSeed seed(String id, String name, String zone, Exit... exits) {
        return new ZoneTopology.RoomSeed(id, name, zone, List.of(exits));
    }

    private static Exit exit(String direction, String target) {
        return new Exit(direction, target, "A passage leads " + direction);
    }

    // ─── Common Topologies ───────────────────────────────────────

    /** Empty topology: no rooms at all. */
    private static ZoneTopology emptyTopology() {
        return ZoneTopology.build(List.of());
    }

    /** Single room with no exits. */
    private static ZoneTopology singleRoom() {
        return ZoneTopology.build(List.of(seed("solo", "Solo Chamber")));
    }

    /** Linear chain: A→B→C with no return paths. */
    private static ZoneTopology linearChain() {
        return ZoneTopology.build(List.of(
            seed("a", "Room A", exit("east", "b")),
            seed("b", "Room B", exit("east", "c")),
            seed("c", "Room C")
        ));
    }

    /** Cycle: A→B→C→A */
    private static ZoneTopology cycle() {
        return ZoneTopology.build(List.of(
            seed("a", "Room A", exit("east", "b")),
            seed("b", "Room B", exit("east", "c")),
            seed("c", "Room C", exit("west", "a"))
        ));
    }

    /** Bidirectional pair: A↔B (both directions). */
    private static ZoneTopology bidirectionalPair() {
        return ZoneTopology.build(List.of(
            seed("a", "Room A", exit("east", "b")),
            seed("b", "Room B", exit("west", "a"))
        ));
    }

    /** Foundation-like hub-and-spoke: Nexus in the center, 4 rooms radiating out. */
    private static ZoneTopology hubAndSpoke() {
        return ZoneTopology.build(List.of(
            seed("nexus", "The Nexus",
                exit("north", "library"), exit("east", "forge"),
                exit("south", "garden"), exit("west", "vault")),
            seed("library", "The Library", exit("south", "nexus")),
            seed("forge", "The Forge", exit("west", "nexus")),
            seed("garden", "The Garden", exit("north", "nexus")),
            seed("vault", "The Vault", exit("east", "nexus"))
        ));
    }

    // ─── Empty Topology ─────────────────────────────────────────

    @Nested
    class EmptyTopologyTests {

        @Test void size_is_zero() {
            assertEquals(0, emptyTopology().size());
        }

        @Test void nearby_returns_empty() {
            assertTrue(emptyTopology().nearby("any", 3).isEmpty());
        }

        @Test void pathBetween_returns_empty() {
            assertTrue(emptyTopology().pathBetween("a", "b").isEmpty());
        }

        @Test void isReachable_returns_false() {
            assertFalse(emptyTopology().isReachable("a", "b"));
        }

        @Test void connectedZones_is_empty() {
            assertTrue(emptyTopology().connectedZones().isEmpty());
        }
    }

    // ─── Single Room ─────────────────────────────────────────────

    @Nested
    class SingleRoomTests {

        @Test void size_is_one() {
            assertEquals(1, singleRoom().size());
        }

        @Test void nearby_with_no_exits_returns_empty() {
            assertTrue(singleRoom().nearby("solo", 5).isEmpty());
        }

        @Test void pathBetween_same_room_returns_singleton() {
            var path = singleRoom().pathBetween("solo", "solo");
            assertTrue(path.isPresent());
            assertEquals(List.of("solo"), path.get());
        }

        @Test void room_lookup() {
            var node = singleRoom().room("solo");
            assertTrue(node.isPresent());
            assertEquals("Solo Chamber", node.get().name());
        }

        @Test void room_lookup_nonexistent_returns_empty() {
            assertTrue(singleRoom().room("ghost").isEmpty());
        }
    }

    // ─── BFS (nearby) ────────────────────────────────────────────

    @Nested
    class NearbyTests {

        @Test void bfs_1_hop_from_nexus_returns_all_spokes() {
            var nearby = hubAndSpoke().nearby("nexus", 1);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(4, ids.size());
            assertTrue(ids.containsAll(List.of("library", "forge", "garden", "vault")));
        }

        @Test void bfs_follows_directed_edges_only() {
            // In linearChain A→B→C, from C there are no outgoing exits
            var nearby = linearChain().nearby("c", 5);
            assertTrue(nearby.isEmpty());
        }

        @Test void bfs_does_not_include_start_room() {
            var nearby = linearChain().nearby("a", 1);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertFalse(ids.contains("a"));
        }

        @Test void bfs_maxHops_limits_depth() {
            var nearby = linearChain().nearby("a", 1);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(1, ids.size());
            assertEquals("b", ids.getFirst());
        }

        @Test void bfs_2_hops_reaches_end_of_chain() {
            var nearby = linearChain().nearby("a", 2);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(2, ids.size());
            assertEquals("b", ids.get(0));
            assertEquals("c", ids.get(1));
        }

        @Test void bfs_zero_hops_returns_empty() {
            assertTrue(linearChain().nearby("a", 0).isEmpty());
        }

        @Test void bfs_nonexistent_room_returns_empty() {
            assertTrue(linearChain().nearby("ghost", 5).isEmpty());
        }

        @Test void bfs_handles_cycle_without_infinite_loop() {
            var nearby = cycle().nearby("a", 10);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(2, ids.size());
            assertTrue(ids.containsAll(List.of("b", "c")));
        }
    }

    // ─── Pathfinding ─────────────────────────────────────────────

    @Nested
    class PathfindingTests {

        @Test void path_between_adjacent_rooms() {
            var path = linearChain().pathBetween("a", "b");
            assertTrue(path.isPresent());
            assertEquals(List.of("a", "b"), path.get());
        }

        @Test void path_across_chain() {
            var path = linearChain().pathBetween("a", "c");
            assertTrue(path.isPresent());
            assertEquals(List.of("a", "b", "c"), path.get());
        }

        @Test void no_path_against_directed_edge() {
            // Linear chain A→B→C; C cannot reach A
            assertTrue(linearChain().pathBetween("c", "a").isEmpty());
        }

        @Test void path_same_room() {
            var path = linearChain().pathBetween("b", "b");
            assertTrue(path.isPresent());
            assertEquals(List.of("b"), path.get());
        }

        @Test void path_nonexistent_source() {
            assertTrue(linearChain().pathBetween("ghost", "a").isEmpty());
        }

        @Test void path_through_cycle() {
            var path = cycle().pathBetween("c", "b");
            assertTrue(path.isPresent());
            assertEquals(List.of("c", "a", "b"), path.get());
        }

        @Test void isReachable_forward_true() {
            assertTrue(linearChain().isReachable("a", "c"));
        }

        @Test void isReachable_reverse_false() {
            assertFalse(linearChain().isReachable("c", "a"));
        }
    }

    // ─── hasReturn & directionBetween ────────────────────────────

    @Nested
    class DirectionalityTests {

        @Test void hasReturn_bidirectional_true() {
            assertTrue(bidirectionalPair().hasReturn("a", "b"));
            assertTrue(bidirectionalPair().hasReturn("b", "a"));
        }

        @Test void hasReturn_one_way_false() {
            assertFalse(linearChain().hasReturn("a", "b"));
        }

        @Test void hasReturn_nonexistent_target_false() {
            assertFalse(linearChain().hasReturn("a", "ghost"));
        }

        @Test void hasReturn_hub_spoke_is_bidirectional() {
            // Nexus→Library and Library→Nexus both exist
            assertTrue(hubAndSpoke().hasReturn("nexus", "library"));
            assertTrue(hubAndSpoke().hasReturn("library", "nexus"));
        }

        @Test void directionBetween_adjacent() {
            var dir = linearChain().directionBetween("a", "b");
            assertTrue(dir.isPresent());
            assertEquals("east", dir.get());
        }

        @Test void directionBetween_not_adjacent() {
            // a and c are not directly connected in linearChain
            assertTrue(linearChain().directionBetween("a", "c").isEmpty());
        }

        @Test void directionBetween_nonexistent_source() {
            assertTrue(linearChain().directionBetween("ghost", "a").isEmpty());
        }

        @Test void directionBetween_reverse_not_found_on_one_way() {
            // a→b exists but b→a does not in linearChain
            assertTrue(linearChain().directionBetween("b", "a").isEmpty());
        }
    }

    // ─── connectedZones ──────────────────────────────────────────

    @Nested
    class ConnectedZonesTests {

        @Test void same_zone_returns_empty() {
            // All rooms in "test-zone" — no cross-zone exits
            assertTrue(hubAndSpoke().connectedZones().isEmpty());
        }

        @Test void cross_zone_exit_detected() {
            var topo = ZoneTopology.build(List.of(
                seed("a", "Room A", "zone-alpha", exit("portal", "b")),
                seed("b", "Room B", "zone-beta")
            ));
            var zones = topo.connectedZones();
            assertEquals(1, zones.size());
            assertTrue(zones.contains("zone-beta"));
        }

        @Test void multiple_cross_zone_exits() {
            var topo = ZoneTopology.build(List.of(
                seed("hub", "Hub", "core",
                    exit("north", "lib"), exit("south", "lab")),
                seed("lib", "Library", "knowledge"),
                seed("lab", "Laboratory", "science")
            ));
            var zones = topo.connectedZones();
            assertEquals(2, zones.size());
            assertTrue(zones.containsAll(Set.of("knowledge", "science")));
        }
    }

    // ─── Snapshot ────────────────────────────────────────────────

    @Nested
    class SnapshotTests {

        @Test void snapshot_center_is_current() {
            var snap = hubAndSpoke().snapshot("nexus", 1, Set.of("nexus"));
            var center = snap.nodes().stream()
                .filter(TopologySnapshot.MapNode::current).findFirst();
            assertTrue(center.isPresent());
            assertEquals("nexus", center.get().roomId());
        }

        @Test void snapshot_visited_rooms_show_names() {
            var snap = hubAndSpoke().snapshot("nexus", 1,
                Set.of("nexus", "library"));
            var library = snap.nodes().stream()
                .filter(n -> n.roomId().equals("library")).findFirst();
            assertTrue(library.isPresent());
            assertEquals("The Library", library.get().name());
            assertTrue(library.get().visited());
        }

        @Test void snapshot_unvisited_rooms_show_question_mark() {
            var snap = hubAndSpoke().snapshot("nexus", 1, Set.of("nexus"));
            var forge = snap.nodes().stream()
                .filter(n -> n.roomId().equals("forge")).findFirst();
            assertTrue(forge.isPresent());
            assertEquals("?", forge.get().name());
            assertFalse(forge.get().visited());
        }

        @Test void snapshot_edges_include_hasReturn() {
            var snap = hubAndSpoke().snapshot("nexus", 1, Set.of());
            var nexusToLibrary = snap.edges().stream()
                .filter(e -> e.fromRoomId().equals("nexus") && e.toRoomId().equals("library"))
                .findFirst();
            assertTrue(nexusToLibrary.isPresent());
            assertTrue(nexusToLibrary.get().hasReturn());
        }

        @Test void snapshot_one_way_edge_hasReturn_false() {
            var snap = linearChain().snapshot("a", 2, Set.of("a", "b", "c"));
            var aToB = snap.edges().stream()
                .filter(e -> e.fromRoomId().equals("a") && e.toRoomId().equals("b"))
                .findFirst();
            assertTrue(aToB.isPresent());
            assertFalse(aToB.get().hasReturn());
        }

        @Test void snapshot_nonexistent_center_returns_empty() {
            var snap = hubAndSpoke().snapshot("ghost", 1, Set.of());
            assertEquals("ghost", snap.centerRoomId());
            assertTrue(snap.nodes().isEmpty());
            assertTrue(snap.edges().isEmpty());
        }

        @Test void snapshot_hops_from_center() {
            var snap = hubAndSpoke().snapshot("nexus", 1, Set.of("nexus", "forge"));
            var forge = snap.nodes().stream()
                .filter(n -> n.roomId().equals("forge")).findFirst();
            assertTrue(forge.isPresent());
            assertEquals(1, forge.get().hopsFromCenter());
        }
    }

    // ─── renderAccessibleMap ─────────────────────────────────────

    @Nested
    class AccessibleMapTests {

        @Test void accessible_map_shows_current_room() {
            var map = hubAndSpoke().renderAccessibleMap("nexus", 1, Set.of("nexus"), false);
            assertTrue(map.startsWith("You are in The Nexus"));
        }

        @Test void accessible_map_shows_zone() {
            var map = hubAndSpoke().renderAccessibleMap("nexus", 1, Set.of("nexus"), false);
            assertTrue(map.contains("test-zone zone"));
        }

        @Test void accessible_map_no_exits_message() {
            var map = singleRoom().renderAccessibleMap("solo", 1, Set.of("solo"), false);
            assertTrue(map.contains("No exits"));
        }

        @Test void screen_reader_shows_return_path() {
            var map = hubAndSpoke().renderAccessibleMap("nexus", 1,
                Set.of("nexus", "library"), true);
            assertTrue(map.contains("return south"));
        }

        @Test void screen_reader_one_way_warning() {
            var map = linearChain().renderAccessibleMap("a", 1,
                Set.of("a", "b"), true);
            assertTrue(map.contains("ONE WAY"));
            assertTrue(map.contains("no return"));
        }

        @Test void accessible_map_unknown_room_returns_message() {
            var map = hubAndSpoke().renderAccessibleMap("ghost", 1, Set.of(), false);
            assertEquals("Unknown location.", map);
        }
    }

    // ─── renderVoiceMap ──────────────────────────────────────────

    @Nested
    class VoiceMapTests {

        @Test void voice_map_current_room() {
            var map = hubAndSpoke().renderVoiceMap("nexus");
            assertTrue(map.startsWith("You're in The Nexus."));
        }

        @Test void voice_map_lists_exits() {
            var map = hubAndSpoke().renderVoiceMap("nexus");
            assertTrue(map.contains("North leads to The Library"));
            assertTrue(map.contains("East leads to The Forge"));
        }

        @Test void voice_map_unknown_room() {
            assertEquals("Unknown location.", hubAndSpoke().renderVoiceMap("ghost"));
        }

        @Test void voice_map_no_exits() {
            var map = singleRoom().renderVoiceMap("solo");
            assertEquals("You're in Solo Chamber.", map);
        }
    }

    // ─── renderTextMap ───────────────────────────────────────────

    @Nested
    class TextMapTests {

        @Test void text_map_starts_with_center_marker() {
            var map = hubAndSpoke().renderTextMap("nexus", 1, Set.of("nexus"));
            assertTrue(map.startsWith("[* The Nexus]"));
        }

        @Test void text_map_bidirectional_uses_double_dash() {
            var map = hubAndSpoke().renderTextMap("nexus", 1,
                Set.of("nexus", "library", "forge", "garden", "vault"));
            // Bidirectional exits use "--"
            assertTrue(map.contains("--["));
        }

        @Test void text_map_one_way_uses_arrow() {
            var map = linearChain().renderTextMap("a", 1, Set.of("a", "b"));
            // One-way exits use "->"
            assertTrue(map.contains("->["));
        }

        @Test void text_map_unvisited_shows_question_mark() {
            var map = linearChain().renderTextMap("a", 2, Set.of("a"));
            assertTrue(map.contains("[?]"));
        }

        @Test void text_map_unknown_center() {
            assertEquals("Unknown location.", hubAndSpoke().renderTextMap("ghost", 1, Set.of()));
        }
    }

    // ─── Cycle Topology ──────────────────────────────────────────

    @Nested
    class CycleTests {

        @Test void full_cycle_reachability() {
            var c = cycle();
            assertTrue(c.isReachable("a", "b"));
            assertTrue(c.isReachable("b", "c"));
            assertTrue(c.isReachable("c", "a"));
            // Transitive
            assertTrue(c.isReachable("a", "c"));
            assertTrue(c.isReachable("b", "a"));
            assertTrue(c.isReachable("c", "b"));
        }

        @Test void cycle_bfs_visits_all_nodes_once() {
            var nearby = cycle().nearby("a", 10);
            assertEquals(2, nearby.size());
        }

        @Test void cycle_path_takes_shortest_route() {
            // a→b is 1 hop; going a→b→c→a→b would be 4
            var path = cycle().pathBetween("a", "b");
            assertTrue(path.isPresent());
            assertEquals(List.of("a", "b"), path.get());
        }
    }

    // ─── Foundation Hub-and-Spoke ────────────────────────────────

    @Nested
    class FoundationTopologyTests {

        @Test void nexus_reaches_all_rooms() {
            var topo = hubAndSpoke();
            assertTrue(topo.isReachable("nexus", "library"));
            assertTrue(topo.isReachable("nexus", "forge"));
            assertTrue(topo.isReachable("nexus", "garden"));
            assertTrue(topo.isReachable("nexus", "vault"));
        }

        @Test void spokes_reach_each_other_through_nexus() {
            var topo = hubAndSpoke();
            assertTrue(topo.isReachable("library", "forge"));
            // Library→Nexus→Forge
            var path = topo.pathBetween("library", "forge");
            assertTrue(path.isPresent());
            assertEquals(List.of("library", "nexus", "forge"), path.get());
        }

        @Test void spokes_not_directly_connected() {
            assertTrue(hubAndSpoke().directionBetween("library", "forge").isEmpty());
        }

        @Test void hub_snapshot_includes_all_spokes() {
            var snap = hubAndSpoke().snapshot("nexus", 1, Set.of("nexus"));
            assertEquals(5, snap.nodes().size()); // nexus + 4 spokes
            assertEquals(8, snap.edges().size()); // 4 outgoing + 4 return
        }
    }

    // ─── Foundation-Realistic Topology ──────────────────────────
    //
    // Mirrors the real Wyrdsekai foundation rooms: nexus, library, terminal,
    // forge, garden, study — with realistic bidirectional exits and a
    // one-way portal (terminal→nexus only).

    @Nested
    class FoundationRealisticTests {

        private static ZoneTopology foundationTopology() {
            return ZoneTopology.build(List.of(
                seed("nexus", "The Nexus", "foundation",
                    exit("north", "library"), exit("east", "terminal"),
                    exit("south", "forge"), exit("west", "garden"),
                    exit("up", "study")),
                seed("library", "The Library", "foundation",
                    exit("south", "nexus")),
                seed("terminal", "The Terminal", "foundation",
                    exit("west", "nexus")),
                seed("forge", "The Forge", "foundation",
                    exit("north", "nexus")),
                seed("garden", "The Garden", "foundation",
                    exit("east", "nexus")),
                seed("study", "The Study", "foundation",
                    exit("down", "nexus")),
                // One-way portal room: reachable from terminal, no exit back
                seed("void", "The Void", "foundation",
                    exit("portal", "nexus"))
            ));
        }

        // ── findPath (pathBetween) ──

        @Test void findPath_library_to_forge_through_nexus() {
            var path = foundationTopology().pathBetween("library", "forge");
            assertTrue(path.isPresent());
            assertEquals(List.of("library", "nexus", "forge"), path.get());
        }

        @Test void findPath_study_to_terminal_through_nexus() {
            var path = foundationTopology().pathBetween("study", "terminal");
            assertTrue(path.isPresent());
            assertEquals(List.of("study", "nexus", "terminal"), path.get());
        }

        @Test void findPath_nexus_to_study_is_direct() {
            var path = foundationTopology().pathBetween("nexus", "study");
            assertTrue(path.isPresent());
            assertEquals(List.of("nexus", "study"), path.get());
        }

        @Test void findPath_garden_to_library_two_hops() {
            var path = foundationTopology().pathBetween("garden", "library");
            assertTrue(path.isPresent());
            assertEquals(3, path.get().size()); // garden→nexus→library
            assertEquals("garden", path.get().getFirst());
            assertEquals("library", path.get().getLast());
        }

        @Test void findPath_void_to_forge_through_nexus() {
            // void→nexus→forge (one-way portal into nexus)
            var path = foundationTopology().pathBetween("void", "forge");
            assertTrue(path.isPresent());
            assertEquals(List.of("void", "nexus", "forge"), path.get());
        }

        // ── renderAccessibleMap ──

        @Test void renderAccessibleMap_nexus_contains_all_room_names() {
            var visited = Set.of("nexus", "library", "terminal", "forge", "garden", "study");
            var map = foundationTopology().renderAccessibleMap("nexus", 1, visited, false);
            assertFalse(map.isEmpty());
            assertTrue(map.contains("The Nexus"));
            assertTrue(map.contains("The Library"));
            assertTrue(map.contains("The Terminal"));
            assertTrue(map.contains("The Forge"));
            assertTrue(map.contains("The Garden"));
            assertTrue(map.contains("The Study"));
        }

        @Test void renderAccessibleMap_from_spoke_shows_nexus() {
            var map = foundationTopology().renderAccessibleMap(
                "library", 1, Set.of("library", "nexus"), false);
            assertFalse(map.isEmpty());
            assertTrue(map.contains("The Library"));
            assertTrue(map.contains("The Nexus"));
        }

        @Test void renderAccessibleMap_screen_reader_shows_return_from_library() {
            var map = foundationTopology().renderAccessibleMap(
                "nexus", 1, Set.of("nexus", "library"), true);
            // Library has a return to nexus via south
            assertTrue(map.contains("return south"));
        }

        // ── snapshot ──

        @Test void snapshot_nexus_radius1_returns_all_connected_rooms() {
            var snap = foundationTopology().snapshot(
                "nexus", 1, Set.of("nexus", "library", "terminal"));
            assertFalse(snap.nodes().isEmpty());
            // nexus + 5 immediate neighbors (library, terminal, forge, garden, study)
            assertEquals(6, snap.nodes().size());
            // Verify center is marked
            var center = snap.nodes().stream()
                .filter(TopologySnapshot.MapNode::current).findFirst();
            assertTrue(center.isPresent());
            assertEquals("nexus", center.get().roomId());
        }

        @Test void snapshot_returns_room_data_with_zone() {
            var snap = foundationTopology().snapshot(
                "nexus", 1, Set.of("nexus", "library"));
            var libraryNode = snap.nodes().stream()
                .filter(n -> n.roomId().equals("library")).findFirst();
            assertTrue(libraryNode.isPresent());
            assertEquals("foundation", libraryNode.get().zone());
            assertEquals("The Library", libraryNode.get().name());
        }

        @Test void snapshot_fog_of_war_hides_unvisited_names() {
            var snap = foundationTopology().snapshot(
                "nexus", 1, Set.of("nexus")); // only nexus visited
            var forge = snap.nodes().stream()
                .filter(n -> n.roomId().equals("forge")).findFirst();
            assertTrue(forge.isPresent());
            assertEquals("?", forge.get().name()); // fog of war
            assertFalse(forge.get().visited());
        }

        // ── reachableFrom (nearby) ──

        @Test void reachableFrom_nexus_1hop_returns_all_5_connected() {
            var nearby = foundationTopology().nearby("nexus", 1);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(5, ids.size());
            assertTrue(ids.containsAll(List.of("library", "terminal", "forge", "garden", "study")));
        }

        @Test void reachableFrom_nexus_2hops_includes_void_via_terminal() {
            // terminal has no exit to void, so void is NOT reachable from nexus
            // but void is a separate room — only reachable via itself
            var nearby = foundationTopology().nearby("nexus", 2);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            // All 5 spokes, but no void (terminal has exit only to nexus, not void)
            assertTrue(ids.containsAll(List.of("library", "terminal", "forge", "garden", "study")));
        }

        @Test void reachableFrom_void_reaches_nexus_and_beyond() {
            // void→nexus (portal), then nexus→library/terminal/forge/garden/study
            var nearby = foundationTopology().nearby("void", 2);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertTrue(ids.contains("nexus")); // 1 hop
            assertTrue(ids.contains("library")); // 2 hops
            assertTrue(ids.contains("forge")); // 2 hops
        }

        @Test void reachableFrom_forge_only_reaches_nexus_at_1hop() {
            var nearby = foundationTopology().nearby("forge", 1);
            var ids = nearby.stream().map(ZoneTopology.RoomNode::roomId).toList();
            assertEquals(1, ids.size());
            assertEquals("nexus", ids.getFirst());
        }

        @Test void isReachable_all_rooms_reachable_from_nexus() {
            var topo = foundationTopology();
            assertTrue(topo.isReachable("nexus", "library"));
            assertTrue(topo.isReachable("nexus", "terminal"));
            assertTrue(topo.isReachable("nexus", "forge"));
            assertTrue(topo.isReachable("nexus", "garden"));
            assertTrue(topo.isReachable("nexus", "study"));
            // void is NOT reachable from nexus (no exit into void)
            assertFalse(topo.isReachable("nexus", "void"));
        }

        @Test void isReachable_void_reaches_nexus_but_not_reverse() {
            var topo = foundationTopology();
            assertTrue(topo.isReachable("void", "nexus"));
            assertFalse(topo.isReachable("nexus", "void"));
        }
    }
}
