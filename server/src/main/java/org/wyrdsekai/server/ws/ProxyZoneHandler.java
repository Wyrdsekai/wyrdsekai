package org.wyrdsekai.server.ws;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bridges a remote zone service's WebSocket connection to Wyrdsekai's
 * {@link ZoneCommandHandler} interface.
 * <p>
 * When a player sends a namespaced command (e.g. {@code codeplane.approve}),
 * this handler serializes it as a {@link ZoneBridgeMessage.ForwardCommand}
 * and sends it over the zone service's WS connection. When the service responds
 * with a {@link ZoneBridgeMessage.CommandResponse}, the messages are delivered
 * to the player's session.
 */
public class ProxyZoneHandler implements ZoneCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyZoneHandler.class);

    private final String namespace;
    private final WsContext wsCtx;
    private final Map<String, Consumer<S2CMessage>> pendingRequests = new ConcurrentHashMap<>();

    public ProxyZoneHandler(String namespace, WsContext wsCtx) {
        this.namespace = namespace;
        this.wsCtx = wsCtx;
    }

    @Override
    public void handle(String playerId, String action, List<String> args,
                       Map<String, String> payload, Consumer<S2CMessage> respond) {
        var requestId = UUID.randomUUID().toString();
        pendingRequests.put(requestId, respond);

        var forward = new ZoneBridgeMessage.ForwardCommand(
            requestId, playerId, action, args, payload);

        try {
            var json = Json.mapper().writeValueAsString(forward);
            wsCtx.send(json);
            log.debug("Forwarded {}.{} to zone service (request={})", namespace, action, requestId);
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            log.error("Failed to forward command to zone '{}': {}", namespace, e.getMessage());
            respond.accept(new S2CMessage.Error(0, "zone_unavailable",
                "Zone '" + namespace + "' is not responding", null));
        }
    }

    /**
     * Called when the zone service sends a response to a forwarded command.
     * Converts JSON payloads to S2CMessage.Prose for delivery to the player.
     */
    public void onResponse(ZoneBridgeMessage.CommandResponse response) {
        var respond = pendingRequests.remove(response.requestId());
        if (respond == null) {
            log.warn("Zone '{}' responded to unknown request: {}", namespace, response.requestId());
            return;
        }
        for (var jsonNode : response.messages()) {
            // Command responses use ZoneResponse type for correlation
            respond.accept(toZoneResponse(response.requestId(), jsonNode));
        }
    }

    /** Convert a zone service command response to a ZoneResponse S2CMessage. */
    private S2CMessage toZoneResponse(String requestId, JsonNode node) {
        var text = node.has("text") ? node.get("text").asText()
            : node.has("summary") ? node.get("summary").asText()
            : node.toPrettyString();
        return new S2CMessage.ZoneResponse(0, requestId, namespace, text, node, List.of());
    }

    /** Convert a zone service broadcast event to an S2CMessage (Prose). */
    S2CMessage toS2CMessage(JsonNode node) {
        // If it looks like a native S2CMessage (has "type" field matching S2C types), try to deserialize
        var typeField = node.path("type").asText("");
        if (!typeField.isEmpty()) {
            try {
                return Json.mapper().treeToValue(node, S2CMessage.class);
            } catch (Exception e) {
                // Not a valid S2CMessage — fall through to Prose wrapping
            }
        }
        // Wrap arbitrary JSON as Prose with the JSON as text
        var text = node.has("text") ? node.get("text").asText()
            : node.has("summary") ? node.get("summary").asText()
            : node.toPrettyString();
        return new S2CMessage.Prose(0, namespace, text, List.of(), null, "normal", null);
    }

    /**
     * Whether this handler's connection is still open.
     */
    public boolean isConnected() {
        try {
            return wsCtx.session.isOpen();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Clean up pending requests on disconnect. Sends error to all waiting players.
     */
    public void onDisconnect() {
        pendingRequests.forEach((requestId, respond) ->
            respond.accept(new S2CMessage.Error(0, "zone_disconnected",
                "Zone '" + namespace + "' disconnected", null)));
        pendingRequests.clear();
    }

    public String namespace() {
        return namespace;
    }
}
