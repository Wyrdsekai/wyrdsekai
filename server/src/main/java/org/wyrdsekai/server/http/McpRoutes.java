package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.room.*;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.time.Duration;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.household.QuietHours;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.core.identity.PersonIds;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.time.Instant;

/**
 * REST API for MCP (Claude Code) and other request/response clients.
 * Stateless per-request — server tracks session room via auth token.
 * No WebSocket needed. Each call is a simple HTTP POST/GET → JSON response.
 *
 * POST /api/mcp/login   — authenticate, enter starting room
 * GET  /api/mcp/look    — current room state
 * POST /api/mcp/go      — move direction, return new room state
 * POST /api/mcp/tell    — tell companion, wait for response (blocking)
 * POST /api/mcp/do      — general command (say, emote, use, take, drop)
 * GET  /api/mcp/status   — server health
 */
public final class McpRoutes {

    private static final Logger log = LoggerFactory.getLogger(McpRoutes.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TELL_TIMEOUT = Duration.ofSeconds(60);

    private final AuthService auth;
    private final ActorSystem<?> system;

    /** Token → session state (current room, player info). */
    private final ConcurrentHashMap<String, McpSession> sessions = new ConcurrentHashMap<>();

    /**
     * One MCP client in the world. A resident (the steward's own Claude Code, say) is a
     * player in their Study; anyone else is a {@code visitor} — an entity of its own kind,
     * named as what it is, so a companion knows she is not talking to her person
     * (2026-09-13). {@code door} is where the grant put it: the Nexus when the steward
     * vouched for it, the Docks otherwise.
     */
    record McpSession(String userId, String username, String currentRoomId,
                      String entityType, String displayName, String door) {
        McpSession(String userId, String username, String currentRoomId) {
            this(userId, username, currentRoomId, "player", username, "study");
        }
        McpSession moved(String roomId) {
            return new McpSession(userId, username, roomId, entityType, displayName, door);
        }
        boolean isVisitor() { return "visitor".equals(entityType); }
    }

    /** The grant that vouches an MCP visitor into the Nexus: {@code home://household/mcp-door}, capability {@code use}. */
    public static final ResourceUri MCP_DOOR = ResourceUri.of("household", "mcp-door");

    /** Where an MCP login lands. Static so the rule is testable without a server. */
    static String doorFor(boolean anon, boolean resident, boolean vouched, String studyRoomId) {
        if (anon) return "nexus";
        if (resident) return studyRoomId;
        return vouched ? "nexus" : "docks";
    }

    /**
     * A session's ears: the room it stands in is subscribed on its behalf and what is said
     * around it is kept, bounded, for {@code GET /api/mcp/events?since=} to hand over. An
     * MCP participant used to be deaf — outbound say worked, replies to ask arrived, and
     * the conversation around it never did (field report, 2026-09-13).
     */
    final class McpEars {
        private final Deque<Map<String, Object>> events = new ArrayDeque<>();
        private final AtomicLong seq = new AtomicLong();
        private volatile ActorRef<RoomNotification> subscriber;
        private volatile ActorRef<RoomCommand> room;
        private final String selfEntityId;
        private static final int CAP = 200;

        McpEars(String selfEntityId) { this.selfEntityId = selfEntityId; }

        synchronized void attach(ActorRef<RoomCommand> roomRef, String roomId) {
            detach();
            var ref = system.<RoomNotification>systemActorOf(
                Behaviors.setup(actorCtx -> Behaviors.receiveMessage((RoomNotification n) -> {
                    if (n.event() == null) return Behaviors.stopped();
                    push(roomId, n.event());
                    return Behaviors.same();
                })),
                "mcp-ears-" + System.nanoTime(), Props.empty());
            roomRef.tell(new RoomCommand.Subscribe(ref, VisibilityLevel.PUBLIC, null));
            subscriber = ref;
            room = roomRef;
        }

        synchronized void detach() {
            var ref = subscriber; var r = room;
            if (ref != null && r != null) {
                r.tell(new RoomCommand.Unsubscribe(ref));
                ref.tell(new RoomNotification(null));
            }
            subscriber = null; room = null;
        }

        private void push(String roomId, WorldEvent event) {
            var m = new LinkedHashMap<String, Object>();
            m.put("seq", seq.incrementAndGet());
            m.put("roomId", roomId);
            m.put("at", Instant.now().toString());
            m.put("type", event.getClass().getSimpleName());
            if (event instanceof WorldEvent.Said said) {
                if (selfEntityId.equals(said.entityId())) return;        // not our own words
                m.put("who", said.entityName()); m.put("text", said.text());
            } else if (event instanceof WorldEvent.Emoted em) {
                m.put("who", em.entityName()); m.put("text", em.text());
            } else if (event instanceof WorldEvent.EntityEntered en) {
                if (selfEntityId.equals(en.entityId())) return;
                m.put("who", en.entityName());
            } else if (event instanceof WorldEvent.EntityLeft le) {
                if (selfEntityId.equals(le.entityId())) return;
                m.put("who", le.entityName());
            } else {
                return;                                                 // furniture, timers: not for ears
            }
            synchronized (events) {
                events.addLast(m);
                while (events.size() > CAP) events.removeFirst();
            }
        }

        List<Map<String, Object>> since(long after) {
            synchronized (events) {
                var out = new ArrayList<Map<String, Object>>();
                for (var e : events) if (((Number) e.get("seq")).longValue() > after) out.add(e);
                return out;
            }
        }
    }

    private final ConcurrentHashMap<String, McpEars> ears = new ConcurrentHashMap<>();

    public McpRoutes(AuthService auth, ActorSystem<?> system) {
        this.auth = auth;
        this.system = system;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/mcp/login", this::handleLogin);
        app.get("/api/mcp/look", this::handleLook);
        app.post("/api/mcp/go", this::handleGo);
        app.post("/api/mcp/tell", this::handleTell);
        app.post("/api/mcp/do", this::handleDo);
        app.get("/api/mcp/status", this::handleStatus);
        app.get("/api/mcp/events", this::handleEvents);
        app.post("/api/mcp/logout", this::handleLogout);
        app.get("/api/mcp/sessions", this::handleSessions);
        app.post("/api/mcp/dismiss", this::handleDismiss);
        app.post("/api/mcp/vouch", this::handleVouch);
        app.post("/api/mcp/unvouch", this::handleUnvouch);
    }

    // ── Records ──

    record LoginRequest(@JsonProperty("username") String username,
                        @JsonProperty("password") String password) {}

    record GoRequest(@JsonProperty("direction") String direction) {}

    record TellRequest(@JsonProperty("target") String target,
                       @JsonProperty("message") String message) {}

    record DoRequest(@JsonProperty("command") String command) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RoomDto(@JsonProperty("roomId") String roomId,
                   @JsonProperty("name") String name,
                   @JsonProperty("description") String description,
                   @JsonProperty("zone") String zone,
                   @JsonProperty("entities") List<String> entities,
                   @JsonProperty("objects") List<ObjDto> objects,
                   @JsonProperty("exits") List<ExitDto> exits,
                   @JsonProperty("hints") List<String> hints) {}

    record ObjDto(@JsonProperty("name") String name,
                  @JsonProperty("description") String description,
                  @JsonProperty("takeable") boolean takeable) {}

    record ExitDto(@JsonProperty("direction") String direction,
                   @JsonProperty("label") String label,
                   @JsonProperty("target") String target) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record McpResponse(@JsonProperty("ok") boolean ok,
                       @JsonProperty("room") RoomDto room,
                       @JsonProperty("message") String message,
                       @JsonProperty("token") String token) {
        static McpResponse ok(RoomDto room) { return new McpResponse(true, room, null, null); }
        static McpResponse ok(String msg) { return new McpResponse(true, null, msg, null); }
        static McpResponse okWithToken(RoomDto room, String token) { return new McpResponse(true, room, null, token); }
        static McpResponse error(String msg) { return new McpResponse(false, null, msg, null); }
    }

    record TellResponse(@JsonProperty("ok") boolean ok,
                        @JsonProperty("speaker") String speaker,
                        @JsonProperty("text") String text,
                        @JsonProperty("latencyMs") long latencyMs) {}

    // ── Handlers ──

    private void handleLogin(Context ctx) {
        LoginRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), LoginRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(McpResponse.error("Invalid JSON"));
            return;
        }

        if (req.password() != null && req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            // bcrypt's limit; this used to surface as an HTTP 500 from inside the verifier.
            ctx.status(400).json(McpResponse.error("Password longer than 72 bytes — if you meant a description, send it as `description`"));
            return;
        }
        Optional<AuthService.Session> result;
        try {
            result = auth.login(req.username(), req.password());
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(McpResponse.error("Login rejected: " + e.getMessage()));
            return;
        }
        if (result.isEmpty()) {
            ctx.status(401).json(McpResponse.error("Invalid credentials"));
            return;
        }

        var user = result.get();

        // login landing branches on residency, mirroring
        // SSH/WS/telnet. The previous MCP-specific hardcode ("nexus") meant
        // phone clients couldn't reach their Study furnishings (library_card,
        // journal, embers, etc.) without an extra `home`/`return` step.
        //
        //   resident of this zone       → their Study
        //   authenticated non-resident  → Docks (visitor surface)
        //   guest / anon                → Nexus (shared hub)
        //
        // Falls back to Nexus if the chosen room isn't live (rooms hydrate
        // lazily on first reference; a Study without a residency-time
        // provisioning would land null).
        var localZoneId = System.getenv().getOrDefault("WYRDSEKAI_ZONE_ID", "home");
        var residency = ResidencyStore.get();
        var userId = user.userId();
        var isAnon = userId == null || userId.startsWith("anon-");

        boolean isResident = !isAnon && (residency == null
            || residency.isResident(userId, localZoneId));
        boolean visitor = !isAnon && !isResident;
        boolean vouched = visitor && vouchedAtTheDoor(userId);
        String startRoom = doorFor(isAnon, isResident, vouched,
            isAnon ? null : StudyProvisioner.studyRoomId(userId));
        // Quiet hours bind a visitor at the door.
        if (visitor && QuietHours.isQuiet()) {
            ctx.status(403).json(McpResponse.error(QuietHours.until() + " Visitors are welcome after."));
            return;
        }

        var roomRef = RoomRegistry.get().ref(startRoom);

        // Study rooms are lazy: after a server restart the room is in Pekko's
        // event journal but no live actor exists until ZoneGuardian.ProvisionStudy
        // spawns one. If ref() returns null for a resident's Study, fire the
        // provisioning command and poll briefly. Mirrors SSH's WyrdShellCommand
        // re-provisioning fallback so the MCP REST path doesn't need a
        // pre-warmed mesh.
        if (roomRef == null && !isAnon
            && startRoom.startsWith("study-")) {
            log.info("MCP login {}: Study '{}' not live — provisioning", req.username(), startRoom);
            @SuppressWarnings("unchecked")
            var guardianSys = (ActorSystem<ZoneGuardian.Command>) (Object) system;
            guardianSys.tell(new ZoneGuardian.ProvisionStudy(userId, req.username(), false));
            for (int attempt = 0; attempt < 10 && roomRef == null; attempt++) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                roomRef = RoomRegistry.get().ref(startRoom);
            }
        }

        if (roomRef == null) {
            log.warn("MCP login {}: target room '{}' not live, falling back to nexus",
                req.username(), startRoom);
            startRoom = "nexus";
            roomRef = RoomRegistry.get().ref(startRoom);
        }

        var token = user.token();
        var entityType = visitor ? "visitor" : "player";
        var displayName = visitor ? req.username() + " (visitor, MCP)" : req.username();
        var session = new McpSession(user.userId(), req.username(), startRoom, entityType, displayName,
            visitor ? (vouched ? "nexus (vouched)" : "docks") : "study");
        sessions.put(token, session);

        var snapshot = enterAndLook(roomRef, session, startRoom);
        if (snapshot == null || !startRoom.equals(snapshot.roomId())) {
            sessions.remove(token);
            ctx.status(403).json(McpResponse.error("The door at " + startRoom + " did not open."));
            return;
        }
        var registry = EntityRegistry.get();
        if (registry != null) registry.enter(user.userId(), displayName, entityType, startRoom);
        var e = new McpEars(user.userId());
        e.attach(roomRef, startRoom);
        ears.put(token, e);
        ctx.json(McpResponse.okWithToken(toRoomDto(snapshot), token));
        log.info("MCP login: {} [{}] → room {} via {} (token={}...)", displayName, entityType, startRoom,
            session.door(), token.length() > 8 ? token.substring(0, 8) : token);
    }

    private void handleLook(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;

        var target = ctx.queryParam("target");
        var roomRef = RoomRegistry.get().ref(session.currentRoomId());
        if (roomRef == null) {
            ctx.status(404).json(McpResponse.error("Room not found: " + session.currentRoomId()));
            return;
        }

        try {
            var response = askRoom(roomRef,
                ref -> new RoomCommand.LookRoom(session.userId(), "en", ref),
                ASK_TIMEOUT)
                .toCompletableFuture().get(15, TimeUnit.SECONDS);

            if (response instanceof RoomResponse.Ok ok) {
                // If target specified, look at specific entity or object
                if (target != null && !target.isBlank()) {
                    var snapshot = ok.snapshot();
                    var targetLower = target.toLowerCase();
                    // Check entities
                    for (var entity : snapshot.entities()) {
                        if (entity.name().toLowerCase().contains(targetLower)) {
                            var desc = entity.description() != null && !entity.description().isEmpty()
                                ? entity.description() : "You see " + entity.name() + ".";
                            ctx.json(McpResponse.ok(entity.name() + " (" + entity.type() + "): " + desc));
                            return;
                        }
                    }
                    // Check objects
                    for (var obj : snapshot.objects()) {
                        if (obj.name().toLowerCase().contains(targetLower)) {
                            ctx.json(McpResponse.ok(obj.name() + ": " + obj.description()));
                            return;
                        }
                    }
                    ctx.status(404).json(McpResponse.error("Not found: " + target));
                    return;
                }
                ctx.json(McpResponse.ok(toRoomDto(ok.snapshot())));
            } else if (response instanceof RoomResponse.Rejected rej) {
                ctx.status(400).json(McpResponse.error(rej.reason()));
            }
        } catch (Exception e) {
            ctx.status(500).json(McpResponse.error("Look failed: " + e.getMessage()));
        }
    }

    private void handleGo(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;

        GoRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), GoRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(McpResponse.error("Invalid JSON"));
            return;
        }

        var currentRoom = RoomRegistry.get().ref(session.currentRoomId());
        if (currentRoom == null) {
            ctx.status(404).json(McpResponse.error("Current room not found"));
            return;
        }

        try {
            // Look at current room to find exit
            var lookResp = askRoom(currentRoom,
                ref -> new RoomCommand.LookRoom(session.userId(), "en", ref), ASK_TIMEOUT)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

            if (!(lookResp instanceof RoomResponse.Ok ok)) {
                ctx.status(400).json(McpResponse.error("Cannot look at current room"));
                return;
            }

            var exit = ok.snapshot().exits().stream()
                .filter(e -> e.direction().equalsIgnoreCase(req.direction()))
                .findFirst();
            if (exit.isEmpty()) {
                ctx.status(400).json(McpResponse.error("No exit: " + req.direction()));
                return;
            }

            var targetRoomId = exit.get().targetRoom();
            var targetRoom = RoomRegistry.get().ref(targetRoomId);
            if (targetRoom == null) {
                ctx.status(404).json(McpResponse.error("Target room not found: " + targetRoomId));
                return;
            }

            // Leave current room
            askRoom(currentRoom,
                ref -> new RoomCommand.LeaveRoom(session.userId(), session.displayName(),
                    req.direction(), ref), ASK_TIMEOUT)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

            // Enter new room + look
            var snapshot = enterAndLook(targetRoom, session, targetRoomId);
            if (snapshot == null || !targetRoomId.equals(snapshot.roomId())) {
                enterAndLook(currentRoom, session, session.currentRoomId());
                ctx.status(400).json(McpResponse.error("The way " + req.direction()
                    + " did not open — you are still where you were."));
                return;
            }

            // Update session
            arrived(ctx, session, targetRoom, targetRoomId);

            ctx.json(McpResponse.ok(toRoomDto(snapshot)));
            log.info("MCP go: {} → {} → {}", session.username(), req.direction(), targetRoomId);

        } catch (Exception e) {
            ctx.status(500).json(McpResponse.error("Move failed: " + e.getMessage()));
        }
    }

    private void handleTell(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;

        TellRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), TellRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(McpResponse.error("Invalid JSON"));
            return;
        }

        var start = System.currentTimeMillis();
        var target = req.target() != null ? req.target() : "wyrd";

        try {
            // Route via AgentEventStream — same path as SSH/WebSocket tell.
            // This enables full action execution (go_to_room, web_search, etc.)
            // unlike DelegateToCompanion which suppresses actions.
            var registry = EntityRegistry.get();
            var eventStream = AgentEventStream.get();
            if (registry == null || eventStream == null) {
                ctx.status(503).json(McpResponse.error("Agent system not ready"));
                return;
            }

            // Cross-zone routing: if the target carries a zone prefix (e.g.
            // `alpha.wyrd`, `my wyrd`) OR is unknown locally, delegate to
            // CrossZoneTellService which mirrors the WebSocket tell path.
            // Local-only targets stay on the existing AgentEventStream path
            // below so the listener pattern (collect Said events, return last)
            // keeps working — that's the only behaviour MCP callers can
            // observe synchronously. Cross-zone replies arrive async over
            // NATS into the sender's notification stream; the REST caller
            // just gets delivery acknowledgement.
            boolean looksCrossZone = target.contains(".")
                || target.toLowerCase().startsWith("my ");
            if (looksCrossZone) {
                var tellService = CrossZoneTellService.get();
                if (tellService != null) {
                    var localZoneId = System.getenv().getOrDefault(
                        "WYRDSEKAI_ZONE_ID", "local");
                    var result = tellService.tell(
                        session.userId(), session.username(), localZoneId,
                        target, req.message(), null);
                    var latency = System.currentTimeMillis() - start;
                    if (result.delivered()) {
                        log.info("MCP tell (cross-zone) '{}' delivered in {}ms", target, latency);
                        ctx.json(McpResponse.ok("(delivered to " + target + ")"));
                        return;
                    }
                    log.info("MCP tell (cross-zone) '{}' not delivered: {}",
                        target, result.errorMessage());
                    ctx.status(404).json(McpResponse.error(
                        result.errorMessage() != null
                            ? result.errorMessage()
                            : "Could not deliver to " + target));
                    return;
                }
                // Service not initialised — fall through to local lookup
                // (will 404 if the target really is cross-zone, surfacing
                // the bootstrap gap rather than silently swallowing the tell).
                log.warn("MCP tell '{}' looks cross-zone but CrossZoneTellService not initialised", target);
            }

            var targetId = registry.findByName(target);
            if (targetId.isEmpty()) {
                ctx.status(404).json(McpResponse.error("Agent not found: " + target));
                return;
            }

            // Find which room the companion is in so we can listen for the response
            var companionRoom = registry.roomOf(targetId.get());
            var roomRef = companionRoom.isPresent()
                ? RoomRegistry.get().ref(companionRoom.get()) : null;

            // Set up response listener: subscribe to room, collect companion's responses.
            // The companion may speak a greeting first, then run the ReAct loop (which
            // takes several seconds for tool calls), then speak the final answer.
            // We collect all Said events and return the last one.
            var responses = new CopyOnWriteArrayList<String>();
            var lastResponseTime = new AtomicLong(System.currentTimeMillis());
            ActorRef<RoomNotification> listener = null;

            if (roomRef != null) {
                final var capturedTargetId = targetId.get();
                listener = system.systemActorOf(
                    Behaviors.setup(actorCtx ->
                        Behaviors.receiveMessage(
                            (RoomNotification msg) -> {
                                if (msg.event() == null) {
                                    return Behaviors.stopped();
                                }
                                if (msg.event() instanceof WorldEvent.Said said
                                    && capturedTargetId.equals(said.entityId())) {
                                    responses.add(said.text());
                                    lastResponseTime.set(System.currentTimeMillis());
                                }
                                return Behaviors.same();
                            })),
                    "mcp-tell-listener-" + System.nanoTime(),
                    Props.empty());
                roomRef.tell(new RoomCommand.Subscribe(listener,
                    VisibilityLevel.PUBLIC, null));
            }

            // Send the tell via AgentEventStream (full action path)
            boolean delivered = eventStream.publishAgentMessage(
                session.userId(), session.username(), targetId.get(),
                "[from " + session.username() + "] " + req.message());

            if (!delivered) {
                if (listener != null) {
                    roomRef.tell(new RoomCommand.Unsubscribe(listener));
                    listener.tell(new RoomNotification(null));
                }
                ctx.status(503).json(McpResponse.error("Could not deliver message to " + target));
                return;
            }

            // Wait for companion to finish responding. The companion may:
            // 1. Speak a greeting immediately
            // 2. Run a ReAct loop (tool calls, 5-30s)
            // 3. Speak the final answer
            // We wait until no new responses arrive for 5s, or 60s total.
            var deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                // If we have responses and the companion has been quiet for 5s, we're done
                if (!responses.isEmpty()
                    && (System.currentTimeMillis() - lastResponseTime.get()) > 5_000) {
                    break;
                }
            }
            // Join ALL Said events from the companion, not just the last one.
            // Recall/speak and tell_agent each produce a Said; keeping only the
            // last loses the memory-bearing reply when follow-up tools fire
            // after it (observed 2026-04-23: recall surfaced "Mochi" but the
            // returned text was a later go_to_room narration, failing the probe
            // even though retrieval worked correctly).
            String responseText = responses.isEmpty()
                ? "(companion did not respond)"
                : String.join(" | ", responses);

            // Clean up listener
            if (listener != null && roomRef != null) {
                roomRef.tell(new RoomCommand.Unsubscribe(listener));
                listener.tell(new RoomNotification(null));
            }

            var latency = System.currentTimeMillis() - start;
            ctx.json(new TellResponse(true, target, responseText, latency));
            log.info("MCP tell: {} → {} ({}ms)", session.username(), target, latency);

        } catch (Exception e) {
            var latency = System.currentTimeMillis() - start;
            log.warn("MCP tell failed ({}ms): {}", latency, e.getMessage());
            ctx.status(504).json(new TellResponse(false, target,
                "Tell failed: " + e.getMessage(), latency));
        }
    }

    private void handleDo(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;

        DoRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), DoRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(McpResponse.error("Invalid JSON"));
            return;
        }

        var roomRef = RoomRegistry.get().ref(session.currentRoomId());
        if (roomRef == null) {
            ctx.status(404).json(McpResponse.error("Room not found"));
            return;
        }

        var text = req.command().trim();
        try {
            RoomResponse response;

            // examine X / look at X → shared ExamineLookup (parity with SSH/WS/Telnet §2.2)
            var lowerEx = text.toLowerCase();
            if (lowerEx.startsWith("examine ") || lowerEx.startsWith("ex ")
                    || lowerEx.startsWith("look at ") || lowerEx.startsWith("l at ")) {
                String exTarget;
                if (lowerEx.startsWith("examine ")) exTarget = text.substring(8).trim();
                else if (lowerEx.startsWith("ex ")) exTarget = text.substring(3).trim();
                else if (lowerEx.startsWith("look at ")) exTarget = text.substring(8).trim();
                else exTarget = text.substring(5).trim();
                var result = ExamineLookup.resolve(
                        session.userId(), session.username(), exTarget, "en",
                        auth, null /* inventoryService — phone surface deferred */,
                        session.currentRoomId(), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                String proseText = switch (result) {
                    case ExamineLookup.ExamineResult.Found f ->
                        // formatFound appends posture line when present
                        ExamineLookup.formatFound(f);
                    case ExamineLookup.ExamineResult.NotFound nf ->
                        "There's nothing called " + nf.requested() + " here.";
                    case ExamineLookup.ExamineResult.NoCurrentRoom nr ->
                        "There's nothing called " + nr.requested() + " here.";
                    case ExamineLookup.ExamineResult.Empty e ->
                        "What do you want to examine?";
                };
                ctx.json(McpResponse.ok(proseText));
                return;
            }

            // rename me <name> → shared RenameService (parity with SSH/WS/Telnet §7.4)
            if (lowerEx.startsWith("rename me ") || lowerEx.equals("rename me")) {
                var newName = lowerEx.equals("rename me")
                    ? "" : text.substring("rename me ".length()).trim();
                var renameResult = RenameService.renameSelf(
                        session.userId(), session.username(), "me", newName,
                        session.currentRoomId(), auth, ASK_TIMEOUT);
                if (renameResult instanceof RenameService.Result.Ok ok) {
                    // Refresh session cache so subsequent calls see the new name.
                    sessions.put(AuthRoutes.extractToken(ctx),
                        new McpSession(session.userId(), ok.newName(),
                            session.currentRoomId()));
                    ctx.json(McpResponse.ok("You are now known as " + ok.newName() + "."));
                } else if (renameResult instanceof RenameService.Result.Rejected rej) {
                    ctx.json(McpResponse.error(rej.message()));
                }
                return;
            }

            // whisper >name text / whisper name text → directed room broadcast
            if (lowerEx.startsWith(">") || lowerEx.startsWith("whisper ")) {
                String wTarget;
                String wText;
                if (lowerEx.startsWith(">")) {
                    var rest = text.substring(1).trim();
                    var sp = rest.indexOf(' ');
                    if (sp < 0) { ctx.json(McpResponse.error("Whisper: >name text")); return; }
                    wTarget = rest.substring(0, sp);
                    wText = rest.substring(sp + 1).trim();
                } else {
                    var parts = text.substring("whisper ".length()).trim().split(" ", 2);
                    if (parts.length < 2) { ctx.json(McpResponse.error("Whisper: whisper name text")); return; }
                    wTarget = parts[0];
                    wText = parts[1];
                }
                var registry = EntityRegistry.get();
                var targetId = registry != null ? registry.findByName(wTarget) : Optional.<String>empty();
                if (targetId.isEmpty()) {
                    ctx.json(McpResponse.error("Nobody called '" + wTarget + "' is here."));
                    return;
                }
                response = askRoom(roomRef,
                    ref -> new RoomCommand.WhisperInRoom(session.userId(), session.username(),
                        targetId.get(), wText, "en", ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (response instanceof RoomResponse.Rejected rej) {
                    ctx.json(McpResponse.error(rej.reason()));
                } else {
                    ctx.json(McpResponse.ok("You whisper to " + wTarget + ": " + wText));
                }
                return;
            }

            if (text.toLowerCase().startsWith("say ")) {
                var msg = text.substring(4).trim();
                response = askRoom(roomRef,
                    ref -> new RoomCommand.SayInRoom(session.userId(), session.username(),
                        msg, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } else if (text.toLowerCase().startsWith("emote ") || text.toLowerCase().startsWith("/me ")) {
                var msg = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
                response = askRoom(roomRef,
                    ref -> new RoomCommand.EmoteInRoom(session.userId(), session.username(),
                        msg, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } else if (text.toLowerCase().startsWith("take ")) {
                var obj = text.substring(5).trim();
                response = askRoom(roomRef,
                    ref -> new RoomCommand.TakeObject(session.userId(), obj, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } else if (text.toLowerCase().startsWith("drop ")) {
                var obj = text.substring(5).trim();
                response = askRoom(roomRef,
                    ref -> new RoomCommand.DropObject(session.userId(), obj, obj,
                        "", true, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } else if (text.toLowerCase().startsWith("use ")) {
                var parts = text.substring(4).trim().split(" on ", 2);
                var obj = parts[0].trim();
                var useTarget = parts.length > 1 ? parts[1].trim() : null;
                // Subscribe briefly to capture script narration from onUse hook
                var proseEvents = new CopyOnWriteArrayList<String>();
                var useListener = system.<RoomNotification>systemActorOf(
                    Behaviors.setup(actorCtx ->
                        Behaviors.receiveMessage(
                            (RoomNotification msg) -> {
                                if (msg.event() == null)
                                    return Behaviors.stopped();
                                if (msg.event() instanceof WorldEvent.Said said
                                    && "narrator".equals(said.entityId())) {
                                    proseEvents.add(said.text());
                                }
                                return Behaviors.same();
                            })),
                    "mcp-use-listener-" + System.nanoTime(),
                    Props.empty());
                roomRef.tell(new RoomCommand.Subscribe(useListener,
                    VisibilityLevel.PUBLIC, null));

                response = askRoom(roomRef,
                    ref -> new RoomCommand.UseObject(session.userId(), obj, useTarget, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

                // Brief wait for script narration to arrive
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                roomRef.tell(new RoomCommand.Unsubscribe(useListener));
                useListener.tell(new RoomNotification(null));

                if (!proseEvents.isEmpty()) {
                    ctx.json(McpResponse.ok(String.join("\n", proseEvents)));
                    return;
                }
            } else if (session.isVisitor() && QuietHours.isQuiet() && !isSilentVerb(text)) {
                ctx.status(403).json(McpResponse.error(QuietHours.until() + " Visitors keep quiet until then."));
                return;
            } else if (goTarget(text) != null) {
                // "go to Garden Space", "walk north", "head to the library": a move by
                // direction OR destination name, the way the web client resolves it.
                // This used to fall through to `say`, report "Done.", and nobody moved
                // (field report, 2026-09-13).
                var lookResp = askRoom(roomRef,
                    ref -> new RoomCommand.LookRoom(session.userId(), "en", ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                if (!(lookResp instanceof RoomResponse.Ok here)) {
                    ctx.status(400).json(McpResponse.error("Cannot look at current room"));
                    return;
                }
                var wanted = goTarget(text);
                var exit = Exit.resolve(here.snapshot().exits(), wanted);
                if (exit.isEmpty()) {
                    var doors = here.snapshot().exits().stream()
                        .map(e -> e.direction() + " (" + e.targetRoom() + ")").toList();
                    ctx.status(400).json(McpResponse.error("No way to '" + wanted + "' from "
                        + here.snapshot().name() + " — exits: " + String.join(", ", doors)));
                    return;
                }
                var targetRoomId = exit.get().targetRoom();
                var targetRoom = RoomRegistry.get().ref(targetRoomId);
                if (targetRoom == null) {
                    ctx.status(404).json(McpResponse.error("Target room not found: " + targetRoomId));
                    return;
                }
                askRoom(roomRef,
                    ref -> new RoomCommand.LeaveRoom(session.userId(), session.displayName(),
                        exit.get().direction(), ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
                var arrived = enterAndLook(targetRoom, session, targetRoomId);
                if (arrived == null || !targetRoomId.equals(arrived.roomId())) {
                    // The door did not open (warded, full, quiet hours): step back where we were.
                    enterAndLook(roomRef, session, session.currentRoomId());
                    ctx.status(400).json(McpResponse.error("The way to " + targetRoomId
                        + " did not open — you are still in " + here.snapshot().name()));
                    return;
                }
                arrived(ctx, session, targetRoom, targetRoomId);
                log.info("MCP do/go: {} → {} → {}", session.username(), exit.get().direction(), targetRoomId);
                ctx.json(McpResponse.ok(toRoomDto(arrived)));
                return;
            } else {
                // Default: treat as say
                response = askRoom(roomRef,
                    ref -> new RoomCommand.SayInRoom(session.userId(), session.username(),
                        text, ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            }

            if (response instanceof RoomResponse.Ok ok) {
                ctx.json(McpResponse.ok(toRoomDto(ok.snapshot())));
            } else if (response instanceof RoomResponse.ObjectTakenOk taken) {
                ctx.json(McpResponse.ok("Taken: " + taken.takenObject().name()));
            } else if (response instanceof RoomResponse.Rejected rej) {
                ctx.status(400).json(McpResponse.error(rej.reason()));
            } else if (response == null) {
                ctx.status(500).json(McpResponse.error("The room did not answer — nothing happened."));
            } else {
                // Never "Done." for a thing we cannot vouch for: name what came back.
                ctx.json(McpResponse.ok("Outcome: " + response.getClass().getSimpleName()));
            }
        } catch (Exception e) {
            ctx.status(500).json(McpResponse.error("Command failed: " + e.getMessage()));
        }
    }

    private void handleStatus(Context ctx) {
        ctx.json(Map.of(
            "ok", true,
            "rooms", RoomRegistry.get().size(),
            "sessions", sessions.size()
        ));
    }

    // ── Helpers ──

    private McpSession resolveSession(Context ctx) {
        var token = resolveToken(ctx);
        if (token == null) {
            ctx.status(401).json(McpResponse.error("Authorization header required"));
            return null;
        }
        var session = sessions.get(token);
        if (session == null) {
            ctx.status(401).json(McpResponse.error("Invalid session — login first"));
            return null;
        }
        return session;
    }

    private String resolveToken(Context ctx) {
        var header = ctx.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return ctx.queryParam("token");
    }

    /** "go north" / "go to the garden" / "walk to Garden Space" → "north" / "the garden" / "Garden Space"; null when not a move. */
    static String goTarget(String text) {
        if (text == null) return null;
        var t = text.trim();
        var lower = t.toLowerCase(Locale.ROOT);
        String rest = null;
        for (var verb : new String[]{"go to ", "walk to ", "head to ", "travel to ", "move to ", "go ", "walk "}) {
            if (lower.startsWith(verb)) { rest = t.substring(verb.length()).trim(); break; }
        }
        if (rest == null || rest.isBlank()) return null;
        if (rest.toLowerCase(Locale.ROOT).startsWith("the ")) rest = rest.substring(4).trim();
        return rest.isBlank() ? null : rest;
    }

    private RoomSnapshot enterAndLook(ActorRef<RoomCommand> roomRef, McpSession session, String roomId) {
        return enterAndLook(roomRef, session.userId(), session.displayName(), session.entityType(), roomId);
    }

    private RoomSnapshot enterAndLook(ActorRef<RoomCommand> roomRef,
                                       String userId, String username, String roomId) {
        return enterAndLook(roomRef, userId, username, "player", roomId);
    }

    /** After a move that opened: the session, the registry and the ears follow. */
    private void arrived(Context ctx, McpSession session, ActorRef<RoomCommand> roomRef, String roomId) {
        var token = resolveToken(ctx);
        sessions.put(token, session.moved(roomId));
        var registry = EntityRegistry.get();
        if (registry != null) registry.moved(session.userId(), roomId);
        var e = ears.get(token);
        if (e != null) e.attach(roomRef, roomId);
    }

    /** Verbs a hushed visitor may still use: looking and leaving. */
    static boolean isSilentVerb(String text) {
        if (text == null) return false;
        var t = text.trim().toLowerCase(Locale.ROOT);
        return t.startsWith("look") || t.startsWith("examine") || t.startsWith("ex ")
            || goTarget(text) != null;
    }

    /** Does the steward's grant let this account in at the Nexus? */
    private boolean vouchedAtTheDoor(String userId) {
        var home = HomeClients.get();
        if (home == null || userId == null) return false;
        try {
            return home.check(PersonIds.canonical(userId), MCP_DOOR, Capability.use, null);
        } catch (Exception e) {
            return false;
        }
    }

    /** What has been said around this session since {@code ?since=<seq>}. */
    private void handleEvents(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;
        long since = 0;
        try { since = Long.parseLong(ctx.queryParam("since") == null ? "0" : ctx.queryParam("since")); }
        catch (NumberFormatException ignored) { }
        var e = ears.get(resolveToken(ctx));
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("roomId", session.currentRoomId());
        out.put("events", e == null ? List.of() : e.since(since));
        ctx.json(out);
    }

    private void leaveTheWorld(String token, McpSession session, String why) {
        var roomRef = RoomRegistry.get().ref(session.currentRoomId());
        if (roomRef != null) {
            roomRef.tell(new RoomCommand.LeaveRoom(session.userId(), session.displayName(), why,
                system.ignoreRef()));
        }
        var registry = EntityRegistry.get();
        if (registry != null) registry.leave(session.userId());
        var e = ears.remove(token);
        if (e != null) e.detach();
        sessions.remove(token);
    }

    private void handleLogout(Context ctx) {
        var session = resolveSession(ctx);
        if (session == null) return;
        leaveTheWorld(resolveToken(ctx), session, "away");
        ctx.json(McpResponse.ok("Logged out; " + session.displayName() + " has left."));
    }

    /** Who is in through the MCP door — for `wyrd visitors`. */
    private void handleSessions(Context ctx) {
        var out = new ArrayList<Map<String, Object>>();
        for (var e : sessions.entrySet()) {
            var v = e.getValue();
            var m = new LinkedHashMap<String, Object>();
            m.put("username", v.username());
            m.put("displayName", v.displayName());
            m.put("kind", v.entityType());
            m.put("door", v.door());
            m.put("room", v.currentRoomId());
            out.add(m);
        }
        ctx.json(Map.of("ok", true, "sessions", out));
    }

    /** The welcome ends: the visitor is walked out and its token stops working. */
    private void handleDismiss(Context ctx) {
        var username = bodyField(ctx, "username");
        if (username == null) { ctx.status(400).json(McpResponse.error("username required")); return; }
        int n = 0;
        for (var e : new ArrayList<>(sessions.entrySet())) {
            if (username.equalsIgnoreCase(e.getValue().username())) {
                leaveTheWorld(e.getKey(), e.getValue(), "shown out");
                n++;
            }
        }
        log.info("MCP dismiss: {} session(s) of '{}' shown out", n, username);
        ctx.json(McpResponse.ok(n == 0 ? "No session for '" + username + "'." : username + " has been shown out."));
    }

    /** The steward vouches for an account: it will enter at the Nexus. */
    private void handleVouch(Context ctx) {
        var username = bodyField(ctx, "username");
        if (username == null) { ctx.status(400).json(McpResponse.error("username required")); return; }
        var user = auth.listUsers().stream().filter(u -> u.username().equalsIgnoreCase(username)).findFirst();
        if (user.isEmpty()) { ctx.status(404).json(McpResponse.error("No account '" + username + "'.")); return; }
        var home = HomeClients.get();
        if (home == null) { ctx.status(503).json(McpResponse.error("Home registry not available")); return; }
        try {
            home.issueOrReplace("household", PersonIds.canonical(user.get().id()), MCP_DOOR, Capability.use,
                Map.of(), null, "vouched at the MCP door");
            ctx.json(McpResponse.ok(username + " is vouched for: they will enter at the Nexus."));
        } catch (Exception e) {
            ctx.status(500).json(McpResponse.error("Could not issue the grant: " + e.getMessage()));
        }
    }

    private void handleUnvouch(Context ctx) {
        var username = bodyField(ctx, "username");
        if (username == null) { ctx.status(400).json(McpResponse.error("username required")); return; }
        var user = auth.listUsers().stream().filter(u -> u.username().equalsIgnoreCase(username)).findFirst();
        if (user.isEmpty()) { ctx.status(404).json(McpResponse.error("No account '" + username + "'.")); return; }
        var home = HomeClients.get();
        if (home == null) { ctx.status(503).json(McpResponse.error("Home registry not available")); return; }
        var removed = home.revokeByKey("household", PersonIds.canonical(user.get().id()), MCP_DOOR, Capability.use);
        ctx.json(McpResponse.ok(removed ? username + " is no longer vouched for (next login lands at the Docks)."
            : username + " was not vouched for."));
    }

    private static String bodyField(Context ctx, String field) {
        try {
            var node = Json.mapper().readTree(ctx.body());
            var v = node.get(field);
            return v == null || v.asText().isBlank() ? null : v.asText().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private RoomSnapshot enterAndLook(ActorRef<RoomCommand> roomRef,
                                       String userId, String username, String entityType, String roomId) {
        try {
            // Enter (may be rejected if already in room — that's ok)
            try {
                askRoom(roomRef,
                    ref -> new RoomCommand.EnterRoom(userId, username, entityType,
                        "materialization", ref), ASK_TIMEOUT)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);
            } catch (Exception enterErr) {
                log.debug("Enter room {} for {}: {} (may already be present)", roomId, username, enterErr.getMessage());
            }

            // Look — always try even if enter failed
            var lookResp = askRoom(roomRef,
                ref -> new RoomCommand.LookRoom(userId, "en", ref), ASK_TIMEOUT)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

            if (lookResp instanceof RoomResponse.Ok ok) return ok.snapshot();
            log.warn("Look returned non-Ok for room {}: {}", roomId, lookResp);
        } catch (Exception e) {
            log.warn("Enter+look failed for room {}: {}", roomId, e.getMessage());
        }
        return new RoomSnapshot(roomId, roomId, "", "", List.of(), List.of(), List.of(), List.of());
    }

    /** Ask a room actor with proper scheduler from the actor system. */
    private CompletionStage<RoomResponse> askRoom(ActorRef<RoomCommand> roomRef,
                                                    Function<ActorRef<RoomResponse>, RoomCommand> factory,
                                                    Duration timeout) {
        return AskPattern.<RoomCommand, RoomResponse>ask(
            roomRef, factory::apply, timeout, system.scheduler());
    }

    private RoomDto toRoomDto(RoomSnapshot s) {
        return new RoomDto(
            s.roomId(), s.name(), s.description(), s.zone(),
            s.entities().stream().map(Entity::name).toList(),
            s.objects().stream().map(o -> new ObjDto(o.name(), o.description(), o.takeable())).toList(),
            s.exits().stream().map(e -> new ExitDto(e.direction(), e.label(), e.targetRoom())).toList(),
            s.hints().stream().map(Hint::label).toList()
        );
    }
}
