package org.wyrdsekai.core.coding;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of {@link CodingItemMetadata} keyed by
 * {@code RoomObject} id.
 *
 * <p>{@link CodingTaskItemBridge} stamps an entry every time it
 * places a codex/artifact item in a room. {@link
 * org.wyrdsekai.core.room.RoomActor#onUseObject} consults the
 * registry on every {@code use <object> <verb>} so coding items
 * route through the same {@link
 * org.wyrdsekai.core.agent.LocalCommandRouter} that serves player
 * WebSocket commands and agent {@code zone_command} actions.</p>
 *
 * <p>The registry is best-effort and not persisted: if the process
 * restarts, the bridge re-stamps on the next task completion. Items
 * placed before the restart that still exist as RoomObjects without a
 * matching registry entry fall back to the room script's regular
 * {@code onUse} path (ie. they look like ordinary inert objects).
 * This is acceptable for v1 — Phase E adds persistence to the
 * existing CodeItemStore so the registry survives restarts.</p>
 */
public final class CodingItemRegistry {

    private static final CodingItemRegistry INSTANCE = new CodingItemRegistry();

    private final ConcurrentHashMap<String, CodingItemMetadata> byRoomObjectId =
        new ConcurrentHashMap<>();

    private CodingItemRegistry() {}

    public static CodingItemRegistry get() { return INSTANCE; }

    /** Stamp metadata for a placed RoomObject. Last writer wins. */
    public void stamp(CodingItemMetadata metadata) {
        if (metadata == null || metadata.roomObjectId() == null
                || metadata.roomObjectId().isBlank()) {
            return;
        }
        byRoomObjectId.put(metadata.roomObjectId(), metadata);
    }

    /** Read by RoomObject id. */
    public Optional<CodingItemMetadata> lookup(String roomObjectId) {
        if (roomObjectId == null) return Optional.empty();
        return Optional.ofNullable(byRoomObjectId.get(roomObjectId));
    }

    /** Read by artifact UUID — useful for cross-references. */
    public Optional<CodingItemMetadata> lookupByArtifact(UUID artifactId) {
        if (artifactId == null) return Optional.empty();
        return byRoomObjectId.values().stream()
            .filter(m -> artifactId.equals(m.artifactId()))
            .findFirst();
    }

    /** Drop the entry for a RoomObject id. */
    public void forget(String roomObjectId) {
        if (roomObjectId == null) return;
        byRoomObjectId.remove(roomObjectId);
    }

    /** Test seam — clear all entries. */
    public void clear() { byRoomObjectId.clear(); }

    public int size() { return byRoomObjectId.size(); }
}
