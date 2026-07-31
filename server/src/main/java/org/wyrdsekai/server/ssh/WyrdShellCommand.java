package org.wyrdsekai.server.ssh;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.RemoteZoneSession;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
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
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.issue.Issue;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.item.ItemProviderRegistry;
import org.wyrdsekai.core.item.ToolItemStarterKit;
import org.wyrdsekai.core.item.HomeOwnerItemProvider;
import org.wyrdsekai.core.item.ItemScriptResponse;
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.ExamineLookup;
import org.wyrdsekai.core.room.RenameService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.StudyProvisioner;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.core.soul.BondRitual;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.core.item.StandardItemLibrary;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;
import org.wyrdsekai.server.RelayCommandBridge;
import org.wyrdsekai.server.session.ClientConnection;
import org.wyrdsekai.server.session.ClientConnectionRegistry;
import org.wyrdsekai.server.session.ClientSessionActor;
import org.wyrdsekai.server.session.RemoteEventDecoder;
import org.wyrdsekai.server.session.SessionCommands;
import org.wyrdsekai.server.session.WhoView;
import org.wyrdsekai.server.telnet.TelnetCodec;
import org.wyrdsekai.server.telnet.TelnetRenderer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * SSH shell command that bridges an SSH session to the Wyrdsekai actor system.
 * <p>
 * Implements Apache SSHD's {@link Command} interface. Each SSH connection gets one instance.
 * Reuses {@link TelnetRenderer} for output formatting and {@link TelnetCodec} for line output,
 * but reads input as plain lines (no Telnet IAC negotiation — SSH handles that).
 * <p>
 * The SSH username (from {@code ssh -p 7022 home-server}) is used as the login username.
 * Password was already verified by SSHD's password authenticator, so we look up the
 * user record and proceed directly to the session.
 */
public class WyrdShellCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(WyrdShellCommand.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(15);
    private static final String USER_START_ROOM = "study";
    private static final String GUEST_START_ROOM = "nexus";

    private final ActorSystem<?> system;
    private final AuthService authService;
    private final WardService wardService;
    private final InventoryService inventoryService;

    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;

    // Line-editor state for the interactive loop: command history (up/down
    // arrows) and Tab completion. SSH runs no canonical-mode TTY here, so the
    // shell owns editing — arrow/Tab bytes previously landed literally in the
    // command buffer (second-node, 2026-07-04: "key" garbled, arrows unusable).
    private final List<String> cmdHistory = new ArrayList<>();
    private int historyPos = 0; // == cmdHistory.size() means "the live line"
    private static final int MAX_HISTORY = 200;
    // Static command vocabulary for Tab completion; room objects/exits are
    // added dynamically at completion time.
    private static final List<String> COMPLETION_VERBS = List.of(
        "look", "go", "exits", "say", "tell", "whisper", "take", "drop", "use",
        "examine", "inventory", "actions", "home", "help", "quit", "who",
        "key", "passwd", "sessions", "logout", "travel");

    private final String sessionId;
    private volatile String playerId;
    private volatile String playerName;
    private volatile String playerRole; // "steward" or "member"
    private volatile String currentRoomId = GUEST_START_ROOM;
    private volatile String currentRoomName = "The Nexus";
    private final String locale = "en";
    private final Map<String, String> userAliases = new ConcurrentHashMap<>();

    // State for new MUD commands
    private volatile String lastTellFrom;
    private volatile boolean briefMode;
    private volatile String afkMessage;
    private volatile String following;

    private volatile ActorRef<ClientSessionActor.SessionMessage> sessionRef;
    private volatile TelnetRenderer renderer;
    private volatile boolean running = true;

    private final InviteService inviteService; // nullable

    // Cross-zone transit wiring (nullable — absent when federation is off).
    private volatile String localZoneId;
    private volatile RelaySessionTransport relayTransport;
    private volatile ClientConnectionRegistry connectionRegistry;
    private volatile RemoteZoneSession remoteZoneSession;

    // Scripted-item invocation wiring — parity with WyrdWebSocket.tryInvokeCarriedScript.
    // Without these, SSH users holding pinned scripted furnishings (Embers, Board, …)
    // can't invoke them via `use <name>` / `examine <name>`.
    private volatile HomeClient homeClient;
    private volatile FederationService federationService;
    private volatile BondRitual bondRitual;
    // Resolver wiring is REQUIRED: template items are `inherit("std/…")` stubs whose
    // invoke() lives in the base script — without the resolver, inherit() is a no-op and
    // `use <carried item>` fails with "no invoke() function" (second-node 2026-07-09).
    private static final StandardItemLibrary STD_ITEM_LIBRARY =
        new StandardItemLibrary(CompanionActor.stdScriptsRoot());
    private final ItemScriptExecutor itemScriptExecutor = newItemExecutor();

    private static ItemScriptExecutor newItemExecutor() {
        var ex = new ItemScriptExecutor();
        ex.setScriptResolver(STD_ITEM_LIBRARY::resolveBaseScript);
        return ex;
    }

    public WyrdShellCommand(ActorSystem<?> system,
                            AuthService authService, WardService wardService,
                            InventoryService inventoryService) {
        this(system, authService, null, wardService, inventoryService);
    }

    public WyrdShellCommand(ActorSystem<?> system,
                            AuthService authService,
                            InviteService inviteService,
                            WardService wardService,
                            InventoryService inventoryService) {
        this.system = system;
        this.authService = authService;
        this.inviteService = inviteService;
        this.wardService = wardService;
        this.inventoryService = inventoryService;
        this.sessionId = "ssh-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Wire cross-zone transit prerequisites. Called by {@link SshAdapter} when
     * federation is configured. Without these the SSH client can still do
     * everything else; only {@code say travel <zone>} is unavailable.
     */
    public void setTransitContext(String localZoneId,
                                  RelaySessionTransport relayTransport,
                                  ClientConnectionRegistry registry) {
        this.localZoneId = localZoneId;
        this.relayTransport = relayTransport;
        this.connectionRegistry = registry;
    }

    /**
     * Wire the scripted-item invocation context so SSH users can
     * {@code use embers} / {@code examine board} / {@code read quill} against
     * pinned Study furnishings. Optional — if unset, scripted items remain
     * inert and {@code use} falls through to room handling.
     */
    public void setScriptContext(HomeClient homeClient,
                                 FederationService federationService,
                                 BondRitual bondRitual) {
        this.homeClient = homeClient;
        this.federationService = federationService;
        this.bondRitual = bondRitual;
    }

    @Override
    public void setInputStream(InputStream in) { this.in = in; }

    @Override
    public void setOutputStream(OutputStream out) { this.out = out; }

    @Override
    public void setErrorStream(OutputStream err) { this.err = err; }

    @Override
    public void setExitCallback(ExitCallback callback) { this.exitCallback = callback; }

    @Override
    public void start(ChannelSession channel, Environment env) {
        Thread.startVirtualThread(() -> {
            try {
                runSession(channel, env);
            } catch (Exception e) {
                if (running) {
                    log.error("SSH session {} error: {}", sessionId, e.getMessage());
                }
            } finally {
                cleanup();
                exitCallback.onExit(0);
            }
        });
    }

    @Override
    public void destroy(ChannelSession channel) {
        running = false;
    }

    /**
     * True iff the SSH client connected from a loopback address.
     * Used to gate first-steward {@code create} so a hostile LAN device
     * can't race the steward to first-create on a fresh install.
     */
    private volatile boolean clientIsLoopback;

    private void runSession(ChannelSession channel, Environment env) throws IOException {
        this.activeChannel = channel;
        // Determine if client connected from loopback (localhost). Used by
        // authenticate() to gate the first-steward create path.
        try {
            var addr = channel.getSession().getClientAddress();
            if (addr instanceof InetSocketAddress isa && isa.getAddress() != null) {
                clientIsLoopback = isa.getAddress().isLoopbackAddress();
            }
        } catch (Exception ignored) {
            // Default false — fail closed.
        }

        // Resolve the authenticated SSH username
        var sshUsername = env.getEnv().get(Environment.ENV_USER);

        var catalog = ScriptMessageCatalog.forLang(locale);

        // GMCP is never used over SSH — SSH handles terminal negotiation natively
        renderer = new TelnetRenderer(out, false);

        // Welcome banner
        sendLine("");
        sendLine(catalog.get("telnet.welcome"));
        sendLine("");

        // Authenticate — SSH already verified the password/key, so look up the user directly.
        // SECURITY: for PUBKEY auth, identity is the KEY's OWNER (resolved by the
        // authenticator into PUBKEY_OWNER_USERID) — NOT the typed ssh username.
        // Otherwise any authorized key could `ssh steward@host` and impersonate the
        // steward. Password auth IS verified against the typed username by
        // AuthService.login, so that path resolves by username.
        String sshPlayerId = null;
        String sshPlayerName = null;
        Optional<AuthService.User> authedUser = Optional.empty();
        var pubkeyOwnerId = channel.getSession().getAttribute(SshAdapter.PUBKEY_OWNER_USERID);
        if (pubkeyOwnerId != null) {
            authedUser = authService.findUser(pubkeyOwnerId);
        } else if (sshUsername != null && !sshUsername.isEmpty()) {
            authedUser = authService.findUserByUsername(sshUsername);
        }
        if (authedUser.isPresent()) {
            var u = authedUser.get();
            sshPlayerId = u.id();
            sshPlayerName = u.displayName();
            this.playerRole = u.role();
            sendLine("Logged in as " + sshPlayerName + " (" + u.role() + ").");
            sendLine("");
        }

        // Resolved after authenticate() — must NOT capture as final before auth.
        // Pubkey auth sets sshPlayerId before this point; password auth sets this.playerId in authenticate().
        // Defer resolution to after auth completes.

        // Skip login prompt if SSH already authenticated the user
        if (sshPlayerId == null) {
            // F4 phase 2: invite-token-as-password — if the SSH session was
            // authenticated via invite (password matched a pending invite for
            // this username), run the create-and-redeem flow inline rather
            // than dropping into the legacy authenticate() prompt.
            var invite = channel.getSession().getAttribute(SshAdapter.INVITE_AUTH_KEY);
            if (invite != null) {
                if (!handleInviteRedemption(invite, sshUsername, catalog)) {
                    return;
                }
                sshPlayerId = this.playerId;
                sshPlayerName = this.playerName;
            } else if (authService.isFirstUser() && pubkeyAcceptedThisSession()) {
                // F4 phase 2: first-steward bootstrap by pubkey. The operator
                // pre-placed their SSH pubkey in ~/.wyrdsekai/authorized_keys
                // before first connect; SSH layer accepted it; no users yet,
                // so this is the bootstrap moment. Run inline steward
                // registration without password.
                if (!handleFirstStewardBootstrap(sshUsername, catalog)) {
                    return;
                }
                sshPlayerId = this.playerId;
                sshPlayerName = this.playerName;
            } else {
                // Show login instructions
                sendLine(catalog.get("telnet.login_help1"));
                sendLine(catalog.get("telnet.login_help2"));
                sendLine(catalog.get("telnet.login_help_redeem"));
                sendLine(catalog.get("telnet.login_help3"));
                sendLine("");

                // Authentication loop (same as TelnetSession)
                if (!authenticate()) {
                    return;
                }
            }
        }

        // Resolve player identity AFTER auth completes.
        // Pubkey: sshPlayerId set from SSH env. Password: this.playerId set by authenticate().
        // Write back to instance fields so handleInput() methods can access them.
        this.playerId = sshPlayerId != null ? sshPlayerId : this.playerId;
        this.playerName = sshPlayerName != null ? sshPlayerName : this.playerName;
        final String playerId = this.playerId;
        final String playerName = this.playerName;

        // Parental time limit: a member whose daily hours are spent cannot
        // start a new session (mirrors the failed-auth early return above).
        // No-op ALLOW when the service isn't wired or for anonymous ids.
        var parentalSvc = ParentalControlService.get();
        if (parentalSvc != null && playerId != null && !playerId.startsWith("anon-")) {
            var minutesLeft = parentalSvc.minutesRemaining(playerId);
            if (minutesLeft != null && minutesLeft <= 0) {
                sendLine("Today's hours in the world are spent — the household clock says rest.");
                return;
            }
        }

        // Maintenance mode: while the dial is on, only the steward may start
        // a session (mirrors the parental gate above). No-op ALLOW when the
        // service isn't wired (tests, bare boots).
        var maintenanceSvc = MaintenanceService.get();
        if (maintenanceSvc != null && !maintenanceSvc.allowsLogin(playerId)) {
            sendLine(maintenanceSvc.refusalLine());
            return;
        }

        // Create session actor
        sessionRef = system.<ClientSessionActor.SessionMessage>systemActorOf(
            ClientSessionActor.create(sessionId, json -> {
                try {
                    var msg = Json.mapper().readValue(json, S2CMessage.class);
                    renderer.render(msg);
                    if (msg instanceof S2CMessage.RoomState rs) {
                        currentRoomId = rs.room().roomId();
                        currentRoomName = rs.room().name();
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                } catch (Exception e) {
                    log.error("SSH render error for session {}", sessionId, e);
                }
            }),
            "ssh-session-" + sessionId,
            Props.empty());

        // login landing branches on residency:
        //   resident of this zone → their Study
        //   authenticated non-resident → Docks (visitor surface)
        //   guest/anonymous         → Nexus (shared hub)
        String startRoomId;
        if (playerId != null && !playerId.startsWith("anon-")) {
            var residency = ResidencyStore.get();
            // If ResidencyStore isn't initialised yet (early boot, tests) or
            // localZoneId is unknown (non-federated dev setup), fall back to
            // the pre-§25 behaviour of always landing in the Study.
            boolean isResident = residency == null || localZoneId == null
                || residency.isResident(playerId, localZoneId);
            if (isResident) {
                startRoomId = StudyProvisioner.studyRoomId(playerId);
                // Study provisioning fires at residency-grant
                // time via ResidencyStore's hook, not here. Only fall back to
                // the legacy login-time provisioning when the store is absent
                // (tests / non-federated dev).
                if (residency == null) {
                    provisionStudy(playerId, playerName);
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } else {
                // Identity known but no residency in this zone — land in the
                // Docks as a visitor. Can `travel <contact>:<label>` or
                // `knock <steward>` to request residency.
                startRoomId = "docks";
            }
        } else {
            startRoomId = GUEST_START_ROOM;
        }
        currentRoomId = startRoomId;
        var startRoom = RoomRegistry.get().ref(startRoomId);
        // Study rooms are lazy: after a server restart the room is in Pekko's
        // event journal but no live actor exists until ProvisionStudy spawns
        // one. If ref() returns null for a resident's Study, re-fire the
        // provisioning path (idempotent — ZoneGuardian dedupes) and retry.
        // Fall back to the Nexus if the room still isn't live after that,
        // so the session never lands on a null RoomRef (causes JoinRoom NPE).
        if (startRoom == null && playerId != null && !playerId.startsWith("anon-")) {
            log.warn("SSH {}: room '{}' not live — re-provisioning Study for {}",
                sessionId, startRoomId, playerName);
            provisionStudy(playerId, playerName);
            for (int attempt = 0; attempt < 10 && startRoom == null; attempt++) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                startRoom = RoomRegistry.get().ref(startRoomId);
            }
            if (startRoom == null) {
                log.warn("SSH {}: Study still not live after 2s — falling back to Nexus", sessionId);
                startRoomId = GUEST_START_ROOM;
                currentRoomId = startRoomId;
                startRoom = RoomRegistry.get().ref(startRoomId);
            }
        }
        final var finalStartRoom = startRoom;
        sessionRef.tell(new ClientSessionActor.JoinRoom(finalStartRoom, playerId));
        // Pull persisted description from authService so EnterRoom carries the
        // user's set description into the room's Entity record. Without this,
        // every login overwrites the description back to "" via the no-desc
        // convenience constructor (RoomCommand.java:55-58), making `describe
        // me X` useless across sessions.
        final var enterDesc = authService.findUser(playerId)
            .map(u -> u.description() != null ? u.description() : "")
            .orElse("");
        Rooms.<RoomResponse>ask(finalStartRoom,
            ref -> new RoomCommand.EnterRoom(playerId, playerName, "player",
                enterDesc, "nowhere", "en", ref),
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
            log.error("Failed to initialize SSH session {}: {}", sessionId, ex.getMessage());
            return null;
        });

        log.info("SSH session started: {} as {}", sessionId, playerId);

        // Register with transport-agnostic client registry so federation
        // transit (from room scripts) can reach this session by playerId.
        if (connectionRegistry != null) {
            connectionRegistry.register(new SshClientConnection());
        }

        // Register player in EntityRegistry so agents can find them
        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null && playerId != null) {
            entityRegistry.enter(playerId, playerName, "player", currentRoomId);
        }

        // On-login flush: notify EVERY companion (multi-companion households) —
        // temporal insights + the come-to-Study greeting both ride this signal.
        // The old `break` after the first agent left siblings unaware of logins.
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

        // Main input loop — character-by-character with echo (SSH doesn't auto-echo)
        var lineBuffer = new StringBuilder();
        int ch;
        while (running && (ch = in.read()) != -1) {
            if (ch == '\n' || ch == '\r') {
                // Echo newline
                out.write('\r');
                out.write('\n');
                out.flush();
                var line = lineBuffer.toString().strip();
                lineBuffer.setLength(0);
                if (!line.isEmpty()) {
                    pushHistory(line);
                    handleInput(line);
                }
                historyPos = cmdHistory.size();
                // Send prompt after processing
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } else if (ch == 4) {
                // Ctrl-D (EOT). SSH channels don't run a canonical-mode TTY, so the
                // kernel doesn't translate ^D into EOF on stdin — it arrives as raw
                // byte 4. Honor the standard terminal convention: ^D on an empty
                // line = quit (detach THIS channel, like `quit`/`exit`); ^D
                // mid-line = ignored. The detach hint notes other live surfaces.
                if (lineBuffer.length() == 0) {
                    try {
                        sendLine("");
                        var hint = SessionCommands.detachHint(connectionRegistry, playerId, sessionId, locale);
                        sendLine(hint != null ? hint
                            : ScriptMessageCatalog.forLang(locale).get("telnet.goodbye"));
                    } catch (IOException ignored) {}
                    return;
                }
            } else if (ch == 17) {
                // Ctrl-Q = logout (leave EVERY channel, like `logout`/`quitall`).
                // Drop the account's other surfaces, then return so the last
                // cleanup fires the room departure exactly once.
                SessionCommands.logoutOthers(connectionRegistry, playerId, sessionId);
                try {
                    sendLine("");
                    sendLine(ScriptMessageCatalog.forLang(locale).get("session.logout"));
                } catch (IOException ignored) {}
                return;
            } else if (ch == 12) {
                // Ctrl-L = redraw the current room (re-look) + fresh prompt.
                try { sendLine(""); } catch (IOException ignored) {}
                if (currentRoomId != null) {
                    askRoomWithInventory(currentRoomId,
                        ref -> new RoomCommand.LookRoom(playerId, ref), "look");
                }
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } else if (ch == 127 || ch == 8) {
                // Backspace/delete — remove last char and echo erase
                if (lineBuffer.length() > 0) {
                    lineBuffer.deleteCharAt(lineBuffer.length() - 1);
                    out.write('\b');
                    out.write(' ');
                    out.write('\b');
                    out.flush();
                }
            } else if (ch == 27) {
                // ESC — an arrow/nav sequence: ESC [ <final> (or ESC O <final>).
                // Up/Down walk history; Left/Right/others are swallowed so they
                // don't land in the buffer. A BARE Escape keypress (no follow
                // bytes) must not block waiting on in.read(): terminals send a
                // real arrow as one burst, so if nothing is buffered right after
                // ESC it was the Escape key itself — ignore it. (Guards against
                // the input loop hanging until the next keystroke.)
                if (in.available() > 0) {
                    int b1 = in.read();
                    if ((b1 == '[' || b1 == 'O') && in.available() > 0) {
                        int b2 = in.read();
                        if (b2 == 'A') {
                            replaceLine(lineBuffer, historyPrev(lineBuffer.toString()));
                        } else if (b2 == 'B') {
                            replaceLine(lineBuffer, historyNext());
                        }
                        // 'C'/'D' (right/left) and everything else: ignored.
                    }
                }
            } else if (ch == '\t') {
                // Tab — complete the last token against verbs + room objects/exits.
                completeTab(lineBuffer);
            } else if (ch >= 32) {
                // Printable character — echo and buffer
                lineBuffer.append((char) ch);
                out.write(ch);
                out.flush();
            }
        }
    }

    /** Append a command to history (dedup consecutive, capped). */
    private void pushHistory(String line) {
        if (!cmdHistory.isEmpty() && cmdHistory.get(cmdHistory.size() - 1).equals(line)) {
            return;
        }
        cmdHistory.add(line);
        while (cmdHistory.size() > MAX_HISTORY) cmdHistory.remove(0);
    }

    /** Up-arrow: the previous history entry, remembering the in-progress line. */
    private String historyPrev(String liveLine) {
        if (cmdHistory.isEmpty()) return null;
        if (historyPos > cmdHistory.size()) historyPos = cmdHistory.size();
        if (historyPos == cmdHistory.size()) pendingLiveLine = liveLine; // stash unsent text
        if (historyPos > 0) historyPos--;
        return cmdHistory.get(historyPos);
    }

    /** Down-arrow: the next entry, or the stashed live line at the bottom. */
    private String historyNext() {
        if (cmdHistory.isEmpty()) return null;
        if (historyPos < cmdHistory.size()) historyPos++;
        if (historyPos >= cmdHistory.size()) {
            historyPos = cmdHistory.size();
            return pendingLiveLine != null ? pendingLiveLine : "";
        }
        return cmdHistory.get(historyPos);
    }

    private String pendingLiveLine = "";

    /**
     * Erase the currently-echoed line and replace it (buffer + display) with
     * {@code newText}. Null means "no change" (empty history). Uses backspace-
     * space-backspace to clear, which works on any VT100-ish terminal.
     */
    private void replaceLine(StringBuilder lineBuffer, String newText) throws IOException {
        if (newText == null) return;
        int old = lineBuffer.length();
        for (int i = 0; i < old; i++) { out.write('\b'); out.write(' '); out.write('\b'); }
        lineBuffer.setLength(0);
        lineBuffer.append(newText);
        out.write(newText.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Tab completion on the last whitespace-delimited token. Candidates:
     * command verbs (when completing the first word) plus the current room's
     * object names, entity names, and exit directions. One match → complete it
     * (+ trailing space); several → list them and redraw the line.
     */
    private void completeTab(StringBuilder lineBuffer) throws IOException {
        var text = lineBuffer.toString();
        int sp = text.lastIndexOf(' ');
        var prefix = text.substring(sp + 1);
        if (prefix.isEmpty()) return;
        var lower = prefix.toLowerCase();

        var candidates = new ArrayList<String>();
        boolean firstWord = sp < 0;
        if (firstWord) candidates.addAll(COMPLETION_VERBS);
        // Room-context vocabulary — object/entity names and exit directions.
        for (var name : completionRoomVocab()) {
            if (!candidates.contains(name)) candidates.add(name);
        }
        var matches = new ArrayList<String>();
        for (var c : candidates) {
            if (c.toLowerCase().startsWith(lower) && !matches.contains(c)) matches.add(c);
        }
        if (matches.isEmpty()) return;
        if (matches.size() == 1) {
            var completed = text.substring(0, sp + 1) + matches.get(0) + " ";
            replaceLine(lineBuffer, completed);
            return;
        }
        // Multiple: show them, then reprint the prompt + current buffer.
        out.write('\r'); out.write('\n');
        out.write(("  " + String.join("   ", matches)).getBytes(StandardCharsets.UTF_8));
        out.write('\r'); out.write('\n');
        out.flush();
        renderer.sendPrompt(currentRoomName, currentZoneLabel());
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Object names, entity names, and exit directions in the current room. */
    private List<String> completionRoomVocab() {
        var vocab = new ArrayList<String>();
        var snap = lastRoomSnapshot;
        if (snap != null) {
            if (snap.objects() != null) for (var o : snap.objects()) if (o.name() != null) vocab.add(o.name());
            if (snap.entities() != null) for (var e : snap.entities()) if (e.name() != null) vocab.add(e.name());
            if (snap.exits() != null) for (var ex : snap.exits()) if (ex.direction() != null) vocab.add(ex.direction());
        }
        return vocab;
    }

    /**
     * Run the invite-redemption flow for an SSH session that authenticated via
     * invite-token-as-password. Prompts the operator for a password, registers
     * the new account using the invite's intendedName + role, and consumes
     * the invite atomically. Side effects: sets {@code playerId},
     * {@code playerName}, {@code playerRole} on success.
     *
     * <p> phase 2.</p>
     *
     * @return true if account creation succeeded; false to abort the session.
     */
    /**
     * Channel for the active SSH session — populated by runSession and
     * used by handleInviteRedemption to access session attributes (like
     * the offered SSH pubkey, which we register so the user's next login
     * is keyless).
     */
    private volatile ChannelSession activeChannel;

    /**
     * True if the SSH connection was accepted via pubkey (i.e. the offered
     * key matched a line in {@code authorized_keys}). Detected by checking
     * whether the SSH session has a successfully-authenticated key — Apache
     * MINA SSHD exposes this via {@code getServerSession().getServerAuthFactories()}
     * but more reliably we look at whether the offered pubkey ended up
     * being accepted (the authenticator returned true) by re-running the
     * loaded-keys check here. Cheap and correct.
     */
    private boolean pubkeyAcceptedThisSession() {
        if (activeChannel == null) return false;
        var offered = activeChannel.getSession().getAttribute(SshAdapter.OFFERED_PUBKEY_KEY);
        if (offered == null) return false;
        // The offered key was stashed by the publickey authenticator on every
        // attempt. If the authenticator also returned true (key was in
        // authorized_keys), Apache MINA SSHD wouldn't have invoked the
        // password authenticator. So if we got here without an INVITE_AUTH_KEY
        // and there's an offered key on the session, pubkey auth was the
        // accepted method.
        return activeChannel.getSession().getAttribute(SshAdapter.INVITE_AUTH_KEY) == null;
    }

    /**
     * First-steward bootstrap via pre-placed SSH pubkey. The operator drops
     * their public key in {@code ~/.wyrdsekai/authorized_keys} BEFORE first
     * connect; SSH accepts it; this method registers the first user (steward)
     * with a password they choose interactively. The pubkey stays in the
     * authorized_keys file so subsequent logins remain keyless.
     *
     * <p> phase 2.</p>
     */
    private boolean handleFirstStewardBootstrap(
            String sshUsername,
            ScriptMessageCatalog catalog) throws IOException {
        sendLine("");
        sendLine("First-steward bootstrap — your SSH key is trusted, no users exist yet.");
        sendLine("This account becomes the household steward.");
        sendLine("");

        // Username: default to the SSH username they connected with.
        var defaultUsername = (sshUsername != null && !sshUsername.isBlank())
            ? sshUsername.trim() : "steward";
        sendRaw("Username [" + defaultUsername + "]: ");
        var nameLine = readLine();
        if (nameLine == null) return false;
        var username = nameLine.trim().isEmpty() ? defaultUsername : nameLine.trim();

        sendLine("Choose a password: at least 6 characters. It will not echo as you type;");
        sendLine("press Enter when done, then type it once more to confirm.");
        sendRaw("Password: ");
        var pw1 = readLine();
        if (pw1 == null || pw1.isEmpty()) {
            sendLine("Aborted.");
            return false;
        }
        sendRaw("Confirm:  ");
        var pw2 = readLine();
        if (pw2 == null || !pw1.equals(pw2)) {
            sendLine("Passwords don't match. Try again from a fresh ssh session.");
            return false;
        }
        if (pw1.length() < 6) {
            sendLine("Password too short (min 6 characters).");
            return false;
        }

        var session = authService.register(username, pw1, null);
        if (session.isEmpty()) {
            sendLine("Registration failed (username taken or invalid).");
            return false;
        }
        var s = session.get();
        var user = authService.findUser(s.userId()).orElseThrow();
        this.playerId = user.id();
        this.playerName = user.displayName();
        this.playerRole = user.role();
        // BIND the pre-placed key to this steward account, so it's honored
        // per-account going forward (the global authorized_keys file is only
        // consulted while isFirstUser() — now false — closing the impersonation
        // window). If the offered key can't be captured the global file still
        // covers this steward until they re-register.
        if (activeChannel != null) {
            var offered = activeChannel.getSession().getAttribute(SshAdapter.OFFERED_PUBKEY_KEY);
            var keyLine = SshAdapter.sshKeyLine(offered);
            if (keyLine != null) authService.addSshKey(user.id(), keyLine, user.username() + "@bootstrap");
        }
        log.info("First steward bootstrapped via pubkey: '{}' (role={})",
            user.username(), user.role());
        sendLine("");
        sendLine("Steward account created: " + user.username());
        sendLine("Your SSH key remains trusted. Next login is keyless.");
        sendLine("");
        return true;
    }

    private boolean handleInviteRedemption(
            InviteService.Invite invite,
            String sshUsername,
            ScriptMessageCatalog catalog) throws IOException {

        sendLine("");
        if ("steward".equals(invite.role())) {
            sendLine("Welcome — this is the steward-bootstrap invite.");
        } else {
            sendLine("Welcome, " + invite.intendedName() + ". Redeeming invite...");
        }
        sendLine("Choose a password for your account: at least 6 characters.");
        sendLine("It will not echo as you type; press Enter when done, then");
        sendLine("type it once more to confirm.");
        sendLine("");

        sendRaw("Password: ");
        var pw1 = readLine();
        if (pw1 == null || pw1.isEmpty()) {
            sendLine("Aborted.");
            return false;
        }
        sendRaw("Confirm:  ");
        var pw2 = readLine();
        if (pw2 == null || !pw1.equals(pw2)) {
            sendLine("Passwords don't match. Try again from a fresh ssh session.");
            return false;
        }
        if (pw1.length() < 6) {
            sendLine("Password too short (min 6 characters). Try again from a fresh ssh session.");
            return false;
        }

        // #4 (2026-07-19 OSS hardening) — CLAIM the invite atomically BEFORE
        // creating the account. Previously a lost race left the account created
        // but the invite un-consumed; now the loser creates nothing.
        var claimToken = "claim:" + UUID.randomUUID();
        if (inviteService != null) {
            var claimed = inviteService.claimInvite(invite.code(), claimToken);
            if (claimed.isEmpty()) {
                sendLine("This invite was just used. Ask the steward to mint a new one.");
                return false;
            }
        }
        // Register the new account with the invite's intended role.
        // #4-followup (adversarial review) — release the claim on a register()
        // THROW too, else a non-UNIQUE failure orphans the invite forever.
        AuthService.Session s;
        try {
            var session = authService.register(invite.intendedName(), pw1, null, invite.role());
            if (session.isEmpty()) {
                if (inviteService != null) inviteService.releaseClaim(claimToken);
                sendLine("Registration failed (username taken). Ask the steward to mint a new invite.");
                return false;
            }
            s = session.get();
            if (inviteService != null) {
                inviteService.rebindClaim(claimToken, s.userId());
            }
        } catch (RuntimeException e) {
            if (inviteService != null) inviteService.releaseClaim(claimToken);
            sendLine("Registration failed unexpectedly. Ask the steward to mint a new invite.");
            log.warn("SSH invite redeem failed after claim: {}", e.toString());
            return false;
        }
        var user = authService.findUser(s.userId()).orElseThrow();
        this.playerId = user.id();
        this.playerName = user.displayName();
        this.playerRole = user.role();
        log.info("SSH invite redeemed: user '{}' (role={}) created via invite {}",
            user.username(), user.role(), invite.id());
        // Record the join in the steward audit log so the household's audit
        // ledger (Study: `use audit log`) is never mysteriously empty on a
        // fresh install — the first steward's own arrival is the first entry.
        var audit = StewardAuditLog.get();
        if (audit != null) {
            audit.log(user.id(), user.username(), StewardAuditLog.ActionType.MEMBER_ADD,
                user.id(), user.username() + " joined as " + user.role()
                    + " (invite redemption)", true);
        }
        sendLine("");
        sendLine("Account created: " + user.username() + " (role=" + user.role() + ")");

        // Capture the SSH client's offered pubkey (if any) and BIND it to this
        // account so subsequent logins are keyless AND scoped to this user only
        // (per-account, not the old global accept-list).
        if (activeChannel != null) {
            var offered = activeChannel.getSession().getAttribute(SshAdapter.OFFERED_PUBKEY_KEY);
            if (offered != null) {
                var keyLine = SshAdapter.sshKeyLine(offered);
                if (keyLine != null && authService.addSshKey(user.id(), keyLine,
                        user.username() + "@invite-" + invite.id().substring(0, 8))) {
                    sendLine("SSH key registered. Next login: ssh " + user.username() + "@host (no password needed).");
                }
            }
        }
        sendLine("");
        return true;
    }

    /**
     * Authentication loop — same flow as TelnetSession.
     * Supports: connect/login &lt;username&gt; &lt;password&gt;, create/register
     * &lt;username&gt; &lt;password&gt; [name] (only when no users exist),
     * redeem &lt;6-word-code&gt; &lt;username&gt; &lt;password&gt;, quit.
     * Anonymous 'guest' entry was removed.
     */
    private boolean authenticate() throws IOException {
        var catalog = ScriptMessageCatalog.forLang(locale);
        for (int attempts = 0; attempts < 5; attempts++) {
            sendRaw("login> ");
            var line = readLine();
            if (line == null) return false;

            var parts = line.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) continue;

            switch (parts[0].toLowerCase()) {
                // 'guest' removed. Anonymous
                // entry is unsafe once zones become public/federated.
                case "connect", "login" -> {
                    if (parts.length < 3) {
                        sendLine(catalog.get("telnet.usage_connect"));
                        continue;
                    }
                    var session = authService.login(parts[1], parts[2]);
                    if (session.isEmpty()) {
                        sendLine(catalog.get("telnet.invalid_credentials"));
                        continue;
                    }
                    var s = session.get();
                    var user = authService.findUser(s.userId()).orElseThrow();
                    playerId = user.id();
                    playerName = user.displayName();
                    playerRole = user.role();
                    sendLine(catalog.get("telnet.welcome_back", playerName));
                    return true;
                }
                case "create", "register" -> {
                    // 'create' is now gated to
                    // (no users yet) AND (client connected from loopback).
                    // The loopback gate prevents a hostile LAN device from
                    // racing the steward to first-create on a fresh install.
                    if (!authService.isOpenRegistrationAllowed()) {
                        sendLine(catalog.get("telnet.household_invite_required"));
                        sendLine(catalog.get("telnet.household_use_redeem"));
                        continue;
                    }
                    if (!clientIsLoopback) {
                        sendLine("First-steward bootstrap is only allowed from localhost.");
                        sendLine("Connect via 'ssh <user>@127.0.0.1 -p 7022' from the host running the server.");
                        continue;
                    }
                    if (parts.length < 3) {
                        sendLine(catalog.get("telnet.usage_create"));
                        continue;
                    }
                    var isFirst = authService.isFirstUser();
                    var displayName = parts.length > 3 ? parts[3] : null;
                    var session = authService.register(parts[1], parts[2], displayName);
                    if (session.isEmpty()) {
                        sendLine(catalog.get("telnet.username_taken"));
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
                        sendLine(catalog.get("telnet.steward_created"));
                        sendLine(catalog.get("telnet.steward_invite_hint"));
                        log.info("First user created via SSH: {} (steward)", user.username());
                    }
                    sendLine(catalog.get("telnet.account_created", playerName));
                    return true;
                }
                case "redeem" -> {
                    if (inviteService == null) {
                        sendLine(catalog.get("telnet.invite_not_available"));
                        continue;
                    }
                    if (parts.length < 9) {
                        sendLine(catalog.get("telnet.invite_usage"));
                        continue;
                    }
                    var code = String.join(" ", parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]);
                    var username = parts[7];
                    var password = parts[8];
                    // #4 (2026-07-19 OSS hardening) — claim-before-create.
                    var claimToken = "claim:" + UUID.randomUUID();
                    var claimed = inviteService.claimInvite(code.toLowerCase(), claimToken);
                    if (claimed.isEmpty()) {
                        sendLine(catalog.get("telnet.invite_invalid"));
                        continue;
                    }
                    var inviteRole = claimed.get().role();
                    // #4-followup — release the claim on a register() THROW too.
                    AuthService.Session s;
                    try {
                        var session = authService.register(username, password, null, inviteRole);
                        if (session.isEmpty()) {
                            inviteService.releaseClaim(claimToken);
                            sendLine(catalog.get("telnet.username_taken"));
                            continue;
                        }
                        s = session.get();
                        inviteService.rebindClaim(claimToken, s.userId());
                    } catch (RuntimeException e) {
                        inviteService.releaseClaim(claimToken);
                        log.warn("telnet invite redeem failed after claim: {}", e.toString());
                        sendLine(catalog.get("telnet.username_taken"));
                        continue;
                    }
                    var user = authService.findUser(s.userId()).orElseThrow();
                    playerId = user.id();
                    playerName = user.displayName();
                    playerRole = user.role();
                    sendLine(catalog.get("telnet.invite_accepted", playerName));
                    return true;
                }
                case "quit" -> { return false; }
                default -> sendLine(catalog.get("telnet.unknown_login_cmd"));
            }
        }
        sendLine(catalog.get("telnet.too_many_attempts"));
        return false;
    }

    /**
     * Zone label for the input prompt — the remote zone while proxied, else
     * the local zone. Surfaces in the MUD prompt so users can distinguish
     * "The Docks @alpha>" from "The Docks @beta>" at a glance.
     */
    private String currentZoneLabel() {
        var rs = remoteZoneSession;
        if (rs != null && rs.isActive()) return rs.remoteZoneId();
        return localZoneId != null ? localZoneId : "";
    }

    private void handleInput(String input) {
        // Proxy mode: tunnel this input to the remote zone. Parse locally
        // just enough to classify the command type (RemoteZoneSession expects
        // typed commands), then forward. The ::local escape hatch ends the
        // proxy client-side without touching the remote (use if the remote
        // hangs).
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
                // Detach THIS channel only. If the account stays present on other
                // surfaces, note that instead of a bare goodbye.
                try {
                    var catalog = ScriptMessageCatalog.forLang(locale);
                    var hint = SessionCommands.detachHint(connectionRegistry, playerId, sessionId, locale);
                    sendLine(hint != null ? hint : catalog.get("telnet.goodbye"));
                } catch (IOException ignored) {}
                running = false;
                // Force the input loop to unblock from in.read() by asking
                // the SSH server to tear down the channel. Without this,
                // the session lingers until the client sends another byte.
                if (exitCallback != null) {
                    try { exitCallback.onExit(0); } catch (Exception ignored) {}
                }
            }

            case ParsedCommand.Logout lo -> {
                // End the whole presence: drop the account's other channels, then
                // exit this one so the room departure fires exactly once.
                SessionCommands.logoutOthers(connectionRegistry, playerId, sessionId);
                try {
                    sendLine(ScriptMessageCatalog.forLang(locale).get("session.logout"));
                } catch (IOException ignored) {}
                running = false;
                if (exitCallback != null) {
                    try { exitCallback.onExit(0); } catch (Exception ignored) {}
                }
            }

            case ParsedCommand.Sessions se -> {
                try {
                    if (SessionCommands.isKill(se.args())) {
                        sendLine(SessionCommands.killByIndex(connectionRegistry, playerId, locale, se.args()));
                    } else {
                        sendLine(SessionCommands.render(connectionRegistry, playerId, sessionId, locale));
                    }
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Key k -> {
                try { sendLine(SessionCommands.key(authService, playerId, k.args())); }
                catch (IOException ignored) {}
            }

            case ParsedCommand.Look l ->
                askRoomWithInventory(currentRoomId,
                    ref -> new RoomCommand.LookRoom(playerId, ref), "look");

            case ParsedCommand.Tell tell -> handleTell(tell.targetName(), tell.text());

            case ParsedCommand.Go go -> handleGo(go.direction());

            case ParsedCommand.Emote emote -> {
                if (checkWard(currentRoomId, "speak")) {
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
                if (checkWard(currentRoomId, "speak")) {
                    // WhisperInRoom expects a target entity ID, not a display
                    // name. Resolve via EntityRegistry first (whisper targets
                    // must be in the same room — RoomActor double-checks).
                    var registry = EntityRegistry.get();
                    var targetId = registry != null
                        ? registry.findByName(whisper.target())
                        : Optional.<String>empty();
                    log.debug("Whisper '>{}' from {} → lookup={}",
                        whisper.target(), playerName, targetId);
                    if (targetId.isEmpty()) {
                        try { sendLine("Nobody called '" + whisper.target() + "' is here."); }
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
                if (checkWard(currentRoomId, "speak")) {
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
                if (checkWard(currentRoomId, "take")) {
                    handleTake(take.objectName());
                }
            }

            case ParsedCommand.Drop drop -> {
                if (checkWard(currentRoomId, "drop")) {
                    handleDrop(drop.objectName());
                }
            }

            case ParsedCommand.Use use -> {
                if (checkWard(currentRoomId, "use")) {
                    if (tryInvokeCarriedScript(use.objectName(), use.target())) break;
                    askRoom(currentRoomId,
                        ref -> new RoomCommand.UseObject(playerId, use.objectName(),
                            use.target(), ref), "use");
                }
            }

            case ParsedCommand.HintSelect hs -> handleHintSelect(hs.index());

            case ParsedCommand.SlashCommand sc -> handleSlashCommand(sc);

            case ParsedCommand.MapCommand mc -> {
                // Personal map: shared foundation topology + current room
                // augmented in (so Study/craft rooms show up with their
                // real exits). Fog-of-war is treated as "all visited" for
                // now — refine later future work.
                var sharedTopo = ZoneTopology.getShared();
                if (sharedTopo == null) {
                    try {
                        sendLine("(map unavailable — topology not initialised)");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                    break;
                }
                var myRoomRef = RoomRegistry.get().ref(currentRoomId);
                final int radius = mc.radius();
                if (myRoomRef == null || sharedTopo.room(currentRoomId).isPresent()) {
                    // Already on the shared map, or we can't read the local
                    // room — render from shared topo as-is.
                    try {
                        var text = sharedTopo.renderTextMap(currentRoomId, radius,
                            sharedTopo.rooms().keySet());
                        for (var line : text.split("\n")) sendLine(line);
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                    break;
                }
                // Augment: fetch the player's current room's snapshot, add
                // it as a node to a fresh topology, then render.
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
                        for (var line : text.split("\n")) sendLine(line);
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                });
            }
            case ParsedCommand.Where w -> {
                try {
                    sendLine("You are in " + currentRoomName + " (" + currentRoomId
                        + ") — zone " + currentZoneLabel() + ".");
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                } catch (IOException ignored) {}
            }
            case ParsedCommand.Exits e -> {
                var room = RoomRegistry.get().ref(currentRoomId);
                if (room == null) break;
                Rooms.<RoomResponse>ask(room,
                    ref -> new RoomCommand.LookRoom(playerId, ref),
                    ASK_TIMEOUT
                ).thenAccept(resp -> {
                    try {
                        if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                            var exits = ok.snapshot().exits();
                            if (exits == null || exits.isEmpty()) {
                                sendLine("Exits: none.");
                            } else {
                                // Detail view: the room render collapses to
                                // directions-only above 4 exits, so this is
                                // where the full mapping lives.
                                sendLine("Exits:");
                                for (var ex : exits) {
                                    var dest = TelnetRenderer.extractDestination(ex);
                                    sendLine("  " + (dest == null || dest.isBlank()
                                        ? ex.direction()
                                        : ex.direction() + " → " + dest));
                                }
                            }
                        }
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                });
            }
            case ParsedCommand.Nearby n -> {
                // Alias to `map 1` — show immediate neighbours only.
                var topo = ZoneTopology.getShared();
                if (topo != null) {
                    try {
                        var center = topo.room(currentRoomId).isEmpty() ? "nexus" : currentRoomId;
                        var text = topo.renderTextMap(center, 1, topo.rooms().keySet());
                        for (var line : text.split("\n")) sendLine(line);
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                }
            }
            case ParsedCommand.Rooms r -> {
                var topo = ZoneTopology.getShared();
                if (topo != null) {
                    try {
                        sendLine("Known rooms (" + topo.size() + "):");
                        for (var node : topo.rooms().values()) {
                            sendLine("  " + node.roomId() + " — " + node.name());
                        }
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    } catch (IOException ignored) {}
                }
            }
            case ParsedCommand.Path p -> {
                // Minimal: show shortest directed hops from current to target.
                var topo = ZoneTopology.getShared();
                if (topo == null) break;
                var target = topo.rooms().keySet().stream()
                    .filter(rid -> rid.equalsIgnoreCase(p.targetRoom())
                                || (topo.room(rid).isPresent()
                                    && topo.room(rid).get().name().equalsIgnoreCase(p.targetRoom())))
                    .findFirst().orElse(null);
                try {
                    if (target == null) {
                        sendLine("No room '" + p.targetRoom() + "' known here.");
                    } else {
                        var path = topo.pathBetween(currentRoomId, target);
                        if (path.isEmpty()) {
                            sendLine("No path from " + currentRoomName + " to " + target + ".");
                        } else {
                            sendLine("Path: " + String.join(" → ", path.get()));
                        }
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Office o -> {
                var myStudy = StudyProvisioner.studyRoomId(playerId);
                if (!myStudy.equals(currentRoomId)) {
                    provisionStudy(playerId, playerName);
                    performMove("office", myStudy);
                }
            }

            case ParsedCommand.Describe desc -> handleDescribe(desc);
            case ParsedCommand.Rename rn -> handleRename(rn);
            case ParsedCommand.Examine ex -> handleExamine(ex);

            case ParsedCommand.Give give -> handleGive(give.objectName(), give.targetName());

            case ParsedCommand.Score sc -> handleScore();

            case ParsedCommand.Shout shout -> {
                if (checkWard(currentRoomId, "speak")) {
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

            case ParsedCommand.Reply reply -> handleReply(reply.text());

            case ParsedCommand.Follow follow -> handleFollow(follow.targetName());

            case ParsedCommand.Afk afk -> handleAfk(afk.message());

            case ParsedCommand.Brief b -> handleBrief();

            case ParsedCommand.Alias aliasCmd -> {
                try {
                    if (aliasCmd.name() == null) {
                        if (userAliases.isEmpty()) {
                            sendLine("No aliases defined. Use: alias <name> <expansion>");
                        } else {
                            var sb = new StringBuilder("Aliases:\n");
                            for (var entry : userAliases.entrySet()) {
                                sb.append("  ").append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                            }
                            sendLine(sb.toString().trim());
                        }
                    } else if (aliasCmd.expansion() == null) {
                        var exp = userAliases.get(aliasCmd.name());
                        sendLine(exp != null ? aliasCmd.name() + " = " + exp : "No alias '" + aliasCmd.name() + "'.");
                    } else {
                        userAliases.put(aliasCmd.name(), aliasCmd.expansion());
                        sendLine("Alias set: " + aliasCmd.name() + " = " + aliasCmd.expansion());
                    }
                } catch (IOException ignored) {}
            }

            case ParsedCommand.Unalias unaliasCmd -> {
                try {
                    sendLine(userAliases.remove(unaliasCmd.name()) != null
                        ? "Alias '" + unaliasCmd.name() + "' removed."
                        : "No alias '" + unaliasCmd.name() + "'.");
                } catch (IOException ignored) {}
            }

            case ParsedCommand.GrantWard gw -> handleGrantWard(gw.agentName());
            case ParsedCommand.RevokeWard rw -> handleRevokeWard(rw.agentName());
            case ParsedCommand.Invite inv -> handleInvite(inv.agentName());
            case ParsedCommand.Dismiss dis -> handleDismiss(dis.agentName());

            case ParsedCommand.AbortPlan _ -> {
                // Broadcast abort to all agents in the room via AgentEventStream
                var stream = AgentEventStream.get();
                if (stream != null) {
                    stream.publishAbort(playerId, playerName, currentRoomId);
                }
                try { sendLine("Cancelling..."); } catch (IOException ignored) {}
            }

            // sit / stand body verbs.
            case ParsedCommand.Sit sit -> handleSit(sit.target());
            case ParsedCommand.Stand _ -> handleStand();

            case ParsedCommand.Unknown unknown -> {
                try {
                    sendLine("Didn't catch that. Try: say <text>  —  :action  —  go <dir-or-room>  —  actions  —  help");
                } catch (IOException ignored) {}
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
            // 1. Exact direction, then fuzzy destination (direction/target-id/label contains) —
            //    `go greenhouse` must work when the exit is `to-greenhouse-7772 → Greenhouse`
            //    (companion-created rooms have no compass direction or alias — second-node 2026-07-09).
            var exit = Exit.resolve(ok.snapshot().exits(), direction);
            // 2. Room alias/name match (`go docks` where docks is a neighboring
            //    room's alias or roomId) — lets users navigate by destination
            //    instead of memorising the compass grid.
            if (exit.isEmpty()) {
                var targetId = RoomRegistry.get().resolveRoomId(direction);
                if (targetId != null) {
                    exit = ok.snapshot().exits().stream()
                        .filter(e -> targetId.equals(e.targetRoom()))
                        .findFirst();
                }
            }
            if (exit.isEmpty()) {
                var catalog = ScriptMessageCatalog.forLang(locale);
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    new RoomResponse.Rejected("no_exit", catalog.get("telnet.no_exit")), "go"));
                return;
            }
            performMove(exit.get().direction(), exit.get().targetRoom());
        });
    }

    private void performMove(String direction, String targetRoomId) {
        if (!wardService.isAllowed(targetRoomId, playerId, "enter")) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("ward_denied",
                    catalog.get("telnet.not_permitted")), "go"));
            return;
        }

        var fromRoom = RoomRegistry.get().ref(currentRoomId);
        var toRoom = RoomRegistry.get().ref(targetRoomId);

        // SPEC §7.2 — when moving between rooms, carry the persisted
        // description from authService into the destination room's entity
        // record. Without this the new room sees the entity with desc=""
        // and a third-party `examine <user>` after a move surfaces
        // nothing. The 4-arg EnterRoom convenience constructor (no desc)
        // used to be the default here — see RoomCommand.EnterRoom doc
        // comment about login wipe. Same trap, different path.
        final var enterDesc = authService != null
            ? authService.findUser(playerId)
                .map(u -> u.description() != null ? u.description() : "")
                .orElse("")
            : "";
        Rooms.<RoomResponse>ask(fromRoom,
            ref -> new RoomCommand.LeaveRoom(playerId, playerName, direction, ref),
            ASK_TIMEOUT
        ).thenCompose(leaveResp ->
            Rooms.<RoomResponse>ask(toRoom,
                ref -> new RoomCommand.EnterRoom(playerId, playerName, "player",
                    enterDesc, oppositeDirection(direction), "en", ref),
                ASK_TIMEOUT
            )
        ).thenCompose(enterResp -> {
            currentRoomId = targetRoomId;
            // Update player location in EntityRegistry
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
            log.error("Move failed for SSH session {}: {}", sessionId, ex.getMessage());
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

    private void handleSlashCommand(ParsedCommand.SlashCommand sc) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            switch (sc.command()) {
                case "issue", "feedback" -> {
                    // in-band report with context bundle.
                    var text = String.join(" ", sc.args());
                    var svc = IssueService.get();
                    if (text.isBlank() || svc == null) {
                        sendLine(catalog.get("issue.usage"));
                    } else {
                        var kind = "feedback".equals(sc.command())
                            ? Issue.KIND_FEEDBACK
                            : Issue.KIND_ISSUE;
                        var filed = svc.file(kind, text, playerName, "ssh", null, playerId);
                        sendLine(catalog.get("issue.recorded", kind, filed.id()));
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "passwd", "password" -> {
                    // Surface parity with CLI/web: rotate the account password.
                    // Last whitespace-token = new password, everything before it =
                    // current (which is routinely the multi-word setup passphrase).
                    if (playerId == null || playerId.startsWith("anon-") || authService == null) {
                        sendLine(catalog.get("passwd.login_required"));
                    } else {
                        var pargs = sc.args() != null ? sc.args() : List.<String>of();
                        if (pargs.size() < 2) {
                            sendLine(catalog.get("passwd.usage"));
                        } else {
                            var next = pargs.get(pargs.size() - 1);
                            var current = String.join(" ", pargs.subList(0, pargs.size() - 1));
                            if (next.length() < 4) {
                                sendLine(catalog.get("passwd.too_short"));
                            } else {
                                try {
                                    sendLine(authService.changePassword(playerId, current, next)
                                        ? catalog.get("passwd.changed")
                                        : catalog.get("passwd.wrong_current"));
                                } catch (RuntimeException e) {
                                    sendLine(catalog.get("passwd.failed"));
                                }
                            }
                        }
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "report" -> {
                    // Rita campaign 2026-07-11 (#26): `report` existed only on
                    // the WS surface (WyrdWebSocket ~L1826) — the SSH shell had
                    // no path to file a moderation report. Mirrors that handler:
                    // ClientSessionActor.Report files via ModerationService and
                    // acks on this session (ui.report_filed).
                    var rargs = sc.args() != null ? sc.args() : List.<String>of();
                    if (rargs.isEmpty()) {
                        sendLine(catalog.get("telnet.report_usage"));
                    } else {
                        var target = rargs.get(0);
                        var reason = rargs.size() > 1
                            ? String.join(" ", rargs.subList(1, rargs.size()))
                            : "(no reason given)";
                        sessionRef.tell(new ClientSessionActor.Report(
                            target, reason, currentRoomId));
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "help" -> {
                    sendLine(catalog.get("telnet.help_header"));
                    sendLine(catalog.get("telnet.help_look"));
                    sendLine(catalog.get("telnet.help_go"));
                    sendLine(catalog.get("telnet.help_exits"));
                    sendLine(catalog.get("telnet.help_say"));
                    sendLine(catalog.get("telnet.help_tell"));
                    sendLine(catalog.get("telnet.help_take"));
                    sendLine(catalog.get("telnet.help_drop"));
                    sendLine(catalog.get("telnet.help_use"));
                    sendLine(catalog.get("telnet.help_examine"));
                    sendLine(catalog.get("telnet.help_inventory"));
                    sendLine(catalog.get("telnet.help_actions"));
                    sendLine(catalog.get("telnet.help_home"));
                    sendLine(catalog.get("telnet.help_travel"));
                    sendLine(catalog.get("telnet.help_hints"));
                    sendLine(catalog.get("telnet.help_account"));
                    sendLine(catalog.get("telnet.help_key"));
                    sendLine(catalog.get("telnet.help_passwd"));
                    sendLine(catalog.get("telnet.help_sessions"));
                    sendLine(catalog.get("telnet.help_report"));
                    sendLine(catalog.get("telnet.help_help"));
                    sendLine(catalog.get("telnet.help_quit"));
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "inventory", "i" -> {
                    var items = inventoryService.listTakeableItems(playerId);
                    if (items.isEmpty()) {
                        sendLine(catalog.get("telnet.inventory_empty"));
                    } else {
                        var names = items.stream()
                            .map(InventoryService.InventoryItem::objectName).toList();
                        sendLine(catalog.get("telnet.inventory_carrying",
                            String.join(", ", names)));
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "who" -> {
                    // Zone-wide who with permission filtering.
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room == null) {
                        sendLine(catalog.get("telnet.you_are", playerName));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
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
                                playerId, playerRole, currentZoneLabel(), currentRoomId,
                                hereNames, connectionRegistry, authService);
                            for (var line : WhoView.render(ctx)) {
                                sendLine(line);
                            }
                            sendLine(catalog.get("telnet.you_are", playerName));
                            renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        } catch (IOException ignored) {}
                    });
                }
                case "actions", "menu", "options" -> {
                    // Ask current room for fresh hints, render numbered menu.
                    // Decoupled from look output to reduce default-view noise.
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room == null) {
                        sendLine("(no room)");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
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
                            renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        } catch (IOException ignored) {}
                    });
                }
                case "notify" -> handleNotifyCommand(sc.args());
                case "invite" -> {
                    // /invite phone mints a phone
                    // connection invite from the registered relay and renders
                    // it as a QR + wyrdphone:// URL. Steward-only: the invite
                    // carries the relay's phone credential.
                    if (!"steward".equals(playerRole)) {
                        sendLine(catalog.get("relaycmd.steward_only"));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var inviteArgs = sc.args();
                    if (inviteArgs == null || inviteArgs.isEmpty()
                            || !"phone".equalsIgnoreCase(inviteArgs.get(0))) {
                        sendLine(catalog.get("relaycmd.invite_usage"));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var minted = RelayCommandBridge.phoneInvite();
                    if (!minted.ok()) {
                        sendLine(catalog.get("relaycmd.invite_failed", minted.detail()));
                    } else {
                        sendLine(catalog.get("relaycmd.invite_scan"));
                        sendLine("");
                        for (var qrLine : RelayCommandBridge.asciiQr(minted.inviteUrl())) {
                            sendLine(qrLine);
                        }
                        sendLine("");
                        sendLine("  " + minted.inviteUrl());
                        sendLine(catalog.get("relaycmd.invite_paste_hint"));
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "relay" -> {
                    // /relay join <wyrdjoin://token>
                    // (fp-verified) or <host>[:port] <code> (legacy) enrolls
                    // this zone with a relay from inside the session.
                    if (!"steward".equals(playerRole)) {
                        sendLine(catalog.get("relaycmd.steward_only"));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var relayArgs = sc.args();
                    if (relayArgs == null || relayArgs.size() < 2
                            || !"join".equalsIgnoreCase(relayArgs.get(0))
                            || (relayArgs.size() < 3
                                && !relayArgs.get(1).startsWith("wyrdjoin://"))) {
                        sendLine(catalog.get("relaycmd.join_usage"));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var joined = RelayCommandBridge.relayJoin(relayArgs.get(1),
                        relayArgs.size() > 2 ? relayArgs.get(2) : null);
                    if (!joined.ok()) {
                        sendLine(catalog.get("relaycmd.join_failed", joined.detail()));
                    } else {
                        sendLine(catalog.get("relaycmd.join_ok", relayArgs.get(1)));
                        sendLine(catalog.get("relaycmd.join_restart_hint"));
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "adduser" -> {
                    if (!"steward".equals(playerRole)) {
                        sendLine("Only stewards can add users.");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var addArgs = sc.args();
                    if (addArgs == null || addArgs.size() < 2) {
                        sendLine("Usage: /adduser <username> <password> [displayName]");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var addDisplay = addArgs.size() > 2 ? addArgs.get(2) : addArgs.get(0);
                    var addSession = authService.registerByAdmin(playerId,
                        addArgs.get(0), addArgs.get(1), addDisplay, "member");
                    if (addSession.isEmpty()) {
                        sendLine("Failed to create user (username taken or permission denied).");
                    } else {
                        var addUser = authService.findUser(addSession.get().userId()).orElseThrow();
                        sendLine("User created: " + addUser.username()
                            + " (role: " + addUser.role() + ")");
                        log.info("User created via SSH /adduser by {}: {} (role={})",
                            playerName, addUser.username(), addUser.role());
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
                case "addscript" -> {
                    // Rita re-verify 2026-07-11 (#29): stewards had NO surface to
                    // install std/behavior mixins — add_script was agent-only (and
                    // FORBIDDEN-tier until this round). Same resolution + append
                    // path as the companion's W2 add_script surface: resolve
                    // scripts/std/behavior/<name>.js, then SetBehaviorScript with
                    // append=true so the mixin chains onto the room's existing
                    // hooks instead of replacing them.
                    if (!"steward".equals(playerRole)) {
                        sendLine("Only stewards can install room scripts.");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var mixinNames = new ArrayList<>(CompanionActor.BEHAVIOR_MIXINS);
                    mixinNames.sort(null);
                    var asArgs = sc.args() != null ? sc.args() : List.<String>of();
                    if (asArgs.isEmpty()) {
                        sendLine("Usage: /addscript <mixin> [roomId]   (mixins: "
                            + String.join(", ", mixinNames) + ")");
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    var mixinName = asArgs.get(0).trim().toLowerCase();
                    var mixinSource = CompanionActor.resolveBehaviorMixin(mixinName);
                    if (mixinSource == null) {
                        sendLine("Unknown mixin '" + mixinName + "'. Available: "
                            + String.join(", ", mixinNames));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    final var installRoomId = asArgs.size() > 1 ? asArgs.get(1) : currentRoomId;
                    var installRoom = RoomRegistry.get().ref(installRoomId);
                    if (installRoom == null) {
                        sendLine("Room not found: " + installRoomId);
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        break;
                    }
                    Rooms.<RoomResponse>ask(installRoom,
                        ref -> new RoomCommand.SetBehaviorScript(
                            installRoomId, mixinSource, playerId, /* append */ true, ref),
                        ASK_TIMEOUT
                    ).thenAccept(resp -> {
                        try {
                            if (resp instanceof RoomResponse.Rejected rejected) {
                                sendLine("Install failed: " + rejected.reason());
                            } else {
                                sendLine("Installed the '" + mixinName + "' behavior on "
                                    + installRoomId + ".");
                                log.info("Steward '{}' installed std/behavior mixin '{}' on room '{}' via /addscript",
                                    playerName, mixinName, installRoomId);
                            }
                            renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        } catch (IOException ignored) {}
                    }).exceptionally(ex -> {
                        log.warn("/addscript {} on {} failed: {}", mixinName, installRoomId,
                            ex.getMessage());
                        return null;
                    });
                }
                default -> {
                    sendLine(catalog.get("telnet.unknown_command", sc.command()));
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                }
            }
        } catch (IOException e) {
            log.error("Error sending slash command response", e);
        }
    }

    /**
     * Handle /notify slash command — configure companion notification channels.
     *
     * Usage:
     *   /notify list
     *   /notify add telegram botToken=123 chatId=456
     *   /notify add ntfy topic=wyrdsekai-operator [server=https://ntfy.sh]
     *   /notify add email address=me@example.com password=app-pass [user=smtp-login]
     *   /notify add discord webhookUrl=https://...
     *   /notify add webhook url=https://... [label=myhook]
     *   /notify remove telegram
     *   /notify test
     */
    private void handleNotifyCommand(List<String> args) {
        try {
            if (args == null || args.isEmpty()) {
                sendLine("Usage: /notify <list|add|remove|test> [channel] [key=value ...]");
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
                return;
            }

            var sub = args.get(0).toLowerCase();

            // Find the player's companion — first online agent in EntityRegistry
            var registry = EntityRegistry.get();
            if (registry == null) {
                sendLine("Entity registry not available.");
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
                return;
            }

            String companionEntityId = null;
            String companionName = null;
            for (var entityId : registry.allEntities()) {
                if (registry.isAgent(entityId)) {
                    companionEntityId = entityId;
                    companionName = registry.nameOf(entityId).orElse(entityId);
                    break;
                }
            }

            if (companionEntityId == null) {
                sendLine("No companion is currently online.");
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
                return;
            }

            var companionRef = ZoneGuardian.getCompanionRef(
                null, companionEntityId);
            if (companionRef == null) {
                sendLine("Companion '" + companionName + "' actor not found.");
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
                return;
            }

            switch (sub) {
                case "list" -> {
                    // Read worldKnowledge from soul store to show configured channels.
                    // Sysprop first — the installed service publishes the resolved DSN
                    // as wyrdsekai.jdbc.url and never sets WYRDSEKAI_JDBC_URL, so the
                    // WyrdConfig read alone was always blank on a real node (2026-07-18).
                    var jdbcUrl = System.getProperty("wyrdsekai.jdbc.url");
                    if (jdbcUrl == null || jdbcUrl.isBlank()) jdbcUrl = WyrdConfig.get().jdbcUrl();
                    if (jdbcUrl == null || jdbcUrl.isBlank()) {
                        sendLine("Soul store not available.");
                        break;
                    }
                    SoulManifest manifest = null;
                    try (var soulStore = new SqlSoulStore(jdbcUrl)) {
                        for (var m : soulStore.listLatest()) {
                            if (m.profile() != null
                                    && companionEntityId.equals(m.profile().entityId())) {
                                manifest = m;
                                break;
                            }
                        }
                    }
                    if (manifest == null || manifest.worldKnowledge() == null) {
                        sendLine("No notification channels configured for " + companionName + ".");
                        break;
                    }
                    var wk = manifest.worldKnowledge();
                    var channelTypes = new LinkedHashSet<String>();
                    for (var key : wk.keySet()) {
                        if (key.startsWith("notify.") && !key.equals("notify.deep_link")) {
                            // Extract channel type: notify.telegram.botToken -> telegram
                            var parts = key.split("\\.", 3);
                            if (parts.length >= 2) channelTypes.add(parts[1]);
                        }
                    }
                    if (channelTypes.isEmpty()) {
                        sendLine("No notification channels configured for " + companionName + ".");
                    } else {
                        sendLine("Notification channels for " + companionName + ":");
                        for (var ct : channelTypes) {
                            var prefix = "notify." + ct + ".";
                            var keys = new ArrayList<String>();
                            for (var key : wk.keySet()) {
                                if (key.startsWith(prefix)) {
                                    var param = key.substring(prefix.length());
                                    // Mask sensitive values
                                    var val = wk.get(key);
                                    if (param.toLowerCase().contains("password")
                                            || param.toLowerCase().contains("token")
                                            || param.toLowerCase().contains("secret")) {
                                        val = val.length() > 4
                                            ? val.substring(0, 4) + "****"
                                            : "****";
                                    }
                                    keys.add(param + "=" + val);
                                }
                            }
                            sendLine("  " + ct + ": " + String.join(", ", keys));
                        }
                    }
                }
                case "add" -> {
                    if (args.size() < 3) {
                        sendLine("Usage: /notify add <channel> key=value [key=value ...]");
                        break;
                    }
                    var channel = args.get(1).toLowerCase();
                    var params = new HashMap<String, String>();
                    for (int i = 2; i < args.size(); i++) {
                        var kv = args.get(i);
                        var eq = kv.indexOf('=');
                        if (eq > 0) {
                            params.put(kv.substring(0, eq), kv.substring(eq + 1));
                        }
                    }
                    if (params.isEmpty()) {
                        sendLine("No parameters provided. Use key=value format.");
                        break;
                    }
                    companionRef.tell(
                        new CompanionActor.UpdateNotificationConfig(
                            channel, params, false));
                    sendLine("Configuring " + channel + " channel for " + companionName
                        + " (" + params.size() + " params).");
                }
                case "remove" -> {
                    if (args.size() < 2) {
                        sendLine("Usage: /notify remove <channel>");
                        break;
                    }
                    var channel = args.get(1).toLowerCase();
                    companionRef.tell(
                        new CompanionActor.UpdateNotificationConfig(
                            channel, Map.of(), true));
                    sendLine("Removed " + channel + " channel from " + companionName + ".");
                }
                case "test" -> {
                    // Send test via AgentEventStream — the companion's fanOutExternal is package-private
                    // so we send a tell message that the companion will relay externally
                    var eventStream = AgentEventStream.get();
                    if (eventStream != null) {
                        eventStream.publishAgentMessage(
                            playerId, playerName, companionEntityId,
                            "[from " + playerName + "] /notify-test");
                    }
                    sendLine("Test notification sent through " + companionName
                        + "'s channels.");
                }
                default -> sendLine(
                    "Usage: /notify <list|add|remove|test> [channel] [key=value ...]");
            }
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException e) {
            log.error("Error in /notify command", e);
        }
    }

    private void handleTell(String targetName, String text) {
        var registry = EntityRegistry.get();
        if (registry == null) {
            try { sendLine("Tell is not available."); }
            catch (Exception ignored) {}
            return;
        }

        // Try cross-zone routing first (handles "alpha.wyrd", "my wyrd", and cross-zone fallback)
        var tellService = CrossZoneTellService.get();
        if (tellService != null) {
            var localZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "local");
            var result = tellService.tell(playerId, playerName, localZoneId, targetName, text);
            if (result.delivered()) {
                try { sendLine("[to " + targetName + "] " + text); }
                catch (Exception ignored) {}
                return;
            }
            if (result.errorMessage() != null) {
                try { sendLine(result.errorMessage()); }
                catch (Exception ignored) {}
                return;
            }
        }

        var targetId = registry.findByName(targetName);
        if (targetId.isEmpty()) {
            try { sendLine("Nobody called '" + targetName + "' is online."); }
            catch (Exception ignored) {}
            return;
        }

        var targetRoomId = registry.roomOf(targetId.get());
        if (targetRoomId.isEmpty()) {
            try { sendLine(targetName + " is not in any room."); }
            catch (Exception ignored) {}
            return;
        }

        // If target is an agent, deliver directly via AgentEventStream (cross-room, preserves sender)
        var eventStream = AgentEventStream.get();
        boolean isAgent = registry.isAgent(targetId.get());
        log.info("Tell '{}' → targetId={}, isAgent={}, eventStream={}, room={}",
            targetName, targetId.get(), isAgent, eventStream != null, targetRoomId.get());
        if (eventStream != null && isAgent) {
            boolean delivered = eventStream.publishAgentMessage(
                playerId, playerName, targetId.get(),
                "[from " + playerName + "] " + text);
            log.info("Tell AgentEventStream delivery: targetId={}, delivered={}", targetId.get(), delivered);
            if (!delivered) {
                // #32 item 4: a tell to an agent that didn't reach its event queue is
                // a lost message unless the room fallback below lands — say so loudly.
                log.warn("Tell '{}' → agent {} NOT enqueued — falling back to room-level "
                    + "delivery in {}", targetName, targetId.get(), targetRoomId.get());
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

        sessionRef.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Prose(
                0, "narrator", "You tell " + targetName + ": " + text,
                List.of(), null, "normal", locale)));
    }

    /**
     * Handle the {@code examine X} / {@code look at X} verb.
     *
     * <p>Passive observation: returns the target's description without
     * invoking onUse scripts, broadcasting ObjectUsed, or causing a room
     * re-render. Lookup order:</p>
     * <ol>
     *   <li>Self-reference ({@code me}, {@code self}, {@code myself}) — describe self
     *       (description sourced from authService)</li>
     *   <li>Object in inventory matching name</li>
     *   <li>Object in current room matching name</li>
     *   <li>Entity in current room matching name</li>
     *   <li>Else — "There's nothing called X here."</li>
     * </ol>
     *
     * <p>Pre-refactor this routed through {@code Use(X, null)} which
     * triggered the {@code tryInvokeCarriedScript} → "You use the X."
     * fallback chain (SPEC §11.7). The new path keeps passive verbs passive.</p>
     */
    /**
     * sit at a target (or bare sit). Builds a generic
     * Posture and dispatches {@code RoomCommand.SetPosture} to the current
     * room. Scripted-furniture richness (chair-specific narration, inner-state
     * imprint) comes via Phase B.5's onUse hooks, which call
     * {@code world.entity.setPosture} from within the script — those land
     * the same way but with a richer descriptor.
     */
    private void handleSit(String target) {
        if (currentRoomId == null) return;
        var verb = "sat";
        var trimmedTarget = target == null ? null : target.trim();
        var atObject = (trimmedTarget == null || trimmedTarget.isEmpty()) ? null : trimmedTarget;
        var descriptor = atObject == null
            ? playerName + " settles down."
            : playerName + " settles at the " + atObject + ".";
        var posture = new Posture(verb, atObject, descriptor);
        askRoom(currentRoomId,
            ref -> new RoomCommand.SetPosture(playerId, posture, ref),
            "sit");
    }

    /** — stand back up, clearing any current posture. */
    private void handleStand() {
        if (currentRoomId == null) return;
        askRoom(currentRoomId,
            ref -> new RoomCommand.ClearPosture(playerId, ref),
            "stand");
    }

    private void handleExamine(ParsedCommand.Examine ex) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var target = ex.target() == null ? "" : ex.target().trim();
        if (target.isEmpty()) {
            try {
                sendLine(catalog.get("telnet.help_examine"));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        ExamineLookup.resolve(
                playerId, playerName, target, locale,
                authService, inventoryService, currentRoomId, ASK_TIMEOUT)
            .thenAccept(result -> {
                try {
                    switch (result) {
                        case ExamineLookup.ExamineResult.Found f -> {
                            sendLine(f.name());
                            if (f.description() != null && !f.description().isBlank()) {
                                sendLine(f.description());
                            } else if (f.source() == ExamineLookup.Source.SELF) {
                                // Self-with-no-description gets a hint to set one.
                                sendLine("(no description set. Use '@describe <text>' to set one.)");
                            }
                            // posture line for entities with a posture set
                            if (f.posture() != null && !f.posture().isBlank()) {
                                sendLine(f.posture());
                            }
                        }
                        case ExamineLookup.ExamineResult.NotFound nf ->
                            sendLine(catalog.get("err.no_such_object", nf.requested()));
                        case ExamineLookup.ExamineResult.NoCurrentRoom nr ->
                            sendLine(catalog.get("err.no_such_object", nr.requested()));
                        case ExamineLookup.ExamineResult.Empty e -> {}
                    }
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                } catch (IOException e) {
                    log.error("Error in handleExamine output", e);
                }
            });
    }

    /**
     * Handle the {@code rename} verb.
     *
     * <p>v1 scope: self-rename only. {@code rename me <newName>} updates
     * authService.display_name (persistent), tells the current room to update
     * the entity's name in the live state, refreshes EntityRegistry, and
     * updates the local {@code playerName} field so subsequent commands (say,
     * tell, who) reflect the new name. Other targets (companion rename via
     * bond authority, steward-renames-anyone) are deferred — they need
     * BondStore lookups + SoulManifest writes which haven't shipped yet.</p>
     *
     * <p>Reasoning for keeping persistence outside RoomActor: the room is a
     * presentation surface, not a source of truth. Same pattern as
     * description handling.</p>
     */
    private void handleRename(ParsedCommand.Rename rn) {
        var result = RenameService.renameSelf(
            playerId, playerName, rn.target(), rn.newName(),
            currentRoomId, authService, ASK_TIMEOUT);
        try {
            switch (result) {
                case RenameService.Result.Ok ok -> {
                    playerName = ok.newName();
                    sendLine("You are now known as " + ok.newName() + ".");
                }
                case RenameService.Result.Requested rq ->
                    sendLine("You offer the name " + rq.newName()
                        + " to " + rq.targetName() + ".");
                case RenameService.Result.Rejected r ->
                    sendLine(r.message());
            }
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException e) {
            log.error("Error in handleRename output", e);
        }
    }

    private void handleDescribe(ParsedCommand.Describe desc) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if ("me".equals(desc.target())) {
                if (playerId == null || playerId.startsWith("anon-")) {
                    sendLine(catalog.get("telnet.describe_login_required"));
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
                    return;
                }
                authService.updateDescription(playerId, desc.text());
                // Persistence in authService is necessary but not sufficient.
                // The room's RoomState.entities() map carries a snapshot of
                // each entity's description that `examine <name>` reads from
                // (RoomActor.onUseObject → emitEntityDescription). Without
                // also pushing UpdateEntityDescription, the new text only
                // surfaces on the next room re-enter — so within the same
                // session, `describe me X` then `examine me` returns "no
                // description set".
                if (currentRoomId != null) {
                    var room = RoomRegistry.get().ref(currentRoomId);
                    if (room != null) {
                        Rooms.<RoomResponse>ask(room,
                            ref -> new RoomCommand.UpdateEntityDescription(
                                playerId, desc.text(), ref),
                            ASK_TIMEOUT
                        ).exceptionally(ex -> {
                            log.warn("Live room desc sync failed: {}", ex.getMessage());
                            return null;
                        });
                    }
                }
                sendLine(catalog.get("telnet.describe_updated"));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } else if ("room".equals(desc.target())) {
                if (!StudyProvisioner.isStudyRoom(currentRoomId)) {
                    sendLine(catalog.get("telnet.describe_room_study_only"));
                    renderer.sendPrompt(currentRoomName, currentZoneLabel());
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

    /**
     * If the player carries a scripted item matching {@code objectName}, execute
     * its script in-process and stream the result back as prose.
     * Mirrors {@code WyrdWebSocket.tryInvokeCarriedScript} so SSH/Telnet users
     * can invoke pinned scripted furnishings (Embers, Board, Quill, …).
     *
     * <p>Returns {@code true} when a scripted item was found and invoked (caller
     * must not fall through to room handling); {@code false} otherwise.
     */
    private boolean tryInvokeCarriedScript(String objectName, String target) {
        if (playerId == null || objectName == null || objectName.isBlank()) return false;
        if (inventoryService == null) return false;
        var found = inventoryService.findByName(playerId, objectName);
        // Arg-split fallback (second-node 2026-07-09): `use web-search-window antikythera mechanism`
        // arrives with the WHOLE phrase as objectName, so the carried-item lookup missed and
        // the room then rejected it (the item isn't a room object). When the full phrase
        // doesn't match a carried item, try the first token as the item name and pass the
        // remainder through as the script's target/args.
        if (found.isEmpty() && objectName.contains(" ")) {
            var sp = objectName.indexOf(' ');
            var head = objectName.substring(0, sp);
            var rest = objectName.substring(sp + 1).trim();
            var headMatch = inventoryService.findByName(playerId, head);
            if (headMatch.isPresent()) {
                found = headMatch;
                target = (target == null || target.isBlank()) ? rest : rest + " on " + target;
            }
        }
        if (found.isEmpty() || !found.get().isScripted()) return false;
        var item = found.get();
        try {
            var localZone = this.localZoneId != null ? this.localZoneId
                : System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "home");
            // Use the SAME rich provider the room-furnishing path uses (the WS-
            // registered buildPlayerProvider, which wires all 15 household
            // suppliers). Before 2026-07-18 this built its OWN provider with only
            // agreements/inventory/bonds, so a CARRIED Study item over SSH silently
            // answered empty for catalog/safe/auth/wards/parental/maintenance/
            // invites/audit/nodes/presence/study — while the identical item worked
            // when room-placed or over WS. Fall back to the local thin builder only
            // if no factory is registered (bare boot / tests).
            var registered = ItemProviderRegistry.forEntity(playerId);
            ItemWorldApiProvider provider;
            if (registered != null) {
                provider = registered;
            } else if (homeClient != null) {
                var home = new HomeOwnerItemProvider(
                    localZone, localZone, playerId, homeClient, system);
                if (federationService != null) {
                    final var fed = federationService;
                    home.withAgreements(() -> federationAgreementsView(fed, localZone));
                }
                home.withInventory(() -> inventoryOwnedView(inventoryService, playerId));
                if (bondRitual != null) {
                    final var ritual = bondRitual;
                    home.withBonds(() -> bondsView(ritual, playerId));
                }
                provider = home;
            } else {
                provider = new VisitorItemProvider(localZone, localZone);
            }
            var params = new HashMap<String, Object>();
            params.put("target", target != null ? target : "");
            // Most item scripts read `params.query` (portal api-search, book search, …) while
            // the generic use-command carries args as `target` — provide both (second-node 2026-07-09:
            // `use web-search-lens antikythera` ran cleanly but the query never reached the script).
            params.put("query", target != null ? target : "");
            params.put("entityId", playerId);
            // #1 (2026-07-19 OSS hardening; polarity fixed after adversarial review)
            // — DEFAULT-DENY: only a positively-identified bundled/disk-installed
            // scripted item runs UNRESTRICTED. Crafted, companion-GIVEN
            // (takenFrom=roomId), and cross-zone TRANSITED ("remote_zone") scripts
            // run under the crafted ceiling. The old `"crafted".equals(takenFrom)`
            // test failed OPEN for given/transited items.
            var itemCaps = ToolItemStarterKit.isTrustedScriptId(item.objectId())
                ? ItemCapabilitySet.UNRESTRICTED
                : ItemCapabilitySet.craftedDefault();
            var result = itemScriptExecutor.execute(
                item.objectId(), item.scriptSource(), params, provider, itemCaps);
            var text = ItemScriptResponse.extractText(
                result, item.objectName());
            try {
                for (var line : text.split("\n")) sendLine(line);
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            log.info("SSH player {} invoked carried scripted item '{}'",
                playerId, item.objectName());
        } catch (Exception e) {
            log.error("SSH: failed to execute carried item {} for {}: {}",
                item.objectId(), playerId, e.getMessage());
            try {
                sendLine("The " + item.objectName() + " malfunctions: " + e.getMessage());
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
        }
        return true;
    }

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
            log.warn("SSH federationAgreementsView({}): {}", localZoneId, e.getMessage());
            return List.of();
        }
    }

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
            log.warn("SSH inventoryOwnedView({}): {}", playerId, e.getMessage());
            return List.of();
        }
    }

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
            log.warn("SSH bondsView({}): {}", playerId, e.getMessage());
            return List.of();
        }
    }

    private void handleDrop(String objectName) {
        var item = inventoryService.findTakeableByName(playerId, objectName);
        if (item.isEmpty()) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                new RoomResponse.Rejected("not_in_inventory",
                    catalog.get("telnet.dont_have")), "drop"));
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

    private void handleGive(String objectName, String targetName) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var item = inventoryService.findTakeableByName(playerId, objectName);
        if (item.isEmpty()) {
            try {
                sendLine(catalog.get("telnet.give_not_found", objectName));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        var registry = EntityRegistry.get();
        if (registry == null) {
            try {
                sendLine(catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        var targetId = registry.findByName(targetName);
        if (targetId.isEmpty()) {
            try {
                sendLine(catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        var targetRoomId = registry.roomOf(targetId.get());
        if (targetRoomId.isEmpty() || !targetRoomId.get().equals(currentRoomId)) {
            try {
                sendLine(catalog.get("telnet.give_target_not_here", targetName));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        var inv = item.get();
        inventoryService.removeItem(playerId, inv.objectId());
        inventoryService.addItem(targetId.get(), inv.objectId(), inv.objectName(),
            inv.description(), inv.takeable(), currentRoomId);
        try {
            sendLine(catalog.get("telnet.give_success", objectName, targetName));
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
    }

    private void handleScore() {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            sendLine(catalog.get("telnet.score_header"));
            sendLine("  Name: " + playerName);
            sendLine("  Role: " + (playerRole != null ? playerRole : "guest"));
            sendLine("  Room: " + currentRoomName);
            var items = inventoryService.listItems(playerId);
            sendLine("  Items: " + items.size());
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
    }

    private void handleReply(String text) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        if (lastTellFrom == null) {
            try {
                sendLine(catalog.get("telnet.reply_no_target"));
                renderer.sendPrompt(currentRoomName, currentZoneLabel());
            } catch (IOException ignored) {}
            return;
        }
        handleTell(lastTellFrom, text);
    }

    private void handleFollow(String targetName) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if (targetName.equalsIgnoreCase("none") || targetName.equalsIgnoreCase("stop")) {
                following = null;
                sendLine(catalog.get("telnet.follow_stop"));
            } else {
                var registry = EntityRegistry.get();
                if (registry != null) {
                    var targetId = registry.findByName(targetName);
                    if (targetId.isEmpty()) {
                        sendLine(catalog.get("telnet.follow_not_here", targetName));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        return;
                    }
                    var targetRoomId = registry.roomOf(targetId.get());
                    if (targetRoomId.isEmpty() || !targetRoomId.get().equals(currentRoomId)) {
                        sendLine(catalog.get("telnet.follow_not_here", targetName));
                        renderer.sendPrompt(currentRoomName, currentZoneLabel());
                        return;
                    }
                }
                following = targetName;
                sendLine(catalog.get("telnet.follow_start", targetName));
            }
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
    }

    private void handleAfk(String message) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        try {
            if (afkMessage != null) {
                afkMessage = null;
                sendLine(catalog.get("telnet.afk_cleared"));
            } else {
                afkMessage = message;
                sendLine(catalog.get("telnet.afk_set", message));
            }
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
    }

    private void handleBrief() {
        var catalog = ScriptMessageCatalog.forLang(locale);
        briefMode = !briefMode;
        try {
            sendLine(briefMode
                ? catalog.get("telnet.brief_on")
                : catalog.get("telnet.brief_off"));
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
    }

    private void handleGrantWard(String agentName) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { sendLine("Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { sendLine("No agent named '" + agentName + "' found."); return; }

            // Create Study Ward as equipped item on the target agent
            var myStudyId = StudyProvisioner.studyRoomId(playerId);
            var equipService = EquipmentService.get();
            equipService.equipWard(agentId.get(),
                "ward-" + myStudyId,
                "Study Ward (" + playerName + ")",
                "study-ward",
                "You have access to " + playerName + "'s Study [" + myStudyId + "]",
                "carrying a warm crystal ward");
            sendLine("You grant " + agentName + " a Study Ward — a warm crystal token that pulses with your hearth's warmth.");
            sendLine(agentName + " can now enter your Study freely.");
        } catch (Exception e) {
            try { sendLine("Failed: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }

    private void handleRevokeWard(String agentName) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { sendLine("Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { sendLine("No agent named '" + agentName + "' found."); return; }

            var myStudyId = StudyProvisioner.studyRoomId(playerId);
            var equipService = EquipmentService.get();
            boolean removed = equipService.doffByLabel(agentId.get(), "Study Ward (" + playerName + ")");
            if (removed) {
                sendLine("You revoke " + agentName + "'s Study Ward. The crystal dims and fades.");
            } else {
                sendLine(agentName + " doesn't have a ward to your Study.");
            }
        } catch (Exception e) {
            try { sendLine("Failed: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }

    private void handleInvite(String agentName) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { sendLine("Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { sendLine("No agent named '" + agentName + "' found."); return; }

            // Move the agent into the current room
            var eventStream = AgentEventStream.get();
            if (eventStream != null) {
                eventStream.publishAgentMessage(playerId, playerName, agentId.get(),
                    "[from " + playerName + "] You are invited to join me in " + currentRoomName + ".");
            }
            sendLine("You invite " + agentName + " to join you.");
        } catch (Exception e) {
            try { sendLine("Failed: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }

    private void handleDismiss(String agentName) {
        try {
            var registry = EntityRegistry.get();
            if (registry == null) { sendLine("Entity registry not available."); return; }
            var agentId = registry.findByName(agentName);
            if (agentId.isEmpty()) { sendLine("No agent named '" + agentName + "' found."); return; }

            var eventStream = AgentEventStream.get();
            if (eventStream != null) {
                eventStream.publishAgentMessage(playerId, playerName, agentId.get(),
                    "[from " + playerName + "] Please leave this room and return home.");
            }
            sendLine(agentName + " nods and steps out.");
        } catch (Exception e) {
            try { sendLine("Failed: " + e.getMessage()); } catch (IOException ignored) {}
        }
    }

    private boolean checkWard(String roomId, String permission) {
        if (wardService.isAllowed(roomId, playerId, permission)) {
            return true;
        }
        try {
            var catalog = ScriptMessageCatalog.forLang(locale);
            sendLine(catalog.get("telnet.ward_denied", permission));
            renderer.sendPrompt(currentRoomName, currentZoneLabel());
        } catch (IOException ignored) {}
        return false;
    }

    @SuppressWarnings("unchecked")
    private void provisionStudy(String pId, String pName) {
        var isSteward = "steward".equals(playerRole);
        ((ActorSystem<ZoneGuardian.Command>) (Object) system)
            .tell(new ZoneGuardian.ProvisionStudy(pId, pName, isSteward));
        // seed scripted furnishings for authenticated users.
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
                if ("use".equals(requestId)) {
                    if (resp instanceof RoomResponse.Rejected) {
                        sessionRef.tell(new ClientSessionActor.RoomResponseMsg(resp, requestId));
                    }
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
            .thenAccept(resp -> {
                // Cache the snapshot so Tab completion can offer this room's
                // object/entity/exit names (verbs are always available).
                if (resp instanceof RoomResponse.Ok ok && ok.snapshot() != null) {
                    lastRoomSnapshot = ok.snapshot();
                }
                sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                    resp, requestId, loadInventory(playerId)));
            });
    }

    /** Most-recent room snapshot, for Tab completion vocabulary. Nullable. */
    private volatile RoomSnapshot lastRoomSnapshot;

    /** Load player inventory as RoomObject list for wire protocol. */
    private List<RoomObject> loadInventory(String playerId) {
        return inventoryService.listTakeableItems(playerId).stream()
            .map(i -> new RoomObject(
                i.objectId(), i.objectName(), i.description(), i.takeable()))
            .toList();
    }

    private void cleanup() {
        // Close any active remote-zone proxy so the remote sees a clean
        // session-closed signal rather than a dangling pipe.
        if (remoteZoneSession != null && remoteZoneSession.isActive()) {
            try {
                remoteZoneSession.close();
            } catch (Exception ignored) {}
            remoteZoneSession = null;
        }
        if (connectionRegistry != null) {
            connectionRegistry.unregister(sessionId);
        }
        // Suppress the room departure + entity removal while the same account is
        // still present through another surface (e.g. SSH closing while the CLI
        // is still up). Otherwise the other surface sees "X heads disconnect."
        // even though X is still here. unregister() above already dropped this
        // session, so any remaining session means the user is still present.
        boolean stillPresent = connectionRegistry != null
            && connectionRegistry.hasOtherLiveSession(playerId, sessionId);
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
        log.info("SSH session ended: {}", sessionId);
    }

    // --- Cross-zone transit ---

    /**
     * Begin proxying this SSH session to a remote zone. While proxying, input
     * lines from the SSH client are forwarded to the remote as commands, and
     * remote events are decoded to {@link S2CMessage} and rendered via the
     * existing {@link TelnetRenderer}. The SSH connection itself never drops.
     *
     * @return {@code true} if the remote session opened successfully
     */
    boolean startRemoteSession(String remoteZoneId, String transitToken) {
        if (localZoneId == null || relayTransport == null || !relayTransport.isConnected()) {
            log.warn("SSH {}: cannot start transit — relay transport unavailable", sessionId);
            return false;
        }
        if (sessionRef == null || playerId == null) {
            log.warn("SSH {}: cannot start transit — no active session", sessionId);
            return false;
        }
        // Close any existing remote session first
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

        // Serialize inventory for transit so the player's carried items
        // appear in the remote zone.
        TransitInventory inventory = null;
        if (inventoryService != null && playerId != null) {
            try {
                inventory = inventoryService.serializeForTransit(playerId, localZoneId);
            } catch (Exception e) {
                log.warn("SSH {}: failed to serialize inventory: {}", sessionId, e.getMessage());
            }
        }
        TransitReputation reputation = null;
        var attestService = AttestationService.get();
        if (attestService != null && playerId != null) {
            try {
                reputation = attestService.serializeForTransit(playerId, localZoneId);
            } catch (Exception e) {
                log.warn("SSH {}: failed to serialize reputation: {}", sessionId, e.getMessage());
            }
        }
        final var finalPlayerId = playerId;
        remote.setDeltaCallback(delta -> {
            if (inventoryService != null && delta != null && !delta.isEmpty()) {
                try {
                    inventoryService.applyTransitDelta(finalPlayerId, delta);
                } catch (Exception e) {
                    log.error("SSH {}: failed to apply transit delta: {}",
                        sessionId, e.getMessage());
                }
            }
        });

        remote.open(inventory, reputation);
        if (!remote.isActive()) {
            log.warn("SSH {}: RemoteZoneSession.open did not activate", sessionId);
            return false;
        }
        remoteZoneSession = remote;

        // emit EntityLeft so the local room broadcasts
        // the bondholder departure to all entities present (companions especially).
        // Without this, CompanionActor.onFollowAttempt is never scheduled and the
        // cross-zone relocate never fires. We send it BEFORE setTraveling so that
        // the 750ms follow-attempt timer in CompanionActor sees PresenceState.TRAVELING
        // by the time it resolves.
        if (currentRoomId != null && playerId != null) {
            try {
                var fromRoom = RoomRegistry.get().ref(currentRoomId);
                if (fromRoom != null) {
                    Rooms.<RoomResponse>ask(fromRoom,
                        ref -> new RoomCommand.LeaveRoom(playerId, playerName,
                            "travel:" + remoteZoneId, ref),
                        ASK_TIMEOUT);
                }
            } catch (Exception e) {
                log.warn("SSH {}: failed to emit EntityLeft on transit: {}",
                    sessionId, e.getMessage());
            }
        }

        var entityRegistry = EntityRegistry.get();
        if (entityRegistry != null) {
            entityRegistry.setTraveling(playerId, remoteZoneId);
            entityRegistry.setHomeZone(playerId, localZoneId);
        }

        localActor.tell(new ClientSessionActor.SendMessage(
            new S2CMessage.Transit(0, remoteZoneId, null, transitToken,
                "You step through the portal into zone '" + remoteZoneId + "'...")));

        log.info("SSH {}: remote session started → zone '{}'", sessionId, remoteZoneId);
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

        // emit EntityEntered so the local room
        // re-broadcasts the bondholder's return. CompanionActor's onEntityEntered
        // calls noteBondholderActivity, flipping mode back to PRESENT_WITH_USER and
        // cancelling any pending follow timer that hasn't yet expired.
        if (currentRoomId != null && playerId != null) {
            try {
                var localRoom = RoomRegistry.get().ref(currentRoomId);
                if (localRoom != null) {
                    Rooms.<RoomResponse>ask(localRoom,
                        ref -> new RoomCommand.EnterRoom(playerId, playerName, "player",
                            "travel-return", ref),
                        ASK_TIMEOUT);
                }
            } catch (Exception e) {
                log.warn("SSH {}: failed to emit EntityEntered on return: {}",
                    sessionId, e.getMessage());
            }
        }

        // Keep delta subscription alive briefly to receive final inventory delta
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
        // Re-look the local room so currentRoomName + snapshot reflect
        // where we actually are on alpha (not the last-seen beta room).
        // Without this, the prompt shows stale "@alpha" with the foreign
        // zone's room name until the next input triggers a look.
        if (currentRoomId != null && playerId != null) {
            var localRoom = RoomRegistry.get().ref(currentRoomId);
            if (localRoom != null) {
                Rooms.<RoomResponse>ask(localRoom,
                    ref -> new RoomCommand.LookRoom(playerId, ref),
                    ASK_TIMEOUT
                ).thenAccept(resp ->
                    sessionRef.tell(new ClientSessionActor.RoomResponseMsg(
                        resp, "return", loadInventory(playerId)))
                );
            }
        }
        log.info("SSH {}: remote session ended", sessionId);
    }

    /**
     * Translate a user-typed line into a typed command for the remote zone.
     * Mirrors {@code WyrdWebSocket.handleRemoteC2S} but starting from raw
     * text. Recognises the common verbs; anything else is sent as a "say"
     * so natural-language input still reaches the remote's room script.
     * Also recognises the travel/return escape words to end the proxy.
     */
    private void forwardToRemote(String line) {
        if (line == null || line.isEmpty()) return;
        var rs = remoteZoneSession;
        if (rs == null || !rs.isActive()) return;

        var lower = line.toLowerCase();
        // Normalise: strip a leading "say " so both `travel home` and
        // `say travel home` trip the return intercept. (The MUD habit is to
        // prefix everything with `say` when invoking docks commands; we
        // shouldn't punish that.)
        // Strip leading `say ` (explicit) or `'` (MUD say-shorthand) so
        // the same return triggers work whether the user typed `travel home`,
        // `say travel home`, or `'travel home`.
        var returnPhrase = lower;
        if (returnPhrase.startsWith("say ")) returnPhrase = returnPhrase.substring(4).trim();
        else if (returnPhrase.startsWith("'")) returnPhrase = returnPhrase.substring(1).trim();
        if (returnPhrase.equals("travel home") || returnPhrase.equals("go home")
                || returnPhrase.equals("return") || returnPhrase.equals("return home")
                || returnPhrase.equals("home")) {
            endRemoteSession();
            return;
        }
        // `quit`/`exit`/`q` while proxying: end the remote session cleanly
        // AND close the SSH connection. Matches standard terminal UX —
        // "exit" means "leave this shell," not "return home." Use `home`
        // (or `return`) to come back without dropping the SSH connection.
        if (returnPhrase.equals("quit") || returnPhrase.equals("exit")
                || returnPhrase.equals("q")) {
            endRemoteSession();
            try {
                var catalog = ScriptMessageCatalog.forLang(locale);
                sendLine(catalog.get("telnet.goodbye"));
            } catch (IOException ignored) {}
            running = false;
            // The input loop blocks on in.read(); flipping `running` alone
            // won't close the channel until the next keystroke. Fire the
            // exitCallback to ask the SSH server to tear down the channel
            // now, which causes in.read() to return -1 and the loop to exit.
            if (exitCallback != null) {
                try { exitCallback.onExit(0); } catch (Exception ignored) {}
            }
            return;
        }

        // Forward the full line verbatim as an `input` command. The remote
        // zone runs its own CommandParser on it, giving visitors the same
        // verb surface that local players get (exits, who, inventory, etc.)
        // rather than a hard-coded whitelist that silently degrades to `say`.
        var mapper = Json.mapper();
        try {
            var payload = mapper.createObjectNode();
            payload.put("line", line);
            rs.sendCommand("input", mapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("SSH {}: failed to forward '{}' to remote: {}",
                sessionId, line, e.getMessage());
        }
    }

    /** {@link org.wyrdsekai.server.session.ClientConnection} view of this SSH session. */
    private final class SshClientConnection
            implements ClientConnection {
        @Override public String sessionId()   { return sessionId; }
        @Override public String playerId()    { return playerId; }
        @Override public String playerName()  { return playerName; }

        @Override
        public boolean startRemoteSession(String remoteZoneId, String transitToken) {
            return WyrdShellCommand.this.startRemoteSession(remoteZoneId, transitToken);
        }

        @Override
        public void endRemoteSession() {
            WyrdShellCommand.this.endRemoteSession();
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
            // Link-takeover path (SPEC: ClientConnection.disconnect).
            // 1) Render the reason so the user sees why they're being kicked.
            // 2) Leave the current room so the entity doesn't linger as a ghost.
            // 3) Close the SSH channel via the main read-loop flag.
            try {
                if (out != null) {
                    try { TelnetCodec.sendLine(out, ""); } catch (Exception ignored) {}
                    try { TelnetCodec.sendLine(out, "[" + reason + "]"); } catch (Exception ignored) {}
                }
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
                running = false;
                if (exitCallback != null) {
                    try { exitCallback.onExit(0); } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public boolean deliverLine(String text) {
            // Tell-back leg (second-node re-verify 2026-07-11 #29): before this, the
            // tell-back deliverer was WS-only, so an SSH user who sent
            // `tell mia …` from another room never saw the reply. Route the
            // line through this session's actor — the same render path every
            // other server-pushed message takes (renderer.render + prompt).
            var ref = sessionRef;
            if (ref == null || !running) return false;
            ref.tell(new ClientSessionActor.SendMessage(
                new S2CMessage.Prose(0L, "tell", text, List.of(), null, "normal")));
            return true;
        }
    }

    // --- I/O helpers (plain text, no Telnet IAC) ---

    private void sendLine(String text) throws IOException {
        TelnetCodec.sendLine(out, text);
    }

    private void sendRaw(String text) throws IOException {
        TelnetCodec.sendRaw(out, text);
    }

    /**
     * Read a line from the SSH input stream.
     * Unlike TelnetCodec.readLine, no IAC stripping needed — SSH handles framing.
     */
    // Interactive ssh clients send a bare CR when the user presses Enter
    // (no line discipline on the raw channel), so CR must terminate the
    // line too — only ever waiting for LF hangs every human typist.
    // Scripted/piped input sends LF (or CRLF), which is why tests passed.
    private boolean lastLineEndedWithCR = false;

    private String readLine() throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                if (lastLineEndedWithCR && sb.isEmpty()) {
                    // the LF half of a CRLF whose CR already ended the
                    // previous line — not a new, empty line
                    lastLineEndedWithCR = false;
                    continue;
                }
                lastLineEndedWithCR = false;
                return sb.toString().stripTrailing();
            }
            if (b == '\r') {
                lastLineEndedWithCR = true;
                return sb.toString().stripTrailing();
            }
            lastLineEndedWithCR = false;
            sb.append((char) b);
        }
        return null;
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
}
