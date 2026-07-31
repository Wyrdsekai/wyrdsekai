package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.core.identity.PlayerPresence;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player presence layer for The Between.
 *
 * <p>Publishes and subscribes to player presence updates via NATS, maintaining
 * a local map of all online players across the household. Simple pub/sub model
 * with no complex actor machinery — presence is ephemeral and eventually consistent.</p>
 *
 * <p>NATS subjects:
 * <pre>
 *   between.{zone}.*.*.presence.update    — player connected, moved, or refreshed
 *   between.{zone}.*.*.presence.offline   — player disconnected
 * </pre></p>
 *
 * <p>Stale presence entries (no heartbeat for 2x heartbeat interval) are evicted
 * when queried. The Between heartbeat tick is expected to call {@link #evictStale()}
 * periodically.</p>
 */
public class PresenceLayer {

    private static final Logger log = LoggerFactory.getLogger(PresenceLayer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** How long before a presence entry is considered stale and evicted. */
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(90);

    /** Listener for remote presence changes — injected by server to update local rooms. */
    public interface RemotePresenceListener {
        void onRemoteEnter(String did, String displayName, String roomId);
        void onRemoteLeave(String did, String previousRoomId);
        void onRemoteMove(String did, String displayName, String fromRoomId, String toRoomId);
    }

    private final NatsBridge nats;
    private final String localNodeId;
    private volatile RemotePresenceListener listener;

    /** All known player presences: did -> PlayerPresence. */
    private final ConcurrentHashMap<String, PlayerPresence> presences = new ConcurrentHashMap<>();

    public PresenceLayer(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    /** Set a listener for remote presence changes (enter/leave/move). */
    public void setRemotePresenceListener(RemotePresenceListener listener) {
        this.listener = listener;
    }

    /**
     * Start listening for presence updates from other nodes.
     */
    public void start() {
        nats.subscribeLayer("presence", env -> {
            var payload = env.payload();
            var type = payload.has("type") ? payload.get("type").asText() : "unknown";
            switch (type) {
                case "update" -> handlePresenceUpdate(payload);
                case "offline" -> handlePresenceOffline(payload);
                default -> log.debug("PresenceLayer: unknown message type: {}", type);
            }
        });
        log.info("PresenceLayer started on node {}", localNodeId);
    }

    /**
     * Publish a presence update when a player connects or moves.
     */
    public void publishPresence(PlayerPresence presence) {
        presences.put(presence.did(), presence);

        var payload = MAPPER.createObjectNode();
        payload.put("type", "update");
        payload.put("did", presence.did());
        payload.put("displayName", presence.displayName());
        payload.put("nodeId", presence.nodeId());
        payload.put("roomId", presence.roomId());
        payload.put("lastSeen", presence.lastSeen().getEpochSecond());

        nats.broadcast("presence", "update", payload);
        log.debug("Published presence: {} in {} on {}", presence.did(), presence.roomId(), presence.nodeId());
    }

    /**
     * Publish an offline notice when a player disconnects.
     */
    public void publishOffline(String did) {
        presences.remove(did);

        var payload = MAPPER.createObjectNode();
        payload.put("type", "offline");
        payload.put("did", did);
        payload.put("nodeId", localNodeId);

        nats.broadcast("presence", "offline", payload);
        log.debug("Published offline: {}", did);
    }

    /**
     * Get the current presence for a specific player.
     */
    public Optional<PlayerPresence> getPresence(String did) {
        return Optional.ofNullable(presences.get(did));
    }

    /**
     * Get all known online players.
     */
    public Collection<PlayerPresence> allPresences() {
        return Collections.unmodifiableCollection(presences.values());
    }

    /**
     * Get the number of online players.
     */
    public int onlineCount() {
        return presences.size();
    }

    /**
     * Evict stale presence entries (no update for > STALE_THRESHOLD).
     * Call periodically from heartbeat tick.
     */
    public void evictStale() {
        var cutoff = Instant.now().minus(STALE_THRESHOLD);
        presences.entrySet().removeIf(entry -> {
            if (entry.getValue().lastSeen().isBefore(cutoff)) {
                log.debug("Evicting stale presence: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // --- Internal handlers ---

    private void handlePresenceUpdate(JsonNode payload) {
        var did = payload.get("did").asText();
        var displayName = payload.has("displayName") ? payload.get("displayName").asText() : "unknown";
        var nodeId = payload.get("nodeId").asText();
        var roomId = payload.has("roomId") ? payload.get("roomId").asText() : "unknown";
        var lastSeen = payload.has("lastSeen")
            ? Instant.ofEpochSecond(payload.get("lastSeen").asLong())
            : Instant.now();

        // Skip our own node's presences — we already have them locally
        if (localNodeId.equals(nodeId)) return;

        var previous = presences.get(did);
        var presence = new PlayerPresence(did, displayName, nodeId, roomId, lastSeen);
        presences.put(did, presence);

        // Fire listener for remote presence changes
        if (listener != null) {
            if (previous == null) {
                listener.onRemoteEnter(did, displayName, roomId);
            } else if (!previous.roomId().equals(roomId)) {
                listener.onRemoteMove(did, displayName, previous.roomId(), roomId);
            }
        }

        log.debug("Presence update: {} in {} on {} (remote)", did, roomId, nodeId);
    }

    private void handlePresenceOffline(JsonNode payload) {
        var did = payload.get("did").asText();
        var previous = presences.remove(did);

        // Fire listener
        if (listener != null && previous != null) {
            listener.onRemoteLeave(did, previous.roomId());
        }

        log.debug("Presence offline: {}", did);
    }
}
