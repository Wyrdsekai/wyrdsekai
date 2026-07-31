package org.wyrdsekai.server.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import io.javalin.router.JavalinDefaultRoutingApi;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.RevocationMode;
import org.wyrdsekai.core.home.HomeRegistryActor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * HTTP endpoints for the Home model.
 *
 * <h3>Surface</h3>
 *   GET    /api/home/summary?owner={did}                — aggregated counts + recent audit
 *   GET    /api/home/grants/issued?owner={did}          — grants this owner issued
 *   GET    /api/home/grants/held?subject={did}          — grants issued to this subject
 *   GET    /api/home/grants/{id}                        — one grant
 *   POST   /api/home/grants                             — issue a grant (body = grant shape)
 *   DELETE /api/home/grants/{id}?actor={did}            — revoke (actor must be issuer or owner)
 *   GET    /api/home/audit?owner={did}&since={epochSec}&limit={n}
 *   POST   /api/home/check                              — ad-hoc CheckAccess for debugging
 *
 * <p>Auth: the acting DID is passed via query param or body. In M1a this is
 * the simplest plausible shape; Phase M1b/M2 move to session-derived DIDs
 * via AuthService session lookup.</p>
 */
public final class HomeRoutes {

    private static final Logger log = LoggerFactory.getLogger(HomeRoutes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private final ActorRef<HomeRegistryActor.Command> home;
    private final ActorSystem<?> system;
    /**
     * §97: when set, incoming grant-requests are stamped with a
     * {@code trustTier} scope key reflecting the requester's federation
     * trust level. {@code null} returns mean "no tier applied."
     */
    private volatile Function<String, String> trustTierResolver;

    public HomeRoutes(ActorRef<HomeRegistryActor.Command> home, ActorSystem<?> system) {
        this.home = home;
        this.system = system;
    }

    /** Install a requester → trust-tier resolver (see {@link #trustTierResolver}). */
    public void setTrustTierResolver(Function<String, String> resolver) {
        this.trustTierResolver = resolver;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.get("/api/home/summary", this::handleSummary);
        app.get("/api/home/grants/issued", this::handleGrantsIssued);
        app.get("/api/home/grants/held", this::handleGrantsHeld);
        app.get("/api/home/grants/{id}", this::handleFetchGrant);
        app.post("/api/home/grants", this::handleIssue);
        app.delete("/api/home/grants/{id}", this::handleRevoke);
        app.get("/api/home/audit", this::handleAudit);
        app.post("/api/home/check", this::handleCheck);
        // §10 grant-request flow
        app.post("/api/home/grant-requests", this::handleCreateRequest);
        app.get("/api/home/grant-requests/pending", this::handlePendingRequests);
        app.get("/api/home/grant-requests/by-requester", this::handleRequestsByRequester);
        app.post("/api/home/grant-requests/{id}/approve", this::handleApproveRequest);
        app.post("/api/home/grant-requests/{id}/deny", this::handleDenyRequest);
        app.post("/api/home/grant-requests/{id}/cancel", this::handleCancelRequest);
    }

    // --- Handlers ------------------------------------------------------

    private void handleSummary(Context ctx) {
        var owner = requiredQuery(ctx, "owner");
        if (owner == null) return;
        var summary = ask(replyTo -> new HomeRegistryActor.GetSummary(owner, replyTo),
            HomeRegistryActor.HomeSummary.class);
        var view = new LinkedHashMap<String, Object>();
        view.put("ownerDid", summary.ownerDid());
        view.put("grantsIssued", summary.grantsIssued());
        view.put("grantsHeld", summary.grantsHeld());
        view.put("grantsIssuedActive", summary.grantsIssuedActive());
        view.put("grantsHeldActive", summary.grantsHeldActive());
        view.put("recentAudit", summary.recentAudit().stream().map(HomeRoutes::auditView).toList());
        ctx.json(view);
    }

    private void handleGrantsIssued(Context ctx) {
        var owner = requiredQuery(ctx, "owner");
        if (owner == null) return;
        var list = ask(replyTo -> new HomeRegistryActor.EnumerateIssued(owner, replyTo),
            HomeRegistryActor.GrantList.class).grants();
        ctx.json(Map.of("owner", owner, "grants", list.stream().map(HomeRoutes::grantView).toList()));
    }

    private void handleGrantsHeld(Context ctx) {
        var subject = requiredQuery(ctx, "subject");
        if (subject == null) return;
        var list = ask(replyTo -> new HomeRegistryActor.EnumerateHeld(subject, replyTo),
            HomeRegistryActor.GrantList.class).grants();
        ctx.json(Map.of("subject", subject, "grants", list.stream().map(HomeRoutes::grantView).toList()));
    }

    private void handleFetchGrant(Context ctx) {
        var id = ctx.pathParam("id");
        var detail = ask(replyTo -> new HomeRegistryActor.FetchGrant(id, replyTo),
            HomeRegistryActor.GrantDetail.class);
        if (detail.grant() == null) {
            ctx.status(404).json(Map.of("error", "grant not found"));
            return;
        }
        ctx.json(grantView(detail.grant()));
    }

    private void handleIssue(Context ctx) {
        JsonNode body;
        try {
            body = MAPPER.readTree(ctx.body());
        } catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON: " + e.getMessage()));
            return;
        }
        var issuer = text(body, "issuer");
        var subject = text(body, "subject");
        var resourceStr = text(body, "resource");
        var capStr = text(body, "capability");
        if (issuer == null || subject == null || resourceStr == null || capStr == null) {
            ctx.status(400).json(Map.of("error",
                "required fields: issuer, subject, resource, capability"));
            return;
        }
        var resource = ResourceUri.parseOrNull(resourceStr);
        if (resource == null) {
            ctx.status(400).json(Map.of("error", "resource must be a home:// URI"));
            return;
        }
        var cap = Capability.parse(capStr);
        if (cap == null) {
            ctx.status(400).json(Map.of("error", "unknown capability: " + capStr));
            return;
        }
        Map<String, Object> scope = Map.of();
        var scopeNode = body.get("scope");
        if (scopeNode != null && scopeNode.isObject()) {
            try {
                scope = MAPPER.convertValue(scopeNode,
                    new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "scope must be an object"));
                return;
            }
        }
        var revocationMode = RevocationMode.parse(text(body, "revocationMode"));
        Instant expiresAt = null;
        if (body.hasNonNull("expiresAt")) {
            try { expiresAt = Instant.parse(body.get("expiresAt").asText()); }
            catch (Exception e) {
                ctx.status(400).json(Map.of("error", "expiresAt must be ISO-8601"));
                return;
            }
        }
        var grant = new Grant(
            UUID.randomUUID().toString(),
            issuer, subject, resource, cap, scope,
            revocationMode,
            Instant.now(), expiresAt, null,
            text(body, "reason"), text(body, "witness"), text(body, "delegatedFrom"));

        var result = ask(replyTo -> new HomeRegistryActor.IssueGrant(grant, replyTo),
            HomeRegistryActor.IssueResult.class);
        if (result instanceof HomeRegistryActor.Issued issued) {
            ctx.status(201).json(grantView(issued.grant()));
        } else {
            var err = (HomeRegistryActor.IssueError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    private void handleRevoke(Context ctx) {
        var id = ctx.pathParam("id");
        var actor = requiredQuery(ctx, "actor");
        if (actor == null) return;
        var result = ask(replyTo -> new HomeRegistryActor.RevokeGrant(id, actor, replyTo),
            HomeRegistryActor.RevokeResult.class);
        if (result instanceof HomeRegistryActor.Revoked ok) {
            ctx.json(Map.of(
                "revoked", ok.grant().id(),
                "cascadeCount", ok.cascadeCount(),
                "revokedAt", ok.grant().revokedAt().toString()));
        } else {
            var err = (HomeRegistryActor.RevokeError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    private void handleAudit(Context ctx) {
        var owner = requiredQuery(ctx, "owner");
        if (owner == null) return;
        var sinceStr = ctx.queryParam("since");
        var limitStr = ctx.queryParam("limit");
        Instant since = null;
        if (sinceStr != null && !sinceStr.isBlank()) {
            try { since = Instant.ofEpochSecond(Long.parseLong(sinceStr)); }
            catch (NumberFormatException e) {
                try { since = Instant.parse(sinceStr); }
                catch (Exception ex) {
                    ctx.status(400).json(Map.of("error", "since must be epoch seconds or ISO-8601"));
                    return;
                }
            }
        }
        var limit = 100;
        if (limitStr != null) {
            try { limit = Math.max(1, Math.min(1000, Integer.parseInt(limitStr))); }
            catch (NumberFormatException ignored) {}
        }
        var finalSince = since;
        var finalLimit = limit;
        var list = ask(replyTo -> new HomeRegistryActor.QueryAudit(owner, finalSince, finalLimit, replyTo),
            HomeRegistryActor.AuditList.class).entries();
        ctx.json(Map.of("owner", owner, "entries", list.stream().map(HomeRoutes::auditView).toList()));
    }

    private void handleCheck(Context ctx) {
        JsonNode body;
        try { body = MAPPER.readTree(ctx.body()); }
        catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON"));
            return;
        }
        var subject = text(body, "subject");
        var resourceStr = text(body, "resource");
        var capStr = text(body, "capability");
        if (subject == null || resourceStr == null || capStr == null) {
            ctx.status(400).json(Map.of("error", "required: subject, resource, capability"));
            return;
        }
        var resource = ResourceUri.parseOrNull(resourceStr);
        var cap = Capability.parse(capStr);
        if (resource == null || cap == null) {
            ctx.status(400).json(Map.of("error", "invalid resource or capability"));
            return;
        }
        Map<String, Object> rawScope = Map.of();
        var scopeNode = body.get("scope");
        if (scopeNode != null && scopeNode.isObject()) {
            rawScope = MAPPER.convertValue(scopeNode,
                new TypeReference<Map<String, Object>>() {});
        }
        final var finalScope = rawScope;
        var decision = ask(replyTo -> new HomeRegistryActor.CheckAccess(
            subject, resource, cap, finalScope, replyTo), HomeRegistryActor.AccessDecision.class);
        if (decision instanceof HomeRegistryActor.Allow allow) {
            ctx.json(Map.of("allowed", true, "byGrant", allow.byGrant().id()));
        } else {
            var deny = (HomeRegistryActor.Deny) decision;
            ctx.json(Map.of("allowed", false, "reason", deny.reason()));
        }
    }

    // --- Grant-request handlers (§10) --------------------------------

    private void handleCreateRequest(Context ctx) {
        JsonNode body;
        try { body = MAPPER.readTree(ctx.body()); }
        catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON"));
            return;
        }
        var requester = text(body, "requester");
        var owner = text(body, "owner");
        var resourceStr = text(body, "resource");
        var capStr = text(body, "capability");
        if (requester == null || owner == null || resourceStr == null || capStr == null) {
            ctx.status(400).json(Map.of("error",
                "required: requester, owner, resource, capability"));
            return;
        }
        var resource = ResourceUri.parseOrNull(resourceStr);
        var cap = Capability.parse(capStr);
        if (resource == null || cap == null) {
            ctx.status(400).json(Map.of("error", "invalid resource or capability"));
            return;
        }
        Map<String, Object> scope = Map.of();
        var scopeNode = body.get("scope");
        if (scopeNode != null && scopeNode.isObject()) {
            scope = MAPPER.convertValue(scopeNode,
                new TypeReference<Map<String, Object>>() {});
        }
        // §97: stamp trustTier from the local federation view of the requester.
        // Only when scope doesn't already carry one — let callers override.
        var resolver = trustTierResolver;
        if (resolver != null && !scope.containsKey("trustTier")) {
            var tier = resolver.apply(requester);
            if (tier != null) {
                var enriched = new HashMap<String, Object>(scope);
                enriched.put("trustTier", tier);
                scope = enriched;
            }
        }
        var reason = text(body, "reason");
        var req = GrantRequest.create(
            requester, owner, resource, cap, scope, reason);
        var result = ask(replyTo -> new HomeRegistryActor.CreateGrantRequest(req, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) {
            ctx.status(201).json(requestView(ok.request()));
        } else {
            var err = (HomeRegistryActor.GrantRequestError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    private void handlePendingRequests(Context ctx) {
        var owner = requiredQuery(ctx, "owner");
        if (owner == null) return;
        var list = ask(replyTo -> new HomeRegistryActor.PendingRequestsForOwner(owner, replyTo),
            HomeRegistryActor.GrantRequestList.class).requests();
        ctx.json(Map.of("owner", owner,
            "requests", list.stream().map(HomeRoutes::requestView).toList()));
    }

    private void handleRequestsByRequester(Context ctx) {
        var requester = requiredQuery(ctx, "requester");
        if (requester == null) return;
        var list = ask(replyTo -> new HomeRegistryActor.RequestsByRequester(requester, replyTo),
            HomeRegistryActor.GrantRequestList.class).requests();
        ctx.json(Map.of("requester", requester,
            "requests", list.stream().map(HomeRoutes::requestView).toList()));
    }

    private void handleApproveRequest(Context ctx) {
        var id = ctx.pathParam("id");
        JsonNode body;
        try { body = MAPPER.readTree(ctx.body().isEmpty() ? "{}" : ctx.body()); }
        catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON"));
            return;
        }
        var actor = text(body, "actor");
        if (actor == null) actor = ctx.queryParam("actor");
        if (actor == null || actor.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing actor"));
            return;
        }
        Instant expiresAt = null;
        if (body.hasNonNull("expiresAt")) {
            try { expiresAt = Instant.parse(body.get("expiresAt").asText()); }
            catch (Exception e) {
                ctx.status(400).json(Map.of("error", "expiresAt must be ISO-8601"));
                return;
            }
        }
        var note = text(body, "note");
        final var finalActor = actor;
        final var finalExpiresAt = expiresAt;
        var result = ask(replyTo -> new HomeRegistryActor.ApproveGrantRequest(
            id, finalActor, finalExpiresAt, note, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) {
            ctx.json(requestView(ok.request()));
        } else {
            var err = (HomeRegistryActor.GrantRequestError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    private void handleDenyRequest(Context ctx) {
        var id = ctx.pathParam("id");
        JsonNode body;
        try { body = MAPPER.readTree(ctx.body().isEmpty() ? "{}" : ctx.body()); }
        catch (Exception e) {
            ctx.status(400).json(Map.of("error", "invalid JSON"));
            return;
        }
        var actor = text(body, "actor");
        if (actor == null) actor = ctx.queryParam("actor");
        if (actor == null || actor.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing actor"));
            return;
        }
        var note = text(body, "note");
        final var finalActor = actor;
        var result = ask(replyTo -> new HomeRegistryActor.DenyGrantRequest(
            id, finalActor, note, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) {
            ctx.json(requestView(ok.request()));
        } else {
            var err = (HomeRegistryActor.GrantRequestError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    private void handleCancelRequest(Context ctx) {
        var id = ctx.pathParam("id");
        var actor = ctx.queryParam("actor");
        if (actor == null) {
            JsonNode body;
            try { body = MAPPER.readTree(ctx.body().isEmpty() ? "{}" : ctx.body()); }
            catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid JSON"));
                return;
            }
            actor = text(body, "actor");
        }
        if (actor == null || actor.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing actor"));
            return;
        }
        final var finalActor = actor;
        var result = ask(replyTo -> new HomeRegistryActor.CancelGrantRequest(
            id, finalActor, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) {
            ctx.json(requestView(ok.request()));
        } else {
            var err = (HomeRegistryActor.GrantRequestError) result;
            ctx.status(400).json(Map.of("error", err.reason()));
        }
    }

    static Map<String, Object> requestView(GrantRequest r) {
        var v = new LinkedHashMap<String, Object>();
        v.put("id", r.id());
        v.put("requester", r.requester());
        v.put("owner", r.owner());
        v.put("resource", r.resource().toString());
        v.put("resourceType", r.resource().type());
        v.put("capability", r.capability().name());
        v.put("scope", r.scope());
        if (r.reason() != null) v.put("reason", r.reason());
        v.put("status", r.status().name());
        v.put("createdAt", r.createdAt().toString());
        if (r.respondedAt() != null) v.put("respondedAt", r.respondedAt().toString());
        if (r.responderNote() != null) v.put("responderNote", r.responderNote());
        if (r.issuedGrantId() != null) v.put("issuedGrantId", r.issuedGrantId());
        return v;
    }

    // --- View projection ----------------------------------------------

    static Map<String, Object> grantView(Grant g) {
        var v = new LinkedHashMap<String, Object>();
        v.put("id", g.id());
        v.put("issuer", g.issuer());
        v.put("subject", g.subject());
        v.put("resource", g.resource().toString());
        v.put("resourceType", g.resource().type());
        v.put("capability", g.capability().name());
        v.put("scope", g.scope());
        v.put("revocationMode", g.revocationMode().name());
        v.put("issuedAt", g.issuedAt().toString());
        v.put("expiresAt", g.expiresAt() == null ? null : g.expiresAt().toString());
        v.put("revokedAt", g.revokedAt() == null ? null : g.revokedAt().toString());
        v.put("active", g.isActive(Instant.now()));
        if (g.reason() != null) v.put("reason", g.reason());
        if (g.witness() != null) v.put("witness", g.witness());
        if (g.delegatedFrom() != null) v.put("delegatedFrom", g.delegatedFrom());
        return v;
    }

    static Map<String, Object> auditView(AuditEntry e) {
        var v = new LinkedHashMap<String, Object>();
        v.put("timestamp", e.timestamp().toString());
        v.put("actor", e.actor());
        v.put("verb", e.verb());
        v.put("resource", e.resource());
        v.put("outcome", e.outcome().name());
        v.put("detail", e.detail());
        if (e.correlation() != null) v.put("correlation", e.correlation());
        return v;
    }

    // --- Helpers ------------------------------------------------------

    private String requiredQuery(Context ctx, String name) {
        var v = ctx.queryParam(name);
        if (v == null || v.isBlank()) {
            ctx.status(400).json(Map.of("error", "missing query param: " + name));
            return null;
        }
        return v;
    }

    private static String text(JsonNode n, String field) {
        var v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    @SuppressWarnings("unchecked")
    private <R> R ask(Function<ActorRef<R>, HomeRegistryActor.Command> factory,
                      Class<R> responseClass) {
        try {
            var stage = AskPattern.ask(home, factory::apply, ASK_TIMEOUT, system.scheduler());
            return (R) ((CompletableFuture<Object>) stage.toCompletableFuture())
                .get(ASK_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("HomeRegistryActor ask failed: " + e.getMessage(), e);
        }
    }
}
