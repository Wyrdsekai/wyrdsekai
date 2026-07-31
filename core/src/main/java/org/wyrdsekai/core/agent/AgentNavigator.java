package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.core.room.ZoneTopology.RoomNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Agent pathfinding using ZoneTopology (§N8).
 * All pathfinding respects directed edges — A→B does NOT imply B→A.
 *
 * Used by CompanionActor for autonomous navigation:
 * - Going home for sleep
 * - Meeting other agents
 * - Exploring new rooms
 * - Returning to familiar rooms
 */
public class AgentNavigator {

    private final ZoneTopology topology;

    public AgentNavigator(ZoneTopology topology) {
        this.topology = topology;
    }

    /**
     * Find the next direction to move toward a target room.
     * Uses BFS shortest directed path, returns the first step's direction.
     * Returns empty if no directed path exists (one-way exits may block).
     */
    public Optional<String> nextStep(String currentRoomId, String targetRoomId) {
        if (currentRoomId.equals(targetRoomId)) return Optional.empty(); // already there

        var path = topology.pathBetween(currentRoomId, targetRoomId);
        if (path.isEmpty() || path.get().size() < 2) return Optional.empty();

        var nextRoom = path.get().get(1);
        return topology.directionBetween(currentRoomId, nextRoom);
    }

    /**
     * Find the full directed path to a target room as a list of directions.
     * Returns empty if no path exists.
     */
    public Optional<List<String>> fullPath(String currentRoomId, String targetRoomId) {
        var path = topology.pathBetween(currentRoomId, targetRoomId);
        if (path.isEmpty()) return Optional.empty();

        var rooms = path.get();
        var directions = new ArrayList<String>();
        for (int i = 0; i < rooms.size() - 1; i++) {
            var dir = topology.directionBetween(rooms.get(i), rooms.get(i + 1));
            if (dir.isEmpty()) return Optional.empty(); // shouldn't happen but be safe
            directions.add(dir.get());
        }
        return Optional.of(List.copyOf(directions));
    }

    /**
     * Find the closest reachable room matching a predicate (directed BFS).
     * Returns the room ID if found.
     */
    public Optional<String> findNearest(String fromRoomId, Predicate<RoomNode> predicate) {
        return findNearest(fromRoomId, predicate, 20); // reasonable max search depth
    }

    /**
     * Find the closest reachable room matching a predicate within maxHops.
     */
    public Optional<String> findNearest(String fromRoomId, Predicate<RoomNode> predicate, int maxHops) {
        var nearby = topology.nearby(fromRoomId, maxHops);
        return nearby.stream()
            .filter(predicate)
            .map(RoomNode::roomId)
            .findFirst(); // nearby() returns in BFS order, so first match is closest
    }

    /**
     * Check if a room is reachable via directed edges from current position.
     */
    public boolean isReachable(String fromRoomId, String targetRoomId) {
        return topology.isReachable(fromRoomId, targetRoomId);
    }

    /**
     * Check if taking an exit would strand the agent (no directed return path).
     * An agent should be cautious about taking one-way exits.
     */
    public boolean isOneWay(String fromRoomId, String direction) {
        var room = topology.room(fromRoomId);
        if (room.isEmpty()) return false;

        var exit = room.get().exits().stream()
            .filter(e -> e.direction().equals(direction))
            .findFirst();

        if (exit.isEmpty()) return false;

        return !topology.hasReturn(fromRoomId, exit.get().targetRoom());
    }

    /**
     * Get the distance (hop count) between two rooms.
     * Returns -1 if unreachable.
     */
    public int distance(String fromRoomId, String toRoomId) {
        return topology.pathBetween(fromRoomId, toRoomId)
            .map(path -> path.size() - 1)
            .orElse(-1);
    }

    /** Access the underlying topology for advanced queries. */
    public ZoneTopology topology() {
        return topology;
    }
}
