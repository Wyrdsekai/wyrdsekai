package org.wyrdsekai.core.room;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.TopologySnapshot;
import org.wyrdsekai.common.model.TopologySnapshot.MapEdge;
import org.wyrdsekai.common.model.TopologySnapshot.MapNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Queryable directed graph of room topology within a zone (§N1).
 * Built from live room state. Exits are one-way by default — A→B does NOT imply B→A.
 *
 * Thread-safe: immutable once built. Rebuild on topology change.
 */
public final class ZoneTopology {

    /** A room node with its outgoing exits. */
    public record RoomNode(String roomId, String name, String zone, List<Exit> exits) {}

    private final Map<String, RoomNode> rooms;
    // Reverse index: targetRoomId → set of source roomIds that have exits pointing to it
    private final Map<String, Set<String>> reverseIndex;

    private ZoneTopology(Map<String, RoomNode> rooms) {
        this.rooms = Map.copyOf(rooms);

        // Build reverse index for hasReturn checks
        var reverse = new HashMap<String, Set<String>>();
        for (var node : rooms.values()) {
            for (var exit : node.exits()) {
                reverse.computeIfAbsent(exit.targetRoom(), _ -> new HashSet<>())
                    .add(node.roomId());
            }
        }
        this.reverseIndex = Map.copyOf(reverse.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))));
    }

    /** Build topology from a map of roomId → RoomNode. */
    public static ZoneTopology build(Map<String, RoomNode> rooms) {
        return new ZoneTopology(rooms);
    }

    // Process-wide singleton so transports (SSH, Telnet, WebSocket) share
    // one topology snapshot. Set once by Main.java after foundation-room
    // seeding; rebuilt on topology mutations by whoever holds the ref.
    private static volatile ZoneTopology INSTANCE;

    public static ZoneTopology getShared() { return INSTANCE; }

    /**
     * Rooms that announced themselves before the topology existed.
     *
     * <p>Boot order is: ZoneGuardian starts and every persisted room actor recovers, and
     * only later does {@code Main} build and publish the topology. So the rooms teaching
     * the map about themselves were all talking to a null INSTANCE and being dropped —
     * which is why, after the fix that was supposed to make a companion-made room appear,
     * the map still rendered it {@code ->[?]} across a restart. Held here and merged in.
     */
    private static final Map<String, RoomNode> PENDING = new LinkedHashMap<>();

    public static synchronized void setShared(ZoneTopology topology) {
        if (topology != null && !PENDING.isEmpty()) {
            var map = new HashMap<>(topology.rooms);
            // Seeds keep their canonical exits FIRST, but a recovered room's other
            // exits are kept too. "Seeds win" used to mean the Nexus's 17 recovered
            // exits were replaced by its 9 seeded ones, so every room a companion or
            // person had ever made was a node with no way in — walkable, and off
            // the map on every boot after the one that made it (household node,
            // 2026-09-01: "multiple exits in the Nexus that are not on the map").
            PENDING.forEach((id, recovered) -> map.merge(id, recovered,
                (seed, rec) -> new RoomNode(seed.roomId(), seed.name(), seed.zone(),
                    unionExits(seed.exits(), rec.exits()))));
            PENDING.clear();
            INSTANCE = new ZoneTopology(map);
            return;
        }
        INSTANCE = topology;
    }

    /**
     * Teach the shared topology about a room that did not exist at boot.
     *
     * <h2>Why the map showed a question mark</h2>
     * {@code Main} builds this once, from the hardcoded foundation seeds, and the class
     * comment has always said it is "rebuilt on topology mutations by whoever holds the
     * ref" — nobody ever did. So a room the companion made was walkable, had furnishings
     * and had a way back, and the map still rendered it
     * {@code ├── to-venture-briefing-room-1931->[?]}: an unnamed destination reached by a
     * one-way arrow, because the topology had never heard of it. Live 2026-08-22, after
     * the steward asked whether the map updates.
     *
     * <p>Also registers the inbound exit on the source room, so the connection reads as
     * two-way ({@code --}) rather than one-way ({@code ->}).
     *
     * <p>No-op before {@link #setShared} — during boot the seeds carry everything.
     */
    /** Canonical exits first, then any learned exit to a room not already reachable. */
    static List<Exit> unionExits(List<Exit> canonical, List<Exit> learned) {
        var out = new ArrayList<>(canonical);
        for (var e : learned) {
            if (out.stream().noneMatch(c -> c.targetRoom().equals(e.targetRoom()))) out.add(e);
        }
        return List.copyOf(out);
    }

    public static synchronized void learnRoom(String roomId, String name, String zone,
            List<Exit> exits, String connectedFrom, String inboundDirection) {
        if (roomId == null || roomId.isBlank()) return;
        var current = INSTANCE;
        if (current == null) {
            PENDING.put(roomId, new RoomNode(roomId, name == null ? roomId : name,
                zone == null ? "foundation" : zone,
                exits == null ? List.of() : List.copyOf(exits)));
            return;
        }
        var map = new HashMap<>(current.rooms);
        map.put(roomId, new RoomNode(roomId, name == null ? roomId : name,
            zone == null ? "foundation" : zone, exits == null ? List.of() : List.copyOf(exits)));
        var source = connectedFrom == null ? null : map.get(connectedFrom);
        if (source != null && inboundDirection != null && !inboundDirection.isBlank()
                && source.exits().stream().noneMatch(e -> roomId.equals(e.targetRoom()))) {
            var widened = new ArrayList<>(source.exits());
            widened.add(new Exit(inboundDirection, roomId, name));
            map.put(connectedFrom, new RoomNode(source.roomId(), source.name(),
                source.zone(), List.copyOf(widened)));
        }
        INSTANCE = new ZoneTopology(map);
    }

    /** Teach the shared topology about an exit added after boot. */
    public static synchronized void learnExit(String sourceRoomId, String direction,
            String targetRoom, String label) {
        var current = INSTANCE;
        if (current == null || sourceRoomId == null || targetRoom == null) return;
        var source = current.rooms.get(sourceRoomId);
        if (source == null) return;
        if (source.exits().stream().anyMatch(e -> targetRoom.equals(e.targetRoom()))) return;
        var map = new HashMap<>(current.rooms);
        var widened = new ArrayList<>(source.exits());
        widened.add(new Exit(direction, targetRoom, label));
        map.put(sourceRoomId, new RoomNode(source.roomId(), source.name(),
            source.zone(), List.copyOf(widened)));
        INSTANCE = new ZoneTopology(map);
    }

    public static synchronized void resetForTests() { INSTANCE = null; PENDING.clear(); }

    /** Build from room ID, name, zone, and exit lists. */
    public static ZoneTopology build(List<RoomSeed> seeds) {
        var map = new HashMap<String, RoomNode>();
        for (var seed : seeds) {
            map.put(seed.roomId(), new RoomNode(seed.roomId(), seed.name(), seed.zone(), seed.exits()));
        }
        return new ZoneTopology(map);
    }

    /** Convenience seed for building topology. */
    public record RoomSeed(String roomId, String name, String zone, List<Exit> exits) {}

    // --- Queries ---

    /** All rooms in this topology. */
    public Map<String, RoomNode> rooms() {
        return rooms;
    }

    /** Get a specific room node. */
    public Optional<RoomNode> room(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    /** Number of rooms. */
    public int size() {
        return rooms.size();
    }

    /** Check if target has a directed exit back to source. */
    public boolean hasReturn(String sourceRoomId, String targetRoomId) {
        var target = rooms.get(targetRoomId);
        if (target == null) return false;
        return target.exits().stream().anyMatch(e -> e.targetRoom().equals(sourceRoomId));
    }

    // --- BFS ---

    /**
     * BFS from a room following directed edges, returning rooms within maxHops.
     * Returns nodes in BFS order (closest first).
     */
    public List<RoomNode> nearby(String roomId, int maxHops) {
        if (!rooms.containsKey(roomId) || maxHops < 1) return List.of();

        var result = new ArrayList<RoomNode>();
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String[]>(); // [roomId, depth]
        visited.add(roomId);
        queue.add(new String[]{roomId, "0"});

        while (!queue.isEmpty()) {
            var entry = queue.poll();
            var current = entry[0];
            var depth = Integer.parseInt(entry[1]);

            if (depth > 0) {
                var node = rooms.get(current);
                if (node != null) result.add(node);
            }

            if (depth < maxHops) {
                var node = rooms.get(current);
                if (node == null) continue;
                for (var exit : node.exits()) {
                    if (!visited.contains(exit.targetRoom())) {
                        visited.add(exit.targetRoom());
                        queue.add(new String[]{exit.targetRoom(), String.valueOf(depth + 1)});
                    }
                }
            }
        }
        return result;
    }

    /**
     * Shortest directed path between two rooms (BFS).
     * Returns ordered list of room IDs from source to destination (inclusive).
     * Returns empty if no directed path exists.
     */
    public Optional<List<String>> pathBetween(String fromRoomId, String toRoomId) {
        if (fromRoomId.equals(toRoomId)) return Optional.of(List.of(fromRoomId));
        if (!rooms.containsKey(fromRoomId)) return Optional.empty();

        var visited = new HashSet<String>();
        var parent = new HashMap<String, String>(); // child → parent
        var queue = new ArrayDeque<String>();
        visited.add(fromRoomId);
        queue.add(fromRoomId);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            var node = rooms.get(current);
            if (node == null) continue;

            for (var exit : node.exits()) {
                var target = exit.targetRoom();
                if (visited.contains(target)) continue;
                visited.add(target);
                parent.put(target, current);

                if (target.equals(toRoomId)) {
                    // Reconstruct path
                    var path = new ArrayList<String>();
                    var step = toRoomId;
                    while (step != null) {
                        path.add(step);
                        step = parent.get(step);
                    }
                    Collections.reverse(path);
                    return Optional.of(List.copyOf(path));
                }
                queue.add(target);
            }
        }
        return Optional.empty(); // unreachable
    }

    /** Check if target is reachable from source via directed edges. */
    public boolean isReachable(String fromRoomId, String toRoomId) {
        return pathBetween(fromRoomId, toRoomId).isPresent();
    }

    /**
     * Get the direction to go from one room to reach an adjacent room.
     * Returns empty if rooms are not directly connected.
     */
    public Optional<String> directionBetween(String fromRoomId, String toRoomId) {
        var node = rooms.get(fromRoomId);
        if (node == null) return Optional.empty();
        return node.exits().stream()
            .filter(e -> e.targetRoom().equals(toRoomId))
            .map(Exit::direction)
            .findFirst();
    }

    /** All zones reachable from rooms in this topology via inter-zone exits. */
    public Set<String> connectedZones() {
        var zones = new HashSet<String>();
        for (var node : rooms.values()) {
            for (var exit : node.exits()) {
                var target = rooms.get(exit.targetRoom());
                if (target != null && !target.zone().equals(node.zone())) {
                    zones.add(target.zone());
                }
            }
        }
        return zones;
    }

    // --- Map Rendering ---

    /**
     * Generate a TopologySnapshot for wire transmission.
     *
     * @param centerRoomId Room to center on
     * @param radius       BFS radius (1-5)
     * @param visitedRooms Rooms the player has visited (for fog-of-war)
     */
    public TopologySnapshot snapshot(String centerRoomId, int radius, Set<String> visitedRooms) {
        if (!rooms.containsKey(centerRoomId)) return TopologySnapshot.empty(centerRoomId);

        var nodes = new ArrayList<MapNode>();
        var edges = new ArrayList<MapEdge>();
        var distances = bfsDistances(centerRoomId, radius);

        for (var entry : distances.entrySet()) {
            var rid = entry.getKey();
            var dist = entry.getValue();
            var node = rooms.get(rid);
            if (node == null) continue;

            var visited = visitedRooms.contains(rid);
            var displayName = visited || rid.equals(centerRoomId) ? node.name() : "?";
            nodes.add(new MapNode(rid, displayName, node.zone(),
                rid.equals(centerRoomId), visited, dist));

            // Add outgoing edges to rooms within the snapshot
            for (var exit : node.exits()) {
                if (distances.containsKey(exit.targetRoom())) {
                    var ret = hasReturn(rid, exit.targetRoom());
                    edges.add(new MapEdge(rid, exit.targetRoom(),
                        exit.direction(), exit.label(), ret));
                }
            }
        }

        return new TopologySnapshot(centerRoomId, List.copyOf(nodes), List.copyOf(edges));
    }

    /**
     * Render an accessible text map (hierarchical listing).
     * Used for screen reader mode and as the primary text output.
     *
     * @param centerRoomId Room to center on
     * @param radius       BFS radius
     * @param visitedRooms Rooms the player has visited
     * @param screenReader If true, include return path info and one-way warnings
     */
    public String renderAccessibleMap(String centerRoomId, int radius,
                                       Set<String> visitedRooms, boolean screenReader) {
        var center = rooms.get(centerRoomId);
        if (center == null) return "Unknown location.";

        var sb = new StringBuilder();
        sb.append("You are in ").append(center.name());
        if (center.zone() != null && !center.zone().isEmpty()) {
            sb.append(" (").append(center.zone()).append(" zone)");
        }
        sb.append(".\n");

        if (center.exits().isEmpty()) {
            sb.append("\nNo exits.");
            return sb.toString();
        }

        sb.append("\nNearby rooms:\n");
        renderAccessibleBfs(sb, centerRoomId, radius, visitedRooms, screenReader, "  ", new HashSet<>(), 0);

        return sb.toString().stripTrailing();
    }

    private void renderAccessibleBfs(StringBuilder sb, String roomId, int remainingDepth,
                                      Set<String> visitedRooms, boolean screenReader,
                                      String indent, Set<String> seen, int depth) {
        var node = rooms.get(roomId);
        if (node == null || remainingDepth <= 0) return;

        seen.add(roomId);

        for (var exit : node.exits()) {
            var targetId = exit.targetRoom();
            if (seen.contains(targetId)) continue;
            seen.add(targetId);

            var target = rooms.get(targetId);
            var targetName = target != null
                ? (visitedRooms.contains(targetId) || depth == 0 ? target.name() : "Unknown")
                : "Unknown";
            var steps = depth + 1;

            sb.append(indent);
            if (depth > 0 && node.name() != null) {
                sb.append(capitalize(exit.direction())).append(" from ").append(node.name());
            } else {
                sb.append(capitalize(exit.direction()));
            }
            sb.append(": ").append(targetName);
            sb.append(" (").append(steps).append(steps == 1 ? " step" : " steps");

            if (screenReader && target != null) {
                var ret = hasReturn(roomId, targetId);
                if (ret) {
                    // Find return direction
                    var returnDir = target.exits().stream()
                        .filter(e -> e.targetRoom().equals(roomId))
                        .map(Exit::direction)
                        .findFirst()
                        .orElse("back");
                    sb.append(", return ").append(returnDir);
                } else {
                    sb.append(", ONE WAY \u2014 no return");
                }
            }
            sb.append(")\n");

            // Recurse for children
            if (remainingDepth > 1 && target != null) {
                renderAccessibleBfs(sb, targetId, remainingDepth - 1,
                    visitedRooms, screenReader, indent + "  ", seen, depth + 1);
            }
        }
    }

    /**
     * Render a compact voice-mode map (spoken-form, no visual cruft).
     */
    public String renderVoiceMap(String centerRoomId) {
        var center = rooms.get(centerRoomId);
        if (center == null) return "Unknown location.";

        var sb = new StringBuilder();
        sb.append("You're in ").append(center.name()).append(".");

        for (var exit : center.exits()) {
            var target = rooms.get(exit.targetRoom());
            var name = target != null ? target.name() : "somewhere unknown";
            sb.append(" ").append(capitalize(exit.direction())).append(" leads to ").append(name).append(".");
        }

        return sb.toString();
    }

    /**
     * Render a simple ASCII text map centered on a room.
     * Uses a tree-style layout since rooms are a graph, not a grid.
     */
    public String renderTextMap(String centerRoomId, int radius, Set<String> visitedRooms) {
        var center = rooms.get(centerRoomId);
        if (center == null) return "Unknown location.";

        // For complex topologies, fall back to the accessible listing.
        // A proper tree layout with collision avoidance is the ASCII renderer.
        // This implementation uses a simple indented tree format which is
        // universally readable (works in all terminals, screen readers).
        var sb = new StringBuilder();
        sb.append("[* ").append(center.name()).append("]\n");

        var seen = new HashSet<String>();
        seen.add(centerRoomId);
        renderTreeBranches(sb, centerRoomId, radius, visitedRooms, seen, "");

        return sb.toString().stripTrailing();
    }

    private void renderTreeBranches(StringBuilder sb, String roomId, int remainingDepth,
                                     Set<String> visitedRooms, Set<String> seen, String prefix) {
        var node = rooms.get(roomId);
        if (node == null || remainingDepth <= 0) return;

        var exits = node.exits();
        for (int i = 0; i < exits.size(); i++) {
            var exit = exits.get(i);
            var targetId = exit.targetRoom();
            if (seen.contains(targetId)) continue;
            seen.add(targetId);

            var target = rooms.get(targetId);
            var name = target != null
                ? (visitedRooms.contains(targetId) ? target.name() : "?")
                : "?";

            boolean last = isLastUnseenExit(exits, i, seen);
            var connector = last ? "└── " : "├── ";
            var childPrefix = last ? "    " : "│   ";

            var arrow = hasReturn(roomId, targetId) ? "--" : "->";
            sb.append(prefix).append(connector).append(exit.direction())
                .append(arrow).append("[").append(name).append("]\n");

            if (remainingDepth > 1 && target != null) {
                renderTreeBranches(sb, targetId, remainingDepth - 1,
                    visitedRooms, seen, prefix + childPrefix);
            }
        }
    }

    private boolean isLastUnseenExit(List<Exit> exits, int currentIndex, Set<String> seen) {
        for (int j = currentIndex + 1; j < exits.size(); j++) {
            if (!seen.contains(exits.get(j).targetRoom())) return false;
        }
        return true;
    }

    // --- Internal ---

    /** BFS returning roomId → distance map. */
    private Map<String, Integer> bfsDistances(String startRoomId, int maxHops) {
        var distances = new LinkedHashMap<String, Integer>();
        var queue = new ArrayDeque<String[]>();
        distances.put(startRoomId, 0);
        queue.add(new String[]{startRoomId, "0"});

        while (!queue.isEmpty()) {
            var entry = queue.poll();
            var current = entry[0];
            var depth = Integer.parseInt(entry[1]);

            if (depth >= maxHops) continue;

            var node = rooms.get(current);
            if (node == null) continue;

            for (var exit : node.exits()) {
                var target = exit.targetRoom();
                if (!distances.containsKey(target)) {
                    distances.put(target, depth + 1);
                    queue.add(new String[]{target, String.valueOf(depth + 1)});
                }
            }
        }
        return distances;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
