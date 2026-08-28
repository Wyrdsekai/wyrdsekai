package org.wyrdsekai.server.ws;

import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Javalin WebSocket handler for zone service connections ({@code /ws/zone}).
 * <p>
 * External services connect here to register as zone command handlers.
 * Each connection claims a namespace (e.g. "codezaiku") and receives
 * forwarded player commands for that namespace.
 * <p>
 * Protocol:
 * <ol>
 *   <li>Service connects to {@code /ws/zone}</li>
 *   <li>Service sends {@link ZoneBridgeMessage.Register} with namespace + optional secret</li>
 *   <li>Wyrdsekai validates and sends {@link ZoneBridgeMessage.Registered} (or error)</li>
 *   <li>Player commands with matching namespace prefix are forwarded as
 *       {@link ZoneBridgeMessage.ForwardCommand}</li>
 *   <li>Service responds with {@link ZoneBridgeMessage.CommandResponse}</li>
 *   <li>On disconnect, the zone handler is unregistered</li>
 * </ol>
 */
public class ZoneBridgeEndpoint implements Consumer<WsConfig> {

    private static final Logger log = LoggerFactory.getLogger(ZoneBridgeEndpoint.class);

    private final WyrdWebSocket wyrdWebSocket;
    private final String zoneSecret;

    /** Tracks which namespace each WS session registered. */
    private final Map<String, ProxyZoneHandler> sessionHandlers = new ConcurrentHashMap<>();

    /**
     * @param wyrdWebSocket The main WS handler (for registering/unregistering zone handlers)
     * @param zoneSecret    Shared secret for zone auth, or null to allow any service (household trust)
     */
    public ZoneBridgeEndpoint(WyrdWebSocket wyrdWebSocket, String zoneSecret) {
        this.wyrdWebSocket = wyrdWebSocket;
        this.zoneSecret = zoneSecret;
    }

    @Override
    public void accept(WsConfig ws) {
        // Zone bridge connections are persistent control channels — set a long idle timeout.
        // Jetty 12 default is 30s, which causes zone services to disconnect and reconnect constantly.
        ws.onConnect(ctx -> {
            ctx.session.setIdleTimeout(Duration.ofMinutes(30));
            log.info("Zone service connected: {} (idle timeout: 30m)", ctx.session.getRemoteSocketAddress());
        });

        ws.onMessage(ctx -> {
            try {
                var msg = Json.mapper().readValue(ctx.message(), ZoneBridgeMessage.class);
                handleMessage(ctx, msg);
            } catch (Exception e) {
                log.error("Failed to parse zone bridge message: {}", ctx.message(), e);
                sendError(ctx, null, "Invalid message format: " + e.getMessage());
            }
        });

        ws.onClose(ctx -> {
            var handler = sessionHandlers.remove(sessionKey(ctx));
            if (handler != null) {
                handler.onDisconnect();
                wyrdWebSocket.unregisterZoneHandler(handler.namespace());
                log.info("Zone service disconnected: namespace='{}' ({})",
                    handler.namespace(), ctx.session.getRemoteSocketAddress());

                // Notify agents of zone service disconnection
                var eventStream = AgentEventStream.get();
                if (eventStream != null) {
                    eventStream.publishSystemEvent(
                        AgentEvent.SystemEventType.ZONE_SERVICE_DISCONNECTED,
                        handler.namespace(), "Zone service '" + handler.namespace() + "' disconnected");
                }
            } else {
                log.info("Zone service disconnected (unregistered): {}",
                    ctx.session.getRemoteSocketAddress());
            }
        });

        ws.onError(ctx -> {
            log.error("Zone bridge error for {}", ctx.session.getRemoteSocketAddress(), ctx.error());
        });
    }

    private void handleMessage(WsContext ctx, ZoneBridgeMessage msg) {
        switch (msg) {
            case ZoneBridgeMessage.Register reg -> handleRegister(ctx, reg);
            case ZoneBridgeMessage.CommandResponse resp -> handleResponse(ctx, resp);
            case ZoneBridgeMessage.Broadcast broadcast -> handleBroadcast(ctx, broadcast);
            // Ignore server-bound messages that shouldn't come from the service
            default -> log.warn("Unexpected message type from zone service: {}", msg.getClass().getSimpleName());
        }
    }

    private void handleRegister(WsContext ctx, ZoneBridgeMessage.Register reg) {
        var namespace = reg.namespace();

        if (namespace == null || namespace.isBlank()) {
            sendError(ctx, null, "Namespace cannot be empty");
            return;
        }

        // Validate namespace format: lowercase alphanumeric + hyphens
        if (!namespace.matches("[a-z][a-z0-9-]*")) {
            sendError(ctx, namespace, "Invalid namespace format (must be lowercase alphanumeric, start with letter)");
            return;
        }

        // Reserved namespaces
        if ("system".equals(namespace) || "wyrdsekai".equals(namespace)) {
            sendError(ctx, namespace, "Namespace '" + namespace + "' is reserved");
            return;
        }

        // Auth check
        if (zoneSecret != null && !zoneSecret.isBlank()) {
            if (!zoneSecret.equals(reg.secret())) {
                sendError(ctx, namespace, "Invalid zone secret");
                log.warn("Zone registration rejected — bad secret for namespace '{}' from {}",
                    namespace, ctx.session.getRemoteSocketAddress());
                return;
            }
        }

        // Check if namespace is already claimed by a different connection
        var existing = sessionHandlers.values().stream()
            .filter(h -> h.namespace().equals(namespace))
            .findFirst();
        if (existing.isPresent()) {
            if (existing.get().isConnected()) {
                sendError(ctx, namespace, "Namespace '" + namespace + "' is already registered");
                return;
            }
            // Stale connection — allow re-registration
            wyrdWebSocket.unregisterZoneHandler(namespace);
        }

        // Register
        var handler = new ProxyZoneHandler(namespace, ctx);
        sessionHandlers.put(sessionKey(ctx), handler);
        wyrdWebSocket.registerZoneHandler(namespace, handler);

        // Confirm
        try {
            var ack = Json.mapper().writeValueAsString(new ZoneBridgeMessage.Registered(namespace));
            ctx.send(ack);
            log.info("Zone service registered: namespace='{}' from {}", namespace, ctx.session.getRemoteSocketAddress());
        } catch (Exception e) {
            log.error("Failed to send registration ack", e);
        }

        // Notify agents of zone service registration
        var eventStream = AgentEventStream.get();
        if (eventStream != null) {
            eventStream.publishSystemEvent(
                AgentEvent.SystemEventType.ZONE_SERVICE_REGISTERED,
                namespace, "Zone service '" + namespace + "' connected");
        }
    }

    private void handleResponse(WsContext ctx, ZoneBridgeMessage.CommandResponse resp) {
        var handler = sessionHandlers.get(sessionKey(ctx));
        if (handler == null) {
            log.warn("Response from unregistered zone service: {}", ctx.session.getRemoteSocketAddress());
            return;
        }
        handler.onResponse(resp);
    }

    private void handleBroadcast(WsContext ctx, ZoneBridgeMessage.Broadcast broadcast) {
        var handler = sessionHandlers.get(sessionKey(ctx));
        if (handler == null) {
            log.warn("Broadcast from unregistered zone service: {}", ctx.session.getRemoteSocketAddress());
            return;
        }
        // Convert JSON nodes to S2CMessages and deliver to players in the zone
        for (var jsonNode : broadcast.messages()) {
            var msg = handler.toS2CMessage(jsonNode);
            wyrdWebSocket.broadcastToRoom(broadcast.roomId(), msg);
        }
    }

    private void sendError(WsContext ctx, String namespace, String reason) {
        try {
            var error = Json.mapper().writeValueAsString(
                new ZoneBridgeMessage.RegistrationError(namespace, reason));
            ctx.send(error);
        } catch (Exception e) {
            log.error("Failed to send error to zone service", e);
        }
    }

    private String sessionKey(WsContext ctx) {
        return ctx.session.getRemoteSocketAddress().toString();
    }
}
