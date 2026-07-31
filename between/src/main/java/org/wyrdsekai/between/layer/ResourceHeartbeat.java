package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Heartbeat protocol for companions and room primaries (Wave 2: Node Coordination).
 *
 * Publishes periodic heartbeats via NatsBridge (BetweenEnvelope).
 * Monitors peers for timeout. When a heartbeat is missed for 3 intervals,
 * the resource is considered down and failover is triggered.
 *
 * Uses NatsBridge broadcast/subscribe which wraps payloads in signed BetweenEnvelopes.
 */
public final class ResourceHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(ResourceHeartbeat.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    // NATS layer names
    private static final String LAYER_COMPANION_HB = "companion.heartbeat";
    private static final String LAYER_COMPANION_CLAIM = "companion.claim";
    private static final String LAYER_COMPANION_RELEASE = "companion.release";
    private static final String LAYER_ROOM_HB = "room.primary.heartbeat";
    private static final String LAYER_ROOM_CLAIM = "room.primary.claim";
    private static final String LAYER_ROOM_RELEASE = "room.primary.release";

    /**
     * Heartbeat message payload.
     */
    public record HeartbeatMessage(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("entityId") String entityId,
        @JsonProperty("state") String state,    // IDLE, THINKING, SLEEPING
        @JsonProperty("energy") double energy,
        @JsonProperty("roomId") String roomId,
        @JsonProperty("uptime") long uptimeSeconds,
        @JsonProperty("score") double score,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public HeartbeatMessage {}
    }

    /**
     * Claim message — node asserting ownership of a resource.
     */
    public record ClaimMessage(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("entityId") String entityId,
        @JsonProperty("score") double score,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public ClaimMessage {}
    }

    /**
     * Release message — node relinquishing ownership.
     */
    public record ReleaseMessage(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("entityId") String entityId,
        @JsonProperty("reason") String reason,  // migration, eviction, shutdown, crash
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public ReleaseMessage {}
    }

    /** Listener for heartbeat events. */
    public interface HeartbeatListener {
        void onHeartbeat(HeartbeatMessage hb);
        void onTimeout(String entityId, String lastNodeId);
        void onClaim(ClaimMessage claim);
        void onRelease(ReleaseMessage release);
    }

    // ── State ──

    private final NatsBridge nats;
    private final String localNodeId;
    private final Duration heartbeatInterval;
    private final Duration heartbeatTimeout;
    private final PlacementEngine placementEngine;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> { var t = new Thread(r, "resource-heartbeat"); t.setDaemon(true); return t; });

    /** Track last heartbeat per entity for timeout detection. */
    private final ConcurrentHashMap<String, HeartbeatMessage> lastHeartbeats = new ConcurrentHashMap<>();

    /** Scheduled heartbeat publishers: entityId → future. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> publishers = new ConcurrentHashMap<>();

    private volatile HeartbeatListener listener;

    public ResourceHeartbeat(NatsBridge nats, String localNodeId,
                             PlacementEngine placementEngine,
                             Duration heartbeatInterval, Duration heartbeatTimeout) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.placementEngine = placementEngine;
        this.heartbeatInterval = heartbeatInterval;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public ResourceHeartbeat(NatsBridge nats, String localNodeId,
                             PlacementEngine placementEngine) {
        this(nats, localNodeId, placementEngine,
            Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    public void setListener(HeartbeatListener listener) {
        this.listener = listener;
    }

    // ── Publishing ──

    /**
     * Start publishing heartbeats for a locally-hosted entity.
     */
    public void startPublishing(String entityId, Supplier<HeartbeatMessage> stateSupplier) {
        var existing = publishers.get(entityId);
        if (existing != null) existing.cancel(false);

        var future = scheduler.scheduleAtFixedRate(() -> {
            try {
                var hb = stateSupplier.get();
                var json = toJsonNode(hb);
                nats.broadcast(LAYER_COMPANION_HB, entityId, json);
                lastHeartbeats.put(entityId, hb);
            } catch (Exception e) {
                log.warn("Failed to publish heartbeat for {}: {}", entityId, e.getMessage());
            }
        }, 0, heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);

        publishers.put(entityId, future);
        log.info("Heartbeat publishing started for {} (interval={}s)", entityId, heartbeatInterval.toSeconds());
    }

    /**
     * Stop publishing heartbeats for an entity (migration or shutdown).
     */
    public void stopPublishing(String entityId) {
        var future = publishers.remove(entityId);
        if (future != null) {
            future.cancel(false);
            log.info("Heartbeat publishing stopped for {}", entityId);
        }
    }

    // ── Subscribing ──

    /**
     * Subscribe to heartbeats for a specific entity.
     */
    public void subscribeEntity(String entityId) {
        nats.subscribeBroadcast(LAYER_COMPANION_HB, entityId, env -> {
            try {
                var hb = fromEnvelope(env, HeartbeatMessage.class);
                lastHeartbeats.put(entityId, hb);
                if (listener != null) listener.onHeartbeat(hb);
            } catch (Exception e) {
                log.warn("Failed to deserialize heartbeat for {}: {}", entityId, e.getMessage());
            }
        });

        nats.subscribeBroadcast(LAYER_COMPANION_CLAIM, entityId, env -> {
            try {
                var claim = fromEnvelope(env, ClaimMessage.class);
                if (listener != null) listener.onClaim(claim);
            } catch (Exception e) {
                log.warn("Failed to deserialize claim for {}: {}", entityId, e.getMessage());
            }
        });

        nats.subscribeBroadcast(LAYER_COMPANION_RELEASE, entityId, env -> {
            try {
                var release = fromEnvelope(env, ReleaseMessage.class);
                if (listener != null) listener.onRelease(release);
            } catch (Exception e) {
                log.warn("Failed to deserialize release for {}: {}", entityId, e.getMessage());
            }
        });
    }

    /**
     * Start the timeout checker that monitors all tracked entities.
     */
    public void startTimeoutChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            var now = Instant.now();
            for (var entry : lastHeartbeats.entrySet()) {
                var entityId = entry.getKey();
                var lastHb = entry.getValue();
                if (publishers.containsKey(entityId)) continue;

                var age = Duration.between(lastHb.timestamp(), now);
                if (age.compareTo(heartbeatTimeout) > 0) {
                    log.warn("Heartbeat timeout for {} (last from node {}, {}s ago)",
                        entityId, lastHb.nodeId(), age.toSeconds());
                    lastHeartbeats.remove(entityId);
                    placementEngine.markNodeDown(lastHb.nodeId());
                    if (listener != null) listener.onTimeout(entityId, lastHb.nodeId());
                }
            }
        }, heartbeatTimeout.toMillis(), heartbeatInterval.toMillis() / 2, TimeUnit.MILLISECONDS);
    }

    // ── Claim/Release ──

    public void publishClaim(String entityId, double score) {
        var msg = new ClaimMessage(localNodeId, entityId, score, Instant.now());
        nats.broadcast(LAYER_COMPANION_CLAIM, entityId, toJsonNode(msg));
        placementEngine.recordCompanionClaim(entityId, localNodeId);
        log.info("Published claim for {} (score={})", entityId, score);
    }

    public void publishRelease(String entityId, String reason) {
        var msg = new ReleaseMessage(localNodeId, entityId, reason, Instant.now());
        nats.broadcast(LAYER_COMPANION_RELEASE, entityId, toJsonNode(msg));
        placementEngine.releaseCompanionClaim(entityId);
        stopPublishing(entityId);
        log.info("Published release for {} (reason={})", entityId, reason);
    }

    /** Room primary claim. */
    public void publishRoomPrimaryClaim(String roomId, double score) {
        var msg = new ClaimMessage(localNodeId, roomId, score, Instant.now());
        nats.broadcast(LAYER_ROOM_CLAIM, roomId, toJsonNode(msg));
        placementEngine.recordRoomPrimaryClaim(roomId, localNodeId);
    }

    /** Room primary release. */
    public void releaseRoomPrimary(String roomId, String reason) {
        var msg = new ReleaseMessage(localNodeId, roomId, reason, Instant.now());
        nats.broadcast(LAYER_ROOM_RELEASE, roomId, toJsonNode(msg));
        placementEngine.releaseRoomPrimaryClaim(roomId);
    }

    /**
     * Check if a companion is already claimed by listening for heartbeats.
     * Blocks for the specified duration.
     */
    public Optional<HeartbeatMessage> listenForExistingClaim(String entityId, Duration listenDuration) {
        var result = new CompletableFuture<HeartbeatMessage>();

        nats.subscribeBroadcast(LAYER_COMPANION_HB, entityId, env -> {
            try {
                var hb = fromEnvelope(env, HeartbeatMessage.class);
                // Ignore our own stale heartbeats from a previous process
                if (localNodeId.equals(hb.nodeId())) return;
                result.complete(hb);
            } catch (Exception ignored) {}
        });

        try {
            return Optional.of(result.get(listenDuration.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Error listening for claim on {}: {}", entityId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Cleanup ──

    public void shutdown() {
        publishers.values().forEach(f -> f.cancel(false));
        publishers.clear();
        scheduler.shutdownNow();
    }

    public Optional<HeartbeatMessage> getLastHeartbeat(String entityId) {
        return Optional.ofNullable(lastHeartbeats.get(entityId));
    }

    public Map<String, HeartbeatMessage> getAllHeartbeats() {
        return Map.copyOf(lastHeartbeats);
    }

    // ── Serialization helpers ──

    private JsonNode toJsonNode(Object obj) {
        return MAPPER.valueToTree(obj);
    }

    private <T> T fromEnvelope(BetweenEnvelope env, Class<T> type) {
        return MAPPER.convertValue(env.payload(), type);
    }
}
