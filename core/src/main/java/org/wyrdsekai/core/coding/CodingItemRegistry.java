package org.wyrdsekai.core.coding;

import java.util.List;
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

    /**
     * Every RoomObject id this task placed.
     *
     * <h2>Why the caller cannot just read the description</h2>
     * {@code CompanionActor}'s dispatch hand-off found the freshly placed object by
     * scanning the room for a description CONTAINING the task id — which worked only
     * because the description used to be the codex boilerplate
     * {@code "A goose codex containing 1 file(s) for task <uuid>"}. On 2026-08-20 the
     * description was replaced with what the item says about itself, so the uuid vanished
     * and the hand-off went blind: <i>"nothing placed for task … after 4 looks"</i>, logged
     * seconds after the same task's <i>"Placed 1 goose item(s)"</i>. She had made the thing
     * and could not give it to the person who asked.
     *
     * <p>Making a description prettier must not be able to sever a lookup. The registry is
     * already stamped with the link at placement time; ask it.
     */
    public List<String> roomObjectsForTask(String taskId) {
        if (taskId == null || taskId.isBlank()) return List.of();
        return byRoomObjectId.values().stream()
            .filter(m -> taskId.equals(m.taskId()))
            .map(CodingItemMetadata::roomObjectId)
            .toList();
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
