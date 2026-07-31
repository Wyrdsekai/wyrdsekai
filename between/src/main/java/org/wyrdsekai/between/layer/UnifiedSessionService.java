package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified session management (Wave 5: Unified Sessions).
 *
 * One member = one session, regardless of how many devices or nodes.
 * Session state lives in the Between mesh (NATS gossip), keyed by member DID.
 * Any device connecting with that DID joins the existing session.
 *
 * Preferences (locale, accessibility, inference) follow the member across
 * devices and nodes. Set once, applies everywhere.
 */
public final class UnifiedSessionService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedSessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * Unified session state for a member.
     * Replicated across all nodes via NATS gossip.
     */
    public record MemberSession(
        @JsonProperty("memberId") String memberId,        // DID or user ID
        @JsonProperty("displayName") String displayName,
        @JsonProperty("currentRoom") String currentRoom,
        @JsonProperty("hostNodeId") String hostNodeId,    // node primarily hosting this session
        @JsonProperty("devices") List<DeviceConnection> devices,
        @JsonProperty("preferences") SessionPreferences preferences,
        @JsonProperty("lastActivity") Instant lastActivity,
        @JsonProperty("createdAt") Instant createdAt
    ) {
        @JsonCreator
        public MemberSession {}

        /** Create initial session for a new connection. */
        public static MemberSession create(String memberId, String displayName,
                                            String startRoom, String hostNodeId) {
            return new MemberSession(
                memberId, displayName, startRoom, hostNodeId,
                new ArrayList<>(), SessionPreferences.defaults(),
                Instant.now(), Instant.now()
            );
        }

        /** Session with updated room. */
        public MemberSession withRoom(String roomId) {
            return new MemberSession(memberId, displayName, roomId, hostNodeId,
                devices, preferences, Instant.now(), createdAt);
        }

        /** Session with updated preferences. */
        public MemberSession withPreferences(SessionPreferences prefs) {
            return new MemberSession(memberId, displayName, currentRoom, hostNodeId,
                devices, prefs, Instant.now(), createdAt);
        }

        /** Session with updated host node. */
        public MemberSession withHostNode(String nodeId) {
            return new MemberSession(memberId, displayName, currentRoom, nodeId,
                devices, preferences, Instant.now(), createdAt);
        }
    }

    /**
     * A device connection within a unified session.
     */
    public record DeviceConnection(
        @JsonProperty("deviceId") String deviceId,
        @JsonProperty("nodeId") String nodeId,       // node this device is connected to
        @JsonProperty("connectedAt") Instant connectedAt,
        @JsonProperty("isProxy") boolean isProxy     // thin proxy mode
    ) {
        @JsonCreator
        public DeviceConnection {}
    }

    /**
     * Member preferences that follow the session.
     */
    public record SessionPreferences(
        @JsonProperty("locale") String locale,
        @JsonProperty("verbosity") String verbosity,       // brief, normal, verbose
        @JsonProperty("hintDisplay") boolean hintDisplay,
        @JsonProperty("proseDensity") String proseDensity,  // sparse, normal, rich
        @JsonProperty("notificationLevel") String notificationLevel,  // silent, important, all
        @JsonProperty("preferLocalInference") boolean preferLocalInference,
        @JsonProperty("budgetSensitive") boolean budgetSensitive
    ) {
        @JsonCreator
        public SessionPreferences {}

        public static SessionPreferences defaults() {
            return new SessionPreferences(
                "en", "normal", true, "normal", "important", false, false);
        }
    }

    // ── State ──

    private final NatsBridge nats;
    private final String localNodeId;

    /** Active sessions: memberId → session. Replicated via NATS gossip. */
    private final ConcurrentHashMap<String, MemberSession> sessions = new ConcurrentHashMap<>();

    /** Local device connections: sessionId (ws/ssh/telnet) → memberId. */
    private final ConcurrentHashMap<String, String> localConnections = new ConcurrentHashMap<>();

    public UnifiedSessionService(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    /**
     * Member connects — join existing session or create new one.
     * @return the unified session (existing or newly created)
     */
    public MemberSession memberConnect(String memberId, String displayName,
                                        String deviceId, String startRoom) {
        var existing = sessions.get(memberId);
        if (existing != null) {
            // Join existing session — add device, follow the session's room
            var devices = new ArrayList<>(existing.devices());
            devices.add(new DeviceConnection(deviceId, localNodeId, Instant.now(), false));
            var updated = new MemberSession(
                existing.memberId(), existing.displayName(), existing.currentRoom(),
                existing.hostNodeId(), devices, existing.preferences(),
                Instant.now(), existing.createdAt());
            sessions.put(memberId, updated);
            publishSessionUpdate(updated);
            log.info("Member {} joined existing session (room={}, devices={})",
                memberId, existing.currentRoom(), devices.size());
            return updated;
        }

        // New session
        var session = MemberSession.create(memberId, displayName, startRoom, localNodeId);
        var devices = new ArrayList<>(session.devices());
        devices.add(new DeviceConnection(deviceId, localNodeId, Instant.now(), false));
        session = new MemberSession(session.memberId(), session.displayName(),
            session.currentRoom(), session.hostNodeId(), devices,
            session.preferences(), session.lastActivity(), session.createdAt());
        sessions.put(memberId, session);
        publishSessionUpdate(session);
        log.info("New session for member {} (room={}, node={})",
            memberId, startRoom, localNodeId);
        return session;
    }

    /**
     * Device disconnects from a session.
     * Session continues if other devices remain.
     */
    public void deviceDisconnect(String memberId, String deviceId) {
        var session = sessions.get(memberId);
        if (session == null) return;

        var devices = session.devices().stream()
            .filter(d -> !d.deviceId().equals(deviceId))
            .toList();

        if (devices.isEmpty()) {
            // Last device disconnected — session becomes dormant (but stays in mesh)
            var updated = new MemberSession(session.memberId(), session.displayName(),
                session.currentRoom(), session.hostNodeId(), devices,
                session.preferences(), Instant.now(), session.createdAt());
            sessions.put(memberId, updated);
            publishSessionUpdate(updated);
            log.info("Member {} last device disconnected — session dormant", memberId);
        } else {
            var updated = new MemberSession(session.memberId(), session.displayName(),
                session.currentRoom(), session.hostNodeId(), List.copyOf(devices),
                session.preferences(), Instant.now(), session.createdAt());
            sessions.put(memberId, updated);
            publishSessionUpdate(updated);
            log.debug("Member {} device {} disconnected ({} remaining)",
                memberId, deviceId, devices.size());
        }
    }

    /**
     * Member moves rooms — all devices follow.
     */
    public void memberMove(String memberId, String newRoomId) {
        var session = sessions.get(memberId);
        if (session == null) return;

        var updated = session.withRoom(newRoomId);
        sessions.put(memberId, updated);
        publishSessionUpdate(updated);
    }

    /**
     * Update member preferences — replicated to all nodes.
     */
    public void updatePreferences(String memberId, SessionPreferences prefs) {
        var session = sessions.get(memberId);
        if (session == null) return;

        var updated = session.withPreferences(prefs);
        sessions.put(memberId, updated);
        publishSessionUpdate(updated);
        log.info("Preferences updated for member {} (locale={})", memberId, prefs.locale());
    }

    /** Get a member's session (from local cache or mesh). */
    public Optional<MemberSession> getSession(String memberId) {
        return Optional.ofNullable(sessions.get(memberId));
    }

    /** Get all active sessions. */
    public Map<String, MemberSession> getAllSessions() {
        return Map.copyOf(sessions);
    }

    /** Get the current room for a member (from their session). */
    public Optional<String> getMemberRoom(String memberId) {
        var session = sessions.get(memberId);
        return session != null ? Optional.of(session.currentRoom()) : Optional.empty();
    }

    /** Get preferences for a member. */
    public SessionPreferences getPreferences(String memberId) {
        var session = sessions.get(memberId);
        return session != null ? session.preferences() : SessionPreferences.defaults();
    }

    // ── NATS replication ──

    /**
     * Start subscribing to session updates from other nodes.
     */
    public void startReplication() {
        nats.subscribeBroadcast("session", "update", env -> {
            try {
                var session = MAPPER.convertValue(env.payload(), MemberSession.class);
                // Update local cache (remote sessions)
                sessions.put(session.memberId(), session);
                log.debug("Session replicated: {} on node {}", session.memberId(), session.hostNodeId());
            } catch (Exception e) {
                log.warn("Failed to parse session update: {}", e.getMessage());
            }
        });
        log.info("UnifiedSessionService: replication started");
    }

    private void publishSessionUpdate(MemberSession session) {
        if (nats != null) {
            nats.broadcast("session", "update", MAPPER.valueToTree(session));
        }
    }
}
