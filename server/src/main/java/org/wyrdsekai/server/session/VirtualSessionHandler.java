package org.wyrdsekai.server.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.TransitToken;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.home.ForeignIdentity;
import org.wyrdsekai.core.home.ForeignIdentityStore;
import org.wyrdsekai.core.issue.Issue;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.core.item.ItemScriptResponse;
import org.wyrdsekai.core.item.TransitItemProvider;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.ExamineLookup;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Handles incoming virtual player sessions from remote zones over NATS.
 *
 * When a player on Zone A does "travel beta", Zone A's RemoteZoneSession sends
 * a session.open message over NATS. This handler on Zone B receives it, validates
 * the transit token, creates a virtual player session (room subscription + command dispatch),
 * and forwards room events back to Zone A over NATS.
 *
 * <pre>
 * Listens on:  federation.{localZoneId}.session.open
 *              federation.{localZoneId}.session.close
 *              federation.{localZoneId}.session.{sessionId}.cmd
 *
 * Publishes:   federation.{sourceZoneId}.session.{sessionId}.evt
 * </pre>
 */
public final class VirtualSessionHandler {

    private static final Logger log = LoggerFactory.getLogger(VirtualSessionHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);
    private static final String DOCKS_ROOM = "docks";
    /** Seconds to keep a session alive after close to drain in-flight commands. */
    private static final int DRAIN_SECONDS = 5;

    private final NatsBridge natsBridge;
    private final String localZoneId;
    private final FederationService federationService;
    private final ActorSystem<?> system;
    private final WardService wardService;
    /** ItemScriptExecutor for executing carried scripted items from visitors. */
    private final ItemScriptExecutor itemScriptExecutor =
        new ItemScriptExecutor();
    /** Optional host-zone provider factory for full scripted item transit. When null, uses VisitorItemProvider. */
    private volatile Function<String, ItemWorldApiProvider>
        hostProviderFactory;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "vsession-drain");
            t.setDaemon(true);
            return t;
        });

    /** Active virtual sessions: sessionId -> VirtualSession. */
    private final ConcurrentHashMap<String, VirtualSession> sessions = new ConcurrentHashMap<>();

    /** All session IDs ever seen — prevents re-opening after close (relay amplification). */
    private final Set<String> seenSessionIds = Collections.newSetFromMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > 1000;
            }
        });

    /** NATS dispatchers for per-session command channels (cleaned up on close). */
    private final ConcurrentHashMap<String, Object> cmdDispatchers = new ConcurrentHashMap<>();

    /** Relay transport for direct point-to-point session messaging. */
    private volatile RelaySessionTransport relayTransport;

    /** Test-only event capture. When set, sendEvent diverts here instead of the
     *  relay transport. Used by visitor conformance tests so output can be
     *  asserted without standing up a peer zone + signed transit token. */
    private volatile BiConsumer<String, ObjectNode> testEventCapture;

    public VirtualSessionHandler(NatsBridge natsBridge, String localZoneId,
                                 FederationService federationService,
                                 ActorSystem<?> system, WardService wardService) {
        this.natsBridge = natsBridge;
        this.localZoneId = localZoneId;
        this.federationService = federationService;
        this.system = system;
        this.wardService = wardService;
    }

    /** Set the relay transport for direct session messaging. */
    public void setRelayTransport(RelaySessionTransport transport) {
        this.relayTransport = transport;
    }

    /**
     * Start listening for virtual session open/close messages on NATS.
     */
    public void start() {
        // Session traffic flows over the direct relay transport, not the
        // local NATS bridge. With the embedded-NATS topology (each node
        // running its own 127.0.0.1:4222), local NATS can't see messages
        // that were published straight to the relay — which is what
        // RemoteZoneSession does on the home side. Keep all three session
        // subscriptions (open / close / notify) on the relay transport so
        // this works identically regardless of local-vs-relay topology.
        if (relayTransport == null || !relayTransport.isConnected()) {
            // Expected on every standalone node: no relay configured means no
            // cross-zone sessions, not a fault. It was the only ERROR in the
            // logs of three clean 0.2.2 installs — a red line for nothing.
            log.info("Cross-zone sessions off — no relay transport configured");
            return;
        }

        var openSubject = "federation." + localZoneId + ".session.open";
        relayTransport.subscribe(openSubject, data -> {
            try {
                var json = MAPPER.readTree(data);
                onSessionOpen(json);
            } catch (Exception e) {
                log.error("Error handling session.open: {}", e.getMessage());
            }
        });

        var closeSubject = "federation." + localZoneId + ".session.close";
        relayTransport.subscribe(closeSubject, data -> {
            try {
                var json = MAPPER.readTree(data);
                onSessionClose(json);
            } catch (Exception e) {
                log.error("Error handling session.close: {}", e.getMessage());
            }
        });

        // Incoming notifications for traveling players visiting this zone.
        var notifyWildcard = "federation." + localZoneId + ".session.*.notify";
        relayTransport.subscribe(notifyWildcard, data -> {
            try {
                onIncomingNotification(data);
            } catch (Exception e) {
                log.error("Error handling incoming notification: {}", e.getMessage());
            }
        });

        log.info("VirtualSessionHandler started — relay-transport subscriptions on "
            + "federation.{}.session.{{open,close,*.notify}}", localZoneId);
    }

    /**
     * Forward an incoming notification onto the session's event subject so the home zone's
     * RemoteZoneSession receives it and delivers to the player's WebSocket.
     *
     * The notify subject format is federation.{localZoneId}.session.{sessionId}.notify;
     * we parse sessionId and look up the VirtualSession to get the sourceZone.
     */
    private void onIncomingNotification(byte[] data) throws Exception {
        var node = MAPPER.readTree(data);
        // The subject isn't available in the data payload. NATS dispatcher interface
        // doesn't expose subject here — we rely on the payload including sessionId.
        var sessionId = node.path("sessionId").asText(null);
        if (sessionId == null) {
            // Fall back: iterate active sessions. Less efficient but works when sessionId
            // isn't in payload (forwarder should include it).
            log.debug("Incoming notification without sessionId — attempting broadcast");
            for (var session : sessions.values()) {
                forwardNotificationToEvt(session, node);
            }
            return;
        }
        var session = sessions.get(sessionId);
        if (session == null) {
            log.debug("Notification for unknown session: {}", sessionId);
            return;
        }
        forwardNotificationToEvt(session, node);
    }

    /** Publish the notification payload on this session's evt subject. */
    private void forwardNotificationToEvt(VirtualSession session,
                                           JsonNode payload) {
        // Build notification event for the home zone's RemoteZoneSession
        var evt = MAPPER.createObjectNode();
        evt.put("priority", payload.path("priority").asText("normal"));
        evt.put("fromAgent", payload.path("fromAgent").asText("system"));
        evt.put("message", payload.path("message").asText(""));
        evt.put("timestamp", payload.path("timestamp").asLong(System.currentTimeMillis()));
        sendEvent(session.sourceZoneId, session.sessionId, "notification", evt);
        log.info("Notification forwarded to traveling player {} (session {}) home zone {}",
            session.playerName, session.sessionId, session.sourceZoneId);
    }

    /**
     * Handle a session.open request from a remote zone.
     */
    private void onSessionOpen(JsonNode json) {
        var sessionId = json.path("sessionId").asText(null);
        var tokenStr = json.path("transitToken").asText(null);
        var playerId = json.path("playerId").asText(null);
        var playerName = json.path("playerName").asText("visitor");
        var sourceZoneId = json.path("localZoneId").asText(null);

        if (sessionId == null || tokenStr == null || playerId == null || sourceZoneId == null) {
            log.warn("Invalid session.open — missing required fields");
            return;
        }

        // Dedup: ignore if session ID was ever seen (relay amplification or re-open after close)
        synchronized (seenSessionIds) {
            if (!seenSessionIds.add(sessionId)) {
                log.debug("Ignoring duplicate/late session.open for {}", sessionId);
                return;
            }
        }

        // Validate transit token
        var validToken = federationService.validateTransitToken(tokenStr);
        if (validToken.isEmpty()) {
            log.warn("Session.open rejected — invalid/expired transit token: {}", tokenStr);
            sendEvent(sourceZoneId, sessionId, "error",
                MAPPER.createObjectNode().put("message", "Invalid or expired transit token"));
            return;
        }

        var token = validToken.get();
        log.info("Virtual session opening: {} for {} from zone '{}' (token={})",
            sessionId, playerName, sourceZoneId, token.tokenId());

        // Record visit
        federationService.recordVisit(playerId, localZoneId);

        // upsert the verified visitor as a foreign identity.
        // This DID never becomes a local users row; we're recording "this
        // traveler from <sourceZoneId> has visited us via a signed transit
        // token". Used for cross-zone tell routing + visitor roster.
        var foreignStore = ForeignIdentityStore.get();
        if (foreignStore != null) {
            var canonicalDid = ForeignIdentity.canonicalDid(
                sourceZoneId, playerId);
            var existing = foreignStore.get(canonicalDid);
            var now = Instant.now();
            var firstSeen = existing.map(ForeignIdentity::firstSeenAt)
                .orElse(now);
            foreignStore.upsert(new ForeignIdentity(
                canonicalDid, sourceZoneId, playerName, firstSeen, now, token.tokenId()));
        }

        // Create virtual session state
        var virtualSession = new VirtualSession(
            sessionId, playerId, playerName, sourceZoneId, token, DOCKS_ROOM);
        sessions.put(sessionId, virtualSession);

        // Parse carried inventory (optional)
        var inventoryNode = json.get("inventory");
        if (inventoryNode != null && !inventoryNode.isNull()) {
            try {
                var inventory = MAPPER.treeToValue(inventoryNode,
                    TransitInventory.class);
                if (inventory != null && inventory.items() != null) {
                    for (var item : inventory.items()) {
                        virtualSession.virtualInventory.put(item.id(), item);
                        virtualSession.originalItemIds.add(item.id());
                    }
                    log.info("Virtual session {} carrying {} items from {}",
                        sessionId, inventory.items().size(), sourceZoneId);
                }
            } catch (Exception e) {
                log.warn("Failed to parse carried inventory for session {}: {}",
                    sessionId, e.getMessage());
            }
        }

        // Parse carried reputation (optional) — used for permission decisions
        var repNode = json.get("reputation");
        if (repNode != null && !repNode.isNull()) {
            try {
                var reputation = MAPPER.treeToValue(repNode,
                    TransitReputation.class);
                if (reputation != null) {
                    virtualSession.reputation = reputation;
                    log.info("Virtual session {} carrying reputation: score={} tier={}",
                        sessionId, reputation.compositeScore(), reputation.permissionTier());
                }
            } catch (Exception e) {
                log.warn("Failed to parse carried reputation for session {}: {}",
                    sessionId, e.getMessage());
            }
        }

        // Register entity in EntityRegistry for local visibility
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.enter(playerId, playerName + " (from " + sourceZoneId + ")",
                "visitor", DOCKS_ROOM);
            // Record visitor's home zone — used for routing cross-zone tells/notifications back
            entityRegistry.setHomeZone(playerId, sourceZoneId);
        }

        // Subscribe to commands for this session
        subscribeToCommands(sessionId, sourceZoneId);

        // Enter the player into The Docks room
        var roomRef = RoomRegistry.get().ref(DOCKS_ROOM);
        if (roomRef == null) {
            log.error("Docks room not found — cannot place virtual session {}", sessionId);
            sendEvent(sourceZoneId, sessionId, "error",
                MAPPER.createObjectNode().put("message", "Docks room not available"));
            sessions.remove(sessionId);
            return;
        }

        // Subscribe to room events first, then enter + look
        subscribeToRoomEvents(virtualSession, DOCKS_ROOM);

        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.EnterRoom(playerId, playerName,
                "visitor", "portal", "en", ref),
            ASK_TIMEOUT
        ).thenCompose(enterResp -> {
            // After entering, do a look to get room state
            return Rooms.<RoomResponse>ask(roomRef,
                ref -> new RoomCommand.LookRoom(playerId, "en", ref),
                ASK_TIMEOUT);
        }).thenAccept(lookResp -> {
            if (lookResp instanceof RoomResponse.Ok ok) {
                sendRoomState(sourceZoneId, sessionId, ok.snapshot());
            } else if (lookResp instanceof RoomResponse.Rejected rej) {
                sendEvent(sourceZoneId, sessionId, "error",
                    MAPPER.createObjectNode().put("message", rej.reason()));
            }
        }).exceptionally(ex -> {
            log.error("Failed to enter docks for virtual session {}: {}", sessionId, ex.getMessage());
            sendEvent(sourceZoneId, sessionId, "error",
                MAPPER.createObjectNode().put("message", "Failed to enter docks"));
            return null;
        });
    }

    /**
     * Handle a session.close from a remote zone.
     */
    private void onSessionClose(JsonNode json) {
        var sessionId = json.path("sessionId").asText(null);
        if (sessionId == null) return;

        var session = sessions.get(sessionId);
        if (session == null) return;
        if (session.isDraining()) return; // already draining

        // Send inventory delta back to source zone (items dropped/taken in remote zone)
        sendInventoryDelta(session);

        // Start draining: keep session alive for DRAIN_SECONDS to process in-flight commands,
        // then close. This handles the relay latency — commands sent before close may arrive after.
        session.drainingSince = Instant.now();
        log.info("Virtual session draining: {} (player={}, will close in {}s)",
            sessionId, session.playerName, DRAIN_SECONDS);

        scheduler.schedule(() -> {
            var s = sessions.remove(sessionId);
            if (s != null) {
                cleanupSession(s);
                log.info("Virtual session closed after drain: {} (player={})", sessionId, s.playerName);
            }
        }, DRAIN_SECONDS, TimeUnit.SECONDS);
    }

    /** Set a factory that produces an ItemWorldApiProvider for a given visitor's home zone. */
    public void setHostProviderFactory(
            Function<String, ItemWorldApiProvider> factory) {
        this.hostProviderFactory = factory;
    }

    /**
     * Look up the active visitor session for a given player ID.
     * Returns an array [sessionId, sourceZoneId] or null if not visiting.
     * Used by NotificationService to route notifications back to visitor's home zone.
     */
    public String[] visitorSessionFor(String playerId) {
        if (playerId == null) return null;
        for (var session : sessions.values()) {
            if (playerId.equals(session.playerId) && !session.isDraining()) {
                return new String[] { session.sessionId, session.sourceZoneId };
            }
        }
        return null;
    }

    /**
     * Forward a notification to a visitor by publishing on their session's event subject.
     * The notification flows back through the relay to their home zone's WebSocket.
     */
    public boolean forwardNotificationToVisitor(String playerId,
            S2CMessage.Notification notification) {
        var info = visitorSessionFor(playerId);
        if (info == null) return false;
        var sessionId = info[0];
        var sourceZone = info[1];
        var payload = MAPPER.createObjectNode();
        payload.put("priority", notification.level());
        payload.put("fromAgent", notification.title());
        payload.put("message", notification.message());
        payload.put("timestamp", System.currentTimeMillis());
        sendEvent(sourceZone, sessionId, "notification", payload);
        log.info("Notification forwarded to visitor {} via session {} to home zone {}",
            playerId, sessionId, sourceZone);
        return true;
    }

    /** Execute a carried scripted item's script with appropriate provider. */
    private void executeCarriedItemScript(VirtualSession session,
            TransitInventory.TransitItem item, String target) {
        try {
            // Create provider: use host provider factory if available, else minimal VisitorItemProvider
            ItemWorldApiProvider provider;
            if (hostProviderFactory != null) {
                var hostProvider = hostProviderFactory.apply(session.sourceZoneId);
                provider = new TransitItemProvider(
                    hostProvider, session.sourceZoneId);
            } else {
                provider = new VisitorItemProvider(
                    localZoneId, session.sourceZoneId);
            }

            // FIFTH path that runs an item script, and the fifth to build its own
            // params. This one set target/entityId/entityName and no `args` — the
            // spelling the items-as-tools contract actually promises — so a visitor on
            // the relay or a phone got an item that believed it had been called with
            // nothing. One builder, so the answer to "what does a script receive" is the
            // same wherever it is asked.
            var params = new HashMap<String, Object>(
                CarriedItemUse.params(session.playerId, target));
            params.put("entityName", session.playerName);

            // #1 (2026-07-19 OSS hardening) — a VISITOR's carried item arrived from
            // another zone; it is untrusted by definition and runs under the vetted
            // crafted ceiling, never UNRESTRICTED.
            var result = itemScriptExecutor.execute(
                item.id(), item.scriptSource(), params, provider,
                ItemCapabilitySet.craftedDefault());

            // Send result back as prose
            var text = ItemScriptResponse.extractText(
                result, item.name());
            var prose = MAPPER.createObjectNode();
            prose.put("speaker", "narrator");
            prose.put("text", text);
            prose.put("priority", "normal");
            sendEvent(session.sourceZoneId, session.sessionId, "prose", prose);

            log.info("Visitor {} used carried scripted item '{}' in {}",
                session.playerName, item.name(), localZoneId);
        } catch (Exception e) {
            log.error("Failed to execute carried item script {}: {}", item.id(), e.getMessage());
            var err = MAPPER.createObjectNode();
            err.put("message", "Item malfunctioned: " + e.getMessage());
            sendEvent(session.sourceZoneId, session.sessionId, "error", err);
        }
    }

    /** Find an item in the virtual inventory by name/alias/id. */
    private TransitInventory.TransitItem findCarriedItem(
            VirtualSession session, String query) {
        if (query == null || query.isBlank()) return null;
        // Try exact id match
        var byId = session.virtualInventory.get(query);
        if (byId != null) return byId;
        // Try name/alias match
        var lower = query.toLowerCase();
        for (var item : session.virtualInventory.values()) {
            if (item.name() != null && item.name().equalsIgnoreCase(query)) return item;
            if (item.aliases() != null) {
                for (var alias : item.aliases()) {
                    if (alias.equalsIgnoreCase(query)) return item;
                }
            }
            if (item.name() != null && item.name().toLowerCase().contains(lower)) return item;
        }
        return null;
    }

    /** Send the session's inventory delta back to the source zone on close. */
    private void sendInventoryDelta(VirtualSession session) {
        try {
            var delta = session.computeDelta();
            if (delta.isEmpty()) {
                log.debug("No inventory delta to send for session {}", session.sessionId);
                return;
            }
            var subject = "federation." + session.sourceZoneId +
                ".session." + session.sessionId + ".inventory_delta";
            var data = MAPPER.writeValueAsBytes(delta);
            if (relayTransport != null) {
                relayTransport.publish(subject, data);
                log.info("Sent inventory delta for session {}: -{} +{}",
                    session.sessionId, delta.removedItemIds().size(), delta.addedItems().size());
            } else {
                log.warn("Cannot send inventory delta — relayTransport not configured");
            }
        } catch (Exception e) {
            log.error("Failed to send inventory delta for session {}: {}",
                session.sessionId, e.getMessage());
        }
    }

    /**
     * Handle an incoming command from a remote zone player.
     */
    private void onCommand(String sessionId, JsonNode json) {
        var session = sessions.get(sessionId);
        if (session == null) {
            log.warn("Command for unknown virtual session: {}", sessionId);
            return;
        }

        var sentAt = json.path("sentAt").asLong(0);
        if (sentAt > 0) {
            var delta = System.currentTimeMillis() - sentAt;
            log.info("Command arrived for session {} — type={}, latency={}ms",
                sessionId, json.path("type").asText("?"), delta);
        }

        var type = json.path("type").asText("");
        var payloadStr = json.path("payload").asText("{}");

        JsonNode payload;
        try {
            payload = MAPPER.readTree(payloadStr);
        } catch (Exception e) {
            payload = MAPPER.createObjectNode();
        }

        var roomId = session.currentRoomId;
        var roomRef = RoomRegistry.get().ref(roomId);
        if (roomRef == null) {
            sendEvent(session.sourceZoneId, sessionId, "error",
                MAPPER.createObjectNode().put("message", "Current room not found: " + roomId));
            return;
        }

        switch (type) {
            case "say" -> {
                var text = payload.path("text").asText("");
                if (!wardService.isAllowed(roomId, session.playerId, "speak")) {
                    sendEvent(session.sourceZoneId, sessionId, "error",
                        MAPPER.createObjectNode().put("message", "You are not allowed to speak here."));
                    return;
                }
                Rooms.<RoomResponse>ask(roomRef,
                    ref -> new RoomCommand.SayInRoom(session.playerId, session.playerName,
                        text, "en", null, ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {
                    // Don't send room_state for say — the echo comes from the room subscription.
                    // Sending room_state here causes a flood of duplicate room snapshots.
                });
            }

            case "go" -> {
                var direction = payload.path("direction").asText("");
                handleGo(session, roomRef, direction);
            }

            case "look" -> {
                Rooms.<RoomResponse>ask(roomRef,
                    ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {
                    if (resp instanceof RoomResponse.Ok ok) {
                        sendRoomState(session.sourceZoneId, sessionId, ok.snapshot());
                    } else {
                        handleRoomResponse(session, resp);
                    }
                });
            }

            case "take" -> {
                var objectName = payload.path("objectName").asText("");
                Rooms.<RoomResponse>ask(roomRef,
                    ref -> new RoomCommand.TakeObject(session.playerId, objectName, "en", ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {
                    // If take succeeded, add item to session's takenItems (goes home in delta)
                    if (resp instanceof RoomResponse.ObjectTakenOk takeOk) {
                        var obj = takeOk.takenObject();
                        var item = TransitInventory.TransitItem.simple(
                            obj.id(), obj.name(), obj.description(), obj.takeable(), obj.aliases());
                        session.takenItems.put(obj.id(), item);
                        session.virtualInventory.put(obj.id(), item);
                        log.info("Visitor {} took '{}' in {} (goes home on return)",
                            session.playerName, obj.name(), localZoneId);
                    }
                    handleRoomResponse(session, resp);
                });
            }

            case "drop" -> {
                var objectName = payload.path("objectName").asText("");
                // Check if item is in virtual inventory (carried from home)
                var carriedItem = findCarriedItem(session, objectName);
                if (carriedItem != null) {
                    // Drop a carried item into this zone's room
                    var item = carriedItem;
                    Rooms.<RoomResponse>ask(roomRef,
                        ref -> new RoomCommand.DropObject(session.playerId, item.id(), item.name(),
                            item.description() != null ? item.description() : "", item.takeable(), "en", ref),
                        ASK_TIMEOUT
                    ).thenAccept(resp -> {
                        if (resp instanceof RoomResponse.Ok) {
                            // Remove from virtual inventory — delta will reflect as removed from home
                            session.virtualInventory.remove(item.id());
                            session.takenItems.remove(item.id()); // in case they took then dropped
                            log.info("Visitor {} dropped carried item '{}' in {}",
                                session.playerName, item.name(), localZoneId);
                        }
                        handleRoomResponse(session, resp);
                    });
                } else {
                    // Drop a locally-taken item — just forward to room
                    Rooms.<RoomResponse>ask(roomRef,
                        ref -> new RoomCommand.DropObject(session.playerId, objectName, objectName,
                            "", true, "en", ref),
                        ASK_TIMEOUT
                    ).thenAccept(resp -> {
                        if (resp instanceof RoomResponse.Ok) {
                            session.takenItems.remove(objectName);
                            session.virtualInventory.remove(objectName);
                        }
                        handleRoomResponse(session, resp);
                    });
                }
            }

            case "use" -> {
                var objectName = payload.path("objectName").asText("");
                var target = payload.path("target").asText(null);

                // Check if this is a carried scripted item (visitor's item with script source)
                var carriedItem = findCarriedItem(session, objectName);
                if (carriedItem != null && carriedItem.scriptSource() != null
                        && !carriedItem.scriptSource().isBlank()) {
                    executeCarriedItemScript(session, carriedItem, target);
                    return;
                }

                // Otherwise, forward to room (use a local item)
                Rooms.<RoomResponse>ask(roomRef,
                    ref -> new RoomCommand.UseObject(session.playerId, objectName,
                        target, "en", ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> handleRoomResponse(session, resp));
            }

            case "emote" -> {
                var text = payload.path("text").asText("");
                Rooms.<RoomResponse>ask(roomRef,
                    ref -> new RoomCommand.EmoteInRoom(session.playerId, session.playerName,
                        text, "en", ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {});
            }

            case "input" -> {
                // Raw line from the visitor — run the full local CommandParser
                // and dispatch. Gives foreign-zone visitors the same verb
                // surface (exits, where, inventory, examine, …) that local
                // players get, rather than a hard-coded whitelist that
                // silently degrades to `say`.
                var line = payload.path("line").asText("");
                if (line.isBlank()) return;
                dispatchParsedInput(sessionId, session, roomRef, line);
            }

            default -> {
                log.debug("Unknown command type '{}' from virtual session {}", type, sessionId);
                sendEvent(session.sourceZoneId, sessionId, "error",
                    MAPPER.createObjectNode().put("message", "Unknown command: " + type));
            }
        }
    }

    /**
     * Translate a raw visitor input line into a typed verb and re-dispatch
     * through {@link #onCommand}. New verbs that don't have an existing case
     * (exits, where, inventory) are handled inline.
     */
    private void dispatchParsedInput(String sessionId, VirtualSession session,
                                     ActorRef<RoomCommand> roomRef,
                                     String originalLine) {
        ParsedCommand parsed = CommandParser.parse(originalLine, "en", Map.of());
        if (parsed == null) {
            // Fall back to say so free-form input still travels.
            reDispatchAsSay(sessionId, originalLine);
            return;
        }
        String newType;
        ObjectNode p = MAPPER.createObjectNode();
        switch (parsed) {
            case ParsedCommand.Say s     -> { newType = "say";   p.put("text", s.text()); }
            case ParsedCommand.Go g      -> { newType = "go";    p.put("direction", g.direction()); }
            case ParsedCommand.Look l    -> { newType = "look"; }
            case ParsedCommand.Take t    -> { newType = "take";  p.put("objectName", t.objectName()); }
            case ParsedCommand.Drop d    -> { newType = "drop";  p.put("objectName", d.objectName()); }
            // Relay and phone sessions carry the same verbs the local shells do. Adding a
            // command to one surface and not the others is how a client-parity gap starts.
            case ParsedCommand.Retire r  -> { newType = "retire"; p.put("objectName", r.objectName()); }
            case ParsedCommand.Use u     -> {
                newType = "use"; p.put("objectName", u.objectName());
                if (u.target() != null) p.put("target", u.target());
            }
            case ParsedCommand.Emote e   -> { newType = "emote"; p.put("text", e.text()); }
            case ParsedCommand.Exits e   -> { handleVisitorExits(session, sessionId, roomRef); return; }
            case ParsedCommand.Where w   -> { handleVisitorWhere(session, sessionId); return; }
            case ParsedCommand.Examine ex -> { handleVisitorExamine(session, sessionId, ex.target()); return; }
            case ParsedCommand.Tell t    -> { handleVisitorTell(session, sessionId, t.targetName(), t.text()); return; }
            case ParsedCommand.MapCommand mc -> { handleVisitorMap(session, sessionId, mc.radius()); return; }
            case ParsedCommand.HintSelect hs -> { handleVisitorHintSelect(session, sessionId, roomRef, hs.index()); return; }
            case ParsedCommand.SlashCommand sc when "inventory".equals(sc.command())
                                         -> { handleVisitorInventory(session, sessionId); return; }
            case ParsedCommand.SlashCommand sc when "actions".equals(sc.command())
                                         -> { handleVisitorActions(session, sessionId, roomRef); return; }
            case ParsedCommand.SlashCommand sc when "who".equals(sc.command())
                                         -> { handleVisitorWho(session, sessionId, roomRef); return; }
            case ParsedCommand.SlashCommand sc when "help".equals(sc.command())
                                         -> { handleVisitorHelp(session, sessionId); return; }
            case ParsedCommand.SlashCommand sc when "issue".equals(sc.command())
                                              || "feedback".equals(sc.command())
                                         -> { handleVisitorIssue(session, sessionId, sc); return; }
            case ParsedCommand.Unknown u -> { newType = "say";   p.put("text", u.text()); }
            default                      -> { reDispatchAsSay(sessionId, originalLine); return; }
        }
        var envelope = MAPPER.createObjectNode();
        envelope.put("type", newType);
        try {
            envelope.put("payload", MAPPER.writeValueAsString(p));
        } catch (Exception ex) {
            log.warn("Failed to re-serialise visitor input payload: {}", ex.getMessage());
            return;
        }
        onCommand(sessionId, envelope);
    }

    private void reDispatchAsSay(String sessionId, String line) {
        var envelope = MAPPER.createObjectNode();
        envelope.put("type", "say");
        try {
            envelope.put("payload", MAPPER.writeValueAsString(
                MAPPER.createObjectNode().put("text", line)));
        } catch (Exception e) {
            return;
        }
        onCommand(sessionId, envelope);
    }

    private void handleVisitorExits(VirtualSession session, String sessionId,
                                    ActorRef<RoomCommand> roomRef) {
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                var snap = ok.snapshot();
                var exits = snap.exits();
                String text;
                if (exits == null || exits.isEmpty()) {
                    text = "No obvious exits.";
                } else {
                    var sb = new StringBuilder("Exits: ");
                    for (int i = 0; i < exits.size(); i++) {
                        var e = exits.get(i);
                        if (i > 0) sb.append(", ");
                        sb.append(e.direction());
                        if (e.label() != null && !e.label().isBlank()) {
                            sb.append(" (").append(e.label()).append(")");
                        }
                    }
                    text = sb.toString();
                }
                sendVisitorProse(session, sessionId, text);
            }
        });
    }

    /** Push a plain-text line to the visitor's client. Uses the {@code prose}
     *  event shape that {@code RemoteEventDecoder} knows how to render. */
    private void sendVisitorProse(VirtualSession session, String sessionId, String text) {
        var prose = MAPPER.createObjectNode();
        prose.put("speaker", "narrator");
        prose.put("text", text);
        prose.put("priority", "normal");
        sendEvent(session.sourceZoneId, sessionId, "prose", prose);
    }

    /**
     * §2.2 — passive observation over the visitor REST surface. Mirrors the
     * SSH/Telnet/WS Examine path: returns description text via a single
     * Prose event, never invokes onUse, never broadcasts ObjectUsed, never
     * forwards a room snapshot.
     *
     * <p>Visitors carry inventory in {@code session.virtualInventory}
     * (a transient map populated by cross-zone transit), not the
     * {@code InventoryService} table — so we check the visitor map
     * directly before falling through to the shared room-object/entity
     * lookup. Self lookup uses the visitor's display name only (no
     * authService dependency: visitors are foreign and the local
     * authService doesn't know them).</p>
     */
    private void handleVisitorExamine(VirtualSession session, String sessionId, String target) {
        if (target == null || target.isBlank()) {
            sendVisitorProse(session, sessionId,
                "What do you want to examine?");
            return;
        }
        var trimmed = target.trim();
        var lower = trimmed.toLowerCase(Locale.ROOT);

        // Self → visitor display name (no description for foreign visitors v1).
        if ("me".equals(lower) || "self".equals(lower) || "myself".equals(lower)) {
            sendVisitorProse(session, sessionId,
                session.playerName != null ? session.playerName : "you");
            return;
        }

        // Visitor inventory (transient map; not in InventoryService).
        if (session.virtualInventory != null) {
            for (var item : session.virtualInventory.values()) {
                if (item == null) continue;
                var name = item.name();
                var desc = item.description();
                if (name != null && name.toLowerCase(Locale.ROOT).contains(lower)) {
                    sendVisitorProse(session, sessionId,
                        desc == null || desc.isBlank() ? name : name + "\n" + desc);
                    return;
                }
            }
        }

        // Room object / entity — reuse the shared helper. Pass null for
        // authService + inventoryService so the helper skips those legs.
        ExamineLookup.resolve(
                session.playerId, session.playerName, trimmed, "en",
                null, null, session.currentRoomId, ASK_TIMEOUT)
            .thenAccept(result -> {
                String text = switch (result) {
                    case ExamineLookup.ExamineResult.Found f ->
                        f.description() != null && !f.description().isBlank()
                            ? f.name() + "\n" + f.description() : f.name();
                    case ExamineLookup.ExamineResult.NotFound nf ->
                        "There's nothing called " + nf.requested() + " here.";
                    case ExamineLookup.ExamineResult.NoCurrentRoom nr ->
                        "There's nothing called " + nr.requested() + " here.";
                    case ExamineLookup.ExamineResult.Empty e ->
                        "What do you want to examine?";
                };
                sendVisitorProse(session, sessionId, text);
            });
    }

    private void handleVisitorWhere(VirtualSession session, String sessionId) {
        var roomId = session.currentRoomId;
        var roomRef = RoomRegistry.get().ref(roomId);
        if (roomRef == null) return;
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                var snap = ok.snapshot();
                var text = "You are in " + snap.name() + " — visiting zone '" + localZoneId + "'.";
                sendVisitorProse(session, sessionId, text);
            }
        });
    }

    private void handleVisitorInventory(VirtualSession session, String sessionId) {
        var sb = new StringBuilder("Carrying: ");
        var items = session.virtualInventory;
        if (items == null || items.isEmpty()) {
            sb.append("(nothing)");
        } else {
            boolean first = true;
            for (var it : items.values()) {
                if (!first) sb.append(", ");
                sb.append(it.name());
                first = false;
            }
        }
        sendVisitorProse(session, sessionId, sb.toString());
    }

    private void handleVisitorActions(VirtualSession session, String sessionId,
                                      ActorRef<RoomCommand> roomRef) {
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            var sb = new StringBuilder();
            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                var snap = ok.snapshot();
                sb.append("Here in ").append(snap.name()).append(":\n");
                var hints = snap.hints();
                if (hints != null && !hints.isEmpty()) {
                    int i = 1;
                    for (var h : hints) {
                        sb.append("  ").append(i++).append(". ").append(h.label()).append("\n");
                    }
                }
            }
            sb.append("Always available: look, exits, go <dir>, say <text>, tell <name> <text>, ")
              .append("inventory, who, where, home (return to your zone)");
            sendVisitorProse(session, sessionId, sb.toString());
        });
    }

    private void handleVisitorWho(VirtualSession session, String sessionId,
                                  ActorRef<RoomCommand> roomRef) {
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            var sb = new StringBuilder();
            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                var snap = ok.snapshot();
                sb.append("Here: ");
                if (snap.entities() != null && !snap.entities().isEmpty()) {
                    boolean first = true;
                    for (var e : snap.entities()) {
                        if (!first) sb.append(", ");
                        sb.append(e.name());
                        first = false;
                    }
                } else {
                    sb.append("(just you)");
                }
                sb.append("\nYou are visiting zone '").append(localZoneId)
                  .append("' from '").append(session.sourceZoneId).append("'.");
            }
            sendVisitorProse(session, sessionId, sb.toString());
        });
    }

    /** Route visitor-typed numeric hint index through the room's SelectHint,
     *  same as a local player. Supports go / look / take / use / say actions. */
    private void handleVisitorHintSelect(VirtualSession session, String sessionId,
                                          ActorRef<RoomCommand> roomRef,
                                          int zeroIndex) {
        // CommandParser normalises user input (1-based typed by user) to the
        // 0-based index RoomActor expects. No further conversion here —
        // SSH/Telnet/WebSocket handlers pass through directly; matching them
        // keeps behavior consistent across transports.
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.SelectHint(session.playerId, zeroIndex, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.HintAction ha && "go".equals(ha.actionType())) {
                // Move through an exit like `go <direction>` — reuse handleGo.
                handleGo(session, roomRef, ha.parameter());
            } else if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                // Say / look / take / use all resolved internally by the
                // RoomActor and returned as an Ok with fresh snapshot. Push
                // the updated room state back to the visitor's client.
                try {
                    var payload = MAPPER.createObjectNode();
                    payload.set("room", MAPPER.valueToTree(ok.snapshot()));
                    sendEvent(session.sourceZoneId, sessionId, "room_state", payload);
                } catch (Exception ignored) {}
            } else if (resp instanceof RoomResponse.Rejected r) {
                sendVisitorProse(session, sessionId, r.reason());
            }
        });
    }

    private void handleVisitorMap(VirtualSession session, String sessionId, int radius) {
        var topo = ZoneTopology.getShared();
        if (topo == null) {
            sendVisitorProse(session, sessionId, "(map unavailable — topology not initialised)");
            return;
        }
        var center = session.currentRoomId;
        if (topo.room(center).isEmpty()) center = "nexus";
        var text = topo.renderTextMap(center, radius, topo.rooms().keySet());
        sendVisitorProse(session, sessionId, text);
    }

    private void handleVisitorHelp(VirtualSession session, String sessionId) {
        var text = String.join("\n",
            "Visitor commands (you are in zone '" + localZoneId + "'):",
            "  look              — redescribe this room",
            "  go <direction>    — move (north, east, up, …)",
            "  exits             — list exits",
            "  say <text>        — speak to this room",
            "  tell <name> <msg> — private message",
            "  inventory / i     — what you're carrying",
            "  who               — who's here",
            "  where             — your current location",
            "  actions           — contextual actions menu",
            "  home / return     — return to your home zone");
        var prose = MAPPER.createObjectNode();
        prose.put("speaker", "narrator");
        prose.put("text", text);
        prose.put("priority", "normal");
        sendEvent(session.sourceZoneId, sessionId, "prose", prose);
    }

    /** — `/issue` + `/feedback` from visitor sessions. */
    private void handleVisitorIssue(VirtualSession session, String sessionId,
                                    ParsedCommand.SlashCommand sc) {
        var text = String.join(" ", sc.args());
        var svc = IssueService.get();
        String reply;
        if (text.isBlank() || svc == null) {
            reply = "Usage: /issue <what went wrong> — or /feedback <note>";
        } else {
            var kind = "feedback".equals(sc.command())
                ? Issue.KIND_FEEDBACK
                : Issue.KIND_ISSUE;
            var filed = svc.file(kind, text, session.playerName, "ws",
                null, session.playerId);
            reply = "Noted — filed " + kind + " " + filed.id()
                + ". (wyrd issue show " + filed.id() + ")";
        }
        var prose = MAPPER.createObjectNode();
        prose.put("speaker", "narrator");
        prose.put("text", reply);
        prose.put("priority", "normal");
        sendEvent(session.sourceZoneId, sessionId, "prose", prose);
    }

    private void handleVisitorTell(VirtualSession session, String sessionId,
                                   String targetName, String text) {
        // Route through the cross-zone tell service so residents + visitors
        // both reach their target. Target may be local to this zone or in
        // another zone entirely — CrossZoneTellService decides.
        var svc = CrossZoneTellService.get();
        if (svc == null) {
            sendVisitorProse(session, sessionId,
                "(tell unavailable — cross-zone tell service not initialised)");
            return;
        }
        svc.tell(session.playerId, session.playerName, localZoneId, targetName, text);
        sendVisitorProse(session, sessionId,
            "You tell " + targetName + ": " + text);
    }

    /**
     * Handle a "go" command: look up exits, validate ward, perform move.
     */
    private void handleGo(VirtualSession session, ActorRef<RoomCommand> roomRef,
                          String direction) {
        Rooms.<RoomResponse>ask(roomRef,
            ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
            ASK_TIMEOUT
        ).thenAccept(lookResp -> {
            if (!(lookResp instanceof RoomResponse.Ok ok)) {
                handleRoomResponse(session, lookResp);
                return;
            }
            // 1. Direction match (north/east/up/…)
            var exit = ok.snapshot().exits().stream()
                .filter(e -> e.direction().equalsIgnoreCase(direction))
                .findFirst();
            // 2. Room alias/name match (`go docks`) — same UX as resident go.
            if (exit.isEmpty()) {
                var byName = RoomRegistry.get().resolveRoomId(direction);
                if (byName != null) {
                    exit = ok.snapshot().exits().stream()
                        .filter(e -> byName.equals(e.targetRoom()))
                        .findFirst();
                }
            }
            if (exit.isEmpty()) {
                sendEvent(session.sourceZoneId, session.sessionId, "prose",
                    MAPPER.createObjectNode()
                        .put("speaker", "system")
                        .put("text", "You can't go that way.")
                        .put("priority", "normal"));
                return;
            }

            var targetRoomId = exit.get().targetRoom();
            var targetRoomRef = RoomRegistry.get().ref(targetRoomId);
            if (targetRoomRef == null) {
                sendEvent(session.sourceZoneId, session.sessionId, "error",
                    MAPPER.createObjectNode().put("message", "Room not found: " + targetRoomId));
                return;
            }

            if (!wardService.isAllowed(targetRoomId, session.playerId, "enter")) {
                sendEvent(session.sourceZoneId, session.sessionId, "prose",
                    MAPPER.createObjectNode()
                        .put("speaker", "system")
                        .put("text", "You are not permitted to enter that room.")
                        .put("priority", "normal"));
                return;
            }

            // Leave current room
            Rooms.<RoomResponse>ask(roomRef,
                ref -> new RoomCommand.LeaveRoom(session.playerId, session.playerName, direction, ref),
                ASK_TIMEOUT
            ).thenAccept(leaveResp -> {
                // Unsubscribe from old room events
                unsubscribeFromRoomEvents(session);

                // Update session state
                session.currentRoomId = targetRoomId;

                // Update EntityRegistry
                var er = EntityRegistry.get();
                if (er != null) er.moved(session.playerId, targetRoomId);

                // Subscribe to new room events, enter, and look
                subscribeToRoomEvents(session, targetRoomId);

                Rooms.<RoomResponse>ask(targetRoomRef,
                    ref -> new RoomCommand.EnterRoom(session.playerId, session.playerName,
                        "visitor", oppositeDirection(direction), "en", ref),
                    ASK_TIMEOUT
                ).thenCompose(enterResp ->
                    Rooms.<RoomResponse>ask(targetRoomRef,
                        ref -> new RoomCommand.LookRoom(session.playerId, "en", ref),
                        ASK_TIMEOUT)
                ).thenAccept(newLook -> {
                    if (newLook instanceof RoomResponse.Ok newOk) {
                        sendRoomState(session.sourceZoneId, session.sessionId, newOk.snapshot());
                    }
                });
            });
        });
    }

    // --- Room event forwarding ---

    /**
     * Subscribe this virtual session to room notifications from a local room actor.
     * Uses a Pekko message adapter to forward RoomNotifications over NATS.
     */
    private void subscribeToRoomEvents(VirtualSession session, String roomId) {
        var roomRef = RoomRegistry.get().ref(roomId);
        if (roomRef == null) return;

        // Create a message adapter that forwards room notifications over NATS
        var adapter = system.<RoomNotification>systemActorOf(
            Behaviors.receive(RoomNotification.class)
                .onAnyMessage(notification -> {
                    forwardRoomNotification(session, notification);
                    return Behaviors.same();
                })
                .build(),
            "vsession-" + session.sessionId.substring(0, 8)
                + "-" + roomId + "-" + System.currentTimeMillis(),
            Props.empty());

        session.roomSubscriber = adapter;
        roomRef.tell(new RoomCommand.Subscribe(adapter));
    }

    /**
     * Unsubscribe from room events when the player leaves a room.
     */
    private void unsubscribeFromRoomEvents(VirtualSession session) {
        if (session.roomSubscriber != null) {
            var roomRef = RoomRegistry.get().ref(session.currentRoomId);
            if (roomRef != null) {
                roomRef.tell(new RoomCommand.Unsubscribe(session.roomSubscriber));
            }
            // Stop the adapter actor
            system.systemActorOf(
                Behaviors.stopped(),
                "vsession-stop-" + System.nanoTime(),
                Props.empty());
            session.roomSubscriber = null;
        }
    }

    /**
     * Forward a room notification (Said, EntityEntered, etc.) as JSON event to the remote zone.
     */
    private void forwardRoomNotification(VirtualSession session, RoomNotification notification) {
        try {
            var we = notification.event();

            // Skip own enter/leave events (noisy, player sees room_state instead).
            // DO forward own Said/Emoted so the player sees their speech echoed.
            if (we instanceof WorldEvent.EntityEntered e
                    && session.playerId.equals(e.entityId())) return;
            if (we instanceof WorldEvent.EntityLeft e
                    && session.playerId.equals(e.entityId())) return;

            var event = MAPPER.createObjectNode();
            event.put("eventType", we.getClass().getSimpleName());
            event.set("data", MAPPER.valueToTree(we));
            sendEvent(session.sourceZoneId, session.sessionId, "room_event", event);
        } catch (Exception e) {
            log.debug("Failed to forward room notification for session {}: {}",
                session.sessionId, e.getMessage());
        }
    }

    // --- Response handling ---

    private void handleRoomResponse(VirtualSession session, RoomResponse resp) {
        switch (resp) {
            case RoomResponse.Ok ok ->
                sendRoomState(session.sourceZoneId, session.sessionId, ok.snapshot());
            case RoomResponse.Rejected rej ->
                sendEvent(session.sourceZoneId, session.sessionId, "prose",
                    MAPPER.createObjectNode()
                        .put("speaker", "system")
                        .put("text", rej.reason())
                        .put("priority", "normal"));
            case RoomResponse.ObjectTakenOk taken ->
                sendRoomState(session.sourceZoneId, session.sessionId, taken.snapshot());
            case RoomResponse.HintAction ha ->
                sendEvent(session.sourceZoneId, session.sessionId, "prose",
                    MAPPER.createObjectNode()
                        .put("speaker", "system")
                        .put("text", "Hint: " + ha.actionType() + " " + ha.parameter())
                        .put("priority", "normal"));
            case RoomResponse.Narrated narrated -> {
                // Intentional no-op. The narration line for examine/look-at
                // verbs reaches the client via the Said WorldEvent → Prose
                // path; pushing a room_state here would clobber it.
            }
            case RoomResponse.HookRan _ -> {
                // Script-hook narration reaches the client via the Said
                // WorldEvent it emitted — same no-op rationale as Narrated.
            }
            case RoomResponse.ToolDefinitions _ -> {} // Agent-side query reply — not a session concern
        }
    }

    // --- NATS publishing ---

    private void sendRoomState(String targetZoneId, String sessionId, RoomSnapshot snapshot) {
        var node = MAPPER.createObjectNode();
        node.set("room", MAPPER.valueToTree(snapshot));
        sendEvent(targetZoneId, sessionId, "room_state", node);
    }

    private void sendEvent(String targetZoneId, String sessionId, String type, ObjectNode data) {
        // Test seam: if a capture is wired, divert there instead of publishing.
        var capture = testEventCapture;
        if (capture != null) {
            var envelope = MAPPER.createObjectNode();
            envelope.put("sessionId", sessionId);
            envelope.put("type", type);
            envelope.set("data", data);
            capture.accept(targetZoneId, envelope);
            return;
        }
        var subject = "federation." + targetZoneId + ".session." + sessionId + ".evt";
        var envelope = MAPPER.createObjectNode();
        envelope.put("sessionId", sessionId);
        envelope.put("type", type);
        envelope.set("data", data);
        try {
            var transport = relayTransport;
            if (transport != null && transport.isConnected()) {
                transport.publish(subject, MAPPER.writeValueAsBytes(envelope));
            } else {
                // Fallback to local NATS
                natsBridge.publishRaw(subject, MAPPER.writeValueAsBytes(envelope));
            }
        } catch (Exception e) {
            log.error("Failed to send event to {}: {}", subject, e.getMessage());
        }
    }

    // --- Test-only seams (used by VisitorConformanceTest; never wired in prod) ---

    /** Install/clear a capture function that intercepts {@link #sendEvent} output.
     *  Pass {@code null} to remove. Production code never sets this. */
    public void setTestEventCapture(BiConsumer<String, ObjectNode> capture) {
        this.testEventCapture = capture;
    }

    /** Construct a fake VirtualSession at the given starting room and register
     *  it in the active-sessions map. Used by the visitor conformance test to
     *  exercise the visitor verb surface without standing up a peer zone +
     *  signed transit token. */
    public void testInjectSession(String sessionId, String playerId, String playerName,
                                   String sourceZoneId, String startRoomId) {
        var session = new VirtualSession(sessionId, playerId, playerName,
            sourceZoneId, null, startRoomId);
        sessions.put(sessionId, session);
        seenSessionIds.add(sessionId);
    }

    /** Dispatch a raw visitor input line as if it had arrived via the NATS
     *  command channel. Test-only entry into {@link #dispatchParsedInput}. */
    public void testDispatchInput(String sessionId, String line) {
        var session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No test session: " + sessionId
                + ". Call testInjectSession() first.");
        }
        var roomRef = RoomRegistry.get().ref(session.currentRoomId);
        if (roomRef == null) {
            throw new IllegalStateException("Room not found: " + session.currentRoomId);
        }
        dispatchParsedInput(sessionId, session, roomRef, line);
    }

    // --- NATS command subscription ---

    private void subscribeToCommands(String sessionId, String sourceZoneId) {
        // Subscribe directly on the relay transport (not local NATS) to receive
        // commands from the remote zone without relay bridge forwarding loops.
        var transport = relayTransport;
        if (transport == null || !transport.isConnected()) {
            // Fallback to local NATS if no relay transport
            var cmdSubject = "federation." + localZoneId + ".session." + sessionId + ".cmd";
            natsBridge.subscribeRaw(cmdSubject, data -> {
                try { onCommand(sessionId, MAPPER.readTree(data)); } catch (Exception e) {
                    log.error("Error handling command for session {}: {}", sessionId, e.getMessage());
                }
            });
            return;
        }
        var cmdSubject = "federation." + localZoneId + ".session." + sessionId + ".cmd";
        var dispatcher = transport.subscribe(cmdSubject, data -> {
            try { onCommand(sessionId, MAPPER.readTree(data)); } catch (Exception e) {
                log.error("Error handling command for session {}: {}", sessionId, e.getMessage());
            }
        });
        if (dispatcher != null) cmdDispatchers.put(sessionId, dispatcher);
    }

    // --- Cleanup ---

    private void cleanupSession(VirtualSession session) {
        // Leave current room
        var roomRef = RoomRegistry.get().ref(session.currentRoomId);
        if (roomRef != null) {
            Rooms.<RoomResponse>ask(roomRef,
                ref -> new RoomCommand.LeaveRoom(session.playerId, session.playerName,
                    "portal", ref),
                ASK_TIMEOUT
            ).thenAccept(resp -> {});
        }

        // Unsubscribe from room events
        unsubscribeFromRoomEvents(session);

        // Remove from EntityRegistry
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.remove(session.playerId);
        }

        // Close command dispatcher
        var dispatcher = cmdDispatchers.remove(session.sessionId);
        if (dispatcher != null && relayTransport != null) {
            relayTransport.closeDispatcherObj(dispatcher);
        }
    }

    /**
     * Shut down all virtual sessions (called on server shutdown).
     */
    public void shutdown() {
        sessions.values().forEach(this::cleanupSession);
        sessions.clear();
        scheduler.shutdownNow();
        log.info("VirtualSessionHandler shut down");
    }

    /**
     * Number of active virtual sessions.
     */
    public int activeSessionCount() {
        return sessions.size();
    }

    // --- Helpers ---

    private static String oppositeDirection(String direction) {
        return switch (direction.toLowerCase()) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            case "up" -> "down";
            case "down" -> "up";
            case "northeast" -> "southwest";
            case "northwest" -> "southeast";
            case "southeast" -> "northwest";
            case "southwest" -> "northeast";
            default -> "somewhere";
        };
    }

    // --- Virtual Session state ---

    private static final class VirtualSession {
        final String sessionId;
        final String playerId;
        final String playerName;
        final String sourceZoneId;
        final TransitToken token;
        volatile String currentRoomId;
        volatile ActorRef<RoomNotification> roomSubscriber;
        /** When set, the session is draining — commands still accepted but close is pending. */
        volatile Instant drainingSince;

        /** Reputation snapshot carried from source zone (session-scoped). */
        volatile TransitReputation reputation;

        /** Virtual inventory carried from source zone (session-scoped, not persisted to DB). */
        final ConcurrentHashMap<String, TransitInventory.TransitItem>
            virtualInventory = new ConcurrentHashMap<>();
        /** Item IDs originally carried from source zone (to distinguish dropped-home-items from items-taken-here). */
        final Set<String> originalItemIds =
            ConcurrentHashMap.newKeySet();
        /** Items taken from the remote zone (to bring home). */
        final ConcurrentHashMap<String, TransitInventory.TransitItem>
            takenItems = new ConcurrentHashMap<>();

        VirtualSession(String sessionId, String playerId, String playerName,
                       String sourceZoneId, TransitToken token, String initialRoom) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.sourceZoneId = sourceZoneId;
            this.token = token;
            this.currentRoomId = initialRoom;
        }

        boolean isDraining() { return drainingSince != null; }

        /** Compute the inventory delta to send back to source zone on close. */
        TransitInventory.TransitDelta computeDelta() {
            // Removed: original items no longer in virtualInventory (dropped in remote zone)
            var removed = new ArrayList<String>();
            for (var origId : originalItemIds) {
                if (!virtualInventory.containsKey(origId)) {
                    removed.add(origId);
                }
            }
            // Added: items that were taken from remote zone (bringing home)
            var added = new ArrayList<>(takenItems.values());
            return new TransitInventory.TransitDelta(removed, added);
        }
    }
}
