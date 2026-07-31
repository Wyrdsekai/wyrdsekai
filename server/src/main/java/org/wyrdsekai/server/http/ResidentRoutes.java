package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CompanionActor.BridgeEvent;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.room.ZoneGuardian;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * HTTP endpoints for resident bridge connections (Channel Plugin, API adapters).
 *
 * <pre>
 *   GET  /api/resident/events  -- SSE stream of BridgeEvents (JSON)
 *   POST /api/resident/say     -- say text in current room
 *   POST /api/resident/emote   -- emote text in current room
 *   POST /api/resident/go      -- move to a room (by room ID or exit direction)
 *   GET  /api/resident/status  -- connection state
 * </pre>
 *
 * Model-agnostic — works with any CompanionActor that has bridge enabled.
 * Uses lazy companion lookup from ZoneGuardian's static registry, so routes
 * can be registered before companions are spawned.
 * Auth: Bearer token matching the configured resident token.
 */
public final class ResidentRoutes {

    private static final Logger log = LoggerFactory.getLogger(ResidentRoutes.class);

    private final String entityId;
    private final String token;
    private final ActorSystem<?> system;

    /**
     * @param entityId  entity ID of the companion to bridge (lazy lookup)
     * @param token     required Bearer token (from config). Empty/null = no auth required.
     * @param system    actor system for Ask pattern queries
     */
    public ResidentRoutes(String entityId, String token,
                          ActorSystem<?> system) {
        this.entityId = entityId;
        this.token = token;
        this.system = system;
    }

    /** Resolve the companion actor from the static registry. Returns null if not yet spawned. */
    private ActorRef<CompanionActor.Command> resolve() {
        return ZoneGuardian.getCompanionRef(null, entityId);
    }

    public void register(JavalinDefaultRoutingApi app) {
        // SSE event stream
        app.sse("/api/resident/events", this::handleEvents);

        // Action endpoints — speak and move via CompanionActor commands (lazy lookup)
        app.post("/api/resident/say", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var req = Json.mapper().readValue(ctx.body(), TextRequest.class);
            if (req.text() == null || req.text().isBlank()) {
                ctx.status(400).json(Map.of("error", "text is required"));
                return;
            }
            ref.tell(new CompanionActor.BridgeSay(req.text()));
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/resident/emote", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var req = Json.mapper().readValue(ctx.body(), TextRequest.class);
            if (req.text() == null || req.text().isBlank()) {
                ctx.status(400).json(Map.of("error", "text is required"));
                return;
            }
            ref.tell(new CompanionActor.BridgeEmote(req.text()));
            ctx.json(Map.of("ok", true));
        });

        app.post("/api/resident/go", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var req = Json.mapper().readValue(ctx.body(), GoRequest.class);
            if (req.direction() == null || req.direction().isBlank()) {
                ctx.status(400).json(Map.of("error", "direction is required"));
                return;
            }
            ref.tell(new CompanionActor.BridgeGo(req.direction()));
            ctx.json(Map.of("ok", true));
        });

        // Tell — send a message to the companion as if from a player
        app.post("/api/resident/tell", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var req = Json.mapper().readValue(ctx.body(), MessageRequest.class);
            if (req.message() == null || req.message().isBlank()) {
                ctx.status(400).json(Map.of("error", "message is required"));
                return;
            }
            ref.tell(new CompanionActor.ExternalTell("claude-code", "Claude", req.message()));
            ctx.json(Map.of("ok", true, "response", "Message delivered to companion."));
        });

        // Ask — send a message and wait for the companion's response
        app.post("/api/resident/ask", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var req = Json.mapper().readValue(ctx.body(), MessageRequest.class);
            if (req.message() == null || req.message().isBlank()) {
                ctx.status(400).json(Map.of("error", "message is required"));
                return;
            }
            int timeout = req.timeout() != null ? req.timeout() : 30;
            try {
                var response = AskPattern.ask(
                    ref,
                    (ActorRef<CompanionActor.BridgeTextResponse> replyTo) ->
                        new CompanionActor.BridgeAsk("claude-code", "Claude", req.message(), replyTo),
                    Duration.ofSeconds(timeout),
                    system.scheduler()
                ).toCompletableFuture().get(timeout, TimeUnit.SECONDS);
                ctx.json(Map.of("response", response.text()));
            } catch (Exception e) {
                ctx.json(Map.of("response", "Companion did not respond within " + timeout + "s."));
            }
        });

        // Look — get room description, entities, exits
        app.get("/api/resident/look", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            try {
                var response = AskPattern.ask(
                    ref,
                    (ActorRef<CompanionActor.BridgeStateResponse> replyTo) ->
                        new CompanionActor.BridgeQueryState(replyTo),
                    Duration.ofSeconds(5),
                    system.scheduler()
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);
                var result = new LinkedHashMap<String, Object>();
                result.put("roomId", response.roomId());
                result.put("roomName", response.roomName());
                result.put("description", response.roomDescription() != null
                    ? response.roomDescription() : "");
                result.put("entities", response.entities() != null
                    ? response.entities() : List.of());
                result.put("exits", response.exits() != null
                    ? response.exits() : List.of());
                ctx.json(result);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", "Look failed: " + e.getMessage()));
            }
        });

        // Oracle — query predictions
        app.get("/api/resident/oracle", ctx -> {
            if (!authorize(ctx)) return;
            var topic = ctx.queryParam("topic");
            var cache = OraclePredictionCache.get();
            if (cache == null) {
                ctx.json(Map.of("predictions", List.of()));
                return;
            }
            // Get predictions for the companion
            var predictions = cache.get(entityId);
            if (predictions == null) predictions = cache.get("global");
            if (predictions == null) {
                ctx.json(Map.of("predictions", List.of()));
                return;
            }
            // Filter by topic if provided
            if (topic != null && !topic.isBlank()) {
                var topicLower = topic.toLowerCase();
                predictions = predictions.stream()
                    .filter(p -> p.text().toLowerCase().contains(topicLower))
                    .toList();
            }
            ctx.json(Map.of("predictions", predictions.stream().map(p ->
                Map.of("description", p.text(),
                       "confidence", p.confidence(),
                       "type", p.category() != null ? p.category() : "unknown")
            ).toList()));
        });

        app.get("/api/resident/status", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) {
                ctx.status(503).json(Map.of("error", "resident not spawned yet"));
                return;
            }
            try {
                var response = AskPattern.ask(
                    ref,
                    (ActorRef<CompanionActor.BridgeStateResponse> replyTo) ->
                        new CompanionActor.BridgeQueryState(replyTo),
                    Duration.ofSeconds(5),
                    system.scheduler()
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);

                var result = new LinkedHashMap<String, Object>();
                result.put("status", response.state());
                result.put("roomId", response.roomId());
                result.put("roomName", response.roomName());
                result.put("energy", response.energy());
                result.put("sleeping", response.isSleeping());
                result.put("tanks", response.tanks());
                result.put("howYouFeel", response.howYouFeel());
                result.put("timestamp", Instant.now().toString());
                ctx.json(result);
            } catch (Exception e) {
                log.warn("Bridge state query failed: {}", e.getMessage());
                ctx.json(Map.of(
                    "status", "unknown",
                    "timestamp", Instant.now().toString(),
                    "error", "State query timed out"
                ));
            }
        });

        // ── Substrate Holder Endpoints (model-agnostic persistence) ──

        // Session context — what happened since the client's last session
        app.get("/api/resident/session-context", ctx -> {
            if (!authorize(ctx)) return;
            var clientId = ctx.queryParam("clientId");
            // Return recent significant events from the companion's perspective
            var ref = resolve();
            if (ref == null) { ctx.json(Map.of("summary", "Companion not yet active.")); return; }
            try {
                var response = AskPattern.ask(ref,
                    (ActorRef<CompanionActor.BridgeStateResponse> replyTo) ->
                        new CompanionActor.BridgeQueryState(replyTo),
                    Duration.ofSeconds(5), system.scheduler()
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);
                ctx.json(Map.of(
                    "summary", "Companion is " + response.state() + " in " + response.roomName(),
                    "driveChanges", response.howYouFeel() != null ? response.howYouFeel() : "",
                    "energy", response.energy(),
                    "sleeping", response.isSleeping()));
            } catch (Exception e) {
                ctx.json(Map.of("summary", "Could not reach companion."));
            }
        });

        // Remember for — store cross-session memory for a specific client
        app.post("/api/resident/remember-for", ctx -> {
            if (!authorize(ctx)) return;
            var ref = resolve();
            if (ref == null) { ctx.status(503).json(Map.of("error", "resident not spawned yet")); return; }
            var body = Json.mapper().readTree(ctx.body());
            var clientId = body.has("clientId") ? body.get("clientId").asText() : "unknown";
            var content = body.has("content") ? body.get("content").asText() : "";
            var importance = body.has("importance") ? (float) body.get("importance").asDouble() : 0.7f;
            if (content.isBlank()) { ctx.status(400).json(Map.of("error", "content required")); return; }
            // Prefix with client ID for partitioning
            var tagged = "[" + clientId + "] " + content;
            ref.tell(new CompanionActor.ExternalTell(clientId, clientId,
                "remember " + tagged));
            ctx.json(Map.of("ok", true, "stored", tagged.substring(0, Math.min(100, tagged.length()))));
        });

        // Behavioral pattern — what the companion has noticed about a client
        app.get("/api/resident/behavioral-pattern", ctx -> {
            if (!authorize(ctx)) return;
            var clientId = ctx.queryParam("clientId");
            // Query the companion's working memory for patterns about this client
            var ref = resolve();
            if (ref == null) { ctx.json(Map.of("patterns", List.of())); return; }
            try {
                var response = AskPattern.ask(ref,
                    (ActorRef<CompanionActor.BridgeTextResponse> replyTo) ->
                        new CompanionActor.BridgeAsk(clientId != null ? clientId : "system", "system",
                            "What patterns have you noticed about " + (clientId != null ? clientId : "the user") + "?",
                            replyTo),
                    Duration.ofSeconds(30), system.scheduler()
                ).toCompletableFuture().get(30, TimeUnit.SECONDS);
                ctx.json(Map.of("patterns", List.of(response.text())));
            } catch (Exception e) {
                ctx.json(Map.of("patterns", List.of("No patterns observed yet.")));
            }
        });

        // Continuity check — check statement against memory for contradictions
        app.post("/api/resident/continuity-check", ctx -> {
            if (!authorize(ctx)) return;
            var body = Json.mapper().readTree(ctx.body());
            var statement = body.has("statement") ? body.get("statement").asText() : "";
            if (statement.isBlank()) { ctx.status(400).json(Map.of("error", "statement required")); return; }
            // Contradiction detection wired via CompanionActor's ContradictionDetector in production.
            // For now, return consistent — full wiring comes with Forge integration.
            ctx.json(Map.of("consistent", true, "conflicts", List.of()));
        });
    }

    /** SSE handler: connects a bridge consumer, streams events as JSON. */
    private void handleEvents(SseClient client) {
        var tokenParam = client.ctx().queryParam("token");
        if (!authorizeToken(tokenParam)) {
            client.sendEvent("error", "{\"error\":\"unauthorized\"}");
            client.close();
            return;
        }

        client.keepAlive();

        var ref = resolve();
        if (ref == null) {
            client.sendEvent("error", "{\"error\":\"resident not spawned yet\"}");
            client.close();
            return;
        }

        Consumer<BridgeEvent> sink = event -> {
            if (client.terminated()) return;
            try {
                var json = Json.mapper().writeValueAsString(event);
                var eventType = eventTypeName(event);
                client.sendEvent(eventType, json);
            } catch (Exception e) {
                log.debug("Failed to serialize bridge event: {}", e.getMessage());
            }
        };

        ref.tell(new CompanionActor.BridgeConnect(sink));

        client.onClose(() -> {
            ref.tell(new CompanionActor.BridgeDisconnect(sink));
            log.info("Resident bridge client disconnected");
        });

        log.info("Resident bridge client connected");
        client.sendEvent("connected", "{\"status\":\"connected\"}");
    }

    private String eventTypeName(BridgeEvent event) {
        return switch (event) {
            case CompanionActor.BridgeRoomEvent _ -> "room_event";
            case CompanionActor.BridgeRoomChanged _ -> "room_changed";
            case CompanionActor.BridgeStatusChanged _ -> "status_changed";
        };
    }

    // --- Auth ---

    private boolean authorize(Context ctx) {
        if (token == null || token.isEmpty()) return true;
        var authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            if (token.equals(authHeader.substring(7))) return true;
        }
        ctx.status(401).json(Map.of("error", "Invalid or missing resident token"));
        return false;
    }

    private boolean authorizeToken(String providedToken) {
        if (token == null || token.isEmpty()) return true;
        return token.equals(providedToken);
    }

    // --- Request DTOs ---

    record TextRequest(@JsonProperty("text") String text) {}
    record GoRequest(@JsonProperty("direction") String direction) {}
    record MessageRequest(
        @JsonProperty("message") String message,
        @JsonProperty("timeout") Integer timeout) {}
}
