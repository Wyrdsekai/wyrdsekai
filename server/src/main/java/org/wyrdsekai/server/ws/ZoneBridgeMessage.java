package org.wyrdsekai.server.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Protocol messages for the zone bridge WebSocket ({@code /ws/zone}).
 * <p>
 * External services (e.g. CodeZaiku) connect here to register as zone command handlers.
 * The protocol is bidirectional:
 * <ul>
 *   <li>Service → Wyrdsekai: {@link Register}, {@link CommandResponse}</li>
 *   <li>Wyrdsekai → Service: {@link Registered}, {@link ForwardCommand}, {@link RegistrationError}</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    // Service → Wyrdsekai
    @JsonSubTypes.Type(value = ZoneBridgeMessage.Register.class, name = "register"),
    @JsonSubTypes.Type(value = ZoneBridgeMessage.CommandResponse.class, name = "response"),
    @JsonSubTypes.Type(value = ZoneBridgeMessage.Broadcast.class, name = "broadcast"),
    // Wyrdsekai → Service
    @JsonSubTypes.Type(value = ZoneBridgeMessage.Registered.class, name = "registered"),
    @JsonSubTypes.Type(value = ZoneBridgeMessage.ForwardCommand.class, name = "command"),
    @JsonSubTypes.Type(value = ZoneBridgeMessage.RegistrationError.class, name = "error"),
})
public sealed interface ZoneBridgeMessage {

    // ── Service → Wyrdsekai ────────────────────────────────────────────────

    /**
     * Register a zone namespace. Service sends this on connect.
     *
     * @param namespace Zone namespace to claim (e.g. "codezaiku")
     * @param secret    Optional shared secret for auth (matches WYRDSEKAI_ZONE_SECRET env var)
     */
    record Register(String namespace, String secret) implements ZoneBridgeMessage {}

    /**
     * Response to a forwarded command. Messages can be either S2CMessage objects
     * or arbitrary JSON payloads — zone services are free to send any format.
     * ProxyZoneHandler wraps non-S2C payloads in Prose messages for delivery.
     *
     * @param requestId Correlation ID from the ForwardCommand
     * @param playerId  Target player for the messages
     * @param messages  JSON messages to deliver to the player
     */
    record CommandResponse(String requestId, String playerId,
                           List<JsonNode> messages) implements ZoneBridgeMessage {}

    /**
     * Push an unsolicited message to all players in the zone (or a specific room).
     * Used for board events, status updates, and other push notifications
     * that aren't responses to a specific command.
     *
     * @param roomId   Target room (null = broadcast to all zone players)
     * @param messages JSON messages to deliver
     */
    record Broadcast(String roomId,
                     List<JsonNode> messages) implements ZoneBridgeMessage {}

    // ── Wyrdsekai → Service ────────────────────────────────────────────────

    /**
     * Acknowledgment that namespace registration succeeded.
     */
    record Registered(String namespace) implements ZoneBridgeMessage {}

    /**
     * Forward a player command to the zone service.
     *
     * @param requestId Correlation ID (service must echo this in CommandResponse)
     * @param playerId  Who sent the command
     * @param action    The action part (e.g. "approve" from "codezaiku.approve")
     * @param args      Command arguments
     * @param payload   Structured key-value data
     */
    record ForwardCommand(String requestId, String playerId,
                          String action, List<String> args,
                          Map<String, String> payload) implements ZoneBridgeMessage {}

    /**
     * Registration failed (namespace taken, bad secret, etc.).
     */
    record RegistrationError(String namespace, String reason) implements ZoneBridgeMessage {}
}
