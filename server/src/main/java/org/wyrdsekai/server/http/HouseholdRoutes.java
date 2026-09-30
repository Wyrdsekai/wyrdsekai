package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.household.QuietHours;

import java.util.Map;

import java.time.Instant;

/**
 * HTTP routes for the household's people: the accounts in the users table, with the roles
 * steward, member, guest and child. Anyone with an account may read the list; only a steward
 * changes it, and the household always keeps at least one steward (enforced in
 * {@link AuthService}). Changes and refusals are written to the StewardAuditLog.
 *
 *   GET    /api/household/members                  — list the accounts
 *   DELETE /api/household/members/{id}              — remove an account (steward)
 *   POST   /api/household/members/{id}/promote     — make an account a steward (steward)
 *   GET    /api/household/audit                     — get recent audit log
 *
 * People join by invite ({@code POST /api/auth/invite}, {@code wyrd invite create}).
 */
public final class HouseholdRoutes {

    private static final Logger log = LoggerFactory.getLogger(HouseholdRoutes.class);

    private final StewardAuditLog auditLog;
    private final AuthService auth;

    public HouseholdRoutes(StewardAuditLog auditLog, AuthService auth) {
        this.auditLog = auditLog;
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/household/members", this::handleListMembers);
        app.delete("/api/household/members/{id}", this::handleRemoveMember);
        app.post("/api/household/members/{id}/promote", this::handlePromote);
        app.get("/api/household/audit", this::handleAuditLog);
        app.get("/api/household/quiet", this::handleGetQuiet);
        app.post("/api/household/quiet", this::handleSetQuiet);
    }

    // --- Request/Response records ---

    record MemberResponse(
        String id,
        String username,
        String name,
        String role,
        @JsonProperty("joined_at") Instant joinedAt
    ) {
        static MemberResponse of(AuthService.User u) {
            return new MemberResponse(u.id(), u.username(), u.displayName(), u.role(), u.createdAt());
        }
    }

    record AuditEntry(
        long id,
        Instant timestamp,
        @JsonProperty("actor_did") String actorDid,
        @JsonProperty("actor_name") String actorName,
        String type,
        @JsonProperty("target_id") String targetId,
        String description,
        boolean approved
    ) {}

    record ErrorResponse(String error) {}

    // --- Handlers ---

    /**
     * GET /api/household/members — the household's accounts.
     * Any authenticated user can view the member list.
     */
    private void handleListMembers(Context ctx) {
        if (requireAuth(ctx) == null) return;
        ctx.json(auth.listUsers().stream().map(MemberResponse::of).toList());
    }

    /**
     * DELETE /api/household/members/{id} — remove an account. Steward only; a steward cannot
     * remove themself, and the last steward is never removed.
     */
    private void handleRemoveMember(Context ctx) {
        var actor = requireSteward(ctx, StewardAuditLog.ActionType.MEMBER_REMOVE);
        if (actor == null) return;
        var target = auth.findUserForPerson(ctx.pathParam("id")).orElse(null);
        if (target == null) {
            ctx.status(404).json(new ErrorResponse("No such account"));
            return;
        }
        if (target.id().equals(actor.id())) {
            deny(ctx, actor, StewardAuditLog.ActionType.MEMBER_REMOVE, target.id(), 409,
                "You cannot remove your own account");
            return;
        }
        if (!auth.removeUser(actor.id(), target.id())) {
            if ("steward".equals(target.role())) {
                deny(ctx, actor, StewardAuditLog.ActionType.MEMBER_REMOVE, target.id(), 409,
                    "The household must keep at least one steward");
            } else {
                ctx.status(404).json(new ErrorResponse("No such account"));
            }
            return;
        }
        auditLog.log(actor.id(), actor.username(), StewardAuditLog.ActionType.MEMBER_REMOVE,
            target.id(), "Removed " + target.username(), true);
        log.info("Household account removed: {} ({}), by {}", target.username(), target.id(), actor.username());
        ctx.status(204);
    }

    /** POST /api/household/members/{id}/promote — make an account a steward. Steward only. */
    private void handlePromote(Context ctx) {
        var actor = requireSteward(ctx, StewardAuditLog.ActionType.MEMBER_PROMOTE);
        if (actor == null) return;
        var target = auth.findUserForPerson(ctx.pathParam("id")).orElse(null);
        if (target == null) {
            ctx.status(404).json(new ErrorResponse("No such account"));
            return;
        }
        switch (auth.changeRole(actor.id(), target.id(), "steward")) {
            case CHANGED -> {
                auditLog.log(actor.id(), actor.username(), StewardAuditLog.ActionType.MEMBER_PROMOTE,
                    target.id(), "Promoted " + target.username() + " to steward", true);
                log.info("Household account promoted to steward: {} ({}), by {}",
                    target.username(), target.id(), actor.username());
                ctx.json(MemberResponse.of(auth.findUser(target.id()).orElse(target)));
            }
            case NOT_FOUND -> ctx.status(404).json(new ErrorResponse("No such account"));
            default -> deny(ctx, actor, StewardAuditLog.ActionType.MEMBER_PROMOTE, target.id(), 403,
                "Steward role required");
        }
    }

    private void deny(Context ctx, AuthService.User actor, StewardAuditLog.ActionType type,
                      String targetId, int status, String reason) {
        auditLog.log(actor.id(), actor.username(), type, targetId, "Refused: " + reason, false);
        ctx.status(status).json(new ErrorResponse(reason));
    }

    /**
     * GET /api/household/audit — get recent audit log entries.
     * Any authenticated user can view the audit log.
     */
    private void handleAuditLog(Context ctx) {
        var userId = requireAuth(ctx);
        if (userId == null) return;

        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        var entries = auditLog.recent(limit);

        ctx.json(entries.stream()
            .map(a -> new AuditEntry(a.entryId(), a.timestamp(), a.actorDid(),
                a.actorName(), a.type().name(), a.targetId(),
                a.description(), a.approved()))
            .toList());
    }

    // --- Quiet hours (the record's say) ---

    private void handleGetQuiet(Context ctx) {
        if (requireAuth(ctx) == null) return;
        var spec = QuietHours.spec();
        ctx.json(Map.of("window", spec, "quietNow", QuietHours.isQuiet(),
            "source", auth.getConfig("quiet_hours") != null ? "record" : "config"));
    }

    /** {@code {"window": "22:00-07:00"}} or {@code {"window": "off"}}; steward only. */
    private void handleSetQuiet(Context ctx) {
        var token = AuthRoutes.extractToken(ctx);
        var user = token == null ? java.util.Optional.<AuthService.User>empty() : auth.validateSession(token);
        if (user.isEmpty()) { ctx.status(401).json(Map.of("error", "Authentication required")); return; }
        if (!"steward".equals(user.get().role())) { ctx.status(403).json(Map.of("error", "Only the steward sets quiet hours")); return; }
        String window = null;
        try {
            var node = Json.mapper().readTree(ctx.body() == null || ctx.body().isBlank() ? "{}" : ctx.body());
            if (node.hasNonNull("window")) window = node.get("window").asText().trim();
        } catch (Exception ignored) { }
        if (window == null || window.isBlank()) { ctx.status(400).json(Map.of("error", "window: HH:MM-HH:MM or off")); return; }
        if (!"off".equalsIgnoreCase(window) && QuietHours.parse(window).isEmpty()) {
            ctx.status(400).json(Map.of("error", "window: HH:MM-HH:MM or off")); return;
        }
        auth.setConfig("quiet_hours", window.toLowerCase(), user.get().id());
        QuietHours.refresh();
        ctx.json(Map.of("window", QuietHours.spec(), "quietNow", QuietHours.isQuiet(), "source", "record"));
    }

    // --- Auth helper ---

    /**
     * Extract and validate the authenticated user. Returns the user if valid, null if rejected.
     * Sends 401 response on failure.
     */
    private AuthService.User requireAuth(Context ctx) {
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
        return user.get();
    }

    /** {@link #requireAuth}, then 403 (written to the audit log) unless the caller is a steward. */
    private AuthService.User requireSteward(Context ctx, StewardAuditLog.ActionType type) {
        var user = requireAuth(ctx);
        if (user == null) return null;
        if (!"steward".equals(user.role())) {
            deny(ctx, user, type, null, 403, "Steward role required");
            return null;
        }
        return user;
    }
}
