package org.wyrdsekai.server.http;

import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.persistence.AuthService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;

/**
 * REST routes for residency management.
 *
 * <ul>
 *   <li>{@code POST /api/residency/grant} — steward-only, mint residency for a DID.</li>
 *   <li>{@code POST /api/residency/revoke} — steward-only, remove residency.</li>
 *   <li>{@code GET  /api/residency/list} — auth'd, list residents of this zone.</li>
 *   <li>{@code GET  /api/residency/self} — auth'd, caller's own residency row.</li>
 * </ul>
 */
public final class ResidencyRoutes {

    private static final Logger log = LoggerFactory.getLogger(ResidencyRoutes.class);

    private final AuthService auth;
    private final String localZoneId;

    public ResidencyRoutes(AuthService auth, String localZoneId) {
        this.auth = auth;
        this.localZoneId = localZoneId;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/residency/grant", this::handleGrant);
        app.post("/api/residency/revoke", this::handleRevoke);
        app.get("/api/residency/list", this::handleList);
        app.get("/api/residency/self", this::handleSelf);
    }

    record GrantRequest(String did, String role) {}
    record RevokeRequest(String did) {}
    record ErrorResponse(String error) {}

    private void handleGrant(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;
        var store = ResidencyStore.get();
        if (store == null) {
            ctx.status(503).json(new ErrorResponse("ResidencyStore not initialised"));
            return;
        }
        var req = Json.mapper().readValue(ctx.body(), GrantRequest.class);
        if (req.did() == null || req.did().isBlank()) {
            ctx.status(400).json(new ErrorResponse("did required"));
            return;
        }
        var role = (req.role() == null || req.role().isBlank())
            ? Residency.ROLE_MEMBER : req.role();
        var existing = store.get(req.did(), localZoneId);
        var residency = new Residency(
            req.did(), localZoneId, role, Instant.now(), caller.id(),
            existing.map(Residency::studyRoomId).orElse(null)
        );
        store.grant(residency);
        log.info("Residency: {} granted to {} (role={}) by {}",
            localZoneId, req.did(), role, caller.username());
        ctx.json(Map.of(
            "result", "granted",
            "did", req.did(),
            "zone", localZoneId,
            "role", role
        ));
    }

    private void handleRevoke(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;
        var store = ResidencyStore.get();
        if (store == null) {
            ctx.status(503).json(new ErrorResponse("ResidencyStore not initialised"));
            return;
        }
        var req = Json.mapper().readValue(ctx.body(), RevokeRequest.class);
        if (req.did() == null || req.did().isBlank()) {
            ctx.status(400).json(new ErrorResponse("did required"));
            return;
        }
        boolean removed = store.revoke(req.did(), localZoneId);
        log.info("Residency: {} revoke for {} by {} (removed={})",
            localZoneId, req.did(), caller.username(), removed);
        ctx.json(Map.of(
            "result", removed ? "revoked" : "not_found",
            "did", req.did(),
            "zone", localZoneId
        ));
    }

    private void handleList(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null || auth.validateSession(token).isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Authorization required"));
            return;
        }
        var store = ResidencyStore.get();
        if (store == null) {
            ctx.status(503).json(new ErrorResponse("ResidencyStore not initialised"));
            return;
        }
        var residents = new ArrayList<Map<String, Object>>();
        for (var r : store.listByZone(localZoneId)) {
            residents.add(Map.of(
                "did", r.did(),
                "role", r.role(),
                "grantedAt", r.grantedAt().toString(),
                "grantor", r.grantor(),
                "studyRoomId", r.studyRoomId() == null ? "" : r.studyRoomId()
            ));
        }
        ctx.json(Map.of("zone", localZoneId, "residents", residents));
    }

    private void handleSelf(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authorization required"));
            return;
        }
        var caller = auth.validateSession(token);
        if (caller.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return;
        }
        var store = ResidencyStore.get();
        if (store == null) {
            ctx.status(503).json(new ErrorResponse("ResidencyStore not initialised"));
            return;
        }
        var r = store.get(caller.get().id(), localZoneId);
        if (r.isEmpty()) {
            ctx.json(Map.of(
                "resident", false,
                "zone", localZoneId,
                "did", caller.get().id()
            ));
        } else {
            var rec = r.get();
            ctx.json(Map.of(
                "resident", true,
                "zone", localZoneId,
                "did", rec.did(),
                "role", rec.role(),
                "grantedAt", rec.grantedAt().toString(),
                "grantor", rec.grantor()
            ));
        }
    }

    private AuthService.User requireSteward(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authorization required"));
            return null;
        }
        var caller = auth.validateSession(token);
        if (caller.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return null;
        }
        if (!"steward".equals(caller.get().role())) {
            ctx.status(403).json(new ErrorResponse("Steward role required"));
            return null;
        }
        return caller.get();
    }
}
