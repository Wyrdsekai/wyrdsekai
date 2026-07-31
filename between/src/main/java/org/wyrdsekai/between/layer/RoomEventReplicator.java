package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.topology.ReplicationTier;
import org.wyrdsekai.core.room.RoomEventListener;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Publishes room events to NATS for event-sourced and write-through rooms.
 *
 * <p>This component bridges the core module's {@link RoomEventListener} callback
 * to NATS publication. It is used by the {@link RoomLayer} to replicate room
 * state across household nodes.</p>
 *
 * <p>For event-sourced rooms (Tier 1), every persisted event is published to
 * {@code zone.{zoneId}.rooms.{roomId}.events} in real time.</p>
 *
 * <p>For write-through rooms (Tier 2), the event publication is used to trigger
 * snapshot publication (the actual snapshot is published by the RoomLayer on
 * its snapshot tick, which checks the tier).</p>
 *
 * <p>NATS subject format:</p>
 * <pre>
 *   zone.{zoneId}.rooms.{roomId}.events    — real-time event stream (Tier 1)
 *   zone.{zoneId}.rooms.{roomId}.snapshot  — full room snapshot (Tier 2-4)
 *   zone.{zoneId}.rooms.{roomId}.request   — request a snapshot from primary
 * </pre>
 */
public class RoomEventReplicator implements RoomEventListener {

    private static final Logger log = LoggerFactory.getLogger(RoomEventReplicator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final NatsBridge nats; // nullable — single-node mode
    private final String zoneId;
    private final boolean broadcastAll; // when true, publish ALL events regardless of tier
    private final Set<String> eventSourcedRooms = ConcurrentHashMap.newKeySet();
    private final Set<String> writeThroughRooms = ConcurrentHashMap.newKeySet();

    /** Get the local Between node ID (for dedup filtering in subscribeToAllEvents). */
    public String getLocalNodeId() {
        return nats != null ? nats.nodeId() : "local";
    }

    // Callback for write-through snapshot triggers
    private volatile SnapshotTrigger snapshotTrigger;

    /**
     * Callback interface for triggering immediate snapshot publication
     * when a write-through room changes state.
     */
    @FunctionalInterface
    public interface SnapshotTrigger {
        void triggerSnapshot(String roomId);
    }

    public RoomEventReplicator(NatsBridge nats, String zoneId) {
        this(nats, zoneId, false);
    }

    /**
     * @param nats         NATS bridge (nullable for single-node mode)
     * @param zoneId       zone identifier for NATS subjects
     * @param broadcastAll when {@code true}, every room event is published to NATS
     *                     regardless of the room's replication tier — used by the
     *                     external event bridge (e.g. Claude room-resident bridge)
     */
    public RoomEventReplicator(NatsBridge nats, String zoneId, boolean broadcastAll) {
        this.nats = nats;
        this.zoneId = zoneId;
        this.broadcastAll = broadcastAll;
    }

    /**
     * Set a callback for write-through snapshot triggers.
     * Called when a write-through room persists an event, signaling
     * that a snapshot should be published immediately.
     */
    public void setSnapshotTrigger(SnapshotTrigger trigger) {
        this.snapshotTrigger = trigger;
    }

    /**
     * Enable event-sourcing for a room. Events will be published to NATS in real time.
     *
     * @param roomId room to enable
     */
    public void enableEventSourcing(String roomId) {
        eventSourcedRooms.add(roomId);
        writeThroughRooms.remove(roomId); // can't be both
        log.info("Event-sourcing enabled for room {}", roomId);
    }

    /**
     * Enable write-through for a room. State changes trigger immediate snapshot publication.
     *
     * @param roomId room to enable
     */
    public void enableWriteThrough(String roomId) {
        writeThroughRooms.add(roomId);
        eventSourcedRooms.remove(roomId); // can't be both
        log.info("Write-through enabled for room {}", roomId);
    }

    /**
     * Disable event-sourcing and write-through for a room (demote to periodic/lazy/config).
     *
     * @param roomId room to disable
     */
    public void disableReplication(String roomId) {
        boolean wasEventSourced = eventSourcedRooms.remove(roomId);
        boolean wasWriteThrough = writeThroughRooms.remove(roomId);
        if (wasEventSourced || wasWriteThrough) {
            log.info("Real-time replication disabled for room {}", roomId);
        }
    }

    /**
     * Update the replication mode for a room based on its tier.
     *
     * @param roomId room identifier
     * @param tier   new replication tier
     */
    public void updateTier(String roomId, ReplicationTier tier) {
        switch (tier) {
            case EVENT_SOURCED -> enableEventSourcing(roomId);
            case WRITE_THROUGH -> enableWriteThrough(roomId);
            default -> disableReplication(roomId);
        }
    }

    /**
     * Called by RoomActor (via RoomEventListener) after an event is persisted.
     * Publishes the event to NATS for event-sourced rooms, or triggers a
     * snapshot for write-through rooms.
     */
    @Override
    public void onRoomEvent(String roomId, WorldEvent event) {
        if (broadcastAll) {
            // Broadcast mode: publish every event regardless of tier.
            // Used by the external event bridge (e.g. Claude room-resident bridge)
            // so that NATS subscribers receive all room activity.
            publishEvent(roomId, event);
        } else if (eventSourcedRooms.contains(roomId)) {
            publishEvent(roomId, event);
        } else if (writeThroughRooms.contains(roomId)) {
            triggerWriteThroughSnapshot(roomId);
        }
    }

    /**
     * Publish a single room event to NATS (event-sourced mode).
     *
     * @param roomId room where the event occurred
     * @param event  the world event to publish
     */
    public void publishEvent(String roomId, WorldEvent event) {
        if (nats == null || !nats.isConnected()) {
            log.debug("Skipping event publish for room {} — NATS not connected", roomId);
            return;
        }

        try {
            var eventNode = MAPPER.valueToTree(event);
            var wrapper = MAPPER.createObjectNode();
            wrapper.put("type", "room_event");
            wrapper.put("roomId", roomId);
            wrapper.put("zoneId", zoneId);
            wrapper.set("event", (ObjectNode) eventNode);

            var subject = "zone." + zoneId + ".rooms." + roomId + ".events";
            nats.broadcast("rooms", roomId + ".events", wrapper);

            log.debug("Published event for room {}: {}", roomId, event.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("Failed to publish event for room {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Trigger an immediate snapshot for a write-through room.
     */
    private void triggerWriteThroughSnapshot(String roomId) {
        var trigger = this.snapshotTrigger;
        if (trigger != null) {
            try {
                trigger.triggerSnapshot(roomId);
            } catch (Exception e) {
                log.error("Failed to trigger write-through snapshot for room {}: {}", roomId, e.getMessage());
            }
        }
    }

    /**
     * Subscribe to room events from NATS for a specific room (catch-up / replica mode).
     * The handler receives each event as it arrives.
     *
     * @param roomId  room to subscribe to
     * @param handler callback for each received event
     */
    public void subscribeToEvents(String roomId, Consumer<WorldEvent> handler) {
        if (nats == null || !nats.isConnected()) {
            log.debug("Cannot subscribe to room {} events — NATS not connected", roomId);
            return;
        }

        nats.subscribeBroadcast("rooms", roomId + ".events", envelope -> {
            try {
                var payload = envelope.payload();
                if (payload.has("event")) {
                    var event = MAPPER.treeToValue(payload.get("event"), WorldEvent.class);
                    handler.accept(event);
                }
            } catch (Exception e) {
                log.error("Failed to deserialize event for room {}: {}", roomId, e.getMessage());
            }
        });

        log.info("Subscribed to event stream for room {}", roomId);
    }

    /**
     * Request a snapshot from the primary for catch-up.
     * Publishes a request on {@code zone.{zoneId}.rooms.{roomId}.request}.
     *
     * @param roomId room to request a snapshot for
     */
    public void requestSnapshot(String roomId) {
        if (nats == null || !nats.isConnected()) {
            log.debug("Cannot request snapshot for room {} — NATS not connected", roomId);
            return;
        }

        try {
            var request = MAPPER.createObjectNode();
            request.put("type", "snapshot_request");
            request.put("roomId", roomId);
            request.put("zoneId", zoneId);

            nats.broadcast("rooms", roomId + ".request", request);
            log.info("Requested snapshot for room {}", roomId);
        } catch (Exception e) {
            log.error("Failed to request snapshot for room {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Callback for receiving remote room events with their room ID.
     */
    @FunctionalInterface
    public interface RemoteEventHandler {
        void onRemoteEvent(String roomId, WorldEvent event);
    }

    /**
     * Subscribe to ALL room events from NATS (wildcard).
     * The handler receives the roomId and event for each remote event.
     * Used to inject remote events into local room actors.
     */
    public void subscribeToAllEvents(String localNodeId, RemoteEventHandler handler) {
        if (nats == null || !nats.isConnected()) {
            log.debug("Cannot subscribe to all room events — NATS not connected");
            return;
        }

        nats.subscribeLayer("rooms", envelope -> {
            try {
                // Skip events from our own node (already delivered locally)
                if (localNodeId.equals(envelope.src())) return;

                var payload = envelope.payload();
                if (payload.has("type") && "room_event".equals(payload.get("type").asText())
                        && payload.has("event") && payload.has("roomId")) {
                    var roomId = payload.get("roomId").asText();
                    var event = MAPPER.treeToValue(payload.get("event"), WorldEvent.class);
                    handler.onRemoteEvent(roomId, event);
                }
            } catch (Exception e) {
                log.debug("Failed to deserialize remote room event: {}", e.getMessage());
            }
        });

        log.info("Subscribed to all remote room events (filtering local node {})", localNodeId);
    }

    /**
     * Whether a room is currently in event-sourced mode.
     */
    public boolean isEventSourced(String roomId) {
        return eventSourcedRooms.contains(roomId);
    }

    /**
     * Whether a room is currently in write-through mode.
     */
    public boolean isWriteThrough(String roomId) {
        return writeThroughRooms.contains(roomId);
    }

    /**
     * Number of event-sourced rooms (for testing).
     */
    int eventSourcedCount() {
        return eventSourcedRooms.size();
    }

    /**
     * Number of write-through rooms (for testing).
     */
    int writeThroughCount() {
        return writeThroughRooms.size();
    }
}
