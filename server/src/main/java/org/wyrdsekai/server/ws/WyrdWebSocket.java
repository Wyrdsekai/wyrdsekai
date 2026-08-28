package org.wyrdsekai.server.ws;

import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
// EntityRef removed — rooms use ActorRef via RoomRegistry
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.ContentBlock;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.ClientCommandMapper;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.core.hermod.HermodGrantStore;

import java.nio.file.Path;
import org.wyrdsekai.core.identity.PlayerPresence;
import org.wyrdsekai.core.identity.AccountService;
import org.wyrdsekai.core.identity.PlayerPresence;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.item.ItemRetirement;
import org.wyrdsekai.core.item.CarriedItemUse;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.server.session.SessionCommands;
import org.wyrdsekai.server.RelayCommandBridge;
import org.wyrdsekai.server.session.ClientSessionActor;
import org.wyrdsekai.server.voice.VoiceWebSocket;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.RemoteZoneSession;
import org.wyrdsekai.between.layer.UnifiedSessionService;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;
import org.wyrdsekai.core.agent.CommandRouter;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.agent.NotificationService;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeProxy;
import org.wyrdsekai.core.home.RelayGovernors;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.identity.PlayerAccount;
import org.wyrdsekai.core.item.HomeOwnerItemProvider;
import org.wyrdsekai.core.item.HouseholdItemContent;
import org.wyrdsekai.core.item.ToolItemStarterKit;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.item.ItemProviderRegistry;
import org.wyrdsekai.core.item.ItemScriptResponse;
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.core.room.ExamineLookup;
import org.wyrdsekai.core.room.RenameService;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.StudyProvisioner;
import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.core.soul.BondRitual;
import org.wyrdsekai.core.voice.SpeechToTextService;
import org.wyrdsekai.core.item.StandardItemLibrary;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;
import org.wyrdsekai.server.session.ClientConnection;
import org.wyrdsekai.server.session.ClientConnectionRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.time.Clock;

/**
 * Javalin WebSocket handler for Wyrdsekai connections.
 * Each connection gets a ClientSessionActor subscribed to its current room.
 * Routes commands to rooms via Cluster Sharding entity refs.
 */
public class WyrdWebSocket implements Consumer<WsConfig>, CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(WyrdWebSocket.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);
    private static final String GUEST_START_ROOM = "nexus";
    private static final String USER_START_ROOM = "study";

    /** Optional HomeClient — set by Main.java after HomeRegistryActor is spawned. */
    private volatile HomeClient homeClient;

    /** wiring: register the HomeClient so the {@code home} command can audit. */
    public void setHomeClient(HomeClient homeClient) {
        this.homeClient = homeClient;
    }

    /** Optional BondRitual — needed for the Shelf furnishing. */
    private volatile BondRitual bondRitual;

    public void setBondRitual(BondRitual bondRitual) {
        this.bondRitual = bondRitual;
    }

    /** Optional InviteService — backs world.invite in the Study control panel. */
    private volatile InviteService inviteService;

    public void setInviteService(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    /** Optional steward security-audit log (§101) — backs world.audit.security. */
    private volatile StewardAuditLog stewardAuditLog;

    public void setStewardAuditLog(StewardAuditLog stewardAuditLog) {
        this.stewardAuditLog = stewardAuditLog;
    }

    /** Optional BackupOrchestrator — backs world.safe.snapshots (read-only). */
    private volatile BackupOrchestrator backupOrchestrator;
    private volatile StudyService studyService;

    /** Late-wire: player-side pinboard/journal store (StudyService over Lucene). */
    public void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }

    public void setBackupOrchestrator(BackupOrchestrator backupOrchestrator) {
        this.backupOrchestrator = backupOrchestrator;
    }

    private final ActorSystem<?> system;
    private final AuthService authService;
    private final WardService wardService;
    private final InventoryService inventoryService;
    private final FederationService federationService;
    private final AccountService accountService; // nullable — Phase 3 identity
    private final PairingService pairingService; // nullable — device token auth
    private final boolean allowAnonymous;
    private final int maxConnections;
    private final Map<String, ActorRef<ClientSessionActor.SessionMessage>> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionCurrentRoom = new ConcurrentHashMap<>();
    private final Map<String, String> sessionPlayerIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionUserIds = new ConcurrentHashMap<>();
    private final Map<String, String> sessionLocales = new ConcurrentHashMap<>();
    private final Map<String, SessionRateLimiter> sessionRateLimiters = new ConcurrentHashMap<>();
    private final int rateLimitPerSecond = 10;

    /** Executor for invoking scripted items carried in a player's inventory on their home zone. */
    // Resolver wiring required — see WyrdShellCommand.newItemExecutor (second-node 2026-07-09).
    private static final StandardItemLibrary STD_ITEM_LIBRARY =
        new StandardItemLibrary(CompanionActor.stdScriptsRoot());
    private final ItemScriptExecutor itemScriptExecutor = newItemExecutor();

    private static ItemScriptExecutor newItemExecutor() {
        var ex = new ItemScriptExecutor();
        ex.setScriptResolver(STD_ITEM_LIBRARY::resolveBaseScript);
        return ex;
    }
    private final int rateLimitBurst = 20;
    /** Tracks last known room per playerId — survives session reconnects. */
    private final Map<String, String> playerLastRoom = new ConcurrentHashMap<>();
    /** Tracks display name per session for entity name in events. */
    private final Map<String, String> sessionPlayerNames = new ConcurrentHashMap<>();
    /** Zone command handlers keyed by namespace prefix (e.g. "codezaiku" → handler). §83.7 */
    private final Map<String, ZoneCommandHandler> zoneHandlers = new ConcurrentHashMap<>();
    /**
     * Zone session sinks: playerId → (sessionId → send function). Lets zone
     * adapters push async events to EVERY live web/CLI surface the account holds,
     * not just the most-recent one. Was a flat playerId→sink map (last-write-wins),
     * so a second web/CLI login silently clobbered the first's async-event sink.
     */
    private final Map<String, Map<String, Consumer<S2CMessage>>> zoneSinks = new ConcurrentHashMap<>();

    /**
     * Live WebSocket transport handle per sessionId, so a {@code logout}/
     * {@code sessions kill} from another surface can close this web/CLI channel
     * (the network socket lives here, not in the ClientSessionActor).
     */
    private final Map<String, WsContext> wsContexts = new ConcurrentHashMap<>();
    /** Zone topology for map commands — set post-construction from FoundationRoomLoader seeds. */
    private volatile ZoneTopology zoneTopology;
    /** Between actor for cross-node presence replication (nullable — absent in single-node mode). */
    private volatile ActorRef<BetweenActor.Command> betweenActor;
    /** Unified session service for cross-device session management (nullable). */
    private volatile UnifiedSessionService sessionService;
    /** NATS bridge for remote zone session proxying (nullable — set when Between is active). */
    private volatile NatsBridge natsBridge;
    /** Local zone ID for NATS subject construction. */
    private volatile String localZoneId;
    /** Direct relay transport for session proxy (bypasses relay bridge). */
    private volatile RelaySessionTransport relayTransport;
    /** Active remote zone sessions per WebSocket session: sessionId → RemoteZoneSession. */
    private final Map<String, RemoteZoneSession> remoteZoneSessions = new ConcurrentHashMap<>();
    /** Dedup relay-amplified remote events by content hash. */
    private final Set<Integer> recentRemoteEventHashes = Collections.newSetFromMap(
        new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> e) {
                return size() > 512;
            }
        });

    public WyrdWebSocket(ActorSystem<?> system, AuthService authService,
                         WardService wardService, InventoryService inventoryService) {
        this(system, authService, wardService, inventoryService, null, null, null, true, 100);
    }

    public WyrdWebSocket(ActorSystem<?> system, AuthService authService,
                         WardService wardService, InventoryService inventoryService,
                         FederationService federationService) {
        this(system, authService, wardService, inventoryService, federationService, null, null, true, 100);
    }

    public WyrdWebSocket(ActorSystem<?> system, AuthService authService,
                         WardService wardService, InventoryService inventoryService,
                         FederationService federationService,
                         boolean allowAnonymous, int maxConnections) {
        this(system, authService, wardService, inventoryService, federationService, null, null,
            allowAnonymous, maxConnections);
    }

    public WyrdWebSocket(ActorSystem<?> system, AuthService authService,
                         WardService wardService, InventoryService inventoryService,
                         FederationService federationService,
                         PairingService pairingService,
                         boolean allowAnonymous, int maxConnections) {
        this(system, authService, wardService, inventoryService, federationService, null,
            pairingService, allowAnonymous, maxConnections);
    }

    public WyrdWebSocket(ActorSystem<?> system, AuthService authService,
                         WardService wardService, InventoryService inventoryService,
                         FederationService federationService,
                         AccountService accountService,
                         PairingService pairingService,
                         boolean allowAnonymous, int maxConnections) {
        this.system = system;
        this.authService = authService;
        this.wardService = wardService;
        this.inventoryService = inventoryService;
        this.federationService = federationService;
        this.accountService = accountService;
        this.pairingService = pairingService;
        this.allowAnonymous = allowAnonymous;
        this.maxConnections = maxConnections;
        // Furnishing-as-scripted-item bridge: RoomActor invokes a used
        // furnishing's backing scripts/items/*.js with the provider this
        // factory builds, so `use <furnishing>` reaches live household
        // services (audit/grants/bonds/pairing/…) instead of a stub.
        // Reads this instance's fields lazily at use-time, so late-wired
        // services (homeClient, bondRitual) are picked up once set.
        ItemProviderRegistry.register(this::buildPlayerProvider);
        // W5 (audit 2026-07-11): /voice transcriptions previously went only back
        // to the voice socket — never into the world. Register this instance as
        // the sink so a transcription whose voice-session id matches a live
        // world session lands in its room via ClientSessionActor.VoiceTranscription.
        VoiceWebSocket.setTranscriptionSink(this::routeVoiceTranscription);
    }

    /**
     * Route a /voice transcription into the transcriber's current room. The
     * voice socket carries the world sessionId as its {@code ?session=} query
     * param; unknown ids (standalone voice sessions) are dropped with a debug
     * line rather than spoken by nobody.
     */
    public void routeVoiceTranscription(String sessionId, String text) {
        if (sessionId == null || text == null || text.isBlank()) return;
        var sessionRef = sessions.get(sessionId);
        if (sessionRef == null) {
            log.debug("Voice transcription for unknown session {} — not routed to a room",
                sessionId);
            return;
        }
        sessionRef.tell(new ClientSessionActor.VoiceTranscription(
            sessionId, text, sessionCurrentRoom.get(sessionId),
            sessionPlayerNames.get(sessionId)));
    }

    /**
     * Register a zone command handler for a namespace prefix (§83.7).
     * Commands like "codezaiku.approve" route to the handler registered under "codezaiku".
     */
    public void registerZoneHandler(String namespace, ZoneCommandHandler handler) {
        zoneHandlers.put(namespace, handler);
        log.info("Zone command handler registered: {}", namespace);
    }

    /**
     * Unregister a zone command handler (e.g. when a zone service disconnects).
     */
    public void unregisterZoneHandler(String namespace) {
        if (zoneHandlers.remove(namespace) != null) {
            log.info("Zone command handler unregistered: {}", namespace);
        }
    }

    /**
     * Get the async event sink for a player (used by zone adapters to push events).
     * Returns null if the player has no active session.
     */
    public Consumer<S2CMessage> getZoneSink(String playerId) {
        var sinks = zoneSinks.get(playerId);
        if (sinks == null || sinks.isEmpty()) return null;
        // Fan out to every live surface for this account so async zone events
        // reach all of them, not just the latest login.
        return msg -> {
            for (var sink : sinks.values()) {
                try { sink.accept(msg); } catch (RuntimeException ignored) {}
            }
        };
    }

    /**
     * Get all registered zone handler namespaces.
     */
    public Set<String> zoneNamespaces() {
        return Collections.unmodifiableSet(zoneHandlers.keySet());
    }

    /** Set the zone topology used for MapRequest handling. */
    public void setZoneTopology(ZoneTopology topology) {
        this.zoneTopology = topology;
    }

    /** Wire the Between actor for cross-node presence replication. */
    public void setBetweenActor(ActorRef<BetweenActor.Command> betweenActor) {
        this.betweenActor = betweenActor;
    }

    /** Wire the unified session service for cross-device sessions (Wave 5). */
    public void setSessionService(UnifiedSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Wire the NATS bridge and relay connection for remote zone session proxying. */
    public void setNatsBridge(NatsBridge natsBridge, String localZoneId) {
        this.natsBridge = natsBridge;
        this.localZoneId = localZoneId;
    }

    /** Set direct relay transport for session proxy (bypasses relay bridge forwarding). */
    public void setRelayTransport(RelaySessionTransport transport) {
        this.relayTransport = transport;
    }

    /** Transport-agnostic registry for cross-zone transit lookups. */
    private volatile ClientConnectionRegistry connectionRegistry;

    public void setConnectionRegistry(ClientConnectionRegistry registry) {
        this.connectionRegistry = registry;
    }

    /**
     * Adapter exposing a WS session through the transport-agnostic
     * {@link org.wyrdsekai.server.session.ClientConnection} interface. All
     * methods delegate to the enclosing {@link WyrdWebSocket} so behavior
     * stays identical to the pre-interface world.
     */
    private final class WsClientConnection
            implements ClientConnection {
        private final String sessionId;
        private final String playerId;
        private final String playerName;

        WsClientConnection(String sessionId, String playerId, String playerName) {
            this.sessionId = sessionId;
            this.playerId = playerId;
            this.playerName = playerName;
        }

        @Override public String sessionId() { return sessionId; }
        @Override public String playerId() { return playerId; }
        @Override public String playerName() { return playerName; }

        @Override
        public boolean startRemoteSession(String remoteZoneId, String transitToken) {
            return WyrdWebSocket.this.startRemoteSession(sessionId, remoteZoneId, transitToken);
        }

        @Override
        public void endRemoteSession() {
            WyrdWebSocket.this.endRemoteSession(sessionId);
        }

        @Override
        public boolean isProxying() {
            var rs = remoteZoneSessions.get(sessionId);
            return rs != null && rs.isActive();
        }

        @Override
        public String currentRemoteZoneId() {
            var rs = remoteZoneSessions.get(sessionId);
            return rs != null ? rs.remoteZoneId() : null;
        }

        @Override
        public void disconnect(String reason) {
            // Close this web/CLI channel — used by logout/quitall from another
            // surface, and by sessions-kill. The room departure is handled by
            // the normal onClose cleanup (suppressed if other surfaces remain).
            var ctx = wsContexts.get(sessionId);
            if (ctx != null) {
                try { ctx.closeSession(4000, reason != null ? reason : "logout"); }
                catch (RuntimeException ignored) {}
            }
        }

        @Override
        public boolean deliverLine(String text) {
            // Tell-back leg (second-node re-verify 2026-07-11 #29): route through THIS
            // session's actor so the register covers WS exactly like SSH/Telnet.
            var sessionRef = sessions.get(sessionId);
            if (sessionRef == null) return false;
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0L, "tell", text, List.of(), null, "normal")));
            return true;
        }
    }

    /**
     * Start a remote zone session for a player. Commands are proxied to the remote zone,
     * events are rendered locally. The player never disconnects — this is the "browser" model.
     *
     * @param wsSessionId the WebSocket session ID
     * @param remoteZoneId target zone to proxy into
     * @param transitToken the issued transit token for authentication
     */
    public boolean startRemoteSession(String wsSessionId, String remoteZoneId, String transitToken) {
        if (natsBridge == null || localZoneId == null) {
            log.warn("Cannot start remote session — NATS bridge not wired");
            return false;
        }

        var playerId = sessionPlayerIds.get(wsSessionId);
        var playerName = sessionPlayerNames.getOrDefault(wsSessionId, "player");
        var sessionRef = sessions.get(wsSessionId);
        if (playerId == null || sessionRef == null) {
            log.warn("Cannot start remote session — no session for {}", wsSessionId);
            return false;
        }

        // Close any existing remote session for this WS session
        endRemoteSession(wsSessionId);

        if (relayTransport == null || !relayTransport.isConnected()) {
            log.warn("No relay transport for remote session — transit unavailable");
            return false;
        }
        var remoteSession = new RemoteZoneSession(
            playerId, playerName, transitToken,
            localZoneId, remoteZoneId, relayTransport,
            eventJson -> {
                // Parse incoming event from remote zone and forward to the player's WebSocket
                try {
                    var eventNode = Json.mapper().readTree(eventJson);
                    var type = eventNode.path("type").asText("");
                    var data = eventNode.path("data");
                    log.info("Remote event received: type='{}' size={}", type, eventJson.length());

                    S2CMessage s2cMsg = switch (type) {
                        case "room_state" -> {
                            var roomNode = data.path("room");
                            var snapshot = Json.mapper()
                                .treeToValue(roomNode, RoomSnapshot.class);
                            yield new S2CMessage.RoomState(0, snapshot, List.of());
                        }
                        case "prose" -> new S2CMessage.Prose(0,
                            data.path("speaker").asText("system"),
                            data.path("text").asText(""),
                            List.of(), null,
                            data.path("priority").asText("normal"));
                        case "room_event" -> {
                            var eventType = data.path("eventType").asText("");
                            var eventData = data.path("data");
                            // Convert common room events to Prose for display
                            yield switch (eventType) {
                                case "Said" -> new S2CMessage.Prose(0,
                                    eventData.path("entityName").asText("someone"),
                                    eventData.path("text").asText(""),
                                    List.of(), null, "normal");
                                case "EntityEntered" -> new S2CMessage.Prose(0,
                                    "narrator",
                                    movementArrivalText(eventData.path("entityName").asText("someone"),
                                        eventData.path("fromDirection").asText("")),
                                    List.of(), null, "ambient");
                                case "EntityLeft" -> new S2CMessage.Prose(0,
                                    "narrator",
                                    movementDepartureText(eventData.path("entityName").asText("someone"),
                                        eventData.path("direction").asText("")),
                                    List.of(), null, "ambient");
                                case "Emoted" -> new S2CMessage.Prose(0,
                                    eventData.path("entityName").asText("someone"),
                                    eventData.path("text").asText(""),
                                    List.of(), null, "normal", (String) null, "emote");
                                default -> new S2CMessage.Prose(0, "system",
                                    "[" + eventType + "]", List.of(), null, "ambient");
                            };
                        }
                        case "error" -> new S2CMessage.Error(0, "remote_error",
                            data.path("message").asText("Unknown error"), null);
                        case "notification" -> new S2CMessage.Notification(0,
                            data.path("priority").asText("normal"),
                            data.path("fromAgent").asText("system"),
                            data.path("message").asText(""));
                        default -> new S2CMessage.Prose(0, "system",
                            "[remote: " + type + "]", List.of(), null, "ambient");
                    };
                    sessionRef.tell(new ClientSessionActor.SendMessage(s2cMsg));
                } catch (Exception e) {
                    log.warn("Failed to parse remote zone event for session {}: {}",
                        wsSessionId, e.getMessage());
                }
            });

        // Serialize player's inventory for transit
        TransitInventory inventory = null;
        if (inventoryService != null) {
            try {
                inventory = inventoryService.serializeForTransit(playerId, localZoneId);
                if (!inventory.items().isEmpty()) {
                    log.info("Player {} carrying {} items to {}",
                        playerName, inventory.items().size(), remoteZoneId);
                }
            } catch (Exception e) {
                log.warn("Failed to serialize inventory for {}: {}", playerId, e.getMessage());
            }
        }

        // Serialize player's reputation for transit (destination zone uses for permissions)
        TransitReputation reputation = null;
        var attestService = AttestationService.get();
        if (attestService != null) {
            try {
                reputation = attestService.serializeForTransit(playerId, localZoneId);
                log.info("Player {} carrying reputation: tier={}",
                    playerName, reputation.permissionTier());
            } catch (Exception e) {
                log.warn("Failed to serialize reputation for {}: {}", playerId, e.getMessage());
            }
        }

        // Register delta callback to apply inventory changes when session returns
        final var finalPlayerId = playerId;
        remoteSession.setDeltaCallback(delta -> {
            if (inventoryService != null && delta != null && !delta.isEmpty()) {
                try {
                    inventoryService.applyTransitDelta(finalPlayerId, delta);
                } catch (Exception e) {
                    log.error("Failed to apply transit delta for {}: {}",
                        finalPlayerId, e.getMessage());
                }
            }
        });

        remoteSession.open(inventory, reputation);
        remoteZoneSessions.put(wsSessionId, remoteSession);
        final boolean opened = remoteSession.isActive();

        // Mark player as traveling in EntityRegistry
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.setTraveling(playerId, remoteZoneId);
            entityRegistry.setHomeZone(playerId, localZoneId);
        }

        // Notify the player they've entered the remote zone
        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Transit(0, remoteZoneId, null, transitToken,
                "You step through the portal into zone '" + remoteZoneId + "'...")));

        log.info("Remote session started for {} → zone '{}'", wsSessionId, remoteZoneId);
        return opened;
    }

    /**
     * End a remote zone session, returning the player to their local zone.
     *
     * @param wsSessionId the WebSocket session ID
     */
    public void endRemoteSession(String wsSessionId) {
        var remoteSession = remoteZoneSessions.remove(wsSessionId);
        if (remoteSession != null && remoteSession.isActive()) {
            remoteSession.close();
            // Mark player as returned from travel
            var returningPlayerId = sessionPlayerIds.get(wsSessionId);
            var entityRegistry = EntityRegistry.get();
            if (returningPlayerId != null && entityRegistry != null) {
                entityRegistry.setReturned(returningPlayerId);
                // Flush any buffered notifications from while they were away
                var notifService = NotificationService.get();
                if (notifService != null) {
                    var flushed = notifService.flushBuffered(returningPlayerId);
                    if (!flushed.isEmpty()) {
                        log.info("Delivered {} buffered notifications to returning player {}",
                            flushed.size(), returningPlayerId);
                    }
                }
            }
            // Keep delta subscription alive for 3 seconds to receive final inventory delta
            final var finalRemoteSession = remoteSession;
            CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS)
                .execute(finalRemoteSession::closeDelta);
            log.info("Remote session ended for {} (was in zone '{}')",
                wsSessionId, remoteSession.remoteZoneId());

            // Notify the player they've returned home
            var sessionRef = sessions.get(wsSessionId);
            if (sessionRef != null) {
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Prose(0, "narrator",
                        "You return through the portal to your home zone.",
                        List.of(), null, "normal")));

                // Re-look at the local room they were in
                var currentRoomId = sessionCurrentRoom.get(wsSessionId);
                var playerId = sessionPlayerIds.get(wsSessionId);
                if (currentRoomId != null && playerId != null) {
                    var room = roomRef(currentRoomId);
                    if (room != null) {
                        askRoomWithInventory(room,
                            ref -> new RoomCommand.LookRoom(playerId, getSessionLocale(wsSessionId), ref),
                            sessionRef, playerId, "transit_return");
                    }
                }
            }
        }
    }

    /**
     * Check if a WebSocket session has an active remote zone session.
     */
    public boolean hasActiveRemoteSession(String wsSessionId) {
        var rs = remoteZoneSessions.get(wsSessionId);
        return rs != null && rs.isActive();
    }

    /**
     * Get all session-to-playerId entries. Used by transit starter to find the
     * WebSocket session for a given player.
     */
    public Set<Map.Entry<String, String>> sessionPlayerIdEntries() {
        return sessionPlayerIds.entrySet();
    }

    /**
     * Look up the remote session ID for a given player DID, if that player is currently
     * traveling to a remote zone. Returns null if not traveling.
     */
    public String remoteSessionIdFor(String playerId) {
        if (playerId == null) return null;
        for (var entry : sessionPlayerIds.entrySet()) {
            if (playerId.equals(entry.getValue())) {
                var rs = remoteZoneSessions.get(entry.getKey());
                if (rs != null && rs.isActive()) {
                    return rs.sessionId();
                }
            }
        }
        return null;
    }

    private void publishPresence(String playerId, String displayName, String roomId) {
        if (betweenActor != null) {
            betweenActor.tell(new BetweenActor.PublishPresence(
                new PlayerPresence(playerId, displayName,
                    system.address().toString(), roomId, Instant.now())));
        }
    }

    private void publishOffline(String playerId) {
        if (betweenActor != null) {
            betweenActor.tell(new BetweenActor.PublishOffline(playerId));
        }
    }

    // ── CommandRouter implementation (used by agents) ────────────────────

    @Override
    public boolean execute(String entityId, String command, List<String> args,
                           Map<String, String> payload,
                           Consumer<S2CMessage> respond) {
        var dotIndex = command.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= command.length() - 1) return false;

        var namespace = command.substring(0, dotIndex);
        var action = command.substring(dotIndex + 1);
        var handler = zoneHandlers.get(namespace);
        if (handler == null) return false;

        handler.handle(entityId, action,
            args != null ? args : List.of(),
            payload != null ? payload : Map.of(),
            respond);
        return true;
    }

    @Override
    public Set<String> availableNamespaces() {
        return zoneNamespaces();
    }

    /**
     * Deliver a message to a specific player's session(s), or to all if playerId is "all".
     * Used by NotificationService to push agent notifications to humans.
     *
     * @param playerId the player DID to deliver to, or "all" for broadcast
     * @param msg      the S2CMessage to send
     * @return true when at least one live WS session received the message.
     *         Rita re-verify 2026-07-11 (#29): this was {@code void}, so the
     *         tell-back deliverer wired in Main could not tell "delivered"
     *         from "player has no WS session" (e.g. SSH-only login) and the
     *         companion's teleport fallback was wrongly suppressed.
     */
    public boolean deliverToPlayer(String playerId, S2CMessage msg) {
        boolean delivered = false;
        for (var entry : sessionPlayerIds.entrySet()) {
            if ("all".equals(playerId) || playerId.equals(entry.getValue())) {
                var sessionRef = sessions.get(entry.getKey());
                if (sessionRef != null) {
                    sessionRef.tell(new ClientSessionActor.SendMessage(msg));
                    delivered = true;
                }
            }
        }
        return delivered;
    }

    /**
     * Broadcast a message to all players in a specific room, or all players if roomId is null.
     * Used by zone services to push unsolicited events (board updates, status changes).
     */
    public void broadcastToRoom(String roomId, S2CMessage msg) {
        for (var entry : sessions.entrySet()) {
            var sessionId = entry.getKey();
            if (roomId == null || roomId.equals(sessionCurrentRoom.get(sessionId))) {
                entry.getValue().tell(new ClientSessionActor.SendMessage(msg));
            }
        }

        // Deliver to agents via AgentEventStream
        var eventStream = AgentEventStream.get();
        if (eventStream != null) {
            eventStream.publishZoneBroadcast("zone", roomId, msg);
        }
    }

    private ActorRef<RoomCommand> roomRef(String roomId) {
        return RoomRegistry.get().ref(roomId);
    }

    /**
     * login landing branches on residency. Matches the
     * SSH + Telnet paths so transport choice doesn't change where you wake
     * up.
     *
     * <ul>
     *   <li>Resident of this zone → Study</li>
     *   <li>Known but not a resident (e.g., replicated account record) → Docks</li>
     * </ul>
     *
     * <p>From Docks a non-resident can {@code travel home} back to their
     * resident zone or {@code knock <steward>} to request residency.</p>
     *
     * <p>When ResidencyStore isn't initialised (early boot, non-federated
     * dev) or {@code localZoneId} is unknown, we default to the pre-§25
     * behaviour of always landing in the Study — safer than blocking login
     * on a lookup that legitimately doesn't exist yet.</p>
     */
    private String defaultLandingRoom(String playerId) {
        try {
            var store = ResidencyStore.get();
            if (store == null || localZoneId == null) {
                return StudyProvisioner.studyRoomId(playerId);
            }
            return store.isResident(playerId, localZoneId)
                ? StudyProvisioner.studyRoomId(playerId)
                : "docks";
        } catch (Exception e) {
            log.debug("Residency check failed for {}, defaulting to Study: {}",
                playerId, e.getMessage());
            return StudyProvisioner.studyRoomId(playerId);
        }
    }

    @Override
    public void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            // Connection limit check
            if (sessions.size() >= maxConnections) {
                log.warn("Connection limit reached ({}/{}), rejecting new connection",
                    sessions.size(), maxConnections);
                ctx.closeSession(4029, "Too many connections");
                return;
            }

            var sessionId = UUID.randomUUID().toString();
            ctx.attribute("sessionId", sessionId);

            // Authenticate: transit token, session token, or anonymous
            final String playerId;
            final String playerName;
            final String userId;
            final String startRoom;

            var transitTokenParam = ctx.queryParam("transit_token");
            var token = ctx.queryParam("token");
            var deviceToken = ctx.queryParam("device_token");
            var localeParam = ctx.queryParam("locale");
            var roomParam = ctx.queryParam("room");

            if (transitTokenParam != null && federationService != null) {
                // Transit token authentication — visiting agent from another zone
                var transitToken = federationService.validateTransitToken(transitTokenParam);
                if (transitToken.isEmpty()) {
                    ctx.closeSession(4003, "Invalid or expired transit token");
                    return;
                }
                var tt = transitToken.get();

                // Phase 5: Soul-aware transit arrival
                if (tt.hasSoul()) {
                    playerId = tt.agentDid(); // Use DID as player ID for souled agents
                    playerName = tt.agentName() + " (soul-transit from " + tt.sourceZoneId() + ")";
                    log.info("Soul-aware transit: session={} agent={} did={} manifestHash={} from zone {}",
                        sessionId, tt.agentName(), tt.agentDid(), tt.manifestHash(), tt.sourceZoneId());
                } else {
                    playerId = "tourist-" + tt.agentId().substring(0, 8);
                    playerName = tt.agentName() + " (tourist from " + tt.sourceZoneId() + ")";
                    log.info("Transit WebSocket: {} as {} from zone {}",
                        sessionId, tt.agentName(), tt.sourceZoneId());
                }
                userId = null;
                startRoom = "docks"; // Tourists arrive at The Docks
            } else if (token != null) {
                var user = authService.validateSession(token);
                if (user.isEmpty()) {
                    ctx.closeSession(4001, "Invalid or expired session token");
                    return;
                }
                playerId = user.get().id();
                playerName = user.get().displayName();
                userId = user.get().id();
                // Client sends ?room= on reconnect; fall back to server-tracked, then Nexus.
                // ?home=1 (set by the phone relay tunnel) forces residency landing —
                // a fresh device session should wake in its Study, not resume the
                // last room another surface left behind.
                startRoom = roomParam != null ? roomParam
                    : "1".equals(ctx.queryParam("home")) ? defaultLandingRoom(playerId)
                    : playerLastRoom.getOrDefault(playerId, defaultLandingRoom(playerId));
                log.info("Authenticated WebSocket: {} as {} ({}) room={}",
                    sessionId, user.get().username(), userId, startRoom);
            } else if (deviceToken != null && deviceToken.startsWith("wyrd_dev_") && pairingService != null) {
                // Device token authentication — paired phone/device node
                var device = pairingService.validateDeviceToken(deviceToken);
                if (device.isEmpty()) {
                    ctx.closeSession(4004, "Invalid or revoked device token");
                    return;
                }
                pairingService.touchDevice(deviceToken);
                var d = device.get();

                // Check if device is linked to a user account
                if (d.userId() != null && !d.userId().isBlank()) {
                    var linkedUser = authService.findUser(d.userId());
                    if (linkedUser.isPresent()) {
                        var lu = linkedUser.get();
                        playerId = lu.id();
                        playerName = lu.displayName();
                        userId = lu.id();
                        startRoom = roomParam != null ? roomParam
                            : playerLastRoom.getOrDefault(playerId, defaultLandingRoom(playerId));
                        log.info("Device WebSocket (linked user): {} as {} ({}) device={} room={}",
                            sessionId, lu.username(), lu.id(), d.id(), startRoom);
                    } else {
                        // Linked user not found — fall back to anonymous device
                        playerId = "device-" + d.id().substring(0, 8);
                        playerName = d.name().isBlank() ? "device" : d.name();
                        userId = null;
                        startRoom = roomParam != null ? roomParam
                            : playerLastRoom.getOrDefault(playerId, defaultLandingRoom(playerId));
                        log.warn("Device {} linked to missing user {}, connecting as anonymous device",
                            d.id(), d.userId());
                    }
                } else {
                    // No linked user — connect as anonymous device
                    playerId = "device-" + d.id().substring(0, 8);
                    playerName = d.name().isBlank() ? "device" : d.name();
                    userId = null;
                    startRoom = roomParam != null ? roomParam
                        : playerLastRoom.getOrDefault(playerId, defaultLandingRoom(playerId));
                    log.info("Device WebSocket (anonymous): {} as {} ({}) room={}",
                        sessionId, d.name(), d.id(), startRoom);
                }
            } else {
                // Try device auto-login via AccountService (Phase 3 identity)
                var deviceId = ctx.queryParam("device_id");
                var autoAccount = (accountService != null && deviceId != null)
                    ? accountService.autoLogin(deviceId)
                    : Optional.<PlayerAccount>empty();

                if (autoAccount.isPresent()) {
                    var account = autoAccount.get();
                    playerId = account.did();
                    playerName = account.displayName();
                    userId = null; // DID-based, not session-based
                    startRoom = roomParam != null ? roomParam
                        : playerLastRoom.getOrDefault(playerId, defaultLandingRoom(playerId));
                    log.info("Auto-login WebSocket: {} as {} ({}) room={}",
                        sessionId, account.displayName(), account.did(), startRoom);
                } else {
                    // Anonymous access — gated by config
                    if (!allowAnonymous) {
                        ctx.closeSession(4002, "Authentication required");
                        return;
                    }
                    playerId = "anon-" + sessionId.substring(0, 8);
                    playerName = "anonymous";
                    userId = null;
                    startRoom = roomParam != null ? roomParam
                        : playerLastRoom.getOrDefault(playerId, GUEST_START_ROOM);
                    log.info("Anonymous WebSocket: {} as {} room={}", sessionId, playerId, startRoom);
                }
            }

            // Parental time limit: a member whose daily hours are spent cannot
            // start a new session. Kind refusal, then close (mirrors the
            // failed-login closeSession calls above). No-op ALLOW when the
            // service isn't wired (tests, bare boots) or for non-member ids.
            var parentalSvc = ParentalControlService.get();
            if (parentalSvc != null && userId != null) {
                var minutesLeft = parentalSvc.minutesRemaining(userId);
                if (minutesLeft != null && minutesLeft <= 0) {
                    try {
                        ctx.send(Json.mapper().writeValueAsString(new S2CMessage.Error(0,
                            "parental_time_limit",
                            "Today's hours in the world are spent — the household clock says rest.",
                            null)));
                    } catch (Exception ignored) {
                        // best-effort courtesy line; the close below still lands
                    }
                    ctx.closeSession(4030, "Daily time limit reached");
                    return;
                }
            }

            // Maintenance mode: while the dial is on, only the steward may
            // start a session. Kind refusal, then close (same mechanics as
            // the parental gate above). No-op ALLOW when the service isn't
            // wired (tests, bare boots).
            var maintenanceSvc = MaintenanceService.get();
            if (maintenanceSvc != null) {
                var gateId = userId != null ? userId : playerId;
                if (!maintenanceSvc.allowsLogin(gateId)) {
                    try {
                        ctx.send(Json.mapper().writeValueAsString(new S2CMessage.Error(0,
                            "maintenance_mode",
                            maintenanceSvc.refusalLine(),
                            null)));
                    } catch (Exception ignored) {
                        // best-effort courtesy line; the close below still lands
                    }
                    ctx.closeSession(4031, "Household under maintenance");
                    return;
                }
            }

            var sessionRef = system.<ClientSessionActor.SessionMessage>systemActorOf(
                ClientSessionActor.create(sessionId, ctx::send),
                "session-" + sessionId.substring(0, 8),
                Props.empty());
            sessions.put(sessionId, sessionRef);
            sessionRateLimiters.put(sessionId, new SessionRateLimiter(rateLimitBurst, rateLimitPerSecond));
            // Provision private Study for all players (including anonymous)
            // Steward gets additional study objects (Wave 1: Accounts & Security)
            if (playerId != null) {
                var isSteward = userId != null && authService != null
                    && authService.findUser(userId).map(u -> "steward".equals(u.role())).orElse(false);
                @SuppressWarnings("unchecked")
                var zg = (ActorSystem<ZoneGuardian.Command>) (Object) system;
                zg.tell(new ZoneGuardian.ProvisionStudy(playerId, playerName, isSteward));
                // seed scripted furnishings (Embers, Board) into
                // the owner's inventory. Idempotent — addItem upserts on repeat login.
                if (!playerId.startsWith("anon-")) {
                    seedHomeFurnishings(playerId, startRoom, isSteward);
                }
            }
            // Register player in EntityRegistry so agents can find them
            var entityRegistry = EntityRegistry.get();
            if (entityRegistry != null && playerId != null) {
                entityRegistry.enter(playerId, playerName, "player", startRoom);
            }

            // On-login flush: notify EVERY companion (multi-companion households).
            if (entityRegistry != null && playerId != null && !playerId.startsWith("anon-")) {
                for (var entityId : entityRegistry.allEntities()) {
                    if (entityRegistry.isAgent(entityId)) {
                        var companionRef = ZoneGuardian.getCompanionRef(null, entityId);
                        if (companionRef != null) {
                            companionRef.tell(
                                new CompanionActor.PlayerReturned(
                                    playerId, playerName, startRoom));
                        }
                    }
                }
            }

            sessionCurrentRoom.put(sessionId, startRoom);
            sessionPlayerIds.put(sessionId, playerId);
            playerLastRoom.put(playerId, startRoom);
            sessionPlayerNames.put(sessionId, playerName);
            if (userId != null) sessionUserIds.put(sessionId, userId);

            // Register with transport-agnostic registry so federation and other
            // paths can reach this client by playerId regardless of transport.
            if (connectionRegistry != null) {
                connectionRegistry.register(new WsClientConnection(sessionId, playerId, playerName));
            }
            // Keep the transport handle so logout/sessions-kill from another
            // surface can close this socket.
            wsContexts.put(sessionId, ctx);

            // Register zone sink so zone adapters can push async events to this
            // session. Keyed per-session under the player so multiple surfaces of
            // one account each keep their own sink (fan-out in getZoneSink).
            zoneSinks
                .computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(sessionId, msg -> sessionRef.tell(new ClientSessionActor.SendMessage(msg)));

            // Set locale from URL param (client sends on connect)
            var initialLocale = localeParam != null ? localeParam : "en";
            sessionLocales.put(sessionId, initialLocale);
            sessionRef.tell(new ClientSessionActor.SetLocale(initialLocale));
            // W8 (audit 2026-07-11): companions have a SetLocale handler that was
            // never sent — they never learned the client's language.
            for (var cRef : ZoneGuardian.allCompanionRefs()) {
                cRef.tell(new CompanionActor.SetLocale(initialLocale));
            }

            var startRoomRef = roomRef(startRoom);

            // Subscribe + Enter + Look routed through session actor for guaranteed ordering.
            // Pekko single-sender guarantee ensures Subscribe processes before EnterRoom,
            // so onEnter script narrate events reach the client.
            sessionRef.tell(new ClientSessionActor.JoinRoomAndEnter(
                startRoomRef, playerId, playerName, "player", "nowhere",
                initialLocale, "init", loadInventory(playerId)));

            // record a HOME_ENTERED audit when the login landing
            // point is the player's own Study. This is what makes "my home tracked
            // my arrival" work even when I didn't type `home`.
            if (playerId != null && !playerId.startsWith("anon-")
                    && StudyProvisioner.studyRoomId(playerId).equals(startRoom)) {
                recordHomeEntered(playerId);
            }

            log.info("WebSocket connected: {} as {}", sessionId, playerId);

            // Publish presence to Between for cross-node visibility
            publishPresence(playerId, playerName, startRoom);

            // Wave 5: Unified session — join or create session
            var ss = sessionService;
            if (ss != null && userId != null) {
                try {
                    var memberSession = ss.memberConnect(userId, playerName, sessionId, startRoom);
                    if (memberSession.devices().size() > 1) {
                        log.info("Member {} joined existing session (room={}, devices={})",
                            userId, memberSession.currentRoom(), memberSession.devices().size());
                    }
                } catch (Exception sessionErr) {
                    log.debug("Session service unavailable: {}", sessionErr.getMessage());
                }
            }
        });

        ws.onMessage(ctx -> {
            var sessionId = (String) ctx.attribute("sessionId");
            if (connectionRegistry != null) connectionRegistry.touch(sessionId);
            var limiter = sessionRateLimiters.get(sessionId);
            if (limiter != null && !limiter.tryConsume()) {
                log.warn("Rate limited session {}", sessionId);
                return;
            }
            try {
                // Silently ignore client ping/keepalive messages
                var raw = ctx.message();
                if (raw != null && raw.contains("\"type\":\"ping\"")) {
                    return;
                }
                var msg = Json.mapper().readValue(raw, C2SMessage.class);
                handleC2S(sessionId, msg);
            } catch (Exception e) {
                log.warn("Failed to parse C2S message from {}: {}", sessionId,
                    ctx.message() != null && ctx.message().length() > 100
                        ? ctx.message().substring(0, 100) + "..." : ctx.message());
            }
        });

        ws.onClose(ctx -> {
            var sessionId = (String) ctx.attribute("sessionId");

            // Close any active remote zone session before local cleanup
            endRemoteSession(sessionId);

            var sessionRef = sessions.remove(sessionId);
            var currentRoomId = sessionCurrentRoom.remove(sessionId);
            var playerId = sessionPlayerIds.remove(sessionId);
            var playerDisplayName = sessionPlayerNames.remove(sessionId);
            sessionUserIds.remove(sessionId);
            wsContexts.remove(sessionId);
            if (playerId != null) {
                // Drop only THIS session's sink; keep the account's other surfaces.
                var sinks = zoneSinks.get(playerId);
                if (sinks != null) {
                    sinks.remove(sessionId);
                    if (sinks.isEmpty()) zoneSinks.remove(playerId);
                }
            }
            sessionLocales.remove(sessionId);
            sessionRateLimiters.remove(sessionId);

            // Suppress the room departure + entity removal while the same account
            // is still present through another surface (e.g. the CLI/WS closing
            // while an SSH session is still up). Without this the other surface
            // sees "X heads disconnect." even though X is still here. The registry
            // unregister for this session happens further below, so this session
            // is still present — exclude it by sessionId.
            boolean stillPresent = connectionRegistry != null
                && connectionRegistry.hasOtherLiveSession(playerId, sessionId);

            // Leave current room
            if (currentRoomId != null && playerId != null && !stillPresent) {
                var leaveName = playerDisplayName != null ? playerDisplayName : "player";
                Rooms.<RoomResponse>ask(roomRef(currentRoomId),
                    ref -> new RoomCommand.LeaveRoom(playerId, leaveName, "disconnect", ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {});
            }

            // Unregister player from EntityRegistry — keep the entity if another
            // session still holds this player present.
            var entityRegistry = EntityRegistry.get();
            if (entityRegistry != null && playerId != null && !stillPresent) {
                entityRegistry.remove(playerId);
            }

            // Publish offline to Between — but only when this was the last live
            // session. Otherwise a cross-zone observer sees the account flicker
            // offline when one of its local surfaces drops while another is up.
            if (playerId != null && !stillPresent) {
                publishOffline(playerId);
            }
            // Wave 5: Disconnect from unified session
            var ss = sessionService;
            var uid = sessionUserIds.get(sessionId);
            if (ss != null && uid != null) {
                ss.deviceDisconnect(uid, sessionId);
            }

            if (sessionRef != null) {
                sessionRef.tell(new ClientSessionActor.Disconnected());
            }

            // Unregister from transport-agnostic registry
            if (connectionRegistry != null) {
                connectionRegistry.unregister(sessionId);
            }

            log.info("WebSocket disconnected: {} (code={}, reason={})",
                sessionId, ctx.status(), ctx.reason());
        });

        ws.onError(ctx -> {
            var sessionId = (String) ctx.attribute("sessionId");
            log.error("WebSocket error for {}", sessionId, ctx.error());
        });
    }

    private void handleC2S(String sessionId, C2SMessage msg) {
        var sessionRef = sessions.get(sessionId);
        if (sessionRef == null) {
            log.warn("No session for {}", sessionId);
            return;
        }

        // If this session has an active remote zone session, proxy commands there
        var remoteSession = remoteZoneSessions.get(sessionId);
        if (remoteSession != null && remoteSession.isActive()) {
            handleRemoteC2S(sessionId, sessionRef, remoteSession, msg);
            return;
        }

        var currentRoomId = sessionCurrentRoom.get(sessionId);
        var playerId = sessionPlayerIds.get(sessionId);
        var room = roomRef(currentRoomId);
        var locale = getSessionLocale(sessionId);

        switch (msg) {
            case C2SMessage.Look look ->
                askRoomWithInventory(room, ref -> new RoomCommand.LookRoom(playerId, locale, ref),
                    sessionRef, playerId, look.id());

            case C2SMessage.Say say -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "speak", say.id(), locale)) return;
                var pname = sessionPlayerNames.getOrDefault(sessionId, "player");
                var parsed = InputParser.parse(say.text());
                switch (parsed) {
                    case InputParser.ParsedInput.Emote emote ->
                        askRoom(room, ref -> new RoomCommand.EmoteInRoom(
                                playerId, pname, emote.text(), locale, ref),
                            sessionRef, say.id());
                    case InputParser.ParsedInput.Tell tell ->
                        handleWebSocketTell(playerId, pname, tell.target(), tell.text(),
                            room, sessionRef, say.id(), locale);
                    case InputParser.ParsedInput.Whisper whisper -> {
                        // WhisperInRoom expects an entity ID, not a name. Resolve via
                        // EntityRegistry first (RoomActor double-checks same-room).
                        var wReg = EntityRegistry.get();
                        var wTargetId = wReg != null
                            ? wReg.findByName(whisper.target())
                            : Optional.<String>empty();
                        if (wTargetId.isEmpty()) {
                            sessionRef.tell(new ClientSessionActor.SendMessage(
                                new S2CMessage.Error(0L, "not_found",
                                    "Nobody called '" + whisper.target() + "' is here.",
                                    say.id())));
                        } else {
                            askRoom(room, ref -> new RoomCommand.WhisperInRoom(
                                    playerId, pname, wTargetId.get(), whisper.text(), locale, ref),
                                sessionRef, say.id());
                        }
                    }
                    case InputParser.ParsedInput.Say sayInput ->
                        askRoom(room, ref -> new RoomCommand.SayInRoom(
                                playerId, pname, sayInput.text(), locale, say.attachments(), ref),
                            sessionRef, say.id());
                }
            }

            case C2SMessage.HintSelect hs ->
                handleHintSelect(sessionId, sessionRef, playerId, room, currentRoomId, hs, locale);

            case C2SMessage.Take take -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "take", take.id(), locale)) return;
                handleTake(sessionRef, playerId, currentRoomId, room, take, locale);
            }

            case C2SMessage.Drop drop -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "drop", drop.id(), locale)) return;
                handleDrop(sessionRef, playerId, currentRoomId, room, drop, locale);
            }

            case C2SMessage.Retire retire -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "drop", retire.id(), locale)) return;
                handleRetire(sessionRef, playerId, currentRoomId, room, retire, locale);
            }

            case C2SMessage.Use use -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "use", use.id(), locale)) return;
                // Check inventory for scripted item first — lets players invoke library_card,
                // echo_stone, etc. from their home zone.
                if (tryInvokeCarriedScript(sessionRef, playerId, use.objectName(),
                        use.target(), use.id(), currentRoomId, locale)) {
                    return;
                }
                askRoom(room, ref -> new RoomCommand.UseObject(playerId, use.objectName(), use.target(), locale, ref),
                    sessionRef, use.id());
            }

            case C2SMessage.Examine ex ->
                handleExamine(sessionId, sessionRef, playerId, currentRoomId, ex, locale);

            case C2SMessage.Rename rn ->
                handleRename(sessionId, sessionRef, playerId, currentRoomId, rn);

            case C2SMessage.Go go ->
                handleGo(sessionId, sessionRef, playerId, room, currentRoomId, go, locale);

            case C2SMessage.Reconnect reconnect ->
                sessionRef.tell(new ClientSessionActor.ReplayFrom(reconnect.lastSeenSeq()));

            case C2SMessage.Command cmd ->
                handleCommand(sessionId, sessionRef, playerId, room, currentRoomId, cmd, locale);

            case C2SMessage.SetPreference pref ->
                handleSetPreference(sessionId, playerId, pref);

            case C2SMessage.MapRequest mapReq ->
                handleMapRequest(sessionId, sessionRef, playerId, currentRoomId, mapReq);

            case C2SMessage.VoiceAudio voiceAudio ->
                handleVoiceAudio(sessionId, sessionRef, playerId, room, currentRoomId, voiceAudio, locale);

            case C2SMessage.Emote emote -> {
                if (!checkWard(sessionRef, currentRoomId, playerId, "speak", emote.id(), locale)) return;
                var pname = sessionPlayerNames.get(sessionId);
                askRoom(room, ref -> new RoomCommand.EmoteInRoom(
                        playerId, pname != null ? pname : "player", emote.text(), locale, ref),
                    sessionRef, emote.id());
            }
        }
    }

    /**
     * Handle C2S messages when the session is proxied to a remote zone.
     * Translates C2S messages to JSON commands and forwards them over NATS.
     * Special command "travel home" ends the remote session.
     */
    private void handleRemoteC2S(String sessionId,
                                  ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                  RemoteZoneSession remoteSession,
                                  C2SMessage msg) {
        try {
            var mapper = Json.mapper();
            switch (msg) {
                case C2SMessage.Say say -> {
                    // Check for "travel home" / "go home" to end the remote session
                    var text = say.text().toLowerCase().trim();
                    if (text.equals("travel home") || text.equals("go home")
                            || text.equals("return") || text.equals("return home")) {
                        endRemoteSession(sessionId);
                        return;
                    }
                    var payload = mapper.createObjectNode();
                    payload.put("text", say.text());
                    remoteSession.sendCommand("say", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Go go -> {
                    var payload = mapper.createObjectNode();
                    payload.put("direction", go.direction());
                    remoteSession.sendCommand("go", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Look look ->
                    remoteSession.sendCommand("look", "{}");
                case C2SMessage.Take take -> {
                    var payload = mapper.createObjectNode();
                    payload.put("objectName", take.objectName());
                    remoteSession.sendCommand("take", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Drop drop -> {
                    var payload = mapper.createObjectNode();
                    payload.put("objectName", drop.objectName());
                    remoteSession.sendCommand("drop", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Use use -> {
                    var payload = mapper.createObjectNode();
                    payload.put("objectName", use.objectName());
                    if (use.target() != null) payload.put("target", use.target());
                    remoteSession.sendCommand("use", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Emote emote -> {
                    var payload = mapper.createObjectNode();
                    payload.put("text", emote.text());
                    remoteSession.sendCommand("emote", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Command cmd -> {
                    // Handle "travel home" as command too
                    if ("travel".equalsIgnoreCase(cmd.command())
                            && cmd.args() != null && !cmd.args().isEmpty()
                            && "home".equalsIgnoreCase(cmd.args().get(0))) {
                        endRemoteSession(sessionId);
                        return;
                    }
                    // Forward other commands as "say" with the full command text
                    var args = cmd.args() != null ? cmd.args() : List.<String>of();
                    var fullText = args.isEmpty()
                        ? cmd.command()
                        : cmd.command() + " " + String.join(" ", args);
                    var payload = mapper.createObjectNode();
                    payload.put("text", fullText);
                    remoteSession.sendCommand("say", mapper.writeValueAsString(payload));
                }
                case C2SMessage.Reconnect reconnect ->
                    sessionRef.tell(new ClientSessionActor.ReplayFrom(reconnect.lastSeenSeq()));
                default -> {
                    // HintSelect, SetPreference, MapRequest, VoiceAudio — not forwarded to remote
                    log.debug("Ignoring {} in remote session mode for {}", msg.getClass().getSimpleName(), sessionId);
                }
            }
        } catch (Exception e) {
            log.error("Error forwarding C2S to remote zone for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * §7.4 — self-rename over WS. Delegates to the shared
     * {@link org.wyrdsekai.core.room.RenameService} so SSH/Telnet/WS share
     * a single validation + persistence chain. On success, updates the
     * cached player name in {@code sessionPlayerNames} so subsequent
     * renders (say-echo, who, examine) pick up the new name. Returns the
     * result as a {@code Prose} message.
     */
    private void handleRename(String sessionId,
                               ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                               String playerId,
                               String currentRoomId,
                               C2SMessage.Rename rn) {
        var currentName = sessionPlayerNames.getOrDefault(sessionId, "player");
        var result = RenameService.renameSelf(
            playerId, currentName, rn.target(), rn.newName(),
            currentRoomId, authService, ASK_TIMEOUT);
        String text;
        switch (result) {
            case RenameService.Result.Ok ok -> {
                sessionPlayerNames.put(sessionId, ok.newName());
                text = "You are now known as " + ok.newName() + ".";
            }
            case RenameService.Result.Requested rq ->
                text = "You offer the name " + rq.newName()
                    + " to " + rq.targetName() + ".";
            case RenameService.Result.Rejected r ->
                text = r.message();
        }
        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Prose(
                0L, "narrator", text, List.of(), null, "normal")));
    }

    /**
     * §2.2 — passive observation over WS. Resolves the target via
     * {@link org.wyrdsekai.core.room.ExamineLookup} and sends a single
     * Prose carrying the description text. Crucially: NO {@code ObjectUsed}
     * broadcast, NO {@code onUse} script invocation, NO room snapshot reply
     * (so clients don't re-render the room on examine).
     */
    private void handleExamine(String sessionId,
                                ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                String playerId,
                                String currentRoomId,
                                C2SMessage.Examine ex,
                                String locale) {
        var pname = sessionPlayerNames.getOrDefault(sessionId, "player");
        var catalog = ScriptMessageCatalog.forLang(locale);
        if (ex.target() == null || ex.target().isBlank()) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(
                    0L, "narrator", catalog.get("telnet.help_examine"),
                    List.of(), null, "normal")));
            return;
        }
        ExamineLookup.resolve(
                playerId, pname, ex.target(), locale,
                authService, inventoryService, currentRoomId, ASK_TIMEOUT)
            .thenAccept(result -> {
                String text = switch (result) {
                    case ExamineLookup.ExamineResult.Found f -> {
                        // formatFound now appends posture
                        // descriptor + elapsed clause as a third line when present.
                        if ((f.description() == null || f.description().isBlank())
                                && f.source() == ExamineLookup.Source.SELF) {
                            var hint = "\n(no description set. Use '@describe <text>' to set one.)";
                            yield f.posture() != null && !f.posture().isBlank()
                                ? f.name() + hint + "\n" + f.posture()
                                : f.name() + hint;
                        }
                        yield ExamineLookup.formatFound(f);
                    }
                    case ExamineLookup.ExamineResult.NotFound nf ->
                        catalog.get("err.no_such_object", nf.requested());
                    case ExamineLookup.ExamineResult.NoCurrentRoom nr ->
                        catalog.get("err.no_such_object", nr.requested());
                    case ExamineLookup.ExamineResult.Empty e ->
                        catalog.get("telnet.help_examine");
                };
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Prose(
                        0L, "narrator", text, List.of(), null, "normal")));
            });
    }

    private void handleTake(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                            String playerId, String currentRoomId,
                            ActorRef<RoomCommand> room, C2SMessage.Take take,
                            String locale) {
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.TakeObject(playerId, take.objectName(), locale, ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.ObjectTakenOk taken) {
                // Persist to inventory database
                var obj = taken.takenObject();
                inventoryService.addItem(playerId, obj.id(), obj.name(),
                    obj.description(), obj.takeable(), currentRoomId);
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, take.id(), loadInventory(playerId)));
            } else {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, take.id()));
            }
        });
    }

    /**
     * Try to invoke a scripted item carried in the player's inventory.
     * Returns true if the item was found and executed (caller should skip room-use dispatch).
     * Returns false if no scripted item matches — caller falls through to room handling.
     */
    /**
     * The live {@code world.*} provider for a player acting in the home zone.
     * Home-zone use: currentZone == homeZone, so scripts see
     * {@code isTraveling()=false}. Home furnishings (Embers, Board) need
     * HomeClient-backed world.audit/grants — hand them a
     * {@link HomeOwnerItemProvider}; other scripted items only use the
     * visitor-safe defaults ({@link VisitorItemProvider} parent), so this is
     * a strict superset. Shared by the carried-item path here AND (via
     * {@link ItemProviderRegistry}) RoomActor's furnishing/coding-item
     * invocations, so an item behaves identically carried or placed.
     */
    private VisitorItemProvider buildPlayerProvider(String playerId) {
        var localZone = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "home");
        if (homeClient == null) {
            // Catalog binding (second-node 2026-07-11 #26): without it, `use template
            // catalog` in any Workshop answered "item library isn't bound".
            var visitor = new VisitorItemProvider(localZone, localZone)
                .withCatalog(STD_ITEM_LIBRARY);
            // Safe binding (#31 item 1 — the 4-surfaces class again): the
            // companion-side provider was fixed and the catalog got all four
            // surfaces, but PLAYER providers never got setSafe, so
            // world.safe.list/has answered empty on player-invoked items.
            visitor.setSafe(TheSafe.local());
            // The household's own library / model / web. Without it every content
            // surface answers "visiting foreign zone" — inside the person's own house.
            visitor.withHouseholdContent(HouseholdItemContent.get());
            // Name the person. Without this the knowledge search has no caller
            // and reaches no private shelf — the steward's own books were
            // invisible from his own hands for exactly this reason.
            visitor.withCaller(playerId);
            return visitor;
        }
        var home = new HomeOwnerItemProvider(
            localZone, localZone, playerId, homeClient, system);
        // The acting person, for every caller-aware surface (see StudyReach).
        home.withCaller(playerId);
        // Template catalog for world.catalog.* (second-node 2026-07-11 #26).
        home.withCatalog(STD_ITEM_LIBRARY);
        // world.safe.list/has for player-invoked items (#31 item 1).
        home.setSafe(TheSafe.local());
        // world.library.search / world.llm.* / world.web.* — the CONTENT surfaces.
        // HomeOwnerItemProvider extends the FOREIGN-zone provider and only ever added
        // the household ADMIN surfaces, so a person standing in their own house read
        // "Knowledge search unavailable — visiting foreign zone" from every item they
        // used. Only the companion's own provider had the real library and the real
        // model. See HouseholdItemContent.
        home.withHouseholdContent(HouseholdItemContent.get());
        // Supplier wiring for Ledger/Manifest/Trunk/Shelf/Lantern/....
        var fed = federationService;
        if (fed != null) {
            home.withAgreements(() -> federationAgreementsView(fed, localZone));
        }
        if (inventoryService != null) {
            home.withInventory(() -> inventoryOwnedView(inventoryService, playerId));
        }
        if (bondRitual != null) {
            home.withBonds(() -> bondsView(bondRitual, playerId));
        }
        home.withPresence(() -> presenceInHomeView(playerId));
        // Relay governance (Warden furnishing) — the governor is per-zone and
        // caller-agnostic, so the steward's route gets the same one the
        // companion uses; scope is resolved per-action from the acting DID.
        home.withRelayGovernor(RelayGovernors.forAgent(playerId));
        if (studyService != null) {
            home.withStudy(studyService);
        }
        // Study control-panel services (world.household / invite / ward /
        // audit.security / safe.snapshots / nodes). The provider routes the
        // acting player's id as caller so steward-only checks apply.
        home.withAuth(authService)
            .withWards(wardService);
        // hermod data-domain grants (Ward Room grant stone): flat files
        // under <dataDir>/hermod-grants/, verified against this node's
        // own authority key. Read open; revoke steward-gated in-provider.
        // Failure to wire degrades to "not available here", never fatal.
        try {
            var hermodDataDir = Path.of(System.getenv().getOrDefault(
                "WYRDSEKAI_DATA_DIR", System.getProperty("user.home") + "/.wyrdsekai"));
            home.withHermodGrants(new HermodGrantStore(
                hermodDataDir.resolve("hermod-grants"),
                NodeIdentity.loadOrGenerate(hermodDataDir.resolve("node-identity.json"))
                    .publicKeyBytes(),
                Clock.systemUTC()));
        } catch (Exception hermodGrantErr) {
            log.warn("hermod grant store not wired ({}): grant stone reads empty",
                hermodGrantErr.getMessage());
        }
        var parental = ParentalControlService.get();
        if (parental != null) home.withParental(parental);
        var maintenance = MaintenanceService.get();
        if (maintenance != null) home.withMaintenance(maintenance);
        var invites = inviteService;
        if (invites != null) home.withInvites(invites);
        var securityLog = stewardAuditLog;
        if (securityLog != null) home.withSecurityAudit(securityLog);
        var backups = backupOrchestrator;
        if (backups != null) home.withBackups(backups);
        var between = betweenActor;
        if (between != null) home.withNodes(() -> connectedNodesView(between));
        return home;
    }

    /**
     * Enrolled-node snapshot for {@code world.nodes.list()} — asks the
     * Between actor for its live mesh topology and projects each peer's
     * connection state. First row is this node itself. Empty on timeout or
     * when the mesh is down (single-node installs never wire this supplier).
     */
    private List<Map<String, Object>> connectedNodesView(
            ActorRef<BetweenActor.Command> between) {
        try {
            var snapshot = AskPattern
                .<BetweenActor.Command, BetweenActor.TopologySnapshot>ask(
                    between,
                    BetweenActor.GetTopology::new,
                    Duration.ofSeconds(2),
                    system.scheduler())
                .toCompletableFuture().get(3, TimeUnit.SECONDS);
            var out = new ArrayList<Map<String, Object>>();
            var self = new LinkedHashMap<String, Object>();
            self.put("nodeId", snapshot.localNodeId());
            self.put("connected", true);
            self.put("self", true);
            out.add(self);
            if (snapshot.connections() != null) {
                for (var c : snapshot.connections()) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("nodeId", c.remoteNodeId());
                    m.put("connected", c.connected());
                    m.put("latencyMs", c.latencyMs());
                    m.put("connectionAgeMs", c.connectionAgeMs());
                    if (c.lastHeartbeat() != null) {
                        m.put("lastHeartbeat", c.lastHeartbeat().toString());
                    }
                    if (c.appVersion() != null) m.put("appVersion", c.appVersion());
                    out.add(m);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("connectedNodesView: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean tryInvokeCarriedScript(
            ActorRef<ClientSessionActor.SessionMessage> sessionRef,
            String playerId, String objectName, String target, String requestId,
            String currentRoomId, String locale) {
        if (playerId == null) return false;
        var resolved = CarriedItemUse.resolve(
            inventoryService, playerId, objectName, target).orElse(null);
        if (resolved == null) return false;
        var item = resolved.item();
        try {
            var provider = buildPlayerProvider(playerId);
            CarriedItemUse.attachRoomVoice(provider, currentRoomId, playerId);
            CarriedItemUse.attachLocale(provider, locale);
            var params = CarriedItemUse.params(playerId, resolved.target(), locale);
            var itemCaps = CarriedItemUse.capabilitiesFor(item.objectId());
            var result = itemScriptExecutor.execute(
                item.objectId(), resolved.source(), params, provider, itemCaps);
            var text = ItemScriptResponse.extractText(
                result, item.objectName());
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(
                    0L, "narrator", text, List.of(), null, "normal")));
            log.info("Player {} invoked carried scripted item '{}' in home zone",
                playerId, item.objectName());
        } catch (Exception e) {
            log.error("Failed to execute carried item {} for {}: {}",
                item.objectId(), playerId, e.getMessage());
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Error(
                    0L, "script_error", "Item malfunctioned: " + e.getMessage(), requestId)));
        }
        return true;
    }

    /**
     * Take an object out of the world — the counterpart {@code drop} never was.
     *
     * <p>Same behaviour as the shell and telnet: unregister the scripted item and move
     * its script to {@code items/retired/} so a restart does not bring it back, then clear
     * the inventory row and the room object. Soft, because these are things the companion
     * made and a typo must not erase one.
     */
    private void handleRetire(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                              String playerId, String currentRoomId,
                              ActorRef<RoomCommand> room, C2SMessage.Retire retire,
                              String locale) {
        var name = retire.objectName() == null ? "" : retire.objectName().trim();
        if (name.isEmpty()) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0, "narrator", "Retire what?",
                    List.of(), null, "normal", locale)));
            return;
        }
        var outcome = ItemRetirement.retireAnywhere(name,
            n -> {
                try {
                    if (room == null) return false;
                    var resp = Rooms.<RoomResponse>ask(room,
                        ref -> new RoomCommand.TakeObject(playerId, n, locale, ref),
                        ASK_TIMEOUT).toCompletableFuture()
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
                    return resp instanceof RoomResponse.ObjectTakenOk;
                } catch (Exception e) {
                    return false;
                }
            },
            n -> {
                var carried = inventoryService.findTakeableByName(playerId, n);
                carried.ifPresent(inv -> inventoryService.removeItem(playerId, inv.objectId()));
                return carried.isPresent();
            });
        // Narration, not a rejection — see the telnet copy.
        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Prose(0, "narrator", outcome.describe(name),
                List.of(), null, "normal", locale)));
    }

    private void handleDrop(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                            String playerId, String currentRoomId,
                            ActorRef<RoomCommand> room, C2SMessage.Drop drop,
                            String locale) {
        // Validate player has the item
        var item = inventoryService.findTakeableByName(playerId, drop.objectName());
        if (item.isEmpty()) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("not_in_inventory", catalog.get("ui.dont_have")),
                drop.id()));
            return;
        }
        var inv = item.get();
        // Remove from inventory first
        inventoryService.removeItem(playerId, inv.objectId());
        // Add to room with full metadata
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.DropObject(playerId, inv.objectId(), inv.objectName(),
                inv.description(), inv.takeable(), locale, ref),
            ASK_TIMEOUT
        ).thenAccept(resp ->
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                resp, drop.id(), loadInventory(playerId))));
    }

    /**
     * Handle incoming voice audio: decode base64, transcribe via VoiceConversationManager,
     * then route the transcribed text as a Say event with voice=true.
     */
    private void handleVoiceAudio(String sessionId,
                                   ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                   String playerId,
                                   ActorRef<RoomCommand> room,
                                   String currentRoomId,
                                   C2SMessage.VoiceAudio voiceAudio,
                                   String locale) {
        var stt = SpeechToTextService.get();
        if (stt == null || !stt.isAvailable()) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Error(0, "voice_unavailable",
                    "Speech-to-text is not available on this server", voiceAudio.id())));
            return;
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(voiceAudio.audioBase64());
        } catch (IllegalArgumentException e) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Error(0, "invalid_audio",
                    "Invalid base64 audio data", voiceAudio.id())));
            return;
        }

        stt.transcribe(audioBytes, voiceAudio.format())
            .thenAccept(result -> {
                if (result.text() != null && !result.text().isBlank()) {
                    // Route as a Say event with voice=true.
                    // The display name must be looked up, not hardcoded: passing
                    // the literal "player" made every phone utterance render as
                    // "player: hi" instead of the speaker's name.
                    var vname = sessionPlayerNames.getOrDefault(sessionId, "player");
                    askRoom(room, ref -> new RoomCommand.SayInRoom(
                            playerId, vname, result.text(), locale, null, ref),
                        sessionRef, voiceAudio.id());
                }
            })
            .exceptionally(ex -> {
                log.warn("Voice transcription failed for {}: {}", playerId, ex.getMessage());
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Error(0, "stt_error",
                        "Transcription failed: " + ex.getMessage(), voiceAudio.id())));
                return null;
            });
    }

    private void handleCommand(String sessionId,
                               ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                               String playerId,
                               ActorRef<RoomCommand> room,
                               String currentRoomId,
                               C2SMessage.Command cmd,
                               String locale) {
        var command = cmd.command().toLowerCase();

        // Check for namespaced zone command (§83.7): "codezaiku.approve" → namespace="codezaiku", action="approve"
        var dotIndex = command.indexOf('.');
        if (dotIndex > 0 && dotIndex < command.length() - 1) {
            var namespace = command.substring(0, dotIndex);
            var action = command.substring(dotIndex + 1);
            var handler = zoneHandlers.get(namespace);
            if (handler != null) {
                handler.handle(playerId, action,
                    cmd.args() != null ? cmd.args() : List.of(),
                    cmd.payload() != null ? cmd.payload() : Map.of(),
                    msg -> sessionRef.tell(new ClientSessionActor.SendMessage(msg)));
                return;
            }
            log.warn("No zone handler for namespace '{}' (command: {})", namespace, command);
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0, "system",
                    "Unknown zone command: " + cmd.command(), List.of(), null, "normal", locale)));
            return;
        }

        // Core commands
        switch (command) {
            case "home", "return", "study", "office" -> {
                handleGoHome(sessionId, sessionRef, playerId, room, currentRoomId, cmd.id(), locale);
                return;
            }
            case "knock" -> {
                handleKnock(sessionRef, playerId, cmd, locale);
                return;
            }
            case "approve", "deny" -> {
                handleGrantRequestResponse(sessionRef, playerId, command, cmd, locale);
                return;
            }
            case "seal", "unseal" -> {
                handleSeal(sessionRef, playerId, command, cmd, locale);
                return;
            }
            case "eject" -> {
                handleEject(sessionRef, playerId, cmd, locale);
                return;
            }
            case "inventory", "i" -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var items = inventoryService.listTakeableItems(playerId);
                String text;
                if (items.isEmpty()) {
                    text = catalog.get("ui.inventory_empty");
                } else {
                    var names = items.stream()
                        .map(InventoryService.InventoryItem::objectName)
                        .toList();
                    text = catalog.get("ui.inventory_carrying", String.join(", ", names));
                }
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Prose(0, "system", text, List.of(), null, "normal", locale)));
            }
            case "invite", "relay" -> {
                handleRelayCommand(sessionId, sessionRef, command, cmd, locale);
                return;
            }
            case "passwd", "password" -> {
                handlePasswd(sessionId, sessionRef, cmd, locale);
                return;
            }
            case "quit", "exit" -> {
                // Detach THIS channel only — the account stays present on any other
                // surfaces. A WS client (CLI/web/phone) can't see the registry, so
                // the server computes the count and pushes the SAME hint SSH/telnet
                // render inline, then closes the channel. Last channel → goodbye.
                var quitHint = SessionCommands.detachHint(connectionRegistry, playerId, sessionId, locale);
                sendProse(sessionRef, "system", quitHint != null ? quitHint
                    : ScriptMessageCatalog.forLang(locale).get("telnet.goodbye"));
                var quitCtx = wsContexts.get(sessionId);
                if (quitCtx != null) {
                    try { quitCtx.closeSession(4000, "detach"); } catch (RuntimeException ignored) {}
                }
                return;
            }
            case "logout", "quitall" -> {
                // End the whole presence: drop the account's other channels, then
                // close this one. The last onClose fires the room departure once.
                SessionCommands.logoutOthers(connectionRegistry, playerId, sessionId);
                sendProse(sessionRef, "system",
                    ScriptMessageCatalog.forLang(locale).get("session.logout"));
                var ctx = wsContexts.get(sessionId);
                if (ctx != null) {
                    try { ctx.closeSession(4000, "logout"); } catch (RuntimeException ignored) {}
                }
                return;
            }
            case "sessions" -> {
                var args = cmd.args() != null ? cmd.args() : List.<String>of();
                var text = SessionCommands.isKill(args)
                    ? SessionCommands.killByIndex(connectionRegistry, playerId, locale, args)
                    : SessionCommands.render(connectionRegistry, playerId, sessionId, locale);
                sendProse(sessionRef, "system", text);
                return;
            }
            case "key" -> {
                // Manage this account's own SSH keys (list/add/remove) from the
                // web/CLI surface — parity with the SSH and telnet paths. Scoped to
                // playerId (== the authenticated user's id, set at login line ~768),
                // so a caller can only ever touch their OWN keys.
                var args = cmd.args() != null ? cmd.args() : List.<String>of();
                sendProse(sessionRef, "system",
                    SessionCommands.key(authService, playerId, args));
                return;
            }
            case "help" -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var helpText = catalog.get("ui.help_commands");
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Prose(0, "system", helpText, List.of(), null, "normal", locale)));
            }
            case "socials" -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var socialsList = catalog.get("ui.socials_list");
                sessionRef.tell(new ClientSessionActor.SendMessage(
                    new S2CMessage.Prose(0, "system", socialsList, List.of(), null, "normal", locale)));
            }
            case "report" -> {
                // W5 (audit 2026-07-11): ClientSessionActor.Report existed with no
                // path to reach it. `report <entity> [reason…]` files a moderation
                // report from the player's current room and acks via the session.
                var args = cmd.args() != null ? cmd.args() : List.<String>of();
                if (args.isEmpty()) {
                    sendProse(sessionRef, "system",
                        "Report whom? Usage: report <entity> [reason]");
                    return;
                }
                var target = args.get(0);
                var reason = args.size() > 1
                    ? String.join(" ", args.subList(1, args.size()))
                    : "(no reason given)";
                sessionRef.tell(new ClientSessionActor.Report(target, reason, currentRoomId));
                return;
            }
            case "who" -> {
                // cross-surface parity: `who` lists the
                // people currently present. WS previously had no handler (fell
                // through to speech); telnet/SSH have long supported it.
                var self = sessionPlayerNames.getOrDefault(sessionId, "you");
                String text;
                if (connectionRegistry != null) {
                    var names = connectionRegistry.all().stream()
                        .map(ClientConnection::playerName)
                        .filter(n -> n != null && !n.isBlank())
                        .distinct()
                        .toList();
                    text = names.isEmpty()
                        ? "You are " + self + ", here alone."
                        : "Here now: " + String.join(", ", names) + ". You are " + self + ".";
                } else {
                    text = "You are " + self + ", here.";
                }
                sendProse(sessionRef, "system", text);
            }
            case "describe", "@describe" -> {
                // cross-surface parity for `@describe me=<text>`.
                // Mirrors SSH/telnet handleDescribe: persist via AuthService and push
                // UpdateEntityDescription so `examine me` reflects it in-session.
                var args = cmd.args() != null ? cmd.args() : List.<String>of();
                var joined = String.join(" ", args).trim();
                String target, descText;
                int eq = joined.indexOf('=');
                if (eq >= 0) {
                    target = joined.substring(0, eq).trim();
                    descText = joined.substring(eq + 1).trim();
                } else {
                    int sp = joined.indexOf(' ');
                    target = sp >= 0 ? joined.substring(0, sp).trim() : joined;
                    descText = sp >= 0 ? joined.substring(sp + 1).trim() : "";
                }
                if (!"me".equalsIgnoreCase(target)) {
                    sendProse(sessionRef, "system",
                        "You can only describe yourself here — try `@describe me=<text>`.");
                } else if (playerId == null || playerId.startsWith("anon-")) {
                    sendProse(sessionRef, "system", "You must be logged in to set a description.");
                } else {
                    authService.updateDescription(playerId, descText);
                    if (room != null) {
                        final String fDesc = descText;
                        // Sync the live room snapshot so OTHER users' `examine <me>`
                        // reflects the new text this session (own examine reads
                        // AuthService directly). Best-effort — the entity may not be
                        // tracked in this room's entity map.
                        Rooms.<RoomResponse>ask(room,
                            ref -> new RoomCommand.UpdateEntityDescription(playerId, fDesc, ref),
                            ASK_TIMEOUT).exceptionally(ex -> null);
                        // Re-render the room (emit a fresh room_state), matching the
                        // telnet/SSH describe flow so clients see the update land.
                        askRoom(room, ref -> new RoomCommand.LookRoom(playerId, locale, ref),
                            sessionRef, cmd.id());
                    } else {
                        sendProse(sessionRef, "system", "Your description is set.");
                    }
                }
            }
            default -> {
                var args = cmd.args() != null ? cmd.args() : List.<String>of();
                var fullText = args.isEmpty()
                    ? cmd.command()
                    : cmd.command() + " " + String.join(" ", args);

                // FULL-PARITY FALLBACK (2026-07-24): a relay/phone terminal
                // sends any verb it lacks a typed C2S for as this generic
                // Command. Re-parse it through the SAME CommandParser the
                // SSH/CLI client uses and re-dispatch the resulting typed
                // message, so map/where/nearby/rooms/path/exits/tell/whisper/…
                // behave identically on the phone as over ssh — without a
                // per-client verb table to keep in sync. Only a DIFFERENT typed
                // message is re-dispatched (mapper never returns a Command, so
                // no loop); genuine speech ("hello") parses to Say and is
                // re-dispatched through the same Say handler SSH hits.
                var reMsg = ClientCommandMapper.toWorldC2S(
                    CommandParser.parse(fullText), cmd.id(), currentRoomId);
                if (reMsg != null && !(reMsg instanceof C2SMessage.Command)) {
                    handleC2S(sessionId, reMsg);
                    return;
                }

                // No world mapping (unknown verb / client-local) → route to the
                // room as speech, as before — room scripts parse verbs too.
                if (room != null) {
                    // Same lookup as every other Say path. This fallback catches
                    // ordinary conversation — "hi" has no verb mapping, so it
                    // lands here — and the hardcoded "player" meant the most
                    // common thing a person types rendered as "player: hi".
                    var cname = sessionPlayerNames.getOrDefault(sessionId, "player");
                    askRoom(room, ref -> new RoomCommand.SayInRoom(
                            playerId, cname, fullText, locale, null, ref),
                        sessionRef, cmd.id());
                } else {
                    log.info("Command from session (no room): /{} {}", cmd.command(), cmd.args());
                }
            }
        }
    }

    private void handleSetPreference(String sessionId, String playerId,
                                      C2SMessage.SetPreference pref) {
        switch (pref.key()) {
            case "locale" -> {
                sessionLocales.put(sessionId, pref.value());
                // Propagate locale to session actor for Prose.lang tagging
                var sessionRef = sessions.get(sessionId);
                if (sessionRef != null) {
                    sessionRef.tell(new ClientSessionActor.SetLocale(pref.value()));
                    for (var cRef : ZoneGuardian.allCompanionRefs()) {
                        cRef.tell(new CompanionActor.SetLocale(pref.value()));
                    }
                }
                log.info("Session {} set locale to {}", sessionId, pref.value());
            }
            default -> log.info("Unknown preference from {}: {}={}", sessionId, pref.key(), pref.value());
        }
    }

    /** Get the locale for a session (default: "en"). */
    public String getSessionLocale(String sessionId) {
        return sessionLocales.getOrDefault(sessionId, "en");
    }

    /**
     * Check ward permission. Returns true if allowed, false if denied (sends rejection to session).
     */
    private boolean checkWard(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                              String roomId, String principal, String permission,
                              String requestId, String locale) {
        if (wardService.isAllowed(roomId, principal, permission)) {
            return true;
        }
        log.info("Ward denied: {} cannot {} in {}", principal, permission, roomId);
        var catalog = ScriptMessageCatalog.forLang(locale);
        sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
            new RoomResponse.Rejected("ward_denied",
                catalog.get("ui.no_permission", permission)),
            requestId));
        return false;
    }

    private void handleGo(String sessionId,
                           ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                           String playerId,
                           ActorRef<RoomCommand> currentRoom,
                           String currentRoomId,
                           C2SMessage.Go go,
                           String locale) {
        Rooms.<RoomResponse>ask(currentRoom, 
            ref -> new RoomCommand.LookRoom(playerId, locale, ref),
            ASK_TIMEOUT
        ).thenAccept(lookResp -> {
            if (!(lookResp instanceof RoomResponse.Ok ok)) {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(lookResp, go.id()));
                return;
            }
            var direction = normalizeDirection(go.direction());
            // Exact direction, then fuzzy destination match (see Exit.resolve — second-node 2026-07-09).
            var exit = Exit.resolve(ok.snapshot().exits(), direction);
            if (exit.isEmpty()) {
                var catalog = ScriptMessageCatalog.forLang(locale);
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    new RoomResponse.Rejected("no_exit", catalog.get("ui.no_exit")),
                    go.id()));
                return;
            }
            var targetRoomId = exit.get().targetRoom();
            performMove(sessionId, sessionRef, playerId,
                currentRoom, currentRoomId,
                roomRef(targetRoomId), targetRoomId,
                direction, go.id(), locale);
        });
    }

    /** Expand MUD-style direction abbreviations (e → east) so clients may send either form. */
    private static String normalizeDirection(String dir) {
        if (dir == null) return null;
        return switch (dir.toLowerCase()) {
            case "n" -> "north";
            case "s" -> "south";
            case "e" -> "east";
            case "w" -> "west";
            case "ne" -> "northeast";
            case "nw" -> "northwest";
            case "se" -> "southeast";
            case "sw" -> "southwest";
            case "u" -> "up";
            case "d" -> "down";
            default -> dir;
        };
    }

    /**
     * Route a tell via CrossZoneTellService (handles zone prefix, local, and cross-zone routing),
     * falling back to WhisperInRoom for non-agent local targets.
     *
     * Supports:
     *   "tell wyrd hello"       — local first, then cross-zone if sender is traveling
     *   "tell alpha.wyrd hello" — explicit zone targeting
     *   "tell my wyrd hello"    — always routes to sender's home zone companion
     */
    private void handleWebSocketTell(String playerId, String playerName,
                                      String targetName, String text,
                                      ActorRef<RoomCommand> room,
                                      ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                      String messageId, String locale) {
        var localZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
        var tellService = CrossZoneTellService.get();

        if (tellService != null) {
            var result = tellService.tell(playerId, playerName, localZoneId, targetName, text, locale);
            if (result.delivered()) {
                log.info("WebSocket tell '{}' delivered (targetId={})", targetName, result.targetEntityId());
                // Echo to the sender so the tell visibly "lands" — without this the
                // only feedback is the agent's eventual async reply, which reads as
                // if nothing happened. Whisper-fallback already self-echoes via the
                // Whispered fan-out, so this is only needed on the agent-tell path.
                var catalog = ScriptMessageCatalog.forLang(locale);
                sessionRef.tell(new ClientSessionActor.SendMessage(new S2CMessage.Prose(
                    0, "system", catalog.get("ui.tell_sent", targetName, text),
                    List.of(), null, "normal", locale)));
                return;
            }
            // Tell service is available but didn't deliver — target wasn't a local agent,
            // cross-zone route unavailable, etc. Fall through to whisper. Log at DEBUG
            // because this is expected for non-agent targets (telling a human player).
            log.debug("WebSocket tell '{}' not delivered by CrossZoneTellService (msg='{}'), " +
                "falling back to room whisper", targetName,
                result.errorMessage() != null ? result.errorMessage() : "<no reason>");
        } else {
            // Tell service isn't initialised at all — bootstrap drift. This is the
            // symptom that hid the tell regression for the full Ember suite: the
            // service never got init()'d in the test bootstrap, so every "tell wyrd"
            // became a silent whisper-in-room. Surface it loudly so the next
            // occurrence is immediately visible instead of presenting as an
            // inexplicable agent timeout.
            log.warn("WebSocket tell to '{}' degrading to whisper — CrossZoneTellService not initialised. " +
                "Agent targets will NOT receive tells. Check bootstrap.", targetName);
        }

        // Fallback: deliver as whisper in room (non-agent local target)
        askRoom(room, ref -> new RoomCommand.WhisperInRoom(
                playerId, playerName, targetName, text, locale, ref),
            sessionRef, messageId);
    }

    private void handleHintSelect(String sessionId,
                                   ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                   String playerId,
                                   ActorRef<RoomCommand> room,
                                   String currentRoomId,
                                   C2SMessage.HintSelect hs,
                                   String locale) {
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.SelectHint(playerId, hs.index(), locale, ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.HintAction ha && "go".equals(ha.actionType())) {
                performMove(sessionId, sessionRef, playerId,
                    room, currentRoomId,
                    roomRef(ha.targetRoomId()), ha.targetRoomId(),
                    ha.parameter(), hs.id(), locale);
            } else {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, hs.id(), loadInventory(playerId)));
            }
        });
    }

    /**
     * Execute a room-to-room move: ward check -> LeaveRoom -> Subscribe+Enter+Look (via session actor).
     */
    /**
     * {@code home} / {@code return} / {@code study} / {@code office}.
     * Teleport acting player to their Home (Study) room. Provisions the Study on demand,
     * records a HOME_ENTERED audit entry when HomeClient is wired.
     */
    private void handleGoHome(String sessionId,
                               ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                               String playerId,
                               ActorRef<RoomCommand> currentRoom,
                               String currentRoomId,
                               String requestId,
                               String locale) {
        if (playerId == null || playerId.startsWith("anon-")) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0, "system",
                    "No Home yet — anonymous visitors don't have a Study.",
                    List.of(), null, "normal", locale)));
            return;
        }
        var studyRoomId = StudyProvisioner.studyRoomId(playerId);
        if (studyRoomId.equals(currentRoomId)) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0, "system",
                    "You are already home.", List.of(), null, "normal", locale)));
            return;
        }
        // Ensure Study exists (idempotent).
        try {
            @SuppressWarnings("unchecked")
            var zg = (ActorSystem<
                ZoneGuardian.Command>) (Object) system;
            var pname = sessionPlayerNames.getOrDefault(sessionId, "player");
            zg.tell(new ZoneGuardian.ProvisionStudy(playerId, pname, false));
        } catch (Exception e) {
            log.warn("ProvisionStudy failed for {}: {}", playerId, e.getMessage());
        }
        performMove(sessionId, sessionRef, playerId,
            currentRoom, currentRoomId,
            roomRef(studyRoomId), studyRoomId,
            "home", requestId, locale);
        recordHomeEntered(playerId);
    }

    /**
     * seed the Home furnishings (Embers, Board, ...) into the
     * owner's inventory on first login. Idempotent via InventoryService upsert.
     */
    private void seedHomeFurnishings(String playerId, String homeRoomId, boolean isSteward) {
        for (var item : StudyFurnishingKit.defaultsFor(isSteward)) {
            try {
                inventoryService.addItem(
                    playerId, item.id(), item.name(), item.description(),
                    /* takeable = */ false,  // furnishings live in the Home
                    homeRoomId,
                    item.script(),
                    item.id());
            } catch (Exception e) {
                log.warn("Failed to seed furnishing {} for {}: {}",
                    item.id(), playerId, e.getMessage());
            }
        }
    }

    /**
     * {@code knock <who> [reason]} — create a grant-request
     * for {@code use} on {@code home://<who>/home-room}. When approved, the
     * ward check passes automatically via the mirrored grant.
     */
    private void handleKnock(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                              String playerId,
                              C2SMessage.Command cmd,
                              String locale) {
        if (homeClient == null) {
            sendProse(sessionRef, "system", "Knocking is not available here.");
            return;
        }
        if (playerId == null || playerId.startsWith("anon-")) {
            sendProse(sessionRef, "system", "You must log in before you can knock.");
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        if (args.isEmpty()) {
            sendProse(sessionRef, "system", "Usage: knock <did-or-name> [reason]");
            return;
        }
        var target = args.get(0);
        var reason = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : null;

        // Route through HomeProxy so cross-zone knocks can be handled by the
        // same command. Falls back to direct HomeClient when
        // no proxy installed — identical behaviour in single-zone setups.
        var proxy = HomeProxy.Holder.get();
        if (proxy == null) {
            var zone = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "home");
            proxy = new HomeProxy.Local(homeClient, zone);
        }
        var result = proxy.knock(playerId, target, reason);
        if (result.ok()) {
            var where = result.remote() ? " (zone " + result.homeZone() + ")" : "";
            sendProse(sessionRef, "narrator",
                "You knock at " + target + "'s door" + where
                + ". A request has been sent (id " + result.requestId() + ").");
        } else {
            sendProse(sessionRef, "system",
                "Knock failed: " + (result.note() != null ? result.note() : "unknown"));
        }
    }

    /**
     * {@code approve <id> [note]} / {@code deny <id> [note]} —
     * owner responds to a pending grant-request. On approve a Grant is minted
     * immediately and future ward checks pass.
     */
    private void handleGrantRequestResponse(
            ActorRef<ClientSessionActor.SessionMessage> sessionRef,
            String playerId,
            String verb,
            C2SMessage.Command cmd,
            String locale) {
        if (homeClient == null) {
            sendProse(sessionRef, "system", "Grant requests are not available here.");
            return;
        }
        if (playerId == null || playerId.startsWith("anon-")) {
            sendProse(sessionRef, "system", "You must log in to " + verb + " requests.");
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        if (args.isEmpty()) {
            sendProse(sessionRef, "system", "Usage: " + verb + " <request-id> [note]");
            return;
        }
        var id = args.get(0);
        var note = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : null;
        try {
            var result = switch (verb) {
                case "approve" -> homeClient.approveRequest(id, playerId, null, note);
                case "deny" -> homeClient.denyRequest(id, playerId, note);
                default -> throw new IllegalArgumentException("unknown verb: " + verb);
            };
            // §10 close-the-loop: approving a home-room use-grant must also
            // write the ward row so WardService.isAllowed sees the permission.
            // (The reverse direction — ward writes → grants — is handled by
            // WardGrantSync.)
            if ("approve".equals(verb)
                    && result.issuedGrantId() != null
                    && wardService != null
                    && result.resource() != null
                    && "home-room".equals(result.resource().type())) {
                var studyRoom = StudyProvisioner.studyRoomId(
                    result.resource().owner());
                wardService.grantSilent(studyRoom, result.requester(), "enter", playerId);
            }
            sendProse(sessionRef, "narrator",
                "Request " + id + " " + result.status() + "."
                + (result.issuedGrantId() != null
                    ? "  Grant minted: " + result.issuedGrantId() : ""));
        } catch (Exception e) {
            sendProse(sessionRef, "system", verb + " failed: " + e.getMessage());
        }
    }

    /**
     * §108: {@code seal [reason]} / {@code unseal} — block or admit new
     * knocks against the caller's own Home. Self-requests always pass.
     */
    private void handleSeal(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                             String playerId, String verb,
                             C2SMessage.Command cmd,
                             String locale) {
        if (homeClient == null) {
            sendProse(sessionRef, "system", "Sealing is not available here.");
            return;
        }
        if (playerId == null || playerId.startsWith("anon-")) {
            sendProse(sessionRef, "system", "You must log in to " + verb + ".");
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        try {
            if ("seal".equals(verb)) {
                var reason = args.isEmpty() ? null : String.join(" ", args);
                homeClient.seal(playerId, reason);
                sendProse(sessionRef, "narrator", "Your Home is sealed."
                    + (reason != null ? "  (" + reason + ")" : ""));
            } else {
                homeClient.unseal(playerId);
                sendProse(sessionRef, "narrator", "Your Home is open again.");
            }
        } catch (Exception e) {
            sendProse(sessionRef, "system", verb + " failed: " + e.getMessage());
        }
    }

    /**
     * §108: {@code eject <did>} — revoke any active home-room use-grant the
     * subject holds on the caller's Home, silent-revoke the ward row, and
     * audit the eject.
     */
    private void handleEject(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                              String playerId,
                              C2SMessage.Command cmd,
                              String locale) {
        if (homeClient == null) {
            sendProse(sessionRef, "system", "Eject not available here.");
            return;
        }
        if (playerId == null || playerId.startsWith("anon-")) {
            sendProse(sessionRef, "system", "You must log in to eject.");
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        if (args.isEmpty()) {
            sendProse(sessionRef, "system", "Usage: eject <did>");
            return;
        }
        var target = args.get(0);
        try {
            var resource = ResourceUri.of(
                playerId, ResourceTypeRegistry.HOME_ROOM);
            var revoked = homeClient.revokeByKey(
                playerId, target, resource,
                Capability.use);
            // Silent-revoke the ward row so WardService.isAllowed stops accepting.
            if (wardService != null) {
                var studyRoom = StudyProvisioner.studyRoomId(playerId);
                wardService.revokeSilent(studyRoom, target, "enter");
            }
            homeClient.appendAudit(AuditEntry.now(
                playerId, playerId,
                AuditEntry.Verb.HOME_EJECT,
                "home://" + playerId + "/home-room",
                AuditEntry.Outcome.ok,
                Map.of("subject", target, "revokedGrant", revoked),
                null));
            sendProse(sessionRef, "narrator", target + " is no longer welcome."
                + (revoked ? "" : "  (no active grant found)"));
        } catch (Exception e) {
            sendProse(sessionRef, "system", "Eject failed: " + e.getMessage());
        }
    }

    /** Remote-event arrival prose with placeholder handling — login/spawn reads
     *  "X arrives." not "X arrives from somewhere." Mirrors ClientSessionActor. */
    private static String movementArrivalText(String name, String dir) {
        return isPlaceholderMovementDir(dir)
            ? name + " arrives."
            : name + " enters from " + dir + ".";
    }

    /** Remote-event departure prose. Placeholder exits read "X leaves."; real
     *  exits read "X heads <dir>." (avoids "X leaves in." awkwardness). */
    private static String movementDepartureText(String name, String dir) {
        return isPlaceholderMovementDir(dir)
            ? name + " leaves."
            : name + " heads " + dir + ".";
    }

    private static boolean isPlaceholderMovementDir(String dir) {
        if (dir == null) return true;
        var d = dir.trim().toLowerCase();
        return d.isEmpty() || d.equals("nowhere") || d.equals("somewhere") || d.equals("unknown");
    }

    private void sendProse(ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                            String speaker, String text) {
        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Prose(
                0L, speaker, text, List.of(), null, "normal")));
    }

    /**
     * Self-service password change for SSH / telnet / CLI surfaces.
     * Usage: {@code passwd <current> <new>}. Mirrors the REST
     * {@code POST /api/auth/change-password} endpoint so every surface
     * shares one credential path. Requires a logged-in (session-backed)
     * user — anonymous/device/DID sessions have no password to rotate.
     */
    private void handlePasswd(String sessionId,
                              ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                              C2SMessage.Command cmd,
                              String locale) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var userId = sessionUserIds.get(sessionId);
        if (userId == null || authService == null) {
            sendProse(sessionRef, "system", catalog.get("passwd.login_required"));
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        if (args.size() < 2) {
            sendProse(sessionRef, "system", catalog.get("passwd.usage"));
            return;
        }
        // The bootstrap / invite credential is a multi-word passphrase (e.g.
        // "wisteria flame garnet lake rain citadel"), so the current password
        // routinely contains spaces. Treat the LAST whitespace token as the new
        // password and join everything before it as the current one — that lets
        // a spaced passphrase be entered without quoting. (A new password must
        // therefore be a single token; that's the documented usage.)
        var next = args.get(args.size() - 1);
        var current = String.join(" ", args.subList(0, args.size() - 1));
        if (next.length() < 4) {
            sendProse(sessionRef, "system", catalog.get("passwd.too_short"));
            return;
        }
        try {
            if (authService.changePassword(userId, current, next)) {
                sendProse(sessionRef, "system", catalog.get("passwd.changed"));
            } else {
                sendProse(sessionRef, "system", catalog.get("passwd.wrong_current"));
            }
        } catch (Exception e) {
            log.warn("passwd failed for {}: {}", userId, e.getMessage());
            sendProse(sessionRef, "system", catalog.get("passwd.failed"));
        }
    }

    /**
     * web-surface parity for the steward relay
     * commands. Mirrors WyrdShellCommand's SSH cases through the same
     * {@link RelayCommandBridge}, but where SSH renders a half-block ASCII
     * QR, the web client gets a {@code wyrdsekai.invite_qr} content block
     * carrying a server-rendered PNG (browsers can't scan block glyphs;
     * an image they can). Fallback text carries the bare URL for clients
     * that don't understand the block.
     */
    private void handleRelayCommand(String sessionId,
                                    ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                    String command,
                                    C2SMessage.Command cmd,
                                    String locale) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var userId = sessionUserIds.get(sessionId);
        var isSteward = userId != null && authService != null
            && authService.findUser(userId).map(u -> "steward".equals(u.role())).orElse(false);
        if (!isSteward) {
            sendProse(sessionRef, "system", catalog.get("relaycmd.steward_only"));
            return;
        }
        var args = cmd.args() != null ? cmd.args() : List.<String>of();
        if ("invite".equals(command)) {
            if (args.isEmpty() || !"phone".equalsIgnoreCase(args.get(0))) {
                sendProse(sessionRef, "system", catalog.get("relaycmd.invite_usage"));
                return;
            }
            var minted = RelayCommandBridge.phoneInvite();
            if (!minted.ok()) {
                sendProse(sessionRef, "system", catalog.get("relaycmd.invite_failed", minted.detail()));
                return;
            }
            var data = JsonNodeFactory.instance.objectNode();
            data.put("url", minted.inviteUrl());
            data.put("png", RelayCommandBridge.qrPngBase64(minted.inviteUrl()));
            var block = new ContentBlock("wyrdsekai.invite_qr", data, minted.inviteUrl());
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0L, "system",
                    catalog.get("relaycmd.invite_scan") + "\n" + minted.inviteUrl()
                        + "\n" + catalog.get("relaycmd.invite_paste_hint"),
                    List.of(), null, "normal", locale, false, List.of(block), null, null)));
        } else {
            // /relay join <wyrdjoin://token>  (fp-verified)  or
            // /relay join <host>[:port] <code>  (legacy two-arg).
            if (args.size() < 2 || !"join".equalsIgnoreCase(args.get(0))
                    || (args.size() < 3 && !args.get(1).startsWith("wyrdjoin://"))) {
                sendProse(sessionRef, "system", catalog.get("relaycmd.join_usage"));
                return;
            }
            var joined = RelayCommandBridge.relayJoin(
                args.get(1), args.size() > 2 ? args.get(2) : null);
            if (!joined.ok()) {
                sendProse(sessionRef, "system", catalog.get("relaycmd.join_failed", joined.detail()));
            } else {
                sendProse(sessionRef, "system", catalog.get("relaycmd.join_ok", args.get(1))
                    + "\n" + catalog.get("relaycmd.join_restart_hint"));
            }
        }
    }

    /**
     * Append a HOME_ENTERED audit entry when the player crosses back into their Home.
     * No-op if HomeClient isn't wired (dev/test setups without the registry actor).
     */
    private void recordHomeEntered(String playerId) {
        if (homeClient == null) return;
        try {
            var entry = AuditEntry.now(
                playerId, playerId,
                AuditEntry.Verb.HOME_ENTERED,
                "home://" + playerId + "/home-room",
                AuditEntry.Outcome.ok,
                Map.of("via", "home-command"),
                null);
            homeClient.appendAudit(entry);
        } catch (Exception e) {
            log.debug("home-entered audit append failed: {}", e.getMessage());
        }
    }

    void performMove(String sessionId,
                     ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                     String playerId,
                     ActorRef<RoomCommand> fromRoom, String fromRoomId,
                     ActorRef<RoomCommand> toRoom, String toRoomId,
                     String direction, String requestId, String locale) {
        // Check enter ward on target room before moving
        if (!wardService.isAllowed(toRoomId, playerId, "enter")) {
            log.info("Ward denied: {} cannot enter {} (from {})", playerId, toRoomId, fromRoomId);
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("ward_denied",
                    catalog.get("ui.not_permitted_enter")),
                requestId));
            return;
        }

        // A move must never LEAVE before the destination is real: an exit can
        // outlive its room's actor (persisted room not respawned), and leaving
        // first strands the session in a room that will never send EnterRoom.
        if (toRoom == null) {
            log.warn("Move rejected: no actor for target room {} (from {})", toRoomId, fromRoomId);
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("move_failed", catalog.get("ui.move_failed")),
                requestId));
            return;
        }

        var displayName = sessionPlayerNames.getOrDefault(sessionId, "player");
        Rooms.<RoomResponse>ask(fromRoom,
            ref -> new RoomCommand.LeaveRoom(playerId, displayName, direction, ref),
            ASK_TIMEOUT
        ).thenAccept(leaveResp -> {
            sessionCurrentRoom.put(sessionId, toRoomId);
            var pid = sessionPlayerIds.get(sessionId);
            if (pid != null) {
                playerLastRoom.put(pid, toRoomId);
                // Update player location in EntityRegistry
                var er = EntityRegistry.get();
                if (er != null) er.moved(pid, toRoomId);
                // Update cross-node presence
                var dn = sessionPlayerNames.get(sessionId);
                publishPresence(pid, dn != null ? dn : "player", toRoomId);
                // Wave 5: Update unified session room
                var ss = sessionService;
                var uid = sessionUserIds.get(sessionId);
                if (ss != null && uid != null) ss.memberMove(uid, toRoomId);
            }
            // Subscribe + Enter + Look routed through session actor for guaranteed ordering
            sessionRef.tell(new ClientSessionActor.JoinRoomAndEnter(
                toRoom, playerId, displayName, "player",
                oppositeDirection(direction), locale, requestId, loadInventory(playerId)));
        }).exceptionally(ex -> {
            log.error("Move failed for session {}: {}", sessionId, ex.getMessage());
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("move_failed", catalog.get("ui.move_failed")),
                requestId));
            return null;
        });
    }

    /** Load player inventory as RoomObject list for wire protocol. */
    private List<RoomObject> loadInventory(String playerId) {
        return inventoryService.listTakeableItems(playerId).stream()
            .map(i -> new RoomObject(i.objectId(), i.objectName(), i.description(), i.takeable()))
            .toList();
    }

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

    private void askRoom(ActorRef<RoomCommand> room,
                         Function<ActorRef<RoomResponse>, RoomCommand> cmdFactory,
                         ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                         String requestId) {
        Rooms.<RoomResponse>ask(room, cmdFactory::apply, ASK_TIMEOUT)
            .thenAccept(resp ->
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, requestId)));
    }

    private void askRoomWithInventory(ActorRef<RoomCommand> room,
                         Function<ActorRef<RoomResponse>, RoomCommand> cmdFactory,
                         ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                         String playerId, String requestId) {
        Rooms.<RoomResponse>ask(room, cmdFactory::apply, ASK_TIMEOUT)
            .thenAccept(resp ->
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, requestId, loadInventory(playerId))));
    }

    /**
     * Handle MapRequest by querying ZoneTopology and returning S2CMessage.MapData.
     * Supports commands: map, nearby, rooms, path, where, exits.
     */
    private void handleMapRequest(String sessionId,
                                   ActorRef<ClientSessionActor.SessionMessage> sessionRef,
                                   String playerId, String currentRoomId,
                                   C2SMessage.MapRequest mapReq) {
        var topo = zoneTopology;
        if (topo == null) {
            log.debug("MapRequest ignored — no ZoneTopology available");
            return;
        }

        // Visited rooms: for now treat all rooms as visited (no fog-of-war tracking yet)
        var visitedRooms = topo.rooms().keySet();
        int radius = Math.max(1, Math.min(mapReq.radius(), 5));

        String textMap;
        var topology = topo.snapshot(currentRoomId, radius, visitedRooms);
        List<String> path = null;

        switch (mapReq.command()) {
            case "map" -> textMap = topo.renderTextMap(currentRoomId, radius, visitedRooms);
            case "nearby" -> textMap = topo.renderAccessibleMap(currentRoomId, 1, visitedRooms, false);
            case "rooms" -> textMap = topo.renderAccessibleMap(currentRoomId, 5, visitedRooms, false);
            case "where" -> textMap = topo.renderVoiceMap(currentRoomId);
            case "exits" -> textMap = topo.renderAccessibleMap(currentRoomId, 1, visitedRooms, true);
            case "path" -> {
                if (mapReq.target() != null) {
                    // Find room ID by name
                    var targetId = topo.rooms().values().stream()
                        .filter(n -> n.name().equalsIgnoreCase(mapReq.target()))
                        .map(ZoneTopology.RoomNode::roomId)
                        .findFirst()
                        .orElse(mapReq.target());
                    var pathOpt = topo.pathBetween(currentRoomId, targetId);
                    if (pathOpt.isPresent()) {
                        path = pathOpt.get();
                        var sb = new StringBuilder("Path to ").append(mapReq.target()).append(":\n");
                        for (int i = 0; i < path.size() - 1; i++) {
                            var dir = topo.directionBetween(path.get(i), path.get(i + 1));
                            var nextNode = topo.room(path.get(i + 1));
                            var nextName = nextNode.map(ZoneTopology.RoomNode::name).orElse(path.get(i + 1));
                            sb.append("  ").append(i + 1).append(". ")
                              .append(dir.orElse("?")).append(" → ").append(nextName).append("\n");
                        }
                        textMap = sb.toString().stripTrailing();
                    } else {
                        textMap = "No path found to " + mapReq.target() + ".";
                    }
                } else {
                    textMap = "Usage: path <room name>";
                }
            }
            default -> textMap = topo.renderTextMap(currentRoomId, radius, visitedRooms);
        }

        var mapData = new S2CMessage.MapData(0, mapReq.command(), textMap, topology, path);
        sessionRef.tell(new ClientSessionActor.SendMessage(mapData));
    }

    // ─── — furnishings projections ──────────────────

    /** Federation-agreement view for the Manifest furnishing. */
    private static List<Map<String, Object>> federationAgreementsView(
            FederationService fed, String localZoneId) {
        try {
            var all = fed.listAgreements(localZoneId);
            var out = new ArrayList<Map<String, Object>>(all.size());
            for (var a : all) {
                var m = new HashMap<String, Object>();
                m.put("remoteZone", a.remoteZoneId());
                m.put("status", a.status());
                m.put("trustLevel", a.trustLevel());
                if (a.agreedAt() != null) m.put("agreedAt", a.agreedAt().toString());
                if (a.expiresAt() != null) m.put("expiresAt", a.expiresAt().toString());
                if (a.localQuota() != null) m.put("localQuotaDaily", a.localQuota().inferenceTokensPerDay());
                if (a.remoteQuota() != null) m.put("remoteQuotaDaily", a.remoteQuota().inferenceTokensPerDay());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("federationAgreementsView({}): {}", localZoneId, e.getMessage());
            return List.of();
        }
    }

    /** Owned-inventory view for the Trunk furnishing. */
    private static List<Map<String, Object>> inventoryOwnedView(
            InventoryService inv, String playerId) {
        try {
            var items = inv.listItems(playerId);
            var out = new ArrayList<Map<String, Object>>(items.size());
            for (var it : items) {
                var m = new HashMap<String, Object>();
                m.put("id", it.objectId());
                m.put("name", it.objectName());
                if (it.description() != null) m.put("description", it.description());
                m.put("takeable", it.takeable());
                m.put("scripted", it.isScripted());
                if (it.takenFrom() != null) m.put("takenFrom", it.takenFrom());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("inventoryOwnedView({}): {}", playerId, e.getMessage());
            return List.of();
        }
    }

    /** Bonds view for the Shelf furnishing. */
    private static List<Map<String, Object>> bondsView(
            BondRitual ritual, String playerId) {
        try {
            var bonds = ritual.bondsForAgent(playerId);
            var out = new ArrayList<Map<String, Object>>(bonds.size());
            for (var b : bonds) {
                var partner = b.otherParty(playerId);
                var m = new HashMap<String, Object>();
                m.put("bondId", b.bondId());
                m.put("partner", partner);
                m.put("depth", b.depth().name());
                m.put("depthLevel", b.depth().level());
                m.put("interactionCount", b.interactionCount());
                m.put("scarred", b.scarred());
                m.put("kind", b.canonicalKind().name());
                m.put("active", b.active());
                if (b.formedAt() != null) m.put("formedAt", b.formedAt().toString());
                if (b.lastInteraction() != null) m.put("lastInteraction", b.lastInteraction().toString());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("bondsView({}): {}", playerId, e.getMessage());
            return List.of();
        }
    }

    /** Presence-in-Home view for the Lantern furnishing. */
    private List<Map<String, Object>> presenceInHomeView(String ownerId) {
        try {
            var studyRoom = StudyProvisioner.studyRoomId(ownerId);
            var out = new ArrayList<Map<String, Object>>();
            for (var entry : sessionCurrentRoom.entrySet()) {
                if (!studyRoom.equals(entry.getValue())) continue;
                var sid = entry.getKey();
                var pid = sessionPlayerIds.get(sid);
                var pname = sessionPlayerNames.get(sid);
                var m = new HashMap<String, Object>();
                m.put("entityId", pid != null ? pid : sid);
                m.put("name", pname != null ? pname : (pid != null ? pid : sid));
                m.put("type", "player");
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("presenceInHomeView({}): {}", ownerId, e.getMessage());
            return List.of();
        }
    }
}
