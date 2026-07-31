package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Bridge between NATS room command transport and local room actors.
 *
 * Primary-side: Listens for room commands on NATS, deserializes, forwards to local
 * RoomActor via a callback, serializes response back.
 *
 * Also broadcasts room events and state snapshots so replicas can cache them.
 */
public final class RoomCommandBridge {

    private static final Logger log = LoggerFactory.getLogger(RoomCommandBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private static final String LAYER_ROOM_CMD = "room.command";
    private static final String LAYER_ROOM_EVENT = "room.event";
    private static final String LAYER_ROOM_STATE = "room.state";

    private final NatsBridge nats;
    private final String localNodeId;

    public RoomCommandBridge(NatsBridge nats, String localNodeId) {
        this.nats = nats;
        this.localNodeId = localNodeId;
    }

    /**
     * Start listening for room commands on a specific room (primary side).
     * The handler receives (roomId, commandJson) and returns a CompletionStage of the response JSON.
     */
    public void listenForCommands(String roomId,
                                   BiFunction<String, String, CompletionStage<String>> handler) {
        nats.subscribeRequest(LAYER_ROOM_CMD, roomId, (requestPayload, replySubject) -> {
            try {
                var json = MAPPER.writeValueAsString(requestPayload);
                handler.apply(roomId, json)
                    .whenComplete((responseJson, err) -> {
                        try {
                            if (err != null) {
                                var errorNode = MAPPER.createObjectNode();
                                errorNode.put("type", "rejected");
                                errorNode.put("code", "error");
                                errorNode.put("reason", err.getMessage());
                                nats.respond(replySubject, errorNode);
                            } else {
                                var responseNode = MAPPER.readTree(responseJson);
                                nats.respond(replySubject, responseNode);
                            }
                        } catch (Exception e) {
                            log.warn("Failed to send room command response for {}: {}",
                                roomId, e.getMessage());
                        }
                    });
            } catch (Exception e) {
                log.warn("Failed to process room command for {}: {}", roomId, e.getMessage());
            }
        });
        log.debug("Listening for room commands on {}", roomId);
    }

    /**
     * Send a room command to the primary node (replica side).
     * Returns the response JSON as a CompletionStage.
     */
    public CompletionStage<String> sendCommand(String roomId, String commandJson) {
        try {
            var commandNode = MAPPER.readTree(commandJson);
            return nats.request(LAYER_ROOM_CMD, roomId, commandNode, COMMAND_TIMEOUT)
                .thenApply(responseNode -> {
                    try {
                        return MAPPER.writeValueAsString(responseNode);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to serialize response", e);
                    }
                });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Broadcast a room event to all nodes (primary side, called on every room event).
     */
    public void broadcastEvent(String roomId, WorldEvent event) {
        try {
            var node = MAPPER.valueToTree(event);
            nats.broadcast(LAYER_ROOM_EVENT, roomId, node);
        } catch (Exception e) {
            log.debug("Failed to broadcast room event for {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Subscribe to room events from the primary (replica side).
     */
    public void subscribeEvents(String roomId, Consumer<WorldEvent> handler) {
        nats.subscribeBroadcast(LAYER_ROOM_EVENT, roomId, env -> {
            try {
                var event = MAPPER.convertValue(env.payload(), WorldEvent.class);
                handler.accept(event);
            } catch (Exception e) {
                log.debug("Failed to deserialize room event for {}: {}", roomId, e.getMessage());
            }
        });
    }

    /**
     * Broadcast a room state snapshot (primary side, on significant state changes).
     */
    public void broadcastState(String roomId, RoomSnapshot snapshot) {
        try {
            var node = MAPPER.valueToTree(snapshot);
            nats.broadcast(LAYER_ROOM_STATE, roomId, node);
        } catch (Exception e) {
            log.debug("Failed to broadcast room state for {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Subscribe to room state snapshots from the primary (replica side).
     */
    public void subscribeState(String roomId, Consumer<RoomSnapshot> handler) {
        nats.subscribeBroadcast(LAYER_ROOM_STATE, roomId, env -> {
            try {
                var snapshot = MAPPER.convertValue(env.payload(), RoomSnapshot.class);
                handler.accept(snapshot);
            } catch (Exception e) {
                log.debug("Failed to deserialize room state for {}: {}", roomId, e.getMessage());
            }
        });
    }
}
