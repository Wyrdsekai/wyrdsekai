package org.wyrdsekai.between.layer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.topology.ReplicationTier;
import org.wyrdsekai.common.topology.RoomOwnership;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-room replication tier and handles tier promotion/demotion.
 *
 * <p>Rooms are not permanently assigned a tier. They promote and demote based
 * on activity:</p>
 * <pre>
 *   CONFIG_ONLY → LAZY → PERIODIC → EVENT_SOURCED
 *        ↑                               ↓
 *        └──── demote when idle ←────────┘
 * </pre>
 *
 * <p>Key rules:</p>
 * <ul>
 *   <li>Foundation room, never visited, no state changes → CONFIG_ONLY</li>
 *   <li>No entities, idle &gt; 1 hour → LAZY</li>
 *   <li>Visited but currently empty, idle &lt; 1 hour → PERIODIC (5 min interval)</li>
 *   <li>Has entities (active room) → PERIODIC with 1 min interval</li>
 *   <li>Personal rooms, agent Home rooms → WRITE_THROUGH</li>
 *   <li>Active conversation (companion present + human present) → EVENT_SOURCED</li>
 * </ul>
 *
 * <p>Thread-safe: all state is in ConcurrentHashMaps.</p>
 */
public class RoomReplicationManager {

    private static final Logger log = LoggerFactory.getLogger(RoomReplicationManager.class);

    /** Idle threshold for demotion from PERIODIC to LAZY. */
    static final Duration IDLE_TO_LAZY = Duration.ofHours(1);

    /** Idle threshold for demotion from EVENT_SOURCED to PERIODIC. */
    static final Duration IDLE_TO_PERIODIC = Duration.ofMinutes(10);

    /** Snapshot interval for active rooms (entities present, PERIODIC tier). */
    static final Duration ACTIVE_PERIODIC_INTERVAL = Duration.ofMinutes(1);

    /** Snapshot interval for idle rooms (no entities, PERIODIC tier). */
    static final Duration IDLE_PERIODIC_INTERVAL = Duration.ofMinutes(5);

    // roomId -> current tier
    private final ConcurrentHashMap<String, ReplicationTier> roomTiers = new ConcurrentHashMap<>();
    // roomId -> last activity timestamp
    private final ConcurrentHashMap<String, Instant> lastActivity = new ConcurrentHashMap<>();
    // roomId -> whether the room has ever been visited (state changed from foundation config)
    private final ConcurrentHashMap<String, Boolean> everVisited = new ConcurrentHashMap<>();

    /**
     * Compute and set the appropriate replication tier for a room based on its current state.
     *
     * @param roomId          room identifier
     * @param ownership       room ownership classification
     * @param hasEntities     whether entities are currently present
     * @param isCompanionRoom true if a companion + human are both present (active conversation)
     * @return the computed tier
     */
    public ReplicationTier computeTier(String roomId, RoomOwnership ownership,
                                        boolean hasEntities, boolean isCompanionRoom) {
        var tier = doComputeTier(roomId, ownership, hasEntities, isCompanionRoom);
        roomTiers.put(roomId, tier);
        return tier;
    }

    private ReplicationTier doComputeTier(String roomId, RoomOwnership ownership,
                                           boolean hasEntities, boolean isCompanionRoom) {
        // Active conversation: companion + human present → event-sourced
        if (isCompanionRoom) {
            return ReplicationTier.EVENT_SOURCED;
        }

        // Personal rooms and agent Home rooms → write-through
        if (ownership == RoomOwnership.PERSONAL || ownership == RoomOwnership.AGENT_HOME) {
            return ReplicationTier.WRITE_THROUGH;
        }

        // Has entities → periodic with short interval (active room)
        if (hasEntities) {
            return ReplicationTier.PERIODIC;
        }

        // Was visited before but currently empty → check idle time
        if (Boolean.TRUE.equals(everVisited.get(roomId))) {
            var lastAct = lastActivity.get(roomId);
            if (lastAct != null) {
                var idle = Duration.between(lastAct, Instant.now());
                if (idle.compareTo(IDLE_TO_LAZY) > 0) {
                    return ReplicationTier.LAZY;
                }
            }
            return ReplicationTier.PERIODIC;
        }

        // Never visited → config-only
        return ReplicationTier.CONFIG_ONLY;
    }

    /**
     * Record activity in a room (entity entered, speech, object interaction).
     * Resets the idle timer and marks the room as visited.
     *
     * @param roomId the room where activity occurred
     */
    public void recordActivity(String roomId) {
        lastActivity.put(roomId, Instant.now());
        everVisited.put(roomId, true);
    }

    /**
     * Check if the tier should change for a room and update if so.
     *
     * @param roomId          room identifier
     * @param ownership       room ownership classification
     * @param hasEntities     whether entities are currently present
     * @param isCompanionRoom true if companion + human both present
     * @return the new tier if it changed, or null if unchanged
     */
    public ReplicationTier checkPromotion(String roomId, RoomOwnership ownership,
                                           boolean hasEntities, boolean isCompanionRoom) {
        var oldTier = roomTiers.get(roomId);
        var newTier = doComputeTier(roomId, ownership, hasEntities, isCompanionRoom);

        if (oldTier == null || oldTier != newTier) {
            roomTiers.put(roomId, newTier);
            if (oldTier != null) {
                log.info("Room {} tier changed: {} → {}", roomId, oldTier, newTier);
            }
            return newTier;
        }
        return null; // unchanged
    }

    /**
     * Get the current replication tier for a room.
     *
     * @param roomId room identifier
     * @return current tier, or CONFIG_ONLY if not tracked
     */
    public ReplicationTier getTier(String roomId) {
        return roomTiers.getOrDefault(roomId, ReplicationTier.CONFIG_ONLY);
    }

    /**
     * Get the appropriate snapshot interval for a room based on its current tier and state.
     *
     * <p>For PERIODIC rooms, the interval depends on whether entities are present:</p>
     * <ul>
     *   <li>Entities present (active): 1 minute</li>
     *   <li>No entities (idle): 5 minutes</li>
     * </ul>
     *
     * <p>For other tiers, returns the tier's default interval.</p>
     *
     * @param roomId      room identifier
     * @param hasEntities whether entities are currently present (used for PERIODIC rooms)
     * @return snapshot interval
     */
    public Duration getSnapshotInterval(String roomId, boolean hasEntities) {
        var tier = getTier(roomId);
        if (tier == ReplicationTier.PERIODIC) {
            return hasEntities ? ACTIVE_PERIODIC_INTERVAL : IDLE_PERIODIC_INTERVAL;
        }
        return tier.defaultSnapshotInterval();
    }

    /**
     * Get the appropriate snapshot interval for a room using default (no entities) assumption.
     *
     * @param roomId room identifier
     * @return snapshot interval
     */
    public Duration getSnapshotInterval(String roomId) {
        return getSnapshotInterval(roomId, false);
    }

    /**
     * Remove tracking state for a room (e.g., when a room is destroyed or archived).
     *
     * @param roomId room identifier
     */
    public void remove(String roomId) {
        roomTiers.remove(roomId);
        lastActivity.remove(roomId);
        everVisited.remove(roomId);
    }

    /**
     * Get a snapshot of all tracked room tiers (for diagnostics).
     *
     * @return unmodifiable map of roomId to tier
     */
    public Map<String, ReplicationTier> allTiers() {
        return Map.copyOf(roomTiers);
    }

    /**
     * Number of rooms being tracked (for testing).
     */
    int trackedRoomCount() {
        return roomTiers.size();
    }
}
