package org.wyrdsekai.server.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.server.http.ApiAuth.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.wyrdsekai.server.http.ApiAuth.Level.LOGIN;
import static org.wyrdsekai.server.http.ApiAuth.Level.OPERATOR;
import static org.wyrdsekai.server.http.ApiAuth.Level.PUBLIC;
import static org.wyrdsekai.server.http.ApiAuth.Level.RESIDENT;
import static org.wyrdsekai.server.http.ApiAuth.Level.STEWARD;

/**
 * What each HTTP route needs, keyed by method and the path as registered.
 *
 * <p>PUBLIC routes carry their own proof (a password, a recovery key, the household key,
 * a pairing code, a webhook signature, a signed record) or publish only what is meant to
 * be public. LOGIN routes still decide inside whose data a caller may touch. A route that
 * is not listed needs the steward; {@code ApiPolicyTest} fails when a registered route is
 * missing here.
 */
public final class ApiPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApiPolicy.class);
    private static final Map<String, Level> LEVELS = new HashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private ApiPolicy() {}

    private static void put(Level level, String... routes) {
        for (var r : routes) LEVELS.put(r, level);
    }

    static {
        // Carry their own proof, or are meant to be public.
        put(PUBLIC,
            "POST /api/auth/register", "POST /api/auth/login", "POST /api/auth/redeem",
            "POST /api/auth/recover", "POST /api/auth/reset-zone", "GET /api/auth/status",
            "POST /api/auth/passkey/auth/begin", "POST /api/auth/passkey/auth/complete",
            "POST /api/pair/request", "POST /api/pair/verify", "POST /api/pair/key",
            "POST /api/household/join", "POST /api/household/join/challenge",
            "POST /api/webhook/{subscriptionId}", "POST /api/library/webhook/{serviceId}",
            "GET /api/oauth/openrouter/callback",
            "POST /api/home/grant-requests",
            "GET /api/version",
            "GET /api/directory/recent", "GET /api/directory/tag/{tag}",
            "GET /api/directory/capability/{capability}", "GET /api/directory/known-manifests",
            "GET /api/directory/{did}",
            "GET /api/identity/outbox/{did}", "PUT /api/identity/outbox",
            "POST /api/mcp/login");

        // Any login; the handler scopes to the caller where the data is someone's.
        put(LOGIN,
            "POST /api/auth/logout", "GET /api/auth/me", "POST /api/auth/change-password",
            "POST /api/auth/link-device",
            "POST /api/auth/passkey/register/begin", "POST /api/auth/passkey/register/complete",
            "GET /api/pair/status", "POST /api/pair/device",
            "GET /api/federation/status", "GET /api/federation/mesh-status",
            "GET /api/federation/agreements", "GET /api/federation/agreements/{remoteZone}",
            "GET /api/version/mesh", "GET /api/zone/namespaces",
            "GET /api/recipes", "GET /api/recipes/{name}", "GET /api/recipes/{name}/log",
            "GET /api/home/summary", "GET /api/home/grants/issued", "GET /api/home/grants/held",
            "GET /api/home/grants/{id}", "POST /api/home/grants", "DELETE /api/home/grants/{id}",
            "GET /api/home/audit", "POST /api/home/check",
            "GET /api/home/grant-requests/pending", "GET /api/home/grant-requests/by-requester",
            "POST /api/home/grant-requests/{id}/approve", "POST /api/home/grant-requests/{id}/deny",
            "POST /api/home/grant-requests/{id}/cancel",
            "GET /api/household/members", "GET /api/household/audit",
            "GET /api/household/quiet", "POST /api/household/quiet",
            "GET /api/library/search", "GET /api/library/packs", "GET /api/library/packs/{name}",
            "GET /api/library/status", "GET /api/library/available", "GET /api/library/proposals",
            "GET /api/library/misses", "GET /api/library/bench", "GET /api/library/findings",
            "GET /api/library/opds", "POST /api/library/share-collection",
            "GET /api/mcp/look", "POST /api/mcp/go", "POST /api/mcp/tell", "POST /api/mcp/do",
            "GET /api/mcp/status", "GET /api/mcp/events", "POST /api/mcp/logout",
            "POST /api/oracle/ingest", "POST /api/oracle/anticipate", "GET /api/oracle/stats",
            "GET /api/rooms", "GET /api/search",
            "GET /api/soul/list", "GET /api/soul/{did}", "GET /api/soul/{did}/history",
            "GET /api/soul/{did}/version/{version}", "POST /api/soul/{did}", "POST /api/soul/{did}/identity",
            "GET /api/study/search", "GET /api/study/journal", "POST /api/study/journal",
            "GET /api/study/status", "DELETE /api/study/collection/{name}",
            "PUT /api/study/item/{id}", "GET /api/study/item/{id}/history",
            "POST /api/study/consent/grant", "POST /api/study/consent/revoke",
            "GET /api/study/consent", "GET /api/study/sharing-context", "GET /api/study/consent/search",
            "POST /api/study/share", "POST /api/study/unshare", "GET /api/study/shares",
            "GET /api/study/share/search", "GET /api/study/disk-usage",
            "GET /api/familiar/journal", "GET /api/familiar/journal/search",
            "GET /api/update/status", "GET /api/update/health",
            "GET /api/voice/{did}",
            "GET /api/wards/{roomId}",
            "GET /api/residency/self",
            "POST /api/companion/ask",
            "GET /api/repair");

        put(STEWARD,
            "POST /api/auth/adduser", "GET /api/auth/users", "POST /api/auth/invite",
            "DELETE /api/auth/invite/{inviteId}", "GET /api/auth/invites", "POST /api/auth/config",
            "POST /api/auth/remove-user",
            "GET /api/pair/code", "GET /api/pair/household-key", "POST /api/pair/household-key/generate",
            "GET /api/pair/devices", "DELETE /api/pair/devices/{deviceId}",
            "POST /api/federation/propose/{targetZone}", "POST /api/federation/accept/{targetZone}",
            "POST /api/federation/revoke/{targetZone}",
            "POST /api/backup/snapshot", "GET /api/backup/list",
            "POST /api/recipes/run", "POST /api/recipes/{name}/pause", "POST /api/recipes/{name}/resume",
            "POST /api/inference/pause", "POST /api/inference/resume", "POST /api/quiesce",
            "POST /api/inference/complete",
            "GET /api/body", "POST /api/body/gone", "POST /api/body/vouch", "GET /api/body/immune",
            "POST /api/body/forget", "POST /api/body/door", "GET /api/body/hooks",
            "POST /api/body/hooks/arm", "POST /api/body/hooks/replay",
            "GET /api/vault", "POST /api/vault/snapshot", "POST /api/vault/drill", "POST /api/vault/prune",
            "GET /api/items/broken", "POST /api/items/repair",
            "POST /api/rooms/prune", "DELETE /api/rooms/{roomId}",
            "GET /api/residency/list", "POST /api/residency/grant", "POST /api/residency/revoke",
            "GET /api/consents", "POST /api/consents/{id}",
            "POST /api/forge/{companion}", "POST /api/repair/{companion}/release",
            "POST /api/library/install", "POST /api/library/prune-sidecars",
            "DELETE /api/library/packs/{name}", "POST /api/library/proposals/{id}/approve",
            "POST /api/library/proposals/{id}/reject", "POST /api/library/findings/{id}/accept",
            "POST /api/library/findings/{id}/dispute", "POST /api/library/findings/{id}/retire",
            "POST /api/library/findings/review",
            "GET /api/mcp/sessions", "POST /api/mcp/dismiss", "POST /api/mcp/vouch", "POST /api/mcp/unvouch",
            "GET /api/oauth/openrouter/start", "GET /api/oauth/openrouter/status",
            "POST /api/skill/author", "GET /api/skill/drafts", "GET /api/skill/drafts/{id}",
            "POST /api/skill/drafts/{id}/approve", "POST /api/skill/drafts/{id}/reject",
            "POST /api/skill/drafts/{id}/edit",
            "POST /api/update/check", "GET /api/update/manifest", "GET /api/update/package",
            "POST /api/wards", "DELETE /api/wards", "GET /api/mail/headers",
            "PUT /api/voice/{did}", "POST /api/voice/{did}/clauses/{key}",
            "DELETE /api/voice/{did}/clauses/{key}", "POST /api/voice/{did}/freeze",
            "POST /api/voice/{did}/unfreeze", "POST /api/voice/{did}/revert/{rev}",
            "POST /api/seed/generate", "POST /api/seed/verify", "POST /api/seed/restore",
            "DELETE /api/household/members/{id}", "POST /api/household/members/{id}/promote");

        // The machine itself: server paths, debugging views, test hooks.
        put(OPERATOR,
            "GET /metrics", "GET /api/shadow", "GET /api/shadow/latest",
            "POST /api/study/add", "POST /api/study/import", "POST /api/study/export",
            "POST /api/auth/test-reset",
            "POST /api/issues", "GET /api/issues", "GET /api/issues/{id}",
            "GET /api/issues/{id}/export", "POST /api/issues/{id}/close",
            "GET /api/authored-recipes", "GET /api/authored-recipes/{name}", "POST /api/authored-recipes",
            "DELETE /api/authored-recipes/{name}", "GET /api/recipe-provenance",
            "POST /api/recipes/bondholder/eligibility", "POST /api/recipes/bondholder/pairs",
            "GET /api/recipes/{name}/tune/stats", "POST /api/recipes/{name}/tune/apply",
            "POST /api/library/compact/merge", "POST /api/library/compact/probe",
            "POST /api/library/compact/prune", "POST /api/library/compact/reembed",
            "POST /api/library/compact/snapshot", "POST /api/library/freshness/enumerate",
            "POST /api/library/freshness/prune-ids");
    }

    static Level levelFor(String method, String path) {
        var level = LEVELS.get(method + " " + path);
        if (level != null) return level;
        if (path.startsWith("/api/test/")) return OPERATOR;
        if (path.startsWith("/api/resident/")) return RESIDENT;
        if (WARNED.add(method + " " + path)) {
            log.warn("No access rule for {} {}; only the steward may call it until one is added to ApiPolicy",
                method, path);
        }
        return STEWARD;
    }

    /** For the completeness test. */
    static boolean isListed(String method, String path) {
        return LEVELS.containsKey(method + " " + path)
            || path.startsWith("/api/test/") || path.startsWith("/api/resident/");
    }
}
