package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Room primary ownership protocol (Wave 4: Room Ownership).
 *
 * Each shared room has one primary node (authoritative for state)
 * and zero or more replicas (receive events, serve reads).
 *
 * Protocol:
 *   - Node starts → check room requirements vs capabilities
 *   - If capable: listen for existing primary heartbeat (5s)
 *   - If no heartbeat: claim primary
 *   - If heartbeat heard: become replica
 *   - Primary publishes heartbeat every 10s
 *   - If primary dies (30s timeout): highest-scoring capable replica claims
 *
 * Conflict resolution (same as companion): timestamp → score → lexicographic nodeId.
 */
public final class RoomPrimaryProtocol {

    private static final Logger log = LoggerFactory.getLogger(RoomPrimaryProtocol.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Room primary heartbeat message.
     * Epoch is a monotonic counter incremented on every new claim.
     * Fencing: commands with stale epochs are rejected.
     */
    public record RoomPrimaryHeartbeat(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("entityCount") int entityCount,
        @JsonProperty("score") double score,
        @JsonProperty("epoch") long epoch,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public RoomPrimaryHeartbeat {}
    }

    /** Listener for room primary ownership events. */
    public interface RoomPrimaryListener {
        /** This node should become primary for the room. */
        void onClaimPrimary(String roomId);
        /** This node should become replica for the room (another node is primary). */
        void onBecomeReplica(String roomId, String primaryNodeId);
        /** Primary is down — failover needed. */
        void onPrimaryDown(String roomId, String lastPrimaryNodeId);
    }

    private final NatsBridge nats;
    private final String localNodeId;
    private final PlacementEngine placementEngine;
    private final NodeCapabilities localCapabilities;
    private final Duration claimDelay;

    /** Track room primary heartbeats: roomId → last heartbeat. */
    private final ConcurrentHashMap<String, RoomPrimaryHeartbeat> roomPrimaryHeartbeats = new ConcurrentHashMap<>();

    /** Rooms this node is primary for. */
    private final Set<String> localPrimaries = ConcurrentHashMap.newKeySet();

    /** Monotonic epoch counter — incremented on every new primary claim. */
    private final AtomicLong epochCounter = new AtomicLong(
        System.currentTimeMillis()); // Seed with wall clock so epochs are globally unique-ish

    /** Current epoch per room (for fencing). */
    private final ConcurrentHashMap<String, Long> roomEpochs = new ConcurrentHashMap<>();

    /** Scheduled heartbeat publishers for rooms we're primary on. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> roomHeartbeatPublishers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "room-primary-protocol"); t.setDaemon(true); return t; });

    private volatile RoomPrimaryListener listener;

    public RoomPrimaryProtocol(NatsBridge nats, String localNodeId,
                                PlacementEngine placementEngine,
                                NodeCapabilities localCapabilities) {
        this(nats, localNodeId, placementEngine, localCapabilities, Duration.ofSeconds(5));
    }

    public RoomPrimaryProtocol(NatsBridge nats, String localNodeId,
                                PlacementEngine placementEngine,
                                NodeCapabilities localCapabilities,
                                Duration claimDelay) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.placementEngine = placementEngine;
        this.localCapabilities = localCapabilities;
        this.claimDelay = claimDelay;
    }

    public void setListener(RoomPrimaryListener listener) {
        this.listener = listener;
    }

    /**
     * Initialize room primary claims for all capable rooms.
     * Called on startup after Between is connected.
     *
     * @param roomRequirements map of roomId → required capabilities
     */
    public void initializeRoomClaims(Map<String, Set<String>> roomRequirements) {
        var localCaps = localCapabilities.getCapabilities();

        for (var entry : roomRequirements.entrySet()) {
            var roomId = entry.getKey();
            var required = entry.getValue();

            if (!localCaps.containsAll(required)) {
                log.debug("Room {} requires {} — this node lacks capabilities, skipping", roomId, required);
                continue;
            }

            // Subscribe to primary heartbeats for this room.
            subscribeRoomPrimaryHeartbeat(roomId);

            // Wait claimDelay to see if someone else is already primary
            scheduler.schedule(() -> {
                var existingPrimary = roomPrimaryHeartbeats.get(roomId);
                if (existingPrimary == null) {
                    // No primary heard — claim it
                    claimRoomPrimary(roomId);
                } else {
                    log.info("Room {} already has primary on node {}", roomId, existingPrimary.nodeId());
                    if (listener != null) listener.onBecomeReplica(roomId, existingPrimary.nodeId());
                }
            }, claimDelay.toMillis(), TimeUnit.MILLISECONDS);
        }

        // Also handle rooms with no specific requirements (any node can host)
        // These are handled by the general room claim in RoomLayer
    }

    /**
     * Claim primary ownership for a room.
     */
    public void claimRoomPrimary(String roomId) {
        var epoch = epochCounter.incrementAndGet();
        roomEpochs.put(roomId, epoch);
        localPrimaries.add(roomId);
        localCapabilities.addRoomPrimary(roomId);
        placementEngine.recordRoomPrimaryClaim(roomId, localNodeId);

        // Start publishing heartbeats with epoch
        var future = scheduler.scheduleAtFixedRate(() -> {
            var snap = localCapabilities.snapshot();
            var currentEpoch = roomEpochs.getOrDefault(roomId, epoch);
            var hb = new RoomPrimaryHeartbeat(localNodeId, roomId, 0,
                placementEngine.score(snap), currentEpoch, Instant.now());
            nats.broadcast("room.primary.heartbeat", roomId, MAPPER.valueToTree(hb));
        }, 0, 10, TimeUnit.SECONDS);
        roomHeartbeatPublishers.put(roomId, future);

        log.info("Claimed primary for room {} (epoch={})", roomId, epoch);
        if (listener != null) listener.onClaimPrimary(roomId);
    }

    /**
     * Release primary ownership for a room.
     */
    public void releaseRoomPrimary(String roomId, String reason) {
        localPrimaries.remove(roomId);
        localCapabilities.removeRoomPrimary(roomId);
        placementEngine.releaseRoomPrimaryClaim(roomId);

        var future = roomHeartbeatPublishers.remove(roomId);
        if (future != null) future.cancel(false);

        log.info("Released primary for room {} (reason={})", roomId, reason);
    }

    /** Check if this node is primary for a room. */
    public boolean isPrimary(String roomId) {
        return localPrimaries.contains(roomId);
    }

    /** Get this node's ID. */
    public String getLocalNodeId() { return localNodeId; }

    /** Get the primary node for a room (from last heartbeat). */
    public Optional<String> getPrimaryNode(String roomId) {
        var hb = roomPrimaryHeartbeats.get(roomId);
        return hb != null ? Optional.of(hb.nodeId()) : Optional.empty();
    }

    /** Get all rooms this node is primary for. */
    public Set<String> getLocalPrimaries() {
        return Set.copyOf(localPrimaries);
    }

    /** Get current epoch for a room (for fencing token on mutations). */
    public long getEpoch(String roomId) {
        return roomEpochs.getOrDefault(roomId, 0L);
    }

    /** Get the epoch from the latest heartbeat for a room (from any primary). */
    public long getRemoteEpoch(String roomId) {
        var hb = roomPrimaryHeartbeats.get(roomId);
        return hb != null ? hb.epoch() : 0L;
    }

    /**
     * Validate a fencing token. Returns true if the epoch is current.
     * Used by RoomActor to reject commands from stale primaries.
     */
    public boolean isValidEpoch(String roomId, long epoch) {
        var currentEpoch = roomEpochs.getOrDefault(roomId, 0L);
        if (currentEpoch > 0) return epoch >= currentEpoch;
        // Check remote heartbeat epoch
        var hb = roomPrimaryHeartbeats.get(roomId);
        return hb == null || epoch >= hb.epoch();
    }

    /**
     * Start the timeout checker for room primaries.
     */
    public void startPrimaryTimeoutChecker(Duration timeout) {
        scheduler.scheduleAtFixedRate(() -> {
            var now = Instant.now();
            for (var entry : roomPrimaryHeartbeats.entrySet()) {
                var roomId = entry.getKey();
                var hb = entry.getValue();
                // Skip rooms we're primary for
                if (localPrimaries.contains(roomId)) continue;

                var age = Duration.between(hb.timestamp(), now);
                if (age.compareTo(timeout) > 0) {
                    log.warn("Room {} primary timeout (node {}, {}s ago) — reclaiming locally",
                        roomId, hb.nodeId(), age.toSeconds());
                    roomPrimaryHeartbeats.remove(roomId);
                    // Auto-reclaim: if this node can host the room, take over
                    claimRoomPrimary(roomId);
                    log.info("Room {} reclaimed as primary after node {} timeout", roomId, hb.nodeId());
                    if (listener != null) listener.onPrimaryDown(roomId, hb.nodeId());
                }
            }
        }, timeout.toMillis(), 5000, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        roomHeartbeatPublishers.values().forEach(f -> f.cancel(false));
        roomHeartbeatPublishers.clear();
        scheduler.shutdownNow();
    }

    // ── Internal ──

    private void subscribeRoomPrimaryHeartbeat(String roomId) {
        nats.subscribeBroadcast("room.primary.heartbeat", roomId, env -> {
            try {
                var hb = MAPPER.convertValue(env.payload(), RoomPrimaryHeartbeat.class);
                roomPrimaryHeartbeats.put(roomId, hb);
            } catch (Exception e) {
                log.warn("Failed to parse room primary heartbeat for {}: {}", roomId, e.getMessage());
            }
        });
    }
}
