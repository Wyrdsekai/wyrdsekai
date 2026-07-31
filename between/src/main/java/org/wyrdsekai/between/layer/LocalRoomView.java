package org.wyrdsekai.between.layer;

import org.wyrdsekai.common.topology.NodeAnnouncement;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomClaimMessage;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe local view of room-to-node assignments across the household.
 * Pure data structure (not an actor). Uses ConcurrentHashMap for safe
 * concurrent access from multiple Between layers.
 *
 * <p>Conflict resolution for room claims:
 * <ol>
 *   <li>If room is unclaimed (absent or orphaned), accept the claim.</li>
 *   <li>If claimed by the same node, update the timestamp.</li>
 *   <li>If claimed by a different node, higher snapshotTimestamp wins.</li>
 *   <li>On tie, lexicographically lower nodeId wins (deterministic).</li>
 * </ol>
 */
public class LocalRoomView {

    private final ConcurrentHashMap<String, RoomViewEntry> rooms = new ConcurrentHashMap<>();

    /**
     * A single room's view entry, tracking primary ownership, announcement time,
     * snapshot time, and which nodes hold replicas.
     *
     * @param roomId         unique room identifier
     * @param ownership      ownership classification
     * @param primaryNodeId  node currently hosting this room as primary (null if orphaned)
     * @param lastAnnounced  when this entry was last announced
     * @param lastSnapshotAt when the last state snapshot was taken
     * @param replicas       nodeId to last-snapshot-received timestamp
     */
    public record RoomViewEntry(
        String roomId,
        RoomOwnership ownership,
        String primaryNodeId,
        Instant lastAnnounced,
        Instant lastSnapshotAt,
        Map<String, Instant> replicas
    ) {
        /** Create a copy with an updated replicas map. */
        RoomViewEntry withReplica(String nodeId, Instant snapshotTs) {
            var updated = new HashMap<>(replicas);
            updated.put(nodeId, snapshotTs);
            return new RoomViewEntry(roomId, ownership, primaryNodeId,
                lastAnnounced, lastSnapshotAt, Map.copyOf(updated));
        }

        /** Create a copy with primaryNodeId set to null (orphaned). */
        RoomViewEntry orphaned() {
            return new RoomViewEntry(roomId, ownership, null,
                lastAnnounced, lastSnapshotAt, replicas);
        }
    }

    /**
     * Merge a peer's announcement into the local view.
     * Updates or inserts entries for every room the peer reports.
     */
    public void updateFromAnnouncement(NodeAnnouncement announcement) {
        for (RoomAssignment ra : announcement.rooms()) {
            rooms.compute(ra.roomId(), (_, existing) -> {
                var replicas = existing != null
                    ? new HashMap<>(existing.replicas())
                    : new HashMap<String, Instant>();
                return new RoomViewEntry(
                    ra.roomId(),
                    ra.ownership(),
                    ra.primaryNodeId(),
                    announcement.timestamp(),
                    ra.lastSnapshotAt(),
                    Map.copyOf(replicas)
                );
            });
        }
    }

    /**
     * Apply a room claim with conflict resolution.
     *
     * @return true if the claim was accepted, false if an existing claim wins
     */
    public boolean claimRoom(RoomClaimMessage claim) {
        var result = new boolean[]{false};
        rooms.compute(claim.roomId(), (_, existing) -> {
            if (existing == null || existing.primaryNodeId() == null) {
                // Unclaimed or orphaned: accept
                result[0] = true;
                return new RoomViewEntry(
                    claim.roomId(),
                    claim.ownership(),
                    claim.claimingNodeId(),
                    claim.timestamp(),
                    claim.snapshotTimestamp(),
                    existing != null ? existing.replicas() : Map.of()
                );
            }

            if (existing.primaryNodeId().equals(claim.claimingNodeId())) {
                // Same node re-claiming: update timestamp
                result[0] = true;
                return new RoomViewEntry(
                    existing.roomId(),
                    existing.ownership(),
                    existing.primaryNodeId(),
                    claim.timestamp(),
                    claim.snapshotTimestamp(),
                    existing.replicas()
                );
            }

            // Conflict: higher snapshotTimestamp wins; tie goes to lower nodeId
            int cmp = claim.snapshotTimestamp().compareTo(existing.lastSnapshotAt());
            if (cmp > 0 || (cmp == 0 && claim.claimingNodeId().compareTo(existing.primaryNodeId()) < 0)) {
                result[0] = true;
                return new RoomViewEntry(
                    claim.roomId(),
                    claim.ownership(),
                    claim.claimingNodeId(),
                    claim.timestamp(),
                    claim.snapshotTimestamp(),
                    existing.replicas()
                );
            }

            // Existing claim wins
            result[0] = false;
            return existing;
        });
        return result[0];
    }

    /**
     * Release all rooms whose primary node matches the departed nodeId.
     * Marks those rooms as orphaned (primaryNodeId = null).
     */
    public void releaseRooms(String nodeId) {
        rooms.replaceAll((_, entry) -> {
            if (nodeId.equals(entry.primaryNodeId())) {
                return entry.orphaned();
            }
            return entry;
        });
    }

    /**
     * Get all shared rooms whose primary node is gone (orphaned).
     * Personal rooms are excluded because they only live on their owner's node.
     */
    public List<RoomViewEntry> orphanedSharedRooms() {
        return rooms.values().stream()
            .filter(e -> e.primaryNodeId() == null)
            .filter(e -> e.ownership().replicates())
            .toList();
    }

    /**
     * Should this node create/host a room? Returns true if the room is
     * either not in the view at all, has no primary node, or is already
     * claimed by this node.
     */
    public boolean shouldHostRoom(String roomId, String localNodeId) {
        var entry = rooms.get(roomId);
        if (entry == null) {
            return true;
        }
        if (entry.primaryNodeId() == null) {
            return true;
        }
        return entry.primaryNodeId().equals(localNodeId);
    }

    /**
     * Is this room claimed by a node other than localNodeId?
     */
    public boolean isClaimedByOther(String roomId, String localNodeId) {
        var entry = rooms.get(roomId);
        if (entry == null || entry.primaryNodeId() == null) {
            return false;
        }
        return !entry.primaryNodeId().equals(localNodeId);
    }

    /**
     * Record a snapshot received from a peer for a given room.
     */
    public void recordSnapshot(String roomId, String fromNodeId, Instant snapshotTs) {
        rooms.computeIfPresent(roomId, (_, entry) ->
            entry.withReplica(fromNodeId, snapshotTs));
    }

    /**
     * Immutable snapshot of the current room view state.
     */
    public record Snapshot(Map<String, RoomViewEntry> rooms) {
        /** Empty snapshot (no rooms tracked). */
        public static Snapshot empty() {
            return new Snapshot(Map.of());
        }

        /** Room IDs claimed by a specific node. */
        public Map<String, RoomViewEntry> roomsOnNode(String nodeId) {
            var result = new HashMap<String, RoomViewEntry>();
            for (var entry : rooms.entrySet()) {
                if (nodeId.equals(entry.getValue().primaryNodeId())) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
            return result;
        }
    }

    /**
     * Take an immutable snapshot of the current state.
     */
    public Snapshot snapshot() {
        return new Snapshot(Map.copyOf(rooms));
    }

    /**
     * Human-readable description of the current room view.
     */
    public String describe() {
        if (rooms.isEmpty()) {
            return "LocalRoomView: empty (no rooms tracked)";
        }
        var sb = new StringBuilder("LocalRoomView: ")
            .append(rooms.size()).append(" rooms\n");
        rooms.forEach((id, entry) -> sb
            .append("  ").append(id)
            .append(" [").append(entry.ownership()).append("]")
            .append(" primary=").append(entry.primaryNodeId() != null ? entry.primaryNodeId() : "<orphaned>")
            .append(" replicas=").append(entry.replicas().size())
            .append("\n"));
        return sb.toString().stripTrailing();
    }

    /** Number of tracked rooms (for testing). */
    int size() {
        return rooms.size();
    }
}
