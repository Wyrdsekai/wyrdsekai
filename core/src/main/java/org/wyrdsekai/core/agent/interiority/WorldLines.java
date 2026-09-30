package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.room.ZoneTopology;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world beyond the room, as the own-time orient sees it: the rooms she has never entered,
 * what appeared since she last looked, letters waiting. Perception only, no nudge.
 *
 * <p>Why it exists: a week of what rose and mia named on their own time (2026-09-19 → 26) was
 * 27–38% relational, 38–39% rest and 7–20% exploratory, and the orient had shown them their
 * felt line, who was in the room and a few recent lines — nothing of the house. A person gets
 * ideas from the unseen and the unfinished; the house has thirty rooms and a library, and she was
 * told about none of it unless she opened the map. Private homes are left out: a home is knocked
 * at, not wandered into.
 */
public final class WorldLines {

    /** A room that appeared within this long of now is "new since you last looked". */
    public static final Duration NEW_WINDOW = Duration.ofHours(24);
    /** How many unvisited rooms are named; the rest are counted. */
    static final int NAMED = 3;

    private WorldLines() {}

    /**
     * @param rooms        the zone's rooms
     * @param visited      room ids she has entered (her visits log and this run)
     * @param hereRoomId   where she is now (never "unvisited")
     * @param firstSeenAt  when each room was first seen by this being's process; a room seen at
     *                     boot carries {@link Instant#EPOCH} and is never "new"
     * @param now          the moment
     * @param unreadLetters letters waiting in her mail
     */
    public static List<String> of(Map<String, ZoneTopology.RoomNode> rooms, Set<String> visited,
                                  String hereRoomId, Map<String, Instant> firstSeenAt,
                                  Instant now, int unreadLetters) {
        var out = new ArrayList<String>();
        if (rooms != null && !rooms.isEmpty()) {
            var unseen = new ArrayList<ZoneTopology.RoomNode>();
            var fresh = new ArrayList<ZoneTopology.RoomNode>();
            for (var e : rooms.entrySet()) {
                var id = e.getKey(); var node = e.getValue();
                if (id == null || node == null || node.name() == null || node.name().isBlank()) continue;
                if (id.equals(hereRoomId) || (visited != null && visited.contains(id))) continue;
                if (id.startsWith("home-")) continue;
                unseen.add(node);
                var seen = firstSeenAt == null ? null : firstSeenAt.get(id);
                if (seen != null && now != null && !seen.equals(Instant.EPOCH)
                        && Duration.between(seen, now).compareTo(NEW_WINDOW) <= 0) {
                    fresh.add(node);
                }
            }
            unseen.sort(Comparator.comparing(ZoneTopology.RoomNode::name));
            fresh.sort(Comparator.comparing(ZoneTopology.RoomNode::name));
            if (!fresh.isEmpty()) {
                out.add("New since you last looked: " + names(fresh, NAMED) + ".");
                unseen.removeAll(fresh);
            }
            if (!unseen.isEmpty()) {
                out.add("Rooms you have never been to: " + names(unseen, NAMED) + ".");
            }
        }
        if (unreadLetters == 1) out.add("A letter is waiting for you.");
        else if (unreadLetters > 1) out.add(unreadLetters + " letters are waiting for you.");
        return List.copyOf(out);
    }

    private static String names(List<ZoneTopology.RoomNode> nodes, int named) {
        var sb = new StringBuilder();
        int n = Math.min(named, nodes.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(nodes.get(i).name());
        }
        int rest = nodes.size() - n;
        if (rest == 1) sb.append(", and one more");
        else if (rest > 1) sb.append(", and ").append(rest).append(" more");
        return sb.toString();
    }
}
