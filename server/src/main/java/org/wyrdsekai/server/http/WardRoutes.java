package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.WardService;

import java.util.List;

/**
 * HTTP routes for ward management: list, grant, revoke.
 * Only admin principals can modify wards.
 */
public final class WardRoutes {

    private final WardService wards;
    private final AuthService auth;

    public WardRoutes(WardService wards, AuthService auth) {
        this.wards = wards;
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/wards/{roomId}", this::handleList);
        app.post("/api/wards", this::handleGrant);
        app.delete("/api/wards", this::handleRevoke);
    }

    // --- Request/Response records ---

    record GrantRequest(@JsonProperty("room_id") String roomId,
                        String principal, String permission) {}
    record RevokeRequest(@JsonProperty("room_id") String roomId,
                         String principal, String permission) {}
    record WardResponse(@JsonProperty("room_id") String roomId,
                        String principal, String permission,
                        @JsonProperty("granted_by") String grantedBy,
                        @JsonProperty("created_at") long createdAt) {}
    record ErrorResponse(String error) {}

    // --- Handlers ---

    private void handleList(Context ctx) {
        var roomId = ctx.pathParam("roomId");
        var wardList = wards.listWards(roomId);
        ctx.json(wardList.stream()
            .map(w -> new WardResponse(w.roomId(), w.principal(), w.permission(),
                w.grantedBy(), w.createdAt()))
            .toList());
    }

    private void handleGrant(Context ctx) throws Exception {
        var userId = requireAdmin(ctx);
        if (userId == null) return;

        var req = Json.mapper().readValue(ctx.body(), GrantRequest.class);
        if (req.roomId() == null || req.principal() == null || req.permission() == null) {
            ctx.status(400).json(new ErrorResponse("room_id, principal, and permission required"));
            return;
        }

        // Verify caller is admin on this room
        if (!wards.isAdmin(req.roomId(), userId)) {
            ctx.status(403).json(new ErrorResponse("You are not an admin of this room"));
            return;
        }

        var created = wards.grant(req.roomId(), req.principal(), req.permission(), userId);
        if (created) {
            ctx.status(201).json(new WardResponse(req.roomId(), req.principal(),
                req.permission(), userId, System.currentTimeMillis() / 1000));
        } else {
            ctx.json(new ErrorResponse("Ward already exists"));
        }
    }

    private void handleRevoke(Context ctx) throws Exception {
        var userId = requireAdmin(ctx);
        if (userId == null) return;

        var req = Json.mapper().readValue(ctx.body(), RevokeRequest.class);
        if (req.roomId() == null || req.principal() == null || req.permission() == null) {
            ctx.status(400).json(new ErrorResponse("room_id, principal, and permission required"));
            return;
        }

        // Verify caller is admin on this room
        if (!wards.isAdmin(req.roomId(), userId)) {
            ctx.status(403).json(new ErrorResponse("You are not an admin of this room"));
            return;
        }

        var revoked = wards.revoke(req.roomId(), req.principal(), req.permission());
        if (revoked) {
            ctx.status(204);
        } else {
            ctx.status(404).json(new ErrorResponse("Ward not found"));
        }
    }

    /**
     * Extract and validate the authenticated user. Returns userId if valid, null if rejected.
     */
    private String requireAdmin(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authentication required"));
            return null;
        }
        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return null;
        }
        return user.get().id();
    }
}
