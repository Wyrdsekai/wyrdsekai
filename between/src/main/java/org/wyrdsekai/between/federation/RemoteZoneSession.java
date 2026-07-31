package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Manages a player's proxied session to a remote zone over NATS.
 *
 * This is the "browser" analogy: the local server renders remote zone content,
 * the player never disconnects from their local WebSocket. Commands flow
 * from the local server to the remote zone via NATS, and events flow back.
 *
 * <pre>
 * Commands:  federation.{remoteZoneId}.session.{sessionId}.cmd
 * Events:    federation.{localZoneId}.session.{sessionId}.evt
 * Open:      federation.{remoteZoneId}.session.open
 * Close:     federation.{remoteZoneId}.session.close
 * </pre>
 */
public final class RemoteZoneSession {

    private static final Logger log = LoggerFactory.getLogger(RemoteZoneSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final String sessionId;
    private final String playerId;
    private final String playerName;
    private final String transitToken;
    private final String localZoneId;
    private final String remoteZoneId;
    private final RelaySessionTransport relay;
    private final Consumer<String> eventCallback;

    /** NATS dispatcher for event subscription (stored for cleanup). */
    private volatile Object eventDispatcher; // io.nats.client.Dispatcher wrapped
    /** NATS dispatcher for inventory delta subscription. */
    private volatile Object deltaDispatcher;
    /** Callback for incoming inventory delta on session close. */
    private volatile Consumer<TransitInventory.TransitDelta> deltaCallback;
    private volatile boolean active;

    public RemoteZoneSession(String playerId, String playerName,
                             String transitToken,
                             String localZoneId, String remoteZoneId,
                             RelaySessionTransport relay,
                             Consumer<String> eventCallback) {
        this.sessionId = UUID.randomUUID().toString();
        this.playerId = playerId;
        this.playerName = playerName;
        this.transitToken = transitToken;
        this.localZoneId = localZoneId;
        this.remoteZoneId = remoteZoneId;
        this.relay = relay;
        this.eventCallback = eventCallback;
    }

    /**
     * Open the remote session: subscribe to incoming events, then publish
     * session.open to the remote zone. No inventory carried.
     */
    public void open() {
        open(null);
    }

    /** Register a callback invoked when the remote zone sends an inventory delta back (on session close). */
    public void setDeltaCallback(Consumer<TransitInventory.TransitDelta> callback) {
        this.deltaCallback = callback;
    }

    /**
     * Open the remote session with carried inventory.
     * The player's items travel with them to the remote zone as session-scoped virtual inventory.
     */
    public void open(TransitInventory inventory) {
        open(inventory, null);
    }

    /**
     * Open the remote session with carried inventory + reputation snapshot.
     * Reputation gives the destination zone granular permission data beyond base trust level.
     */
    public void open(TransitInventory inventory,
                     TransitReputation reputation) {
        if (active) {
            log.warn("RemoteZoneSession already open: {}", sessionId);
            return;
        }

        if (relay == null || !relay.isConnected()) {
            log.error("Cannot open remote session — relay not connected");
            return;
        }

        // Subscribe to events directly on the relay connection (not local NATS).
        var eventSubject = "federation." + localZoneId + ".session." + sessionId + ".evt";
        eventDispatcher = relay.subscribe(eventSubject, data -> {
            try {
                var json = new String(data, StandardCharsets.UTF_8);
                eventCallback.accept(json);
            } catch (Exception e) {
                log.error("Error processing remote event for session {}: {}",
                    sessionId, e.getMessage());
            }
        });

        // Subscribe to inventory delta (sent by remote zone on session close).
        var deltaSubject = "federation." + localZoneId + ".session." + sessionId + ".inventory_delta";
        deltaDispatcher = relay.subscribe(deltaSubject, data -> {
            try {
                var delta = MAPPER.readValue(data,
                    TransitInventory.TransitDelta.class);
                log.info("Received inventory delta for session {}: -{} +{}",
                    sessionId, delta.removedItemIds().size(), delta.addedItems().size());
                if (deltaCallback != null) deltaCallback.accept(delta);
            } catch (Exception e) {
                log.error("Error processing inventory delta for session {}: {}",
                    sessionId, e.getMessage());
            }
        });

        // Publish session.open to remote zone
        var openSubject = "federation." + remoteZoneId + ".session.open";
        var payload = MAPPER.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("transitToken", transitToken);
        payload.put("playerId", playerId);
        payload.put("playerName", playerName);
        payload.put("localZoneId", localZoneId);
        if (inventory != null && !inventory.items().isEmpty()) {
            payload.set("inventory", MAPPER.valueToTree(inventory));
            log.info("Carrying {} items to remote zone", inventory.items().size());
        }
        if (reputation != null) {
            payload.set("reputation", MAPPER.valueToTree(reputation));
            log.info("Carrying reputation to remote zone: score={} tier={}",
                reputation.compositeScore(), reputation.permissionTier());
        }
        publish(openSubject, payload);

        active = true;
        log.info("Remote session opened: {} → zone '{}' (player={})",
            sessionId, remoteZoneId, playerName);
    }

    /**
     * Forward a command to the remote zone.
     *
     * @param type command type (e.g. "say", "go", "look", "take", "drop", "use", "emote")
     * @param json command-specific JSON payload
     */
    public void sendCommand(String type, String json) {
        if (!active) {
            log.warn("Cannot send command — remote session not active: {}", sessionId);
            return;
        }

        var cmdSubject = "federation." + remoteZoneId + ".session." + sessionId + ".cmd";
        var payload = MAPPER.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("type", type);
        payload.put("payload", json);
        payload.put("sentAt", System.currentTimeMillis());
        log.debug("Sending command '{}' to {} (session={})", type, cmdSubject, sessionId);
        publish(cmdSubject, payload);
    }

    /**
     * Close the remote session: notify remote zone, unsubscribe from events.
     */
    public void close() {
        if (!active) return;
        active = false;

        // Publish session.close to remote zone
        var closeSubject = "federation." + remoteZoneId + ".session.close";
        var payload = MAPPER.createObjectNode();
        payload.put("sessionId", sessionId);
        payload.put("playerId", playerId);
        publish(closeSubject, payload);

        // Unsubscribe from events on relay
        if (eventDispatcher != null && relay != null) {
            relay.closeDispatcherObj(eventDispatcher);
            eventDispatcher = null;
        }
        // Keep deltaDispatcher subscribed briefly to receive final inventory delta.
        // It's closed in closeDelta() after the delta has been applied.

        log.info("Remote session closed: {} (player={})", sessionId, playerName);
    }

    /** Close the inventory delta subscription. Called after delta is received (or after timeout). */
    public void closeDelta() {
        if (deltaDispatcher != null && relay != null) {
            relay.closeDispatcherObj(deltaDispatcher);
            deltaDispatcher = null;
        }
    }

    public boolean isActive() {
        return active;
    }

    public String sessionId() {
        return sessionId;
    }

    public String playerId() {
        return playerId;
    }

    public String remoteZoneId() {
        return remoteZoneId;
    }

    // --- Internal ---

    private void publish(String subject, ObjectNode payload) {
        try {
            relay.publish(subject, MAPPER.writeValueAsBytes(payload));
        } catch (Exception e) {
            log.error("Failed to publish to {}: {}", subject, e.getMessage());
        }
    }
}
