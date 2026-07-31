package org.wyrdsekai.core.room;

import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;

import java.util.*;

/**
 * Domain-specific CRDT merge strategies for room state.
 * Used when Pekko Replicated Event Sourcing detects concurrent events
 * from different replicas.
 *
 * Strategies:
 * - Entities: OR-Set (union of all entities from all replicas)
 * - Objects: OR-Set (union, with LWW for conflicting IDs)
 * - Exits: OR-Set (union of all exits)
 * - Properties: LWW (last-write-wins by timestamp/replica order)
 * - Hints: LWW (latest hints win)
 * - Description: LWW (latest wins)
 * - Events (Said, ObjectUsed): Append-only (all events preserved)
 */
public final class MergeStrategies {

    private MergeStrategies() {}

    /**
     * Merge two room states from concurrent replicas.
     * Called when replicationContext.concurrent() is true.
     *
     * @param local  the local replica's current state
     * @param remote the state derived from the remote event
     * @return merged state
     */
    public static RoomState merge(RoomState local, RoomState remote) {
        return new RoomState(
            local.roomId(),
            mergeLww(local.name(), remote.name()),
            mergeLww(local.description(), remote.description()),
            mergeLww(local.zone(), remote.zone()),
            mergeExits(local.exits(), remote.exits()),
            mergeEntities(local.entities(), remote.entities()),
            mergeObjects(local.objects(), remote.objects()),
            mergeHints(local.hints(), remote.hints()),
            mergeProperties(local.properties(), remote.properties())
        );
    }

    /** OR-Set merge for entities: union of both sets. */
    public static Map<String, Entity> mergeEntities(
            Map<String, Entity> local, Map<String, Entity> remote) {
        var merged = new HashMap<>(local);
        merged.putAll(remote); // remote wins for same key (LWW within ORSet)
        return Map.copyOf(merged);
    }

    /** OR-Set merge for objects: union of both sets. */
    public static Map<String, RoomObject> mergeObjects(
            Map<String, RoomObject> local, Map<String, RoomObject> remote) {
        var merged = new HashMap<>(local);
        merged.putAll(remote);
        return Map.copyOf(merged);
    }

    /** OR-Set merge for exits: union of both sets. */
    public static Map<String, Exit> mergeExits(
            Map<String, Exit> local, Map<String, Exit> remote) {
        var merged = new HashMap<>(local);
        merged.putAll(remote);
        return Map.copyOf(merged);
    }

    /** LWW merge for properties: remote wins for conflicting keys. */
    public static Map<String, String> mergeProperties(
            Map<String, String> local, Map<String, String> remote) {
        var merged = new HashMap<>(local);
        for (var entry : remote.entrySet()) {
            // Remote event is newer (it's the concurrent one being merged in)
            merged.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(merged);
    }

    /** LWW merge for hints: keep the longer list (more specific). */
    public static List<Hint> mergeHints(List<Hint> local, List<Hint> remote) {
        if (remote.size() >= local.size()) return remote;
        return local;
    }

    /** LWW merge for simple string fields. */
    static String mergeLww(String local, String remote) {
        if (remote != null && !remote.isEmpty()) return remote;
        return local;
    }
}
