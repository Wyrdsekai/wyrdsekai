package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.security.LoginRateLimiter;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.identity.AccountStore;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.naming.ZoneDirectoryService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NATS-subject parallel surface for the wyrdsekai MCP REST API.
 *
 * Implements: phone clients call
 * `wyrd.zone.{zone}.mcp.{login,tell,do}` via NATS request/reply instead of
 * `POST https://relay/api/mcp/{...}`. This removes the Caddy HTTPS hop, lets
 * the relay run on any operator-chosen port (not :443), and stops competing
 * with whatever website the operator already runs.
 *
 * <p>Coexists with {@link org.wyrdsekai.server.http.McpRoutes} during the
 * migration. Both surfaces share {@link AuthService} so a token minted on
 * one side validates on the other.
 *
 * <p>Wire shape: requests + replies are JSON. Auth tokens travel inside the
 * request body (validated via {@link AuthService#validateSession}), not as
 * HTTP Authorization headers — the request is one-shot, no headers.
 *
 * <p>Phase 1 scope: login, tell. do/library/journal land in follow-up
 * commits in this same package.
 */
public final class McpNatsHandler {

    private static final Logger log = LoggerFactory.getLogger(McpNatsHandler.class);

    // Audit F3 — max OUTSTANDING (pending) knocks a zone will hold. Bounds
    // unauthenticated directory.knock flooding; approved/denied requests don't count.
    private static final int MAX_PENDING_KNOCKS = 100;

    private final AuthService auth;
    // #12 (2026-07-19 OSS hardening) — brute-force throttle for NATS password
    // login, keyed per targeted account (no reliable source IP over NATS).
    private final LoginRateLimiter loginLimiter = new LoginRateLimiter();
    private final ActorSystem<?> system;
    private final Connection nats;
    private final String zoneId;
    private final WyrdLuceneStore luceneStore; // nullable — library subjects disabled if null
    private final StudyService studyService;   // nullable — journal subjects disabled if null
    private final InviteService inviteService; // nullable — auth.redeem disabled if null
    private final AccountStore accountStore;   // nullable — account.zonebank disabled if null
    private final AtomicReference<Dispatcher> dispatcherRef = new AtomicReference<>();
    // Subjects we've registered, so we can replay them after a NATS reconnect.
    // jnats restores subscriptions automatically on RECONNECTED in the happy
    // path, but we've seen subscription loss in production (relay-node NATS restart
    // 2026-05-12: home-server's McpNatsHandler stopped delivering wyrd.zone.alpha.*
    // until wyrdsekai was bounced). Explicit replay closes the gap.
    private final List<String> trackedSubjects = new ArrayList<>();

    public McpNatsHandler(AuthService auth, ActorSystem<?> system,
                          Connection nats, String zoneId,
                          WyrdLuceneStore luceneStore, StudyService studyService) {
        this(auth, system, nats, zoneId, luceneStore, studyService, null, null);
    }

    public McpNatsHandler(AuthService auth, ActorSystem<?> system,
                          Connection nats, String zoneId,
                          WyrdLuceneStore luceneStore, StudyService studyService,
                          InviteService inviteService) {
        this(auth, system, nats, zoneId, luceneStore, studyService, inviteService, null);
    }

    public McpNatsHandler(AuthService auth, ActorSystem<?> system,
                          Connection nats, String zoneId,
                          WyrdLuceneStore luceneStore, StudyService studyService,
                          InviteService inviteService, AccountStore accountStore) {
        this.auth = auth;
        this.system = system;
        this.nats = nats;
        this.zoneId = zoneId;
        this.luceneStore = luceneStore;
        this.studyService = studyService;
        this.inviteService = inviteService;
        this.accountStore = accountStore;
    }

    /**
     * Subscribe to NATS subjects. Safe to call once at startup after the
     * NATS connection is established. Each handler runs on the NATS
     * dispatcher thread; long-running work (tell, do) hops to a worker.
     */
    public synchronized void start() {
        if (nats == null || nats.getStatus() != Connection.Status.CONNECTED) {
            log.warn("McpNatsHandler.start(): NATS not connected, skipping");
            return;
        }
        // Build the canonical subject list once. replaySubscriptions() uses
        // this same list on reconnect, so adding a new subject here means
        // it survives a NATS restart automatically.
        trackedSubjects.clear();
        trackedSubjects.add(subject("login"));
        trackedSubjects.add(subject("tell"));
        trackedSubjects.add(authSubject("status"));
        trackedSubjects.add(authSubject("register"));
        // hermod consent mint for relay-resident phones (no HTTP reaches the
        // zone from there). Mirrors POST /api/pair/device — many doors, one
        // identity; session token in the body, same as every auth.* subject.
        trackedSubjects.add("wyrd.zone." + zoneId + ".pair.device");
        // Zone-agnostic discovery: a fresh phone doesn't know which zone it's
        // talking to. It publishes to `wyrd.discover.zone` and we reply with
        // our zone label so the phone can scope subsequent subjects correctly.
        // No auth needed — public information.
        trackedSubjects.add("wyrd.discover.zone");
        // "Find a zone": query the opt-in ZoneDirectory.
        // Only zones that publish themselves appear; hidden zones never do, and a
        // relay's roster is never enumerated. Always registered — the directory
        // degrades to an empty list when uninitialised.
        trackedSubjects.add(directorySubject("search"));
        // request access to THIS zone (a "knock" from
        // someone who found it in the directory). knock = token-free record; the
        // steward lists pending knocks (token-gated) and approves out-of-band.
        if (accountStore != null) {
            trackedSubjects.add(directorySubject("knock"));
            trackedSubjects.add(directorySubject("knock.list"));
        }
        if (inviteService != null) trackedSubjects.add(authSubject("redeem"));
        if (luceneStore != null) trackedSubjects.add(librarySubject("search"));
        if (studyService != null) trackedSubjects.add(studySubject("journal"));
        // zone-bank sync (get/put) for cross-device.
        if (accountStore != null) {
            trackedSubjects.add(accountSubject("get"));
            trackedSubjects.add(accountSubject("put"));
        }

        bindDispatcher();
        log.info("McpNatsHandler started — subscribed to {} subjects on zone {} ({})",
            trackedSubjects.size(), zoneId, joinSubjectsShort());
    }

    /**
     * Re-bind all tracked subjects on a fresh Dispatcher. Called after a NATS
     * reconnect (see Main.java where the ConnectionListener calls this).
     *
     * <p>Why this exists: jnats is documented to auto-restore subscriptions on
     * RECONNECTED, but a production incident on 2026-05-12 showed
     * subscriptions silently lost after the relay NATS container was
     * restarted — phones got "503 No Responders" on wyrd.zone.alpha.* until
     * home-server was bounced. Possible cause: when the disconnect took longer than
     * the reconnect-buffer window to detect (default pingInterval × maxPings),
     * jnats considered the old Dispatcher orphaned. Replaying from a fresh
     * Dispatcher closes that edge.</p>
     */
    public synchronized void replaySubscriptions() {
        if (nats == null || nats.getStatus() != Connection.Status.CONNECTED) {
            log.warn("McpNatsHandler.replaySubscriptions(): NATS not connected, deferring");
            return;
        }
        if (trackedSubjects.isEmpty()) {
            // start() was never called — nothing to replay. Treat as no-op.
            return;
        }
        var stale = dispatcherRef.getAndSet(null);
        if (stale != null) {
            try { nats.closeDispatcher(stale); } catch (Exception ignored) { /* best effort */ }
        }
        bindDispatcher();
        log.info("McpNatsHandler replayed {} subscriptions on zone {} after reconnect ({})",
            trackedSubjects.size(), zoneId, joinSubjectsShort());
    }

    private void bindDispatcher() {
        var dispatcher = nats.createDispatcher(this::dispatch);
        for (var subj : trackedSubjects) {
            dispatcher.subscribe(subj);
        }
        dispatcherRef.set(dispatcher);
    }

    private String joinSubjectsShort() {
        // Strip the common prefix for log compactness.
        var prefix = "wyrd.zone." + zoneId + ".";
        var sb = new StringBuilder();
        for (int i = 0; i < trackedSubjects.size(); i++) {
            if (i > 0) sb.append(',');
            var s = trackedSubjects.get(i);
            sb.append(s.startsWith(prefix) ? s.substring(prefix.length()) : s);
        }
        return sb.toString();
    }

    public synchronized void stop() {
        var d = dispatcherRef.getAndSet(null);
        if (d != null && nats != null && nats.getStatus() == Connection.Status.CONNECTED) {
            nats.closeDispatcher(d);
        }
    }

    private String subject(String op) {
        return "wyrd.zone." + zoneId + ".mcp." + op;
    }

    private String librarySubject(String op) {
        return "wyrd.zone." + zoneId + ".library." + op;
    }

    private String studySubject(String op) {
        return "wyrd.zone." + zoneId + ".study." + op;
    }

    private String authSubject(String op) {
        return "wyrd.zone." + zoneId + ".auth." + op;
    }

    private String accountSubject(String op) {
        return "wyrd.zone." + zoneId + ".account.zonebank." + op;
    }

    private String directorySubject(String op) {
        return "wyrd.zone." + zoneId + ".directory." + op;
    }

    // ── Dispatch ──

    private void dispatch(Message msg) {
        var subject = msg.getSubject();
        try {
            if (subject.endsWith(".mcp.login")) {
                handleLogin(msg);
            } else if (subject.endsWith(".mcp.tell")) {
                handleTell(msg);
            } else if (subject.endsWith(".library.search")) {
                handleLibrarySearch(msg);
            } else if (subject.endsWith(".study.journal")) {
                handleStudyJournal(msg);
            } else if (subject.endsWith(".pair.device")) {
                handlePairDevice(msg);
            } else if (subject.endsWith(".auth.status")) {
                handleAuthStatus(msg);
            } else if (subject.endsWith(".auth.register")) {
                handleAuthRegister(msg);
            } else if (subject.endsWith(".auth.redeem")) {
                handleAuthRedeem(msg);
            } else if (subject.endsWith(".account.zonebank.get")) {
                handleZoneBankGet(msg);
            } else if (subject.endsWith(".account.zonebank.put")) {
                handleZoneBankPut(msg);
            } else if (subject.endsWith(".directory.knock.list")) {
                handleDirectoryKnockList(msg);
            } else if (subject.endsWith(".directory.knock")) {
                handleDirectoryKnock(msg);
            } else if (subject.endsWith(".directory.search")) {
                handleDirectorySearch(msg);
            } else if (subject.equals("wyrd.discover.zone")) {
                respond(msg, Map.of("ok", true, "zoneId", zoneId));
            } else {
                respond(msg, error("unknown_subject", "no handler for " + subject));
            }
        } catch (Exception e) {
            log.warn("McpNatsHandler dispatch failed on {}: {}", subject, e.getMessage());
            try {
                respond(msg, error("dispatch_failed", e.getMessage()));
            } catch (Exception ignored) { /* nothing else to do */ }
        }
    }

    // ── login ──

    /**
     * Request:  { "username": "...", "password": "..." }
     * Reply:    { "ok": true,  "token": "...", "userId": "...", "username": "...", "role": "..." }
     *      or:  { "ok": false, "error": "invalid_credentials", "message": "..." }
     */
    private void handleLogin(Message msg) {
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }

        var username = optString(body, "username");
        var password = optString(body, "password");
        if (username == null || password == null) {
            respond(msg, error("missing_field", "username and password required"));
            return;
        }

        // #12 — throttle brute force per account (no reliable source IP over NATS).
        var acctKey = "acct:" + username.toLowerCase();
        if (loginLimiter.isLocked(acctKey)) {
            respond(msg, error("rate_limited", "Too many failed attempts — try again later"));
            return;
        }
        var result = auth.login(username, password);
        if (result.isEmpty()) {
            loginLimiter.recordFailure(acctKey);
            respond(msg, error("invalid_credentials", "Invalid credentials"));
            return;
        }
        loginLimiter.recordSuccess(acctKey);
        var session = result.get();
        // Best-effort role lookup; not strictly required by the probe.
        var user = auth.findUser(session.userId());
        var role = user.map(AuthService.User::role).orElse("member");

        respond(msg, Map.of(
            "ok", true,
            "token", session.token(),
            "userId", session.userId(),
            "username", username,
            "role", role,
            "zoneId", zoneId  // phone caches in case stored creds don't match a previously-paired zone
        ));
        log.info("MCP-NATS login: {} (token={}...)", username,
            session.token().length() > 8 ? session.token().substring(0, 8) : session.token());
    }

    // ── tell ──

    /**
     * Request:  { "token": "...", "target": "alpha.wyrd", "message": "..." }
     * Reply:    { "ok": true, "delivered": true, "target": "..." }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Cross-zone tells route through {@link CrossZoneTellService}; local
     * tells go through {@link AgentEventStream}. Same logic as
     * {@code McpRoutes.handleTell}, just without the synchronous
     * room-listener wait — phones receive replies via notification stream
     * (out of band), this RPC returns immediately on delivery
     * acknowledgement.
     */
    private void handleTell(Message msg) {
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }

        var token = optString(body, "token");
        if (token == null) {
            respond(msg, error("missing_token", "token required"));
            return;
        }
        var userOpt = auth.validateSession(token);
        if (userOpt.isEmpty()) {
            respond(msg, error("invalid_session", "token rejected — login first"));
            return;
        }
        var user = userOpt.get();

        var target = optString(body, "target");
        var message = optString(body, "message");
        if (target == null || message == null) {
            respond(msg, error("missing_field", "target and message required"));
            return;
        }

        boolean looksCrossZone = target.contains(".")
            || target.toLowerCase().startsWith("my ");

        if (looksCrossZone) {
            var tellService = CrossZoneTellService.get();
            if (tellService == null) {
                respond(msg, error("service_unavailable",
                    "CrossZoneTellService not initialised"));
                return;
            }
            var localZoneId = zoneId;
            var result = tellService.tell(
                user.id(), user.username(), localZoneId, target, message, null);
            if (result.delivered()) {
                log.info("MCP-NATS tell (cross-zone) '{}' delivered", target);
                respond(msg, Map.of(
                    "ok", true,
                    "delivered", true,
                    "target", target
                ));
                return;
            }
            log.info("MCP-NATS tell (cross-zone) '{}' not delivered: {}",
                target, result.errorMessage());
            respond(msg, error("not_delivered", result.errorMessage() != null
                ? result.errorMessage() : "Could not deliver to " + target));
            return;
        }

        // Local-target path: route through AgentEventStream like the
        // SSH/WebSocket tell. We DON'T do the synchronous room-listener wait
        // that McpRoutes does — phones get companion replies via their
        // notification stream subscription, not piggy-backed on this RPC.
        var registry = EntityRegistry.get();
        var eventStream = AgentEventStream.get();
        if (registry == null || eventStream == null) {
            respond(msg, error("service_unavailable", "Agent system not ready"));
            return;
        }
        var targetId = registry.findByName(target);
        if (targetId.isEmpty()) {
            respond(msg, error("agent_not_found", "Agent not found: " + target));
            return;
        }
        boolean delivered = eventStream.publishAgentMessage(
            user.id(), user.username(), targetId.get(),
            "[from " + user.username() + "] " + message);
        if (!delivered) {
            respond(msg, error("not_delivered", "AgentEventStream rejected publish"));
            return;
        }
        log.info("MCP-NATS tell (local) '{}' delivered", target);
        respond(msg, Map.of(
            "ok", true,
            "delivered", true,
            "target", target
        ));
    }

    // ── library.search ──

    /**
     * Request:  { "token": "...", "query": "...", "limit"?: 10 }
     * Reply:    { "ok": true, "query": "...", "count": N, "results": [...] }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Mirrors {@code GET /api/library/search}. Auth is enforced (was anonymous
     * over HTTP — tighter on NATS, fine: the phone always has a token).
     */
    private void handleLibrarySearch(Message msg) {
        if (luceneStore == null) {
            respond(msg, error("service_unavailable", "library not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var token = optString(body, "token");
        if (token == null || auth.validateSession(token).isEmpty()) {
            respond(msg, error("invalid_session", "token required"));
            return;
        }
        var query = optString(body, "query");
        if (query == null || query.isBlank()) {
            respond(msg, error("missing_field", "query required"));
            return;
        }
        int limit = 10;
        var limitNode = body.get("limit");
        if (limitNode != null && limitNode.isInt()) {
            limit = Math.max(1, Math.min(100, limitNode.asInt()));
        }
        var results = luceneStore.searchKnowledgeText(query, limit);
        log.info("MCP-NATS library.search q=\"{}\" limit={} hits={}", query, limit, results.size());
        respond(msg, Map.of(
            "ok", true,
            "query", query,
            "limit", limit,
            "count", results.size(),
            "results", results
        ));
    }

    // ── study.journal ──

    /**
     * Request:  { "token": "...", "content": "...", "isPrivate"?: false }
     *      or:  { "token": "...", "op": "list", "limit"?: 20 }   — list path
     * Reply:    write: { "ok": true, "id": "...", "private": false }
     *           list:  { "ok": true, "count": N, "entries": [...] }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Mirrors {@code POST /api/study/journal} (write) and
     * {@code GET /api/study/journal?user=...} (list). User DID comes from the
     * token, not the request body — phones can't forge a different user.
     */
    private void handleStudyJournal(Message msg) {
        if (studyService == null) {
            respond(msg, error("service_unavailable", "study not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var token = optString(body, "token");
        if (token == null) {
            respond(msg, error("missing_token", "token required"));
            return;
        }
        var userOpt = auth.validateSession(token);
        if (userOpt.isEmpty()) {
            respond(msg, error("invalid_session", "token rejected — login first"));
            return;
        }
        var userId = userOpt.get().id();
        var op = optString(body, "op");
        if ("list".equalsIgnoreCase(op)) {
            int limit = 20;
            var limitNode = body.get("limit");
            if (limitNode != null && limitNode.isInt()) {
                limit = Math.max(1, Math.min(100, limitNode.asInt()));
            }
            var entries = studyService.recentJournal(userId, limit);
            respond(msg, Map.of(
                "ok", true,
                "count", entries.size(),
                "entries", entries
            ));
            return;
        }
        // Default = write
        var content = optString(body, "content");
        if (content == null || content.isBlank()) {
            respond(msg, error("missing_field", "content required"));
            return;
        }
        boolean isPrivate = body.path("isPrivate").asBoolean(false);
        String id = isPrivate
            ? studyService.writePrivateJournalEntry(userId, content)
            : studyService.writeJournalEntry(userId, content);
        log.info("MCP-NATS study.journal write user={} private={} chars={} id={}",
            userId, isPrivate, content.length(), id);
        respond(msg, Map.of(
            "ok", true,
            "id", id,
            "private", isPrivate
        ));
    }

    // ── auth.status ──

    /**
     * Request:  {} (no body needed)
     * Reply:    { "ok": true, "hasUsers": bool, "openRegistration": bool }
     *
     * Public — no token required. Phones probe this to learn whether the
     * household allows open registration or needs an invite. Mirrors
     * GET /api/auth/status.
     */
    // ── pair.device ──

    /**
     * Request:  { "token": "<session>", "deviceName": "...", "deviceType"?: "phone" }
     * Reply:    { "ok": true, "deviceToken": "wyrd_dev_...", "householdId": "...",
     *             "householdName": "...", "serverDid": "...", "natsUrl": "...",
     *             "serverUrl": "..." }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Mirrors POST /api/pair/device — the hermod consent mint for phones
     * whose only leg is the relay. The session is the proof; the registry
     * row is the identity (idempotent per user+name, PairingService).
     */
    private void handlePairDevice(Message msg) {
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("bad_request", "unreadable body"));
            return;
        }
        var token = optString(body, "token");
        if (token == null || token.isBlank()) {
            respond(msg, error("invalid_session", "session token required"));
            return;
        }
        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            respond(msg, error("invalid_session", "invalid or expired session"));
            return;
        }
        var pairing = PairingService.get();
        if (pairing == null) {
            respond(msg, error("pairing_unavailable", "pairing service not initialised"));
            return;
        }
        var r = pairing.pairForUser(user.get().id(),
            optString(body, "deviceName"), optString(body, "deviceType"));
        var reply = new LinkedHashMap<String, Object>();
        reply.put("ok", true);
        reply.put("deviceToken", r.token());
        reply.put("householdId", r.householdId());
        reply.put("householdName", r.householdName());
        reply.put("serverDid", r.serverDid());
        reply.put("natsUrl", r.natsUrl());
        reply.put("serverUrl", r.serverUrl());
        respond(msg, reply);
        log.info("pair.device: device identity minted via relay session for {}",
            user.get().username());
    }

    private void handleAuthStatus(Message msg) {
        boolean hasUsers = !auth.isFirstUser();
        boolean openReg = auth.isOpenRegistrationAllowed();
        respond(msg, Map.of(
            "ok", true,
            "hasUsers", hasUsers,
            "openRegistration", openReg,
            "zoneId", zoneId  // phone confirms which zone it's talking to
        ));
    }

    // ── auth.register ──

    /**
     * Request:  { "username": "...", "password": "...", "displayName"?: "..." }
     * Reply:    { "ok": true, "token": "...", "userId": "...", "username": "...",
     *             "role": "...", "recoveryKey"?: "..." (only for first/steward) }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Mirrors POST /api/auth/register. Honors the steward-bootstrap gate:
     * isOpenRegistrationAllowed() must be true (first user always allowed;
     * after that requires invite — phones get a {@code registration_closed}
     * error and should call {@link #handleAuthRedeem} with the invite code).
     */
    private void handleAuthRegister(Message msg) {
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var username = optString(body, "username");
        var password = optString(body, "password");
        var displayName = optString(body, "displayName");
        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            respond(msg, error("validation",
                "Username required, password must be at least 4 characters"));
            return;
        }
        if (!auth.isOpenRegistrationAllowed()) {
            respond(msg, error("registration_closed",
                "This household requires an invitation to join. Use the invite-redeem flow."));
            return;
        }
        boolean isFirst = auth.isFirstUser();
        var result = auth.register(username.trim(), password, displayName);
        if (result.isEmpty()) {
            respond(msg, error("username_taken", "Username already taken"));
            return;
        }
        var session = result.get();
        var user = auth.findUser(session.userId()).orElseThrow();
        String recoveryKey = null;
        if (isFirst) {
            recoveryKey = auth.generateRecoveryKey();
            // Close open registration after first steward — matches HTTP behavior.
            auth.setConfig("open_registration", "false", user.id());
            log.info("MCP-NATS register: first steward created — {}", user.username());
        }
        // grant residency at registration time (parity with HTTP path).
        var residency = ResidencyStore.get();
        if (residency != null) {
            var residencyZone = residency.localZoneId();
            if (residencyZone != null) {
                residency.grant(new Residency(
                    user.id(), residencyZone, user.role(), Instant.now(),
                    "self-register", null));
            }
        }
        var reply = new LinkedHashMap<String, Object>();
        reply.put("ok", true);
        reply.put("token", session.token());
        reply.put("userId", user.id());
        reply.put("username", user.username());
        reply.put("role", user.role());
        reply.put("zoneId", zoneId);
        if (recoveryKey != null) reply.put("recoveryKey", recoveryKey);
        respond(msg, reply);
        log.info("MCP-NATS register: {} (first={})", user.username(), isFirst);
    }

    // ── auth.redeem ──

    /**
     * Request:  { "code": "...", "username": "...", "password": "...", "displayName"?: "..." }
     * Reply:    { "ok": true, "token": "...", "userId": "...", "username": "...", "role": "..." }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Mirrors POST /api/auth/redeem. This is the canonical join path for
     * closed-registration households — phones use it when auth.register
     * returns {error: "registration_closed"}.
     */
    private void handleAuthRedeem(Message msg) {
        if (inviteService == null) {
            respond(msg, error("not_available", "Invite service not available"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var code = optString(body, "code");
        var username = optString(body, "username");
        var password = optString(body, "password");
        var displayName = optString(body, "displayName");
        if (code == null || code.isBlank()) {
            respond(msg, error("validation", "Invite code required"));
            return;
        }
        if (username == null || username.isBlank() || password == null || password.length() < 4) {
            respond(msg, error("validation",
                "Username required, password must be at least 4 characters"));
            return;
        }
        var normalized = code.trim().toLowerCase();
        // #4 (2026-07-19 OSS hardening) — claim atomically BEFORE creating the
        // account (consume-before-create); a lost race now creates no account.
        var claimToken = "claim:" + UUID.randomUUID();
        var claimed = inviteService.claimInvite(normalized, claimToken);
        if (claimed.isEmpty()) {
            respond(msg, error("invalid_invite", "Invalid, expired, or already-used invite code"));
            return;
        }
        var inviteRole = claimed.get().role();
        // #4-followup (adversarial review) — release the claim on a register()
        // THROW too, not just on empty; otherwise a non-UNIQUE failure orphans the
        // invite forever (consumed_by=claim token).
        AuthService.Session s;
        try {
            var session = auth.register(
                username.trim(), password,
                displayName != null ? displayName : username.trim(),
                inviteRole);
            if (session.isEmpty()) {
                inviteService.releaseClaim(claimToken);
                respond(msg, error("username_taken", "Username already taken"));
                return;
            }
            s = session.get();
            inviteService.rebindClaim(claimToken, s.userId());
        } catch (RuntimeException e) {
            inviteService.releaseClaim(claimToken);
            throw e;
        }
        var user = auth.findUser(s.userId()).orElseThrow();
        log.info("MCP-NATS redeem: {} joined via invite (role={})", user.username(), user.role());
        var residency = ResidencyStore.get();
        if (residency != null) {
            var zone = residency.localZoneId();
            if (zone != null) {
                residency.grant(new Residency(
                    user.id(), zone, user.role(), Instant.now(),
                    "invite:" + normalized, null));
            }
        }
        respond(msg, Map.of(
            "ok", true,
            "token", s.token(),
            "userId", user.id(),
            "username", user.username(),
            "role", user.role(),
            "zoneId", zoneId  // phone caches this — no 'home' fallback needed
        ));
    }

    // ── account.zonebank ── ( §P3: home-zone-anchored device sync)

    /**
     * Request:  { "token": "..." }
     * Reply:    { "ok": true, "bank": "<json string|null>", "updatedAt": <epochMs> }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Pull the caller's synced zone bank. The accountId is whatever the session
     * resolves to ({@code user.id()}) — identity-agnostic, so a UUID userId and a
     * DID resolve to the same row as long as the same session backs them. Secrets
     * (zone passwords) never travel through here; only the address book does.
     */
    private void handleZoneBankGet(Message msg) {
        if (accountStore == null) {
            respond(msg, error("service_unavailable", "account sync not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var token = optString(body, "token");
        if (token == null) {
            respond(msg, error("missing_token", "token required"));
            return;
        }
        var userOpt = auth.validateSession(token);
        if (userOpt.isEmpty()) {
            respond(msg, error("invalid_session", "token rejected — login first"));
            return;
        }
        var accountId = userOpt.get().id();
        var rec = accountStore.getZoneBank(accountId);
        var reply = new HashMap<String, Object>();
        reply.put("ok", true);
        reply.put("bank", rec.map(AccountStore.ZoneBankRecord::bankJson).orElse(null));
        reply.put("updatedAt", rec.map(AccountStore.ZoneBankRecord::updatedAt).orElse(0L));
        respond(msg, reply);
    }

    /**
     * Request:  { "token": "...", "bank": "<json string>", "updatedAt": <epochMs> }
     * Reply:    { "ok": true, "updatedAt": <epochMs> }
     *      or:  { "ok": false, "error": "...", "message": "..." }
     *
     * Store the caller's zone bank. The client has already merged per-entry LWW
     * locally; the server is a dumb last-write blob store keyed by account. The
     * supplied {@code updatedAt} is echoed back so the device can record what it
     * persisted.
     */
    private void handleZoneBankPut(Message msg) {
        if (accountStore == null) {
            respond(msg, error("service_unavailable", "account sync not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var token = optString(body, "token");
        if (token == null) {
            respond(msg, error("missing_token", "token required"));
            return;
        }
        var userOpt = auth.validateSession(token);
        if (userOpt.isEmpty()) {
            respond(msg, error("invalid_session", "token rejected — login first"));
            return;
        }
        var accountId = userOpt.get().id();
        var bankJson = optString(body, "bank");
        if (bankJson == null || bankJson.isBlank()) {
            respond(msg, error("missing_field", "bank required"));
            return;
        }
        long updatedAt = body.path("updatedAt").asLong(0L);
        if (updatedAt <= 0L) {
            updatedAt = Instant.now().toEpochMilli();
        }
        accountStore.putZoneBank(accountId, bankJson, updatedAt);
        log.info("MCP-NATS account.zonebank put account={} bytes={} updatedAt={}",
            accountId, bankJson.length(), updatedAt);
        respond(msg, Map.of(
            "ok", true,
            "updatedAt", updatedAt
        ));
    }

    // ── directory.search ── (: "Find a zone")

    /**
     * Request:  { "query"?: "...", "limit"?: <int> }   (no token — public info)
     * Reply:    { "ok": true, "zones": [ { did, zoneLabel, displayName?, tagline?,
     *                                       icon?, tags?, refreshedAt? }, ... ] }
     *
     * Surfaces the opt-in {@link ZoneDirectoryService} so a phone's "Find a zone"
     * screen can list zones that publish themselves. Hidden zones never appear and
     * a relay's roster is never enumerated — the directory only knows what zones
     * chose to advertise. A blank query returns the most-recently-refreshed zones;
     * a {@code tag:} or {@code capability:} prefix filters; otherwise the query is
     * treated as a recency listing (substring/semantic search lives only in the
     * rendezvous backend, which the directory degrades past gracefully).
     */
    private void handleDirectorySearch(Message msg) {
        JsonNode body;
        try {
            body = msg.getData() == null || msg.getData().length == 0
                ? Json.mapper().createObjectNode()
                : Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var query = optString(body, "query");
        int limit = 20;
        var limitNode = body.get("limit");
        if (limitNode != null && limitNode.isInt()) {
            limit = Math.max(1, Math.min(50, limitNode.asInt()));
        }
        // Map the phone's free-text query onto renderDiscover's mode grammar.
        // A bare term → recency listing (the directory has no substring search at
        // the interface level); explicit tag:/capability: prefixes pass through.
        String mode;
        if (query == null || query.isBlank()) {
            mode = "recent";
        } else if (query.startsWith("tag:") || query.startsWith("capability:")) {
            mode = query.trim();
        } else {
            mode = "recent";
        }
        var zonesJson = ZoneDirectoryService.renderDiscover(mode, Integer.toString(limit));
        try {
            var reply = Json.mapper().createObjectNode();
            reply.put("ok", true);
            reply.set("zones", Json.mapper().readTree(zonesJson));
            respond(msg, reply);
        } catch (Exception e) {
            respond(msg, error("serialization_failed", e.getMessage()));
        }
    }

    // ── directory.knock ── (: request access)

    /**
     * Request:  { "requesterName": "...", "requesterContact"?: "...", "reason"?: "..." }
     *           (token-free — the requester need not have an account here yet;
     *            that is the whole point of a knock.)
     * Reply:    { "ok": true, "requestId": "..." }  or  { ok:false, ... }
     *
     * Records a pending access request for THIS zone (the subject is
     * wyrd.zone.{thisZone}.directory.knock, so the knock arrives at the zone the
     * requester targeted). The steward lists pending knocks via knock.list and
     * approves out-of-band (mints an invite). Notification to the steward is
     * best-effort if a NotificationService is wired.
     */
    private void handleDirectoryKnock(Message msg) {
        if (accountStore == null) {
            respond(msg, error("service_unavailable", "access requests not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var requesterName = optString(body, "requesterName");
        if (requesterName == null || requesterName.isBlank()) {
            respond(msg, error("validation", "requesterName required"));
            return;
        }
        var contact = optString(body, "requesterContact");
        var reason = optString(body, "reason");
        // Audit F3 (pre-OSS): knock is token-free by design (a stranger with no
        // account requests entry), so it is trivially floodable. Cap the number of
        // OUTSTANDING (pending) knocks per zone so an unauthenticated attacker
        // can't exhaust the steward's inbox / account store. Approved/denied
        // knocks don't count, so a legitimate backlog the steward is working
        // through never blocks new requests.
        if (accountStore.listAccessRequests(zoneId, "pending", MAX_PENDING_KNOCKS + 1).size()
                >= MAX_PENDING_KNOCKS) {
            log.warn("MCP-NATS directory.knock: zone {} at pending-knock cap ({}), refusing '{}'",
                zoneId, MAX_PENDING_KNOCKS, requesterName);
            respond(msg, error("too_many_requests",
                "this zone has too many pending access requests — try again later"));
            return;
        }
        var id = UUID.randomUUID().toString();
        accountStore.addAccessRequest(id, zoneId, requesterName.trim(),
            contact, reason, Instant.now().toEpochMilli());
        log.info("MCP-NATS directory.knock: '{}' wants into zone {} (req {})",
            requesterName, zoneId, id);
        respond(msg, Map.of("ok", true, "requestId", id));
    }

    /**
     * Request:  { "token": "...", "status"?: "pending", "limit"?: <int> }
     * Reply:    { "ok": true, "requests": [ { id, requesterName, requesterContact,
     *                                          reason, status, createdAt }, ... ] }
     *
     * The steward's inbox — token-gated (only an authenticated member of this
     * zone may read who has knocked). Defaults to pending.
     */
    private void handleDirectoryKnockList(Message msg) {
        if (accountStore == null) {
            respond(msg, error("service_unavailable", "access requests not available on this zone"));
            return;
        }
        JsonNode body;
        try {
            body = Json.mapper().readTree(msg.getData());
        } catch (Exception e) {
            respond(msg, error("invalid_json", "request body must be JSON"));
            return;
        }
        var token = optString(body, "token");
        if (token == null) {
            respond(msg, error("missing_token", "token required"));
            return;
        }
        // Audit F3 (pre-OSS): the knock list is the steward's inbox and exposes
        // requester PII (name, contact, reason). It used to be readable by ANY
        // authenticated member; restrict to the steward, matching the HTTP
        // ResidencyRoutes/PairingRoutes requireSteward gate.
        var caller = auth.validateSession(token);
        if (caller.isEmpty()) {
            respond(msg, error("invalid_session", "token rejected — login first"));
            return;
        }
        if (!"steward".equals(caller.get().role())) {
            respond(msg, error("forbidden", "only the steward may list access requests"));
            return;
        }
        var status = optString(body, "status");
        if (status == null) status = "pending";
        int limit = 50;
        var limitNode = body.get("limit");
        if (limitNode != null && limitNode.isInt()) {
            limit = Math.max(1, Math.min(200, limitNode.asInt()));
        }
        var requests = accountStore.listAccessRequests(zoneId, status, limit).stream()
            .map(r -> {
                var m = new HashMap<String, Object>();
                m.put("id", r.id());
                m.put("requesterName", r.requesterName());
                if (r.requesterContact() != null) m.put("requesterContact", r.requesterContact());
                if (r.reason() != null) m.put("reason", r.reason());
                m.put("status", r.status());
                m.put("createdAt", r.createdAt());
                return m;
            })
            .toList();
        respond(msg, Map.of("ok", true, "requests", requests));
    }

    // ── helpers ──

    private static String optString(JsonNode node, String field) {
        var v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of(
            "ok", false,
            "error", code,
            "message", message == null ? "" : message
        );
    }

    private void respond(Message msg, Object payload) {
        if (msg.getReplyTo() == null) {
            log.debug("McpNatsHandler: message has no reply subject, dropping reply");
            return;
        }
        byte[] bytes;
        try {
            bytes = Json.mapper().writeValueAsBytes(payload);
        } catch (Exception e) {
            bytes = ("{\"ok\":false,\"error\":\"serialization_failed\",\"message\":\""
                + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        nats.publish(msg.getReplyTo(), bytes);
    }
}
