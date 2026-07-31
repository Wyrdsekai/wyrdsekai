package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * HTTP endpoint for phone bud delegation.
 * POST /api/companion/ask — phone sends COMPLEX query, server companion processes
 * through full pipeline (tools, MCP, soul, memory), returns response.
 * Requires device token (Bearer auth).
 */
public final class CompanionAskRoutes {

    private static final Logger log = LoggerFactory.getLogger(CompanionAskRoutes.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(60);

    private final ActorSystem<?> system;
    private final PairingService pairingService;

    public CompanionAskRoutes(ActorSystem<?> system, PairingService pairingService) {
        this.system = system;
        this.pairingService = pairingService;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/companion/ask", this::handleAsk);
    }

    record AskRequest(
        @JsonProperty("message") String message,
        @JsonProperty("recentHistory") List<String> recentHistory,
        @JsonProperty("locale") String locale
    ) {}

    record ActionDto(
        @JsonProperty("type") String type,
        @JsonProperty("data") Map<String, Object> data
    ) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record AskResponse(
        @JsonProperty("text") String text,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("latencyMs") long latencyMs,
        @JsonProperty("actions") List<ActionDto> actions
    ) {}

    record ErrorResponse(String error) {}

    private void handleAsk(Context ctx) {
        // Auth: device token required
        var authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            ctx.status(401).json(new ErrorResponse("Device token required (Bearer header)"));
            return;
        }
        var token = authHeader.substring(7);
        var device = pairingService.validateDeviceToken(token);
        if (device.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or revoked device token"));
            return;
        }

        AskRequest req;
        try {
            req = Json.mapper().readValue(ctx.body(), AskRequest.class);
        } catch (Exception e) {
            ctx.status(400).json(new ErrorResponse("Invalid JSON: " + e.getMessage()));
            return;
        }
        if (req.message() == null || req.message().isBlank()) {
            ctx.status(400).json(new ErrorResponse("message is required"));
            return;
        }

        var requestId = UUID.randomUUID().toString();
        var locale = req.locale() != null ? req.locale() : "en";
        var start = System.currentTimeMillis();

        try {
            // Delegate to companion via ZoneGuardian
            @SuppressWarnings("unchecked")
            var typedSystem = (ActorSystem<ZoneGuardian.Command>) (ActorSystem<?>) system;
            var future = AskPattern.<ZoneGuardian.Command, CompanionActor.BudDelegateResponse>ask(
                typedSystem,
                replyTo -> new ZoneGuardian.DelegateToCompanion(
                    requestId,
                    device.get().id(),  // fromBudDid = device ID
                    null,               // targetCompanionId = default
                    req.message(),
                    req.recentHistory() != null ? req.recentHistory() : List.of(),
                    locale,
                    replyTo),
                ASK_TIMEOUT,
                system.scheduler()
            );

            var resp = future.toCompletableFuture().get(65, TimeUnit.SECONDS);
            var latency = System.currentTimeMillis() - start;
            var actionDtos = resp.actions() != null
                ? resp.actions().stream().map(this::toActionDto).toList()
                : List.<ActionDto>of();
            var responseJson = Json.mapper().createObjectNode()
                .put("text", resp.text() != null ? resp.text() : "")
                .put("requestId", resp.requestId())
                .put("latencyMs", latency);
            var actionsArray = responseJson.putArray("actions");
            for (var dto : actionDtos) {
                actionsArray.add(Json.mapper().valueToTree(dto));
            }
            ctx.contentType("application/json");
            ctx.result(Json.mapper().writeValueAsString(responseJson));
        } catch (Exception e) {
            var latency = System.currentTimeMillis() - start;
            log.warn("Bud delegation failed (requestId={}, latency={}ms): {}",
                requestId, latency, e.getMessage());
            ctx.status(504).json(new ErrorResponse("Delegation timed out or failed: " + e.getMessage()));
        }
    }

    private ActionDto toActionDto(CompanionActor.DelegationAction action) {
        return switch (action) {
            case CompanionActor.DelegationAction.RoomCreated rc ->
                new ActionDto("room_created", Map.of(
                    "roomName", rc.roomName(), "roomId", rc.roomId(),
                    "exitDirection", rc.exitDirection(), "exitLabel", rc.exitLabel()));
            case CompanionActor.DelegationAction.ItemChanged ic ->
                new ActionDto("item_changed", Map.of(
                    "changeType", ic.changeType(), "itemName", ic.itemName(),
                    "result", ic.result()));
            case CompanionActor.DelegationAction.HintUpdated hu ->
                new ActionDto("hint_updated", Map.of("hints", hu.hints()));
            case CompanionActor.DelegationAction.Notification n ->
                new ActionDto("notification", Map.of(
                    "message", n.message(), "priority", n.priority()));
            case CompanionActor.DelegationAction.RoomNavigated rn ->
                new ActionDto("room_navigated", Map.of(
                    "newRoomId", rn.newRoomId(), "direction", rn.direction()));
        };
    }
}
