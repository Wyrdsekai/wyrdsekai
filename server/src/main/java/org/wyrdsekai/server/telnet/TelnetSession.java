package org.wyrdsekai.server.telnet;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.RemoteZoneSession;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.TransitInventory;
import org.wyrdsekai.common.model.TransitReputation;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.agent.NotificationService;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.issue.Issue;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.ExamineLookup;
import org.wyrdsekai.core.room.RenameService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.StudyProvisioner;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.server.session.ClientConnection;
import org.wyrdsekai.server.session.ClientConnectionRegistry;
import org.wyrdsekai.server.session.ClientSessionActor;
import org.wyrdsekai.server.session.RemoteEventDecoder;
import org.wyrdsekai.server.session.SessionCommands;
import org.wyrdsekai.server.session.WhoView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Handles a single Telnet connection.
 * Manages GMCP negotiation, authentication, input parsing, and output rendering.
 * Creates a ClientSessionActor for room event subscription.
 */
public class TelnetSession implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TelnetSession.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);
    private static final String GUEST_START_ROOM = "nexus";
    private static final String USER_START_ROOM = "study";

    private final Socket socket;
    private final ActorSystem<?> system;
    private final AuthService authService;
    private final InviteService inviteService; // nullable
    private final WardService wardService;
    private final InventoryService inventoryService;

    private final String sessionId;
    private String playerId;
    private String playerName;
    private String playerRole; // "steward" or "member"
    private String currentRoomId = GUEST_START_ROOM;
    private String currentRoomName = "The Nexus";
    private String locale = "en";
    private final Map<String, String> userAliases = new ConcurrentHashMap<>();

    // State for new MUD commands
    private String lastTellFrom;
    private boolean briefMode;
    private String afkMessage;
    private String following;

    private ActorRef<ClientSessionActor.SessionMessage> sessionRef;
    private TelnetRenderer renderer;

    // Cross-zone transit wiring (nullable — absent when federation is off).
    private volatile String localZoneId;
    private volatile RelaySessionTransport relayTransport;
    private volatile ClientConnectionRegistry connectionRegistry;
    private volatile RemoteZoneSession remoteZoneSession;

    /**
     * Wire cross-zone transit prerequisites. See
     * {@code WyrdShellCommand.setTransitContext} for semantics; identical
     * behavior here so Telnet clients reach transit parity with SSH/WS.
     */
    public void setTransitContext(String localZoneId,
                                  RelaySessionTransport relayTransport,
                                  ClientConnectionRegistry registry) {
        this.localZoneId = localZoneId;
        this.relayTransport = relayTransport;
        this.connectionRegistry = registry;
    }

    public TelnetSession(Socket socket, ActorSystem<?> system,
                         AuthService authService, WardService wardService,
                         InventoryService inventoryService) {
        this(socket, system, authService, null, wardService, inventoryService);
    }

    public TelnetSession(Socket socket, ActorSystem<?> system,
                         AuthService authService,
                         InviteService inviteService,
                         WardService wardService,
                         InventoryService inventoryService) {
        this.socket = socket;
        this.system = system;
        this.authService = authService;
        this.inviteService = inviteService;
        this.wardService = wardService;
        this.inventoryService = inventoryService;
        this.sessionId = UUID.randomUUID().toString().substring(0, 12);
    }

    @Override
    public void run() {
        try {
            var in = socket.getInputStream();
            var out = socket.getOutputStream();

            // Negotiate GMCP
            boolean[] gmcpSupported = { false };
            TelnetCodec.negotiateGmcp(out);

            // Welcome banner
            var catalog = ScriptMessageCatalog.forLang(locale);
            TelnetCodec.sendLine(out, "");
            TelnetCodec.sendLine(out, catalog.get("telnet.welcome"));
            TelnetCodec.sendLine(out, "");
            // Telnet is plaintext — make the security posture explicit on connect.
            TelnetCodec.sendLine(out, catalog.get("telnet.insecure_warning"));
            TelnetCodec.sendLine(out, "");
            TelnetCodec.sendLine(out, catalog.get("telnet.login_help1"));
            TelnetCodec.sendLine(out, catalog.get("telnet.login_help2"));
            TelnetCodec.sendLine(out, catalog.get("telnet.login_help_redeem"));
            TelnetCodec.sendLine(out, catalog.get("telnet.login_help3"));
            TelnetCodec.sendLine(out, "");

            // Authentication loop
            if (!authenticate(in, out, gmcpSupported)) {
                socket.close();
                return;
            }

            // Parental time limit: a member whose daily hours are spent
            // cannot start a new session (mirrors the failed-auth close
            // above). No-op ALLOW when the service isn't wired or for
            // anonymous ids.
            var parentalSvc = ParentalControlService.get();
            if (parentalSvc != null && playerId != null && !playerId.startsWith("anon-")) {
                var minutesLeft = parentalSvc.minutesRemaining(playerId);
                if (minutesLeft != null && minutesLeft <= 0) {
                    TelnetCodec.sendLine(out,
                        "Today's hours in the world are spent — the household clock says rest.");
                    socket.close();
                    return;
                }
            }

            // Maintenance mode: while the dial is on, only the steward may
            // start a session (mirrors the parental gate above). No-op ALLOW
            // when the service isn't wired (tests, bare boots).
            var maintenanceSvc = MaintenanceService.get();
            if (maintenanceSvc != null && !maintenanceSvc.allowsLogin(playerId)) {
                TelnetCodec.sendLine(out, maintenanceSvc.refusalLine());
                socket.close();
                return;
            }

            // Create renderer with GMCP support detected during auth phase
            renderer = new TelnetRenderer(out, gmcpSupported[0]);

            // Create session actor
            sessionRef = system.<ClientSessionActor.SessionMessage>systemActorOf(
                ClientSessionActor.create(sessionId, json -> {
                    try {
                        var msg = Json.mapper().readValue(json, S2CMessage.class);
                        renderer.render(msg);
                        // Track room name from RoomState messages
                        if (msg instanceof S2CMessage.RoomState rs) {
                            currentRoomId = rs.room().roomId();
                            currentRoomName = rs.room().name();
                        }
                        renderer.sendPrompt(currentRoomName);
                    } catch (Exception e) {
                        log.error("Telnet render error for session {}", sessionId, e);
                    }
                }),
                "telnet-session-" + sessionId,
                Props.empty());

            // login landing branches on residency:
            //   resident of this zone → their Study
            //   authenticated non-resident → Docks (visitor surface)
            //   guest/anonymous         → Nexus (shared hub)
            String startRoomId;
            if (playerId != null && !playerId.startsWith("anon-")) {
                var residency = ResidencyStore.get();
                // If ResidencyStore isn't initialised (tests, early boot) or
                // localZoneId is unknown (non-federated dev), fall back to
                // the pre-§25 behaviour of always landing in the Study.
                boolean isResident = residency == null || localZoneId == null
                    || residency.isResident(playerId, localZoneId);
                if (isResident) {
                    startRoomId = StudyProvisioner.studyRoomId(playerId);
                    // provisioning fires at grant-time via
                    // ResidencyStore hook. Only fall back to login-time
                    // provisioning when the store is absent (tests).
                    if (residency == null) {
                        provisionStudy(playerId, playerName);
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    }
                } else {
                    startRoomId = "docks";
                }
            } else {
                startRoomId = GUEST_START_ROOM;
            }
            currentRoomId = startRoomId;
            var startRoom = RoomRegistry.get().ref(startRoomId);
            // Study rooms are lazy: post-restart the actor isn't live until
            // ProvisionStudy runs. Re-fire + retry + fall back to Nexus so
            // the session never receives JoinRoom(null).
            if (startRoom == null && playerId != null && !playerId.startsWith("anon-")) {
                log.warn("Telnet {}: room '{}' not live — re-provisioning Study",
                    sessionId, startRoomId);
                provisionStudy(playerId, playerName);
                for (int attempt = 0; attempt < 10 && startRoom == null; attempt++) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    startRoom = RoomRegistry.get().ref(startRoomId);
                }
                if (startRoom == null) {
                    startRoomId = GUEST_START_ROOM;
                    currentRoomId = startRoomId;
                    startRoom = RoomRegistry.get().ref(startRoomId);
                }
            }
            final var finalStartRoom = startRoom;
            sessionRef.tell(new ClientSessionActor.JoinRoom(finalStartRoom, playerId));
            Rooms.<RoomResponse>ask(finalStartRoom,
                ref -> new RoomCommand.EnterRoom(playerId, playerName, "player", "nowhere", ref),
                ASK_TIMEOUT
            ).thenCompose(enterResp ->
                Rooms.<RoomResponse>ask(finalStartRoom,
                    ref -> new RoomCommand.LookRoom(playerId, ref),
                    ASK_TIMEOUT
                )
            ).thenAccept(lookResp ->
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    lookResp, "init", loadInventory(playerId)))
            ).exceptionally(ex -> {
                log.error("Failed to initialize telnet session {}: {}", sessionId, ex.getMessage());
                return null;
            });

            log.info("Telnet session started: {} as {}", sessionId, playerId);

            // Register with transport-agnostic client registry for cross-zone transit.
            if (connectionRegistry != null) {
                connectionRegistry.register(new TelnetClientConnection());
            }

            // Register player in EntityRegistry so agents can find them
            var entityRegistry = EntityRegistry.get();
            if (entityRegistry != null && playerId != null) {
                entityRegistry.enter(playerId, playerName, "player", currentRoomId);
            }

            // On-login flush: notify EVERY companion (multi-companion households).
            if (entityRegistry != null && playerId != null && !playerId.startsWith("anon-")) {
                for (var entityId : entityRegistry.allEntities()) {
                    if (entityRegistry.isAgent(entityId)) {
                        var companionRef = ZoneGuardian.getCompanionRef(
                            null, entityId);
                        if (companionRef != null) {
                            companionRef.tell(
                                new CompanionActor.PlayerReturned(
                                    playerId, playerName, startRoomId));
                        }
                    }
                }
            }

            // Main input loop
            String line;
            while ((line = TelnetCodec.readLine(in, gmcpSupported)) != null) {
                handleInput(line, out);
            }

        } catch (IOException e) {
            if (!socket.isClosed()) {
                log.info("Telnet session {} IO error: {}", sessionId, e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    private boolean authenticate(InputStream in, OutputStream out,
                                  boolean[] gmcpSupported) throws IOException {
        var catalog = ScriptMessageCatalog.forLang(locale);
        for (int attempts = 0; attempts < 5; attempts++) {
            TelnetCodec.sendRaw(out, "login> ");
            var line = TelnetCodec.readLine(in, gmcpSupported);
            if (line == null) return false;

            var parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) continue;

            switch (parts[0].toLowerCase()) {
                case "guest" -> {
                    // phase 2: 'guest' is gated to
                    // explicit test mode. Production deployments (the default)
                    // reject the command. The E2E test bootstrap sets
                    // WYRDSEKAI_TEST_ALLOW_TELNET_GUEST=true so existing
                    // TestTelnetClient.loginAsGuest helpers keep working
                    // without a 24-call-site refactor. Telnet itself is also
                    // OFF by default in production.
                    if (!"true".equalsIgnoreCase(System.getProperty(
                            "wyrdsekai.test.allow_telnet_guest",
                            System.getenv().getOrDefault(
                                "WYRDSEKAI_TEST_ALLOW_TELNET_GUEST", "false")))) {
                        TelnetCodec.sendLine(out, "Anonymous 'guest' is no longer supported. Use:");
                        TelnetCodec.sendLine(out, "  redeem <code> <username> <password>  (with an invite from a steward)");
                        TelnetCodec.sendLine(out, "  connect <username> <password>        (existing accounts)");
                        continue;
                    }
                    playerId = "anon-" + sessionId;
                    playerName = "anonymous";
                    TelnetCodec.sendLine(out, catalog.get("telnet.entering_guest"));
                    return true;
                }
                case "connect", "login" -> {
                    if (parts.length < 3) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.usage_connect"));
                        continue;
                    }
                    var session = authService.login(parts[1], parts[2]);
                    if (session.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.invalid_credentials"));
                        continue;
                    }
                    var s = session.get();
                    var user = authService.findUser(s.userId()).orElseThrow();
                    playerId = user.id();
                    playerName = user.displayName();
                    playerRole = user.role();
                    TelnetCodec.sendLine(out, catalog.get("telnet.welcome_back", playerName));
                    return true;
                }
                case "create", "register" -> {
                    // 'create' is now gated to
                    // (no users yet) AND (client connected from loopback).
                    // Belt-and-suspenders — telnet is also bound 127.0.0.1
                    // by default, so this only matters if the operator
                    // explicitly set WYRDSEKAI_TELNET_BIND=0.0.0.0.
                    if (!authService.isOpenRegistrationAllowed()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.household_invite_required"));
                        TelnetCodec.sendLine(out, catalog.get("telnet.household_use_redeem"));
                        continue;
                    }
                    var addr = socket.getInetAddress();
                    if (addr == null || !addr.isLoopbackAddress()) {
                        TelnetCodec.sendLine(out, "First-steward bootstrap is only allowed from localhost.");
                        TelnetCodec.sendLine(out, "Connect from the host running the server (telnet localhost 7071).");
                        continue;
                    }
                    if (parts.length < 3) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.usage_create"));
                        continue;
                    }
                    var isFirst = authService.isFirstUser();
                    var displayName = parts.length > 3 ? parts[3] : null;
                    var session = authService.register(parts[1], parts[2], displayName);
                    if (session.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.username_taken"));
                        continue;
                    }
                    var s = session.get();
                    var user = authService.findUser(s.userId()).orElseThrow();
                    playerId = user.id();
                    playerName = user.displayName();
                    playerRole = user.role();
                    if (isFirst) {
                        // No more setConfig(OPEN_REGISTRATION, false) — open
                        // registration is now derived from "no users exist".
                        // Once this user exists, isFirstUser() returns false
                        // automatically, closing the door. F4.
                        TelnetCodec.sendLine(out, catalog.get("telnet.steward_created"));
                        TelnetCodec.sendLine(out, catalog.get("telnet.steward_invite_hint"));
                        log.info("First user created via telnet: {} (steward)", user.username());
                    }
                    TelnetCodec.sendLine(out, catalog.get("telnet.account_created", playerName));
                    return true;
                }
                case "redeem" -> {
                    // redeem <code-words...> <username> <password>
                    // Code is 6 words, then username and password
                    if (inviteService == null) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.invite_not_available"));
                        continue;
                    }
                    if (parts.length < 9) { // redeem + 6 code words + username + password
                        TelnetCodec.sendLine(out, catalog.get("telnet.invite_usage"));
                        continue;
                    }
                    var code = String.join(" ", parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]);
                    var username = parts[7];
                    var password = parts[8];
                    // Peek at invite to get role
                    var pending = inviteService.listPendingInvites().stream()
                        .filter(i -> i.code().equals(code.toLowerCase()))
                        .findFirst();
                    if (pending.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.invite_invalid"));
                        continue;
                    }
                    var inviteRole = pending.get().role();
                    var session = authService.register(username, password, null, inviteRole);
                    if (session.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.username_taken"));
                        continue;
                    }
                    var s = session.get();
                    inviteService.redeemInvite(code.toLowerCase(), s.userId());
                    var user = authService.findUser(s.userId()).orElseThrow();
                    playerId = user.id();
                    playerName = user.displayName();
                    playerRole = user.role();
                    TelnetCodec.sendLine(out, catalog.get("telnet.invite_accepted", playerName));
                    return true;
                }
                case "quit" -> { return false; }
                default -> TelnetCodec.sendLine(out, catalog.get("telnet.unknown_login_cmd"));
            }
        }
        TelnetCodec.sendLine(out, catalog.get("telnet.too_many_attempts"));
        return false;
    }

    private void handleInput(String input, OutputStream out) {
        // Proxy mode: tunnel input to the remote zone via RemoteZoneSession.
        // ::local escape hatch ends the proxy client-side.
        if (remoteZoneSession != null && remoteZoneSession.isActive()) {
            var trimmed = input.trim();
            if ("::local".equalsIgnoreCase(trimmed)) {
                endRemoteSession();
                return;
            }
            forwardToRemote(trimmed);
            return;
        }

        if (connectionRegistry != null) connectionRegistry.touch(sessionId);
        var cmd = CommandParser.parse(input, locale, userAliases);
        if (cmd == null) return;

        switch (cmd) {
            case ParsedCommand.Quit q -> {
                // Detach THIS channel only; note other surfaces still present.
                try {
                    var catalog = ScriptMessageCatalog.forLang(locale);
                    var hint = SessionCommands.detachHint(connectionRegistry, playerId, sessionId, locale);
                    TelnetCodec.sendLine(out, hint != null ? hint : catalog.get("telnet.goodbye"));
                    socket.close();
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Logout lo -> {
                // End the whole presence: drop other channels, then close this one.
                SessionCommands.logoutOthers(connectionRegistry, playerId, sessionId);
                try {
                    TelnetCodec.sendLine(out, ScriptMessageCatalog.forLang(locale).get("session.logout"));
                    socket.close();
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Sessions se -> {
                try {
                    if (SessionCommands.isKill(se.args())) {
                        TelnetCodec.sendLine(out, SessionCommands.killByIndex(connectionRegistry, playerId, locale, se.args()));
                    } else {
                        TelnetCodec.sendLine(out, SessionCommands.render(connectionRegistry, playerId, sessionId, locale));
                    }
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Key k -> {
                try {
                    TelnetCodec.sendLine(out, SessionCommands.key(authService, playerId, k.args()));
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Look l ->
                askRoomWithInventory(currentRoomId, ref -> new RoomCommand.LookRoom(playerId, ref), "look");

            case ParsedCommand.Tell tell -> handleTell(tell.targetName(), tell.text(), out);

            case ParsedCommand.Go go -> handleGo(go.direction());

            case ParsedCommand.Emote emote -> {
                if (checkWard(currentRoomId, "speak", out)) {
                    var room = RoomRegistry.get().ref(currentRoomId);
                    Rooms.<RoomResponse>ask(room, 
                        ref -> new RoomCommand.EmoteInRoom(playerId, playerName, emote.text(), ref),
                        ASK_TIMEOUT
                    ).exceptionally(ex -> {
                        log.warn("Emote failed: {}", ex.getMessage());
                        return null;
                    });
                }
            }

            case ParsedCommand.Whisper whisper -> {
                if (checkWard(currentRoomId, "speak", out)) {
                    // WhisperInRoom expects a target entity ID, not a display
                    // name. Resolve via EntityRegistry first.
                    var registry = EntityRegistry.get();
                    var targetId = registry != null
                        ? registry.findByName(whisper.target())
                        : Optional.<String>empty();
                    if (targetId.isEmpty()) {
                        try { out.write(("Nobody called '" + whisper.target() + "' is here.\n").getBytes()); out.flush(); }
                        catch (Exception ignored) {}
                    } else {
                        var room = RoomRegistry.get().ref(currentRoomId);
                        Rooms.<RoomResponse>ask(room,
                            ref -> new RoomCommand.WhisperInRoom(playerId, playerName,
                                targetId.get(), whisper.text(), locale, ref),
                            ASK_TIMEOUT
                        ).exceptionally(ex -> {
                            log.warn("Whisper failed: {}", ex.getMessage());
                            return null;
                        });
                    }
                }
            }

            case ParsedCommand.Say say -> {
                if (checkWard(currentRoomId, "speak", out)) {
                    // Fire-and-forget: the Said event comes back via room notification,
                    // not the command response. Don't render the RoomResponse (it's a
                    // full snapshot that causes a redundant room re-render).
                    var room = RoomRegistry.get().ref(currentRoomId);
                    Rooms.<RoomResponse>ask(room, 
                        ref -> new RoomCommand.SayInRoom(playerId, playerName, say.text(), ref),
                        ASK_TIMEOUT
                    ).exceptionally(ex -> {
                        log.warn("Say failed: {}", ex.getMessage());
                        return null;
                    });
                }
            }

            case ParsedCommand.Take take -> {
                if (checkWard(currentRoomId, "take", out)) {
                    handleTake(take.objectName());
                }
            }

            case ParsedCommand.Drop drop -> {
                if (checkWard(currentRoomId, "drop", out)) {
                    handleDrop(drop.objectName(), out);
                }
            }

            case ParsedCommand.Use use -> {
                if (checkWard(currentRoomId, "use", out)) {
                    askRoom(currentRoomId,
                        ref -> new RoomCommand.UseObject(playerId, use.objectName(), use.target(), ref), "use");
                }
            }

            case ParsedCommand.HintSelect hs ->
                handleHintSelect(hs.index());

            case ParsedCommand.SlashCommand sc -> handleSlashCommand(sc, out);

            case ParsedCommand.MapCommand mc -> {
                var sharedTopo = ZoneTopology.getShared();
                if (sharedTopo == null) {
                    try { TelnetCodec.sendLine(out, "(map unavailable — topology not initialised)"); }
                    catch (IOException ignored) {}
                    break;
                }
                var myRoomRef = RoomRegistry.get().ref(currentRoomId);
                final int radius = mc.radius();
                if (myRoomRef == null || sharedTopo.room(currentRoomId).isPresent()) {
                    try {
                        var text = sharedTopo.renderTextMap(currentRoomId, radius,
                            sharedTopo.rooms().keySet());
                        for (var line : text.split("\n")) TelnetCodec.sendLine(out, line);
                    } catch (IOException ignored) {}
                    break;
                }
                Rooms.<RoomResponse>ask(myRoomRef,
                    ref -> new RoomCommand.LookRoom(playerId, ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {
                    try {
                        var rooms = new HashMap<>(sharedTopo.rooms());
                        if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                            var snap = ok.snapshot();
                            rooms.put(currentRoomId,
                                new ZoneTopology.RoomNode(
                                    currentRoomId, snap.name(),
                                    snap.zone() == null ? "player" : snap.zone(),
                                    snap.exits() == null
                                        ? List.of()
                                        : snap.exits()));
                        }
                        var personal = ZoneTopology.build(rooms);
                        var text = personal.renderTextMap(currentRoomId, radius,
                            personal.rooms().keySet());
                        for (var line : text.split("\n")) TelnetCodec.sendLine(out, line);
                    } catch (IOException ignored) {}
                });
            }
            case ParsedCommand.Where w -> {} // map rendering not supported in telnet
            case ParsedCommand.Nearby n -> {} // map rendering not supported in telnet
            case ParsedCommand.Rooms r -> {} // map rendering not supported in telnet
            case ParsedCommand.Path p -> {} // map rendering not supported in telnet
            case ParsedCommand.Exits e -> {} // map rendering not supported in telnet

            case ParsedCommand.Office o -> {
                var myStudy = StudyProvisioner.studyRoomId(playerId);
                if (!myStudy.equals(currentRoomId)) {
                    // Ensure Study exists, then move there
                    provisionStudy(playerId, playerName);
                    performMove("office", myStudy);
                }
            }

            case ParsedCommand.Describe desc -> handleDescribe(desc, out);

            case ParsedCommand.Examine ex -> handleExamine(ex.target(), out);

            case ParsedCommand.Rename rn -> handleRename(rn.target(), rn.newName(), out);

            case ParsedCommand.Give give -> handleGive(give.objectName(), give.targetName(), out);

            case ParsedCommand.Score sc -> handleScore(out);

            case ParsedCommand.Shout shout -> {
                if (checkWard(currentRoomId, "speak", out)) {
                    var catalog = ScriptMessageCatalog.forLang(locale);
                    var room = RoomRegistry.get().ref(currentRoomId);
                    Rooms.<RoomResponse>ask(room, 
                        ref -> new RoomCommand.SayInRoom(playerId, playerName,
                            catalog.get("telnet.shout", playerName, shout.text()), ref),
                        ASK_TIMEOUT
                    ).exceptionally(ex -> {
                        log.warn("Shout failed: {}", ex.getMessage());
                        return null;
                    });
                }
            }

            case ParsedCommand.Reply reply -> handleReply(reply.text(), out);

            case ParsedCommand.Follow follow -> handleFollow(follow.targetName(), out);

            case ParsedCommand.Afk afk -> handleAfk(afk.message(), out);

            case ParsedCommand.Brief b -> handleBrief(out);

            case ParsedCommand.Alias aliasCmd -> {
                try {
                    if (aliasCmd.name() == null) {
                        // List all aliases
                        if (userAliases.isEmpty()) {
                            TelnetCodec.sendLine(out, "No aliases defined. Use: alias <name> <expansion>");
                        } else {
                            var sb = new StringBuilder("Aliases:\n");
                            for (var entry : userAliases.entrySet()) {
                                sb.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                            }
                            TelnetCodec.sendLine(out, sb.toString().trim());
                        }
                    } else if (aliasCmd.expansion() == null) {
                        // Show specific alias
                        var exp = userAliases.get(aliasCmd.name());
                        if (exp != null) {
                            TelnetCodec.sendLine(out, aliasCmd.name() + " = " + exp);
                        } else {
                            TelnetCodec.sendLine(out, "No alias '" + aliasCmd.name() + "' defined.");
                        }
                    } else {
                        // Define alias
                        userAliases.put(aliasCmd.name(), aliasCmd.expansion());
                        TelnetCodec.sendLine(out, "Alias set: " + aliasCmd.name() + " = " + aliasCmd.expansion());
                    }
                    renderer.sendPrompt(currentRoomName);
                } catch (IOException e) {
                    log.warn("Alias command failed: {}", e.getMessage());
                }
            }

            case ParsedCommand.Unalias unaliasCmd -> {
                try {
                    if (userAliases.remove(unaliasCmd.name()) != null) {
                        TelnetCodec.sendLine(out, "Alias '" + unaliasCmd.name() + "' removed.");
                    } else {
                        TelnetCodec.sendLine(out, "No alias '" + unaliasCmd.name() + "' to remove.");
                    }
                    renderer.sendPrompt(currentRoomName);
                } catch (IOException e) {
                    log.warn("Unalias command failed: {}", e.getMessage());
                }
            }

            case ParsedCommand.GrantWard gw -> handleGrantWard(gw.agentName(), out);
            case ParsedCommand.RevokeWard rw -> handleRevokeWard(rw.agentName(), out);
            case ParsedCommand.Invite inv -> handleInvite(inv.agentName(), out);
            case ParsedCommand.Dismiss dis -> handleDismiss(dis.agentName(), out);

            case ParsedCommand.AbortPlan _ -> {
                var stream = AgentEventStream.get();
                if (stream != null) {
                    stream.publishAbort(playerId, playerName, currentRoomId);
                }
                try { TelnetCodec.sendLine(out, "Cancelling..."); }
                catch (IOException ignored) {}
            }

            // sit / stand body verbs. Build a generic
            // Posture and send SetPosture / ClearPosture to the current room.
            case ParsedCommand.Sit sit -> {
                if (currentRoomId == null) break;
                var trimmed = sit.target() == null ? null : sit.target().trim();
                var atObject = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
                var descriptor = atObject == null
                    ? playerName + " settles down."
                    : playerName + " settles at the " + atObject + ".";
                var posture = new Posture("sat", atObject, descriptor);
                var room = RoomRegistry.get().ref(currentRoomId);
                Rooms.<RoomResponse>ask(room,
                    ref -> new RoomCommand.SetPosture(playerId, posture, ref),
                    ASK_TIMEOUT);
            }
            case ParsedCommand.Stand _ -> {
                if (currentRoomId == null) break;
                var room = RoomRegistry.get().ref(currentRoomId);
                Rooms.<RoomResponse>ask(room,
                    ref -> new RoomCommand.ClearPosture(playerId, ref),
                    ASK_TIMEOUT);
            }

            case ParsedCommand.Unknown unknown -> {
                try {
                    var unknownCatalog = ScriptMessageCatalog.forLang(locale);
                    TelnetCodec.sendLine(out, unknownCatalog.get("telnet.unknown_input"));
                    renderer.sendPrompt(currentRoomName);
                } catch (IOException e) {
                    log.warn("Failed to send unknown command response: {}", e.getMessage());
                }
            }
        }
    }

    private void handleGo(String direction) {
        var room = RoomRegistry.get().ref(currentRoomId);
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.LookRoom(playerId, ref),
            ASK_TIMEOUT
        ).thenAccept(lookResp -> {
            if (!(lookResp instanceof RoomResponse.Ok ok)) {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(lookResp, "go"));
                return;
            }
            // Exact direction, then fuzzy destination match (see Exit.resolve — second-node 2026-07-09).
            var exit = Exit.resolve(ok.snapshot().exits(), direction);
            if (exit.isEmpty()) {
                var catalog = ScriptMessageCatalog.forLang(locale);
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    new RoomResponse.Rejected("no_exit", catalog.get("telnet.no_exit")), "go"));
                return;
            }
            var targetRoomId = exit.get().targetRoom();
            performMove(exit.get().direction(), targetRoomId);
        });
    }

    private void performMove(String direction, String targetRoomId) {
        if (!wardService.isAllowed(targetRoomId, playerId, "enter")) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("ward_denied", catalog.get("telnet.not_permitted")), "go"));
            return;
        }

        var fromRoom = RoomRegistry.get().ref(currentRoomId);
        var toRoom = RoomRegistry.get().ref(targetRoomId);

        Rooms.<RoomResponse>ask(fromRoom, 
            ref -> new RoomCommand.LeaveRoom(playerId, playerName, direction, ref),
            ASK_TIMEOUT
        ).thenCompose(leaveResp ->
            Rooms.<RoomResponse>ask(toRoom, 
                ref -> new RoomCommand.EnterRoom(playerId, playerName, "player",
                    oppositeDirection(direction), ref),
                ASK_TIMEOUT
            )
        ).thenCompose(enterResp -> {
            currentRoomId = targetRoomId;
            var er = EntityRegistry.get();
            if (er != null && playerId != null) er.moved(playerId, targetRoomId);
            sessionRef.tell(new ClientSessionActor.JoinRoom(toRoom, playerId));
            return Rooms.<RoomResponse>ask(toRoom, 
                ref -> new RoomCommand.LookRoom(playerId, ref),
                ASK_TIMEOUT
            );
        }).thenAccept(lookResp ->
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                lookResp, "go", loadInventory(playerId)))
        ).exceptionally(ex -> {
            log.error("Move failed for telnet session {}: {}", sessionId, ex.getMessage());
            return null;
        });
    }

    private void handleHintSelect(int index) {
        var room = RoomRegistry.get().ref(currentRoomId);
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.SelectHint(playerId, index, ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.HintAction ha && "go".equals(ha.actionType())) {
                performMove(ha.parameter(), ha.targetRoomId());
            } else {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, "hint", loadInventory(playerId)));
            }
        });
    }

    private void handleSlashCommand(ParsedCommand.SlashCommand sc, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            switch (sc.command()) {
                case "issue", "feedback" -> {
                    // in-band report with context bundle.
                    var text = String.join(" ", sc.args());
                    var svc = IssueService.get();
                    if (text.isBlank() || svc == null) {
                        TelnetCodec.sendLine(out, catalog.get("issue.usage"));
                    } else {
                        var kind = "feedback".equals(sc.command())
                            ? Issue.KIND_FEEDBACK
                            : Issue.KIND_ISSUE;
                        var filed = svc.file(kind, text, playerName, "telnet", null, playerId);
                        TelnetCodec.sendLine(out, catalog.get("issue.recorded", kind, filed.id()));
                    }
                    renderer.sendPrompt(currentRoomName);
                }
                case "help" -> {
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_header"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_look"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_go"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_exits"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_say"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_tell"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_take"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_drop"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_use"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_examine"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_inventory"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_actions"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_home"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_travel"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_hints"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_account"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_key"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_passwd"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_sessions"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_help"));
                    TelnetCodec.sendLine(out, catalog.get("telnet.help_quit"));
                    renderer.sendPrompt(currentRoomName);
                }
                case "inventory", "i" -> {
                    var items = inventoryService.listTakeableItems(playerId);
                    if (items.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.inventory_empty"));
                    } else {
                        var names = items.stream()
                            .map(InventoryService.InventoryItem::objectName).toList();
                        TelnetCodec.sendLine(out, catalog.get("telnet.inventory_carrying",
                            String.join(", ", names)));
                    }
                    renderer.sendPrompt(currentRoomName);
                }
                case "who" -> {
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room == null) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.you_are", playerName));
                        renderer.sendPrompt(currentRoomName);
                        break;
                    }
                    var zoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", null);
                    Rooms.<RoomResponse>ask(room,
                        ref -> new RoomCommand.LookRoom(playerId, ref),
                        ASK_TIMEOUT
                    ).thenAccept(resp -> {
                        try {
                            List<String> hereNames = List.of();
                            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                                hereNames = ok.snapshot().entities().stream()
                                    .map(e -> e.name()).toList();
                            }
                            var ctx = new WhoView.Context(
                                playerId, playerRole, zoneId, currentRoomId,
                                hereNames, connectionRegistry, authService);
                            for (var line : WhoView.render(ctx)) {
                                TelnetCodec.sendLine(out, line);
                            }
                            TelnetCodec.sendLine(out, catalog.get("telnet.you_are", playerName));
                            renderer.sendPrompt(currentRoomName);
                        } catch (IOException ignored) {}
                    });
                }
                case "actions", "menu", "options" -> {
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room == null) {
                        TelnetCodec.sendLine(out, "(no room)");
                        renderer.sendPrompt(currentRoomName);
                        break;
                    }
                    Rooms.<RoomResponse>ask(room,
                        ref -> new RoomCommand.LookRoom(playerId, ref),
                        ASK_TIMEOUT
                    ).thenAccept(resp -> {
                        try {
                            if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                                var snap = ok.snapshot();
                                renderer.renderActionsMenu(snap.hints(), snap.name(), snap.roomId());
                            } else {
                                renderer.renderActionsMenu(List.of(), currentRoomName, currentRoomId);
                            }
                            renderer.sendPrompt(currentRoomName);
                        } catch (IOException ignored) {}
                    });
                }
                case "adduser" -> {
                    if (!"steward".equals(playerRole)) {
                        TelnetCodec.sendLine(out, "Only stewards can add users.");
                        renderer.sendPrompt(currentRoomName);
                        break;
                    }
                    var addArgs = sc.args();
                    if (addArgs == null || addArgs.size() < 2) {
                        TelnetCodec.sendLine(out, "Usage: /adduser <username> <password> [displayName]");
                        renderer.sendPrompt(currentRoomName);
                        break;
                    }
                    var addDisplay = addArgs.size() > 2 ? addArgs.get(2) : addArgs.get(0);
                    var addSession = authService.registerByAdmin(playerId,
                        addArgs.get(0), addArgs.get(1), addDisplay, "member");
                    if (addSession.isEmpty()) {
                        TelnetCodec.sendLine(out, "Failed to create user (username taken or permission denied).");
                    } else {
                        var addUser = authService.findUser(addSession.get().userId()).orElseThrow();
                        TelnetCodec.sendLine(out, "User created: " + addUser.username()
                            + " (role: " + addUser.role() + ")");
                        log.info("User created via telnet /adduser by {}: {} (role={})",
                            playerName, addUser.username(), addUser.role());
                    }
                    renderer.sendPrompt(currentRoomName);
                }
                default -> {
                    TelnetCodec.sendLine(out, catalog.get("telnet.unknown_command", sc.command()));
                    renderer.sendPrompt(currentRoomName);
                }
            }
        } catch (IOException e) {
            log.error("Error sending slash command response", e);
        }
    }

    private boolean checkWard(String roomId, String permission, OutputStream out) {
        if (wardService.isAllowed(roomId, playerId, permission)) {
            return true;
        }
        try {
            var catalog = ScriptMessageCatalog.forLang(locale);
            TelnetCodec.sendLine(out, catalog.get("telnet.ward_denied", permission));
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
        return false;
    }

    private void handleTell(String targetName, String text, OutputStream out) {
        var registry = EntityRegistry.get();
        if (registry == null) {
            try { TelnetCodec.sendLine(out, "Tell is not available."); }
            catch (Exception ignored) {}
            return;
        }

        // Try cross-zone routing first (handles "alpha.wyrd", "my wyrd", and cross-zone fallback)
        var tellService = CrossZoneTellService.get();
        if (tellService != null) {
            var localZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
            var result = tellService.tell(playerId, playerName, localZoneId, targetName, text);
            if (result.delivered()) {
                try { TelnetCodec.sendLine(out, "[to " + targetName + "] " + text); }
                catch (Exception ignored) {}
                return;
            }
            if (result.errorMessage() != null) {
                try { TelnetCodec.sendLine(out, result.errorMessage()); }
                catch (Exception ignored) {}
                return;
            }
        }

        // Find target by name
        var targetId = registry.findByName(targetName);
        if (targetId.isEmpty()) {
            try { TelnetCodec.sendLine(out, "Nobody called '" + targetName + "' is online."); }
            catch (Exception ignored) {}
            return;
        }

        // Find which room the target is in
        var targetRoomId = registry.roomOf(targetId.get());
        if (targetRoomId.isEmpty()) {
            try { TelnetCodec.sendLine(out, targetName + " is not in any room."); }
            catch (Exception ignored) {}
            return;
        }

        // If target is an agent, deliver directly via AgentEventStream (cross-room, preserves sender)
        var eventStream = AgentEventStream.get();
        if (eventStream != null && registry.isAgent(targetId.get())) {
            boolean delivered = eventStream.publishAgentMessage(
                playerId, playerName, targetId.get(),
                "[from " + playerName + "] " + text);
            if (!delivered) {
                // Fallback to room-level tell
                var targetRoom = RoomRegistry.get().ref(targetRoomId.get());
                Rooms.<RoomResponse>ask(targetRoom, 
                    ref -> new RoomCommand.SayInRoom(playerId, playerName,
                        "[tell " + targetName + "] " + text, locale, ref),
                    ASK_TIMEOUT
                ).exceptionally(ex -> {
                    log.warn("Tell delivery failed: {}", ex.getMessage());
                    return null;
                });
            }
        } else {
            // Non-agent target — deliver via room
            var targetRoom = RoomRegistry.get().ref(targetRoomId.get());
            Rooms.<RoomResponse>ask(targetRoom, 
                ref -> new RoomCommand.SayInRoom(playerId, playerName,
                    "[tell " + targetName + "] " + text, locale, ref),
                ASK_TIMEOUT
            ).exceptionally(ex -> {
                log.warn("Tell delivery failed: {}", ex.getMessage());
                return null;
            });
        }

        // Track for /reply
        this.lastTellFrom = targetName;

        // Confirm to sender
        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Prose(
                0, "narrator", "You tell " + targetName + ": " + text,
                List.of(), null, "normal", locale)));
    }

    private void handleDescribe(ParsedCommand.Describe desc, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if ("me".equals(desc.target())) {
                if (playerId == null || playerId.startsWith("anon-")) {
                    TelnetCodec.sendLine(out, catalog.get("telnet.describe_login_required"));
                    renderer.sendPrompt(currentRoomName);
                    return;
                }
                authService.updateDescription(playerId, desc.text());
                TelnetCodec.sendLine(out, catalog.get("telnet.describe_updated"));
                renderer.sendPrompt(currentRoomName);
            } else if ("room".equals(desc.target())) {
                if (!StudyProvisioner.isStudyRoom(currentRoomId)) {
                    TelnetCodec.sendLine(out, catalog.get("telnet.describe_room_study_only"));
                    renderer.sendPrompt(currentRoomName);
                    return;
                }
                var room = RoomRegistry.get().ref(currentRoomId);
                Rooms.<RoomResponse>ask(room, 
                    ref -> new RoomCommand.SayInRoom(playerId, playerName,
                        "@describe room=" + desc.text(), ref),
                    ASK_TIMEOUT
                ).exceptionally(ex -> {
                    log.warn("Room describe failed: {}", ex.getMessage());
                    return null;
                });
            }
        } catch (IOException e) {
            log.error("Error sending describe response", e);
        }
    }

    /**
     * §7.4 — self-rename over telnet. Delegates to the shared
     * {@link org.wyrdsekai.core.room.RenameService} so SSH and telnet share
     * a single validation + persistence chain. v1 scope: self only.
     */
    private void handleRename(String target, String newName, OutputStream out) {
        var result = RenameService.renameSelf(
            playerId, playerName, target, newName,
            currentRoomId, authService, ASK_TIMEOUT);
        try {
            switch (result) {
                case RenameService.Result.Ok ok -> {
                    playerName = ok.newName();
                    TelnetCodec.sendLine(out, "You are now known as " + ok.newName() + ".");
                }
                case RenameService.Result.Requested rq ->
                    TelnetCodec.sendLine(out, "You offer the name " + rq.newName()
                        + " to " + rq.targetName() + ".");
                case RenameService.Result.Rejected r ->
                    TelnetCodec.sendLine(out, r.message());
            }
        } catch (IOException e) {
            log.error("Error sending rename response", e);
        }
    }

    /**
     * §2.2 — passive observation over telnet. Delegates to the shared
     * {@link org.wyrdsekai.core.room.ExamineLookup} so SSH and telnet share
     * a single resolution chain. Does NOT invoke onUse, broadcast
     * ObjectUsed, or re-render the room.
     */
    private void handleExamine(String target, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if (target == null || target.isBlank()) {
                TelnetCodec.sendLine(out, catalog.get("telnet.help_examine"));
                return;
            }
        } catch (IOException ignored) { return; }
        ExamineLookup.resolve(
                playerId, playerName, target, locale,
                authService, inventoryService, currentRoomId, ASK_TIMEOUT)
            .thenAccept(result -> {
                try {
                    switch (result) {
                        case ExamineLookup.ExamineResult.Found f -> {
                            TelnetCodec.sendLine(out, f.name());
                            if (f.description() != null && !f.description().isBlank()) {
                                TelnetCodec.sendLine(out, f.description());
                            } else if (f.source() == ExamineLookup.Source.SELF) {
                                TelnetCodec.sendLine(out,
                                    "(no description set. Use '@describe <text>' to set one.)");
                            }
                            // posture line if the entity has one
                            if (f.posture() != null && !f.posture().isBlank()) {
                                TelnetCodec.sendLine(out, f.posture());
                            }
                        }
                        case ExamineLookup.ExamineResult.NotFound nf ->
                            TelnetCodec.sendLine(out, catalog.get("err.no_such_object", nf.requested()));
                        case ExamineLookup.ExamineResult.NoCurrentRoom nr ->
                            TelnetCodec.sendLine(out, catalog.get("err.no_such_object", nr.requested()));
                        case ExamineLookup.ExamineResult.Empty e ->
                            TelnetCodec.sendLine(out, catalog.get("telnet.help_examine"));
                    }
                } catch (IOException e) {
                    log.error("Error sending examine response", e);
                }
            });
    }

    private void handleTake(String objectName) {
        var room = RoomRegistry.get().ref(currentRoomId);
        final var roomId = currentRoomId;
        Rooms.<RoomResponse>ask(room,
            ref -> new RoomCommand.TakeObject(playerId, objectName, ref),
            ASK_TIMEOUT
        ).thenAccept(resp -> {
            if (resp instanceof RoomResponse.ObjectTakenOk taken) {
                var obj = taken.takenObject();
                inventoryService.addItem(playerId, obj.id(), obj.name(),
                    obj.description(), obj.takeable(), roomId);
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, "take", loadInventory(playerId)));
            } else {
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, "take"));
            }
        });
    }

    private void handleDrop(String objectName, OutputStream out) {
        var item = inventoryService.findTakeableByName(playerId, objectName);
        if (item.isEmpty()) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("not_in_inventory", catalog.get("telnet.dont_have")), "drop"));
            return;
        }
        var inv = item.get();
        inventoryService.removeItem(playerId, inv.objectId());
        var room = RoomRegistry.get().ref(currentRoomId);
        Rooms.<RoomResponse>ask(room, 
            ref -> new RoomCommand.DropObject(playerId, inv.objectId(), inv.objectName(),
                inv.description(), inv.takeable(), ref),
            ASK_TIMEOUT
        ).thenAccept(resp ->
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                resp, "drop", loadInventory(playerId))));
    }

    @SuppressWarnings("unchecked")
    private void provisionStudy(String pId, String pName) {
        var isSteward = "steward".equals(playerRole);
        ((ActorSystem<ZoneGuardian.Command>) (Object) system)
            .tell(new ZoneGuardian.ProvisionStudy(pId, pName, isSteward));
        // seed scripted furnishings (Embers, Board, Mailbox) for
        // authenticated users. Idempotent via InventoryService upsert.
        if (pId != null && !pId.startsWith("anon-") && inventoryService != null) {
            var studyRoom = StudyProvisioner.studyRoomId(pId);
            for (var item : StudyFurnishingKit.defaultsFor(isSteward)) {
                try {
                    inventoryService.addItem(pId, item.id(), item.name(), item.description(),
                        /* takeable = */ false, studyRoom, item.script(), item.id());
                } catch (Exception e) {
                    log.warn("Failed to seed furnishing {} for {}: {}",
                        item.id(), pId, e.getMessage());
                }
            }
        }
    }

    private void askRoom(String roomId,
                         Function<ActorRef<RoomResponse>, RoomCommand> cmdFactory,
                         String requestId) {
        var room = RoomRegistry.get().ref(roomId);
        Rooms.<RoomResponse>ask(room, cmdFactory::apply, ASK_TIMEOUT)
            .thenAccept(resp -> {
                // For "use" and "examine" actions, don't render the full room snapshot —
                // the room script emits narration via Prose notifications instead.
                // Only forward errors so the user sees "object not found" etc.
                if ("use".equals(requestId)) {
                    if (resp instanceof RoomResponse.Rejected) {
                        sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, requestId));
                    }
                    // Ok response = script handled it, narration comes via notification
                } else {
                    sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, requestId));
                }
            });
    }

    private void askRoomWithInventory(String roomId,
                         Function<ActorRef<RoomResponse>, RoomCommand> cmdFactory,
                         String requestId) {
        var room = RoomRegistry.get().ref(roomId);
        Rooms.<RoomResponse>ask(room, cmdFactory::apply, ASK_TIMEOUT)
            .thenAccept(resp ->
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, requestId, loadInventory(playerId))));
    }

    /** Load player inventory as RoomObject list for wire protocol. */
    private List<RoomObject> loadInventory(String playerId) {
        return inventoryService.listTakeableItems(playerId).stream()
            .map(i -> new RoomObject(
                i.objectId(), i.objectName(), i.description(), i.takeable()))
            .toList();
    }

    private void handleGive(String objectName, String targetName, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var item = inventoryService.findTakeableByName(playerId, objectName);
        if (item.isEmpty()) {
            try {
                TelnetCodec.sendLine(out, catalog.get("telnet.give_not_found", objectName));
                renderer.sendPrompt(currentRoomName);
            } catch (IOException ignored) {}
            return;
        }
        // Verify target is in the room
        var registry = EntityRegistry.get();
        if (registry == null) {
            try {
                TelnetCodec.sendLine(out, catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName);
            } catch (IOException ignored) {}
            return;
        }
        var targetId = registry.findByName(targetName);
        if (targetId.isEmpty()) {
            try {
                TelnetCodec.sendLine(out, catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName);
            } catch (IOException ignored) {}
            return;
        }
        var targetRoomId = registry.roomOf(targetId.get());
        if (targetRoomId.isEmpty() || !targetRoomId.get().equals(currentRoomId)) {
            try {
                TelnetCodec.sendLine(out, catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName);
            } catch (IOException ignored) {}
            return;
        }
        // Transfer: remove from giver, add to target
        var inv = item.get();
        inventoryService.removeItem(playerId, inv.objectId());
        inventoryService.addItem(targetId.get(), inv.objectId(), inv.objectName(),
            inv.description(), inv.takeable(), currentRoomId);
        try {
            TelnetCodec.sendLine(out, catalog.get("telnet.give_success", objectName, targetName));
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
    }

    private void handleScore(OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            TelnetCodec.sendLine(out, catalog.get("telnet.score_header"));
            TelnetCodec.sendLine(out, "  Name: " + playerName);
            TelnetCodec.sendLine(out, "  Role: " + (playerRole != null ? playerRole : "guest"));
            TelnetCodec.sendLine(out, "  Room: " + currentRoomName);
            var items = inventoryService.listItems(playerId);
            TelnetCodec.sendLine(out, "  Items: " + items.size());
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
    }

    private void handleReply(String text, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        if (lastTellFrom == null) {
            try {
                TelnetCodec.sendLine(out, catalog.get("telnet.reply_no_target"));
                renderer.sendPrompt(currentRoomName);
            } catch (IOException ignored) {}
            return;
        }
        handleTell(lastTellFrom, text, out);
    }

    private void handleFollow(String targetName, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if (targetName.equalsIgnoreCase("none") || targetName.equalsIgnoreCase("stop")) {
                following = null;
                TelnetCodec.sendLine(out, catalog.get("telnet.follow_stop"));
            } else {
                // Verify target is in the room
                var registry = EntityRegistry.get();
                if (registry != null) {
                    var targetId = registry.findByName(targetName);
                    if (targetId.isEmpty()) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.follow_not_here", targetName));
                        renderer.sendPrompt(currentRoomName);
                        return;
                    }
                    var targetRoomId = registry.roomOf(targetId.get());
                    if (targetRoomId.isEmpty() || !targetRoomId.get().equals(currentRoomId)) {
                        TelnetCodec.sendLine(out, catalog.get("telnet.follow_not_here", targetName));
                        renderer.sendPrompt(currentRoomName);
                        return;
                    }
                }
                following = targetName;
                TelnetCodec.sendLine(out, catalog.get("telnet.follow_start", targetName));
            }
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
    }

    private void handleAfk(String message, OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if (afkMessage != null) {
                // Toggle off
                afkMessage = null;
                TelnetCodec.sendLine(out, catalog.get("telnet.afk_cleared"));
            } else {
                afkMessage = message;
                TelnetCodec.sendLine(out, catalog.get("telnet.afk_set", message));
            }
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
    }

    private void handleBrief(OutputStream out) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        briefMode = !briefMode;
        try {
            TelnetCodec.sendLine(out, briefMode
                ? catalog.get("telnet.brief_on")
                : catalog.get("telnet.brief_off"));
            renderer.sendPrompt(currentRoomName);
        } catch (IOException ignored) {}
    }

    private void cleanup() {
        // Close any active remote-zone proxy so the remote sees a clean
        // session-closed signal rather than a dangling pipe.
        if (remoteZoneSession != null && remoteZoneSession.isActive()) {
            try { remoteZoneSession.close(); } catch (Exception ignored) {}
            remoteZoneSession = null;
        }
        if (connectionRegistry != null) {
            connectionRegistry.unregister(sessionId);
        }
        // Suppress the room departure + entity removal while the same account is
        // still present through another surface — quitting one surface must not
        // broadcast "X heads disconnect." while X is still here on another.
        // unregister() above already dropped this session.
        boolean stillPresent = connectionRegistry != null
            && connectionRegistry.hasOtherLiveSession(playerId, sessionId);
        // Leave current room
        if (currentRoomId != null && playerId != null && !stillPresent) {
            var room = RoomRegistry.get().ref(currentRoomId);
            Rooms.<RoomResponse>ask(room,
                ref -> new RoomCommand.LeaveRoom(playerId, playerName, "disconnect", ref),
                ASK_TIMEOUT
            ).thenAccept(resp -> {});
        }
        // Unregister player from EntityRegistry — keep the entity if another
        // session still holds this player present.
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null && playerId != null && !stillPresent) {
            entityRegistry.remove(playerId);
        }
        if (sessionRef != null) {
            sessionRef.tell(new ClientSessionActor.Disconnected());
        }
        try {
            socket.close();
        } catch (IOException ignored) {}
        log.info("Telnet session ended: {}", sessionId);
    }

    // --- Cross-zone transit ---

    /** Begin proxying this Telnet session to a remote zone. */
    boolean startRemoteSession(String remoteZoneId, String transitToken) {
        if (localZoneId == null || relayTransport == null || !relayTransport.isConnected()) {
            log.warn("Telnet {}: cannot start transit — relay transport unavailable", sessionId);
            return false;
        }
        if (sessionRef == null || playerId == null) {
            log.warn("Telnet {}: cannot start transit — no active session", sessionId);
            return false;
        }
        if (remoteZoneSession != null && remoteZoneSession.isActive()) {
            remoteZoneSession.close();
        }

        final var localActor = sessionRef;
        var remote = new RemoteZoneSession(
            playerId, playerName, transitToken,
            localZoneId, remoteZoneId, relayTransport,
            eventJson -> RemoteEventDecoder
                .decode(eventJson)
                .ifPresent(msg -> localActor.tell(new ClientSessionActor.SendMessage(msg))));

        TransitInventory inventory = null;
        if (inventoryService != null) {
            try {
                inventory = inventoryService.serializeForTransit(playerId, localZoneId);
            } catch (Exception e) {
                log.warn("Telnet {}: failed to serialize inventory: {}", sessionId, e.getMessage());
            }
        }
        TransitReputation reputation = null;
        var attestService = AttestationService.get();
        if (attestService != null) {
            try {
                reputation = attestService.serializeForTransit(playerId, localZoneId);
            } catch (Exception e) {
                log.warn("Telnet {}: failed to serialize reputation: {}", sessionId, e.getMessage());
            }
        }
        final var finalPlayerId = playerId;
        remote.setDeltaCallback(delta -> {
            if (inventoryService != null && delta != null && !delta.isEmpty()) {
                try {
                    inventoryService.applyTransitDelta(finalPlayerId, delta);
                } catch (Exception e) {
                    log.error("Telnet {}: failed to apply transit delta: {}",
                        sessionId, e.getMessage());
                }
            }
        });

        remote.open(inventory, reputation);
        if (!remote.isActive()) return false;
        remoteZoneSession = remote;

        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.setTraveling(playerId, remoteZoneId);
            entityRegistry.setHomeZone(playerId, localZoneId);
        }

        localActor.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Transit(0, remoteZoneId, null, transitToken,
                "You step through the portal into zone '" + remoteZoneId + "'...")));

        log.info("Telnet {}: remote session started → zone '{}'", sessionId, remoteZoneId);
        return true;
    }

    /** End the current remote session. */
    void endRemoteSession() {
        var rs = remoteZoneSession;
        if (rs == null) return;
        if (rs.isActive()) rs.close();
        remoteZoneSession = null;

        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null && playerId != null) {
            entityRegistry.setReturned(playerId);
            var notifService = NotificationService.get();
            if (notifService != null) {
                notifService.flushBuffered(playerId);
            }
        }
        final var finalRemote = rs;
        CompletableFuture
            .delayedExecutor(3, TimeUnit.SECONDS)
            .execute(finalRemote::closeDelta);

        if (sessionRef != null) {
            sessionRef.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0, "narrator",
                    "You return to the local zone.",
                    List.of(), null, "ambient")));
        }
        log.info("Telnet {}: remote session ended", sessionId);
    }

    /**
     * Translate a Telnet input line into a typed command for the remote zone
     * and forward via {@link org.wyrdsekai.between.federation.RemoteZoneSession}.
     * See WyrdShellCommand.forwardToRemote — identical shape, shared contract.
     */
    private void forwardToRemote(String line) {
        if (line == null || line.isEmpty()) return;
        var rs = remoteZoneSession;
        if (rs == null || !rs.isActive()) return;

        var lower = line.toLowerCase();
        var returnPhrase = lower.startsWith("say ") ? lower.substring(4).trim() : lower;
        if (returnPhrase.equals("travel home") || returnPhrase.equals("go home")
                || returnPhrase.equals("return") || returnPhrase.equals("return home")) {
            endRemoteSession();
            return;
        }
        var mapper = Json.mapper();
        try {
            if (lower.startsWith("say ")) {
                var payload = mapper.createObjectNode();
                payload.put("text", line.substring(4).trim());
                rs.sendCommand("say", mapper.writeValueAsString(payload));
            } else if (lower.startsWith("go ")) {
                var payload = mapper.createObjectNode();
                payload.put("direction", line.substring(3).trim());
                rs.sendCommand("go", mapper.writeValueAsString(payload));
            } else if (lower.equals("look") || lower.startsWith("look ")) {
                rs.sendCommand("look", "{}");
            } else if (lower.startsWith("take ")) {
                var payload = mapper.createObjectNode();
                payload.put("target", line.substring(5).trim());
                rs.sendCommand("take", mapper.writeValueAsString(payload));
            } else if (lower.startsWith("drop ")) {
                var payload = mapper.createObjectNode();
                payload.put("target", line.substring(5).trim());
                rs.sendCommand("drop", mapper.writeValueAsString(payload));
            } else if (lower.startsWith("use ")) {
                var payload = mapper.createObjectNode();
                payload.put("target", line.substring(4).trim());
                rs.sendCommand("use", mapper.writeValueAsString(payload));
            } else if (lower.startsWith("emote ") || lower.startsWith(":")) {
                var payload = mapper.createObjectNode();
                payload.put("text", lower.startsWith(":")
                    ? line.substring(1).trim() : line.substring(6).trim());
                rs.sendCommand("emote", mapper.writeValueAsString(payload));
            } else {
                var payload = mapper.createObjectNode();
                payload.put("text", line);
                rs.sendCommand("say", mapper.writeValueAsString(payload));
            }
        } catch (Exception e) {
            log.warn("Telnet {}: failed to forward '{}' to remote: {}",
                sessionId, line, e.getMessage());
        }
    }

    /** {@link org.wyrdsekai.server.session.ClientConnection} view of this Telnet session. */
    private final class TelnetClientConnection
            implements ClientConnection {
        @Override public String sessionId()   { return sessionId; }
        @Override public String playerId()    { return playerId; }
        @Override public String playerName()  { return playerName; }

        @Override
        public boolean startRemoteSession(String remoteZoneId, String transitToken) {
            return TelnetSession.this.startRemoteSession(remoteZoneId, transitToken);
        }

        @Override
        public void endRemoteSession() {
            TelnetSession.this.endRemoteSession();
        }

        @Override
        public boolean isProxying() {
            return remoteZoneSession != null && remoteZoneSession.isActive();
        }

        @Override
        public String currentRemoteZoneId() {
            var rs = remoteZoneSession;
            return rs != null ? rs.remoteZoneId() : null;
        }

        @Override
        public void disconnect(String reason) {
            // Link-takeover path (see ClientConnection.disconnect javadoc).
            try {
                try { renderer.sendLine(""); } catch (Exception ignored) {}
                try { renderer.sendLine("[" + reason + "]"); } catch (Exception ignored) {}
                if (playerId != null && currentRoomId != null) {
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room != null) {
                        try {
                            Rooms.<RoomResponse>ask(room,
                                ref -> new RoomCommand.LeaveRoom(playerId, playerName, "displaced", ref),
                                ASK_TIMEOUT);
                        } catch (Exception ignored) {}
                    }
                }
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        @Override
        public boolean deliverLine(String text) {
            // Tell-back leg (second-node re-verify 2026-07-11 #29): surface parity
            // with SSH/WS — a companion reply to `tell` rides this session's
            // actor through the normal render path.
            var ref = sessionRef;
            if (ref == null) return false;
            ref.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0L, "tell", text, List.of(), null, "normal")));
            return true;
        }
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

    private void handleGrantWard(String agentName, OutputStream out) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { TelnetCodec.sendLine(out, "Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { TelnetCodec.sendLine(out, "No agent named '" + agentName + "' found."); return; }
            var myStudyId = StudyProvisioner.studyRoomId(playerId);
            var equipService = EquipmentService.get();
            equipService.equipWard(agentId.get(), "ward-" + myStudyId,
                "Study Ward (" + playerName + ")", "study-ward",
                "You have access to " + playerName + "'s Study [" + myStudyId + "]",
                "carrying a warm crystal ward");
            TelnetCodec.sendLine(out, "You grant " + agentName + " a Study Ward.");
            TelnetCodec.sendLine(out, agentName + " can now enter your Study freely.");
        } catch (Exception e) {
            try { TelnetCodec.sendLine(out, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void handleRevokeWard(String agentName, OutputStream out) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { TelnetCodec.sendLine(out, "Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { TelnetCodec.sendLine(out, "No agent named '" + agentName + "' found."); return; }
            var equipService = EquipmentService.get();
            boolean removed = equipService.doffByLabel(agentId.get(), "Study Ward (" + playerName + ")");
            TelnetCodec.sendLine(out, removed
                ? "You revoke " + agentName + "'s Study Ward."
                : agentName + " doesn't have a ward to your Study.");
        } catch (Exception e) {
            try { TelnetCodec.sendLine(out, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void handleInvite(String agentName, OutputStream out) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { TelnetCodec.sendLine(out, "Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { TelnetCodec.sendLine(out, "No agent named '" + agentName + "' found."); return; }
            var eventStream = AgentEventStream.get();
            if (eventStream != null) {
                eventStream.publishAgentMessage(playerId, playerName, agentId.get(),
                    "[from " + playerName + "] You are invited to join me in " + currentRoomName + ".");
            }
            TelnetCodec.sendLine(out, "You invite " + agentName + " to join you.");
        } catch (Exception e) {
            try { TelnetCodec.sendLine(out, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }

    private void handleDismiss(String agentName, OutputStream out) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { TelnetCodec.sendLine(out, "Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { TelnetCodec.sendLine(out, "No agent named '" + agentName + "' found."); return; }
            var eventStream = AgentEventStream.get();
            if (eventStream != null) {
                eventStream.publishAgentMessage(playerId, playerName, agentId.get(),
                    "[from " + playerName + "] Please leave this room and return home.");
            }
            TelnetCodec.sendLine(out, agentName + " nods and steps out.");
        } catch (Exception e) {
            try { TelnetCodec.sendLine(out, "Failed: " + e.getMessage()); } catch (Exception ignored) {}
        }
    }
}
