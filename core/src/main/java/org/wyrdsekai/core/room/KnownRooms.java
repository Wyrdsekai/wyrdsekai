package org.wyrdsekai.core.room;

import java.util.List;
import java.util.Optional;

/**
 * Finding a room she knows of that is not next door.
 *
 * <p>{@code go_to_room} matched only the exits of the room she was standing in. A room she
 * had made herself the day before, two doors away, did not exist to it: "I can't find a way
 * to get to the forge of quiet things from here right now", three times in a night, and then
 * she made the forge again (household node, 2026-09-10). The zone does know the room — the
 * registry holds its name, the topology holds the doors between — the join was never made.
 */
public final class KnownRooms {

    private KnownRooms() {}

    /** A room found by name, and the doors between here and there when the map knows them. */
    public record Found(String roomId, String name, List<String> path) {
        public int hops() { return path == null || path.isEmpty() ? -1 : path.size() - 1; }
    }

    /**
     * Resolve {@code target} against every room in the zone: the registry's id and alias
     * index first (exact, then an unambiguous part), then the same with the name shorn of a
     * description the model may have appended, then a room she made most recently whose name
     * the target contains. Empty when nothing matches, or when the match is the room she is in.
     */
    public static Optional<Found> find(String target, String currentRoomId, String lastCreatedRoomId) {
        if (target == null || target.isBlank()) return Optional.empty();
        var registry = RoomRegistry.get();
        String id = registry.resolveRoomId(target.strip());
        if (id == null) {
            var head = RoomNaming.head(target);
            if (!head.equalsIgnoreCase(target.strip())) id = registry.resolveRoomId(head);
        }
        if (id == null && lastCreatedRoomId != null) {
            var made = ZoneTopology.getShared() == null ? Optional.<ZoneTopology.RoomNode>empty()
                : ZoneTopology.getShared().room(lastCreatedRoomId);
            var t = RoomNaming.normalise(target);
            if (made.isPresent() && !t.isEmpty()) {
                var n = RoomNaming.normalise(made.get().name());
                if (!n.isEmpty() && (t.contains(n) || n.contains(t))) id = lastCreatedRoomId;
            }
        }
        if (id == null || id.equals(currentRoomId)) return Optional.empty();
        var topo = ZoneTopology.getShared();
        var name = topo == null ? id : topo.room(id).map(ZoneTopology.RoomNode::name).orElse(id);
        List<String> path = null;
        if (topo != null && currentRoomId != null) path = topo.pathBetween(currentRoomId, id).orElse(null);
        return Optional.of(new Found(id, name, path));
    }

    /**
     * A room already in the zone with THIS name — the same name once both are shorn of a
     * description and reduced to their letters — so she can be walked to it rather than make
     * it a second time. A part of a name is a different room; the room she stands in is empty.
     */
    public static Optional<String> sameNamed(String proposedName, String currentRoomId) {
        var want = RoomNaming.normalise(RoomNaming.head(proposedName));
        if (want.isEmpty()) return Optional.empty();
        var topo = ZoneTopology.getShared();
        if (topo != null) {
            for (var node : topo.rooms().values()) {
                if (node.roomId().equals(currentRoomId)) continue;
                if (want.equals(RoomNaming.normalise(RoomNaming.head(node.name())))) return Optional.of(node.roomId());
            }
        }
        for (var e : RoomRegistry.get().aliases().entrySet()) {
            if (e.getValue().equals(currentRoomId)) continue;
            if (want.equals(RoomNaming.normalise(RoomNaming.head(e.getKey())))) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }
}
