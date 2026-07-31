package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.household.HouseholdMember;
import org.wyrdsekai.core.household.PermissionChecker;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * HTTP routes for household member management (§101).
 * All mutation routes check permissions via PermissionChecker and
 * log actions via StewardAuditLog.
 *
 *   GET    /api/household/members                  — list all members
 *   POST   /api/household/members                  — add a member
 *   DELETE /api/household/members/{did}             — remove a member
 *   POST   /api/household/members/{did}/promote     — promote to steward
 *   POST   /api/household/members/{did}/deactivate  — deactivate member
 *   GET    /api/household/audit                     — get recent audit log
 */
public final class HouseholdRoutes {

    private static final Logger log = LoggerFactory.getLogger(HouseholdRoutes.class);

    private final PermissionChecker permissions;
    private final StewardAuditLog auditLog;
    private final AuthService auth;

    public HouseholdRoutes(PermissionChecker permissions, StewardAuditLog auditLog,
                           AuthService auth) {
        this.permissions = permissions;
        this.auditLog = auditLog;
        this.auth = auth;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/household/members", this::handleListMembers);
        app.post("/api/household/members", this::handleAddMember);
        app.delete("/api/household/members/{did}", this::handleRemoveMember);
        app.post("/api/household/members/{did}/promote", this::handlePromote);
        app.post("/api/household/members/{did}/deactivate", this::handleDeactivate);
        app.get("/api/household/audit", this::handleAuditLog);
    }

    // --- Request/Response records ---

    record AddMemberRequest(
        String did,
        String name,
        @JsonProperty("role") String role,
        @JsonProperty("permissions") List<String> permissions
    ) {}

    record MemberResponse(
        String did,
        String name,
        String role,
        @JsonProperty("permissions") Set<String> permissions,
        @JsonProperty("joined_at") Instant joinedAt,
        boolean active
    ) {}

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
     * GET /api/household/members — list all household members.
     * Any authenticated user can view the member list.
     */
    private void handleListMembers(Context ctx) {
        var userId = requireAuth(ctx);
        if (userId == null) return;

        var members = permissions.allMembers();
        ctx.json(members.stream()
            .map(m -> new MemberResponse(m.did(), m.name(), m.role().name(),
                m.permissions(), m.joinedAt(), m.active()))
            .toList());
    }

    /**
     * POST /api/household/members — add a new member.
     * Requires member:manage permission.
     */
    private void handleAddMember(Context ctx) throws Exception {
        var actorDid = requireAuth(ctx);
        if (actorDid == null) return;

        var check = permissions.check(actorDid, HouseholdMember.PERM_MEMBER_MANAGE);
        if (!check.allowed()) {
            auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_ADD,
                null, "Permission denied: " + check.reason(), false);
            ctx.status(403).json(new ErrorResponse(check.reason()));
            return;
        }

        var req = Json.mapper().readValue(ctx.body(), AddMemberRequest.class);
        if (req.did() == null || req.did().isBlank() || req.name() == null || req.name().isBlank()) {
            ctx.status(400).json(new ErrorResponse("did and name are required"));
            return;
        }

        // Check for duplicate
        if (permissions.getMember(req.did()).isPresent()) {
            ctx.status(409).json(new ErrorResponse("Member already exists: " + req.did()));
            return;
        }

        var perms = req.permissions() != null ? Set.copyOf(req.permissions()) : Set.<String>of();
        var member = HouseholdMember.member(req.did(), req.name(), perms);
        permissions.register(member);

        auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_ADD,
            req.did(), "Added member: " + req.name(), true);

        log.info("Household member added: did={}, name={}, by={}", req.did(), req.name(), actorDid);

        ctx.status(201).json(new MemberResponse(member.did(), member.name(),
            member.role().name(), member.permissions(), member.joinedAt(), member.active()));
    }

    /**
     * DELETE /api/household/members/{did} — remove a member.
     * Requires member:manage permission. Cannot remove the last steward.
     */
    private void handleRemoveMember(Context ctx) {
        var actorDid = requireAuth(ctx);
        if (actorDid == null) return;

        var targetDid = ctx.pathParam("did");
        var check = permissions.checkMemberAction(actorDid, targetDid,
            HouseholdMember.PERM_MEMBER_MANAGE);
        if (!check.allowed()) {
            auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_REMOVE,
                targetDid, "Permission denied: " + check.reason(), false);
            ctx.status(403).json(new ErrorResponse(check.reason()));
            return;
        }

        var removed = permissions.unregister(targetDid);
        if (!removed) {
            auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_REMOVE,
                targetDid, "Cannot remove: last steward or not found", false);
            ctx.status(400).json(new ErrorResponse("Cannot remove member (last steward or not found)"));
            return;
        }

        auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_REMOVE,
            targetDid, "Removed member", true);

        log.info("Household member removed: did={}, by={}", targetDid, actorDid);
        ctx.status(204);
    }

    /**
     * POST /api/household/members/{did}/promote — promote to steward.
     * Requires member:manage permission.
     */
    private void handlePromote(Context ctx) {
        var actorDid = requireAuth(ctx);
        if (actorDid == null) return;

        var targetDid = ctx.pathParam("did");
        var check = permissions.promote(actorDid, targetDid);
        if (!check.allowed()) {
            auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_PROMOTE,
                targetDid, "Permission denied: " + check.reason(), false);
            ctx.status(403).json(new ErrorResponse(check.reason()));
            return;
        }

        auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_PROMOTE,
            targetDid, "Promoted to steward", true);

        log.info("Household member promoted to steward: did={}, by={}", targetDid, actorDid);

        var member = permissions.getMember(targetDid).orElse(null);
        if (member != null) {
            ctx.json(new MemberResponse(member.did(), member.name(),
                member.role().name(), member.permissions(), member.joinedAt(), member.active()));
        } else {
            ctx.status(404).json(new ErrorResponse("Member not found after promotion"));
        }
    }

    /**
     * POST /api/household/members/{did}/deactivate — deactivate a member.
     * Requires member:manage permission. Cannot deactivate the last steward.
     */
    private void handleDeactivate(Context ctx) {
        var actorDid = requireAuth(ctx);
        if (actorDid == null) return;

        var targetDid = ctx.pathParam("did");
        var check = permissions.deactivate(actorDid, targetDid);
        if (!check.allowed()) {
            auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_DEACTIVATE,
                targetDid, "Permission denied: " + check.reason(), false);
            ctx.status(403).json(new ErrorResponse(check.reason()));
            return;
        }

        auditLog.log(actorDid, actorDid, StewardAuditLog.ActionType.MEMBER_DEACTIVATE,
            targetDid, "Deactivated member", true);

        log.info("Household member deactivated: did={}, by={}", targetDid, actorDid);

        var member = permissions.getMember(targetDid).orElse(null);
        if (member != null) {
            ctx.json(new MemberResponse(member.did(), member.name(),
                member.role().name(), member.permissions(), member.joinedAt(), member.active()));
        } else {
            ctx.status(404).json(new ErrorResponse("Member not found"));
        }
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

    // --- Auth helper ---

    /**
     * Extract and validate the authenticated user. Returns userId if valid, null if rejected.
     * Sends 401 response on failure.
     */
    private String requireAuth(Context ctx) {
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
