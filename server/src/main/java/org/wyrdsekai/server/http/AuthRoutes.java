package org.wyrdsekai.server.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.between.layer.IdentityReplicator;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.security.LoginRateLimiter;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.server.auth.WebAuthnService;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP routes for authentication: register, login, logout, session info, passkeys, invites.
 */
public final class AuthRoutes {

    private static final Logger log = LoggerFactory.getLogger(AuthRoutes.class);

    private final AuthService auth;
    private final InviteService inviteService; // nullable
    private final PairingService pairingService; // nullable
    private final WebAuthnService webAuthn; // nullable
    private volatile IdentityReplicator accountReplicator; // set after Between starts
    // #12 (2026-07-19 OSS hardening) — brute-force throttle for the HTTP login
    // endpoint (the most-exposed password surface: phone + web clients), keyed
    // per source IP and per targeted account. Mirrors the SSH/NATS throttles.
    private final LoginRateLimiter loginLimiter = new LoginRateLimiter();

    public AuthRoutes(AuthService auth) {
        this(auth, null, null, null);
    }

    public AuthRoutes(AuthService auth, WebAuthnService webAuthn) {
        this(auth, null, null, webAuthn);
    }

    public AuthRoutes(AuthService auth, InviteService inviteService,
                      PairingService pairingService, WebAuthnService webAuthn) {
        this.auth = auth;
        this.inviteService = inviteService;
        this.pairingService = pairingService;
        this.webAuthn = webAuthn;
    }

    /** Set the account replicator after Between starts (nullable before that). */
    public void setIdentityReplicator(IdentityReplicator replicator) {
        this.accountReplicator = replicator;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.post("/api/auth/register", this::handleRegister);
        app.post("/api/auth/login", this::handleLogin);
        app.post("/api/auth/logout", this::handleLogout);
        app.get("/api/auth/me", this::handleMe);
        app.get("/api/auth/status", this::handleStatus);
        app.post("/api/auth/adduser", this::handleAddUser);
        app.get("/api/auth/users", this::handleListUsers);
        app.post("/api/auth/link-device", this::handleLinkDevice);
        // Invite routes (Wave 1: Accounts & Security)
        app.post("/api/auth/invite", this::handleCreateInvite);
        app.post("/api/auth/redeem", this::handleRedeemInvite);
        app.get("/api/auth/invites", this::handleListInvites);
        app.delete("/api/auth/invite/{inviteId}", this::handleRevokeInvite);
        app.post("/api/auth/config", this::handleSetConfig);
        app.post("/api/auth/remove-user", this::handleRemoveUser);
        // Authenticated password rotation (any logged-in user).
        app.post("/api/auth/change-password", this::handleChangePassword);
        // Recovery routes
        app.post("/api/auth/recover", this::handleRecover);
        app.post("/api/auth/reset-zone", this::handleResetZone);
        // Test-only reset hook for multi-user conformance tests. Lets the
        // test fixture restore a user's displayName/description to a known
        // state without using the in-band `rename`/`@describe` commands
        // (which would themselves be the tested code path). Gated on
        // -Dwyrdsekai.test.reset_enabled=true OR env
        // WYRDSEKAI_TEST_RESET_ENABLED=true. Off in production. See
        if (isTestResetEnabled()) {
            app.post("/api/auth/test-reset", this::handleTestReset);
        }
        // Passkey routes
        if (webAuthn != null) {
            app.post("/api/auth/passkey/register/begin", this::handlePasskeyRegisterBegin);
            app.post("/api/auth/passkey/register/complete", this::handlePasskeyRegisterComplete);
            app.post("/api/auth/passkey/auth/begin", this::handlePasskeyAuthBegin);
            app.post("/api/auth/passkey/auth/complete", this::handlePasskeyAuthComplete);
        }
    }

    // --- Request/Response records ---

    record RegisterRequest(String username, String password,
                           @JsonProperty("display_name") String displayName) {}
    record LoginRequest(String username, String password) {}
    record AuthResponse(String token, String userId, String username, String role,
                        @JsonProperty("recoveryKey") String recoveryKey) {
        /** Without recovery key (normal login/register). */
        AuthResponse(String token, String userId, String username, String role) {
            this(token, userId, username, role, null);
        }
    }
    record ErrorResponse(String error) {}
    record MeResponse(String userId, String username,
                      @JsonProperty("display_name") String displayName, String role) {}
    record AddUserRequest(String username, String password,
                          @JsonProperty("displayName") String displayName, String role) {}
    record StatusResponse(boolean hasUsers, boolean openRegistration) {}
    record LinkDeviceRequest(String deviceToken) {}
    record UserListEntry(String id, String username,
                         @JsonProperty("displayName") String displayName, String role) {}
    record InviteRequest(@JsonProperty("name") String name, String role,
                         @JsonProperty("expiryHours") Integer expiryHours) {}
    record InviteResponse(String id, String code, String name, String role,
                          @JsonProperty("expiresAt") String expiresAt) {}
    record RedeemRequest(String code, String username, String password,
                         @JsonProperty("displayName") String displayName) {}
    record ConfigRequest(String key, String value) {}
    record RemoveUserRequest(@JsonProperty("userId") String userId) {}
    record RecoverRequest(@JsonProperty("recoveryKey") String recoveryKey,
                          @JsonProperty("newPassword") String newPassword) {}
    record ChangePasswordRequest(@JsonProperty("oldPassword") String oldPassword,
                                 @JsonProperty("newPassword") String newPassword) {}
    record ResetZoneRequest(@JsonProperty("recoveryKey") String recoveryKey) {}
    record InviteListEntry(String id, String code, @JsonProperty("intendedName") String intendedName,
                           String role, @JsonProperty("createdAt") String createdAt,
                           @JsonProperty("expiresAt") String expiresAt,
                           boolean consumed, boolean expired) {}

    // --- Handlers ---

    private void handleRegister(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), RegisterRequest.class);

        if (req.username() == null || req.username().isBlank()
            || req.password() == null || req.password().length() < 4) {
            ctx.status(400).json(new ErrorResponse(
                "Username required, password must be at least 4 characters"));
            return;
        }

        // Gate on open registration — first user always allowed, after that requires invite or steward action
        if (!auth.isOpenRegistrationAllowed()) {
            ctx.status(403).json(new ErrorResponse(
                "This household requires an invitation to join. Use /api/auth/redeem with an invite code."));
            return;
        }

        var isFirst = auth.isFirstUser();
        var session = auth.register(req.username().trim(), req.password(), req.displayName());
        if (session.isEmpty()) {
            ctx.status(409).json(new ErrorResponse("Username already taken"));
            return;
        }

        var s = session.get();
        var user = auth.findUser(s.userId()).orElseThrow();
        String recoveryKey = null;
        if (isFirst) {
            // First user = steward. Generate recovery key.
            recoveryKey = auth.generateRecoveryKey();
            // Explicitly close open registration now that a steward exists.
            // Without this, isOpenRegistrationAllowed() still returns false
            // via the null-default path — but the config row stays absent,
            // so /api/auth/status can't distinguish "default off" from
            // "steward explicitly opened". Make it visible + auditable.
            auth.setConfig("open_registration", "false", user.id());
            log.info("========================================");
            log.info("  STEWARD CREATED: {} (household initialized)", user.username());
            log.info("  Recovery key generated — shown to user ONCE.");
            log.info("  Open registration closed automatically.");
            log.info("  Use 'wyrd invite' or 'use invitation scroll' to add members.");
            log.info("========================================");
        }
        // grant residency at registration time so the
        // newly-created user (esp. the first steward) actually lives here.
        // Without this, register → users-table row, but no residency row
        // → login branches to Docks even though the user created the zone.
        // The store's provisionHook fires inside grant() to set up the Study.
        var residency = ResidencyStore.get();
        if (residency != null) {
            var zoneId = residency.localZoneId();
            if (zoneId != null) {
                residency.grant(new Residency(
                    user.id(), zoneId, user.role(), Instant.now(),
                    "self-register", null));
            }
        }
        // Replicate to mesh
        var repl = accountReplicator;
        if (repl != null) {
            var hash = auth.getPasswordHash(user.id());
            if (hash != null) repl.publishAccountCreated(user.id(), user.username(), hash,
                user.displayName(), user.role());
        }
        ctx.status(201).json(new AuthResponse(s.token(), user.id(), user.username(), user.role(), recoveryKey));
    }

    private void handleLogin(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), LoginRequest.class);

        if (req.username() == null || req.password() == null) {
            ctx.status(400).json(new ErrorResponse("Username and password required"));
            return;
        }

        // #12 (2026-07-19) — brute-force throttle, per source IP + per targeted
        // account. A locked key is refused (429) without touching bcrypt.
        var ipKey = "ip:" + ctx.ip();
        var acctKey = "acct:" + req.username().trim().toLowerCase();
        if (loginLimiter.anyLocked(ipKey, acctKey)) {
            log.warn("HTTP login throttled for '{}' from {} — too many recent failures",
                req.username().trim(), ctx.ip());
            ctx.status(429).json(new ErrorResponse("Too many failed attempts — try again later"));
            return;
        }

        var session = auth.login(req.username().trim(), req.password());
        if (session.isEmpty()) {
            loginLimiter.recordFailureAll(ipKey, acctKey);
            ctx.status(401).json(new ErrorResponse("Invalid credentials"));
            return;
        }
        loginLimiter.recordSuccessAll(ipKey, acctKey);

        var s = session.get();
        var user = auth.findUser(s.userId()).orElseThrow();
        ctx.json(new AuthResponse(s.token(), user.id(), user.username(), user.role()));
    }

    private void handleLogout(Context ctx) {
        var token = extractToken(ctx);
        if (token != null) {
            auth.logout(token);
        }
        ctx.status(204);
    }

    private void handleMe(Context ctx) {
        var token = extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("No session token"));
            return;
        }

        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return;
        }

        var u = user.get();
        ctx.json(new MeResponse(u.id(), u.username(), u.displayName(), u.role()));
    }

    // --- Status, AddUser, ListUsers, LinkDevice handlers ---

    /**
     * GET /api/auth/status — public endpoint for phone to check server state.
     * Returns whether any users exist and whether open registration is allowed.
     */
    private void handleStatus(Context ctx) {
        var hasUsers = !auth.isFirstUser();
        var openRegistration = auth.isOpenRegistrationAllowed();
        ctx.json(new StatusResponse(hasUsers, openRegistration));
    }

    /**
     * POST /api/auth/adduser — steward-only user creation (direct, no invite).
     */
    private void handleAddUser(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;

        var req = Json.mapper().readValue(ctx.body(), AddUserRequest.class);
        if (req.username() == null || req.username().isBlank()
            || req.password() == null || req.password().length() < 4) {
            ctx.status(400).json(new ErrorResponse(
                "Username required, password must be at least 4 characters"));
            return;
        }

        var role = req.role() != null ? req.role() : "member";
        var session = auth.register(req.username().trim(), req.password(),
            req.displayName() != null ? req.displayName() : req.username().trim(), role);
        if (session.isEmpty()) {
            ctx.status(409).json(new ErrorResponse("Username already taken"));
            return;
        }

        var s = session.get();
        var user = auth.findUser(s.userId()).orElseThrow();
        log.info("User created by steward {}: {} (role={})",
            caller.username(), user.username(), user.role());
        var repl = accountReplicator;
        if (repl != null) {
            var hash = auth.getPasswordHash(user.id());
            if (hash != null) repl.publishAccountCreated(user.id(), user.username(), hash,
                user.displayName(), user.role());
        }
        ctx.status(201).json(new AuthResponse(s.token(), user.id(), user.username(), user.role()));
    }

    /**
     * GET /api/auth/users — steward-only user list.
     */
    private void handleListUsers(Context ctx) {
        var caller = requireSteward(ctx);
        if (caller == null) return;

        var users = auth.listUsers().stream()
            .map(u -> new UserListEntry(u.id(), u.username(), u.displayName(), u.role()))
            .toList();
        ctx.json(users);
    }

    /**
     * POST /api/auth/link-device — link a device token to the current user's account.
     * Requires session token (Authorization header) and device token in body.
     */
    private void handleLinkDevice(Context ctx) throws Exception {
        var token = extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("Authorization required"));
            return;
        }
        var caller = auth.validateSession(token);
        if (caller.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return;
        }
        if (pairingService == null) {
            ctx.status(501).json(new ErrorResponse("Device pairing not available"));
            return;
        }

        var req = Json.mapper().readValue(ctx.body(), LinkDeviceRequest.class);
        if (req.deviceToken() == null || req.deviceToken().isBlank()) {
            ctx.status(400).json(new ErrorResponse("deviceToken is required"));
            return;
        }

        var linked = pairingService.linkDeviceToUser(req.deviceToken(), caller.get().id());
        if (!linked) {
            ctx.status(404).json(new ErrorResponse("Device token not found or already revoked"));
            return;
        }

        log.info("Device linked to user {}: token={}...", caller.get().username(),
            req.deviceToken().substring(0, Math.min(16, req.deviceToken().length())));
        ctx.json(Map.of("status", "linked", "userId", caller.get().id()));
    }

    // --- Invite handlers (Wave 1: Accounts & Security) ---

    /**
     * POST /api/auth/invite — steward creates an invite code.
     */
    private void handleCreateInvite(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;
        if (inviteService == null) {
            ctx.status(501).json(new ErrorResponse("Invite service not available"));
            return;
        }

        var req = Json.mapper().readValue(ctx.body(), InviteRequest.class);
        var name = req.name() != null ? req.name() : "";
        var role = req.role() != null ? req.role() : "member";
        var expirySeconds = req.expiryHours() != null
            ? req.expiryHours() * 3600L : 24 * 3600L;

        var invite = inviteService.createInvite(name, role, caller.id(), expirySeconds);
        var repl = accountReplicator;
        if (repl != null) repl.publishInviteCreated(invite);
        ctx.status(201).json(new InviteResponse(
            invite.id(), invite.code(), invite.intendedName(),
            invite.role(), invite.expiresAt().toString()));
    }

    /**
     * POST /api/auth/redeem — redeem an invite code and create account.
     */
    private void handleRedeemInvite(Context ctx) throws Exception {
        if (inviteService == null) {
            ctx.status(501).json(new ErrorResponse("Invite service not available"));
            return;
        }

        var req = Json.mapper().readValue(ctx.body(), RedeemRequest.class);
        if (req.code() == null || req.code().isBlank()) {
            ctx.status(400).json(new ErrorResponse("Invite code required"));
            return;
        }
        if (req.username() == null || req.username().isBlank()
            || req.password() == null || req.password().length() < 4) {
            ctx.status(400).json(new ErrorResponse(
                "Username required, password must be at least 4 characters"));
            return;
        }

        var code = req.code().trim().toLowerCase();

        // #4 (2026-07-19 OSS hardening) — CLAIM the invite atomically BEFORE
        // creating the account (consume-before-create). Previously this peeked,
        // registered, then consumed, treating a lost race as an "acceptable edge
        // case" that still created the account — so a single-use, possibly
        // steward-role invite could mint duplicate/elevated accounts under a race.
        // Now only the caller that wins the atomic claim proceeds; the loser
        // creates nothing.
        var claimToken = "claim:" + UUID.randomUUID();
        var claimed = inviteService.claimInvite(code, claimToken);
        if (claimed.isEmpty()) {
            ctx.status(404).json(new ErrorResponse(
                "Invalid, expired, or already-used invite code"));
            return;
        }

        var inviteRole = claimed.get().role();
        // #4-followup (adversarial review) — register() only returns empty on a
        // username-taken UNIQUE clash; ANY OTHER failure (non-UNIQUE SQL,
        // createSession/grant) THROWS. Without this try, a throw here skips the
        // releaseClaim below and leaves the invite permanently consumed_by the
        // claim token (dead invite). Release on both empty AND throw.
        AuthService.Session s;
        try {
            var session = auth.register(req.username().trim(), req.password(),
                req.displayName() != null ? req.displayName() : req.username().trim(),
                inviteRole);
            if (session.isEmpty()) {
                inviteService.releaseClaim(claimToken);
                ctx.status(409).json(new ErrorResponse("Username already taken"));
                return;
            }
            s = session.get();
            // Bind the now-consumed invite to the real user id (audit trail).
            inviteService.rebindClaim(claimToken, s.userId());
        } catch (RuntimeException e) {
            inviteService.releaseClaim(claimToken);
            throw e;
        }

        var user = auth.findUser(s.userId()).orElseThrow();
        log.info("New member joined via invite: {} (role={})", user.username(), user.role());
        // grant residency so invited users land in their
        // Study, not the Docks. Role comes from the invite (member | child
        // | guest). Mirrors the handleRegister path.
        var residency = ResidencyStore.get();
        if (residency != null) {
            var zoneId = residency.localZoneId();
            if (zoneId != null) {
                residency.grant(new Residency(
                    user.id(), zoneId, user.role(), Instant.now(),
                    "invite:" + code, null));
            }
        }
        // Replicate account + invite consumption to mesh
        var repl = accountReplicator;
        if (repl != null) {
            var hash = auth.getPasswordHash(user.id());
            if (hash != null) repl.publishAccountCreated(user.id(), user.username(), hash,
                user.displayName(), user.role());
            repl.publishInviteConsumed(code, s.userId());
        }
        ctx.status(201).json(new AuthResponse(s.token(), user.id(), user.username(), user.role()));
    }

    /**
     * GET /api/auth/invites — steward lists all invites.
     */
    private void handleListInvites(Context ctx) {
        var caller = requireSteward(ctx);
        if (caller == null) return;
        if (inviteService == null) {
            ctx.status(501).json(new ErrorResponse("Invite service not available"));
            return;
        }

        var invites = inviteService.listInvites().stream()
            .map(i -> new InviteListEntry(
                i.id(), i.code(), i.intendedName(), i.role(),
                i.createdAt().toString(), i.expiresAt().toString(),
                i.isConsumed(), i.isExpired()))
            .toList();
        ctx.json(invites);
    }

    /**
     * DELETE /api/auth/invite/{inviteId} — steward revokes a pending invite.
     */
    private void handleRevokeInvite(Context ctx) {
        var caller = requireSteward(ctx);
        if (caller == null) return;
        if (inviteService == null) {
            ctx.status(501).json(new ErrorResponse("Invite service not available"));
            return;
        }

        var inviteId = ctx.pathParam("inviteId");
        if (inviteService.revokeInvite(inviteId)) {
            ctx.json(Map.of("status", "revoked"));
        } else {
            ctx.status(404).json(new ErrorResponse("Invite not found or already consumed"));
        }
    }

    /**
     * POST /api/auth/config — steward sets household configuration.
     */
    private void handleSetConfig(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;

        var req = Json.mapper().readValue(ctx.body(), ConfigRequest.class);
        if (req.key() == null || req.key().isBlank()) {
            ctx.status(400).json(new ErrorResponse("Config key required"));
            return;
        }

        auth.setConfig(req.key(), req.value(), caller.id());
        var repl = accountReplicator;
        if (repl != null) repl.publishConfigChanged(req.key(), req.value(), caller.id());
        ctx.json(Map.of("status", "updated", "key", req.key(), "value", req.value()));
    }

    /**
     * POST /api/auth/remove-user — steward removes a member.
     */
    private void handleRemoveUser(Context ctx) throws Exception {
        var caller = requireSteward(ctx);
        if (caller == null) return;

        var req = Json.mapper().readValue(ctx.body(), RemoveUserRequest.class);
        if (req.userId() == null) {
            ctx.status(400).json(new ErrorResponse("userId required"));
            return;
        }

        if (auth.removeUser(caller.id(), req.userId())) {
            var repl = accountReplicator;
            if (repl != null) repl.publishAccountRemoved(req.userId());
            ctx.json(Map.of("status", "removed"));
        } else {
            ctx.status(404).json(new ErrorResponse("User not found or cannot remove steward"));
        }
    }

    /**
     * Extract and validate a steward session. Returns null (and sets ctx response) on failure.
     */
    // --- Recovery handlers ---

    /**
     * POST /api/auth/change-password — authenticated rotate. Verifies the caller's session,
     * then their current password, then sets a new one (4+ chars). 403 if the current password
     * is wrong; 401 if not logged in.
     */
    private void handleChangePassword(Context ctx) throws Exception {
        var token = extractToken(ctx);
        if (token == null) {
            ctx.status(401).json(new ErrorResponse("No session token"));
            return;
        }
        var user = auth.validateSession(token);
        if (user.isEmpty()) {
            ctx.status(401).json(new ErrorResponse("Invalid or expired session"));
            return;
        }
        var req = Json.mapper().readValue(ctx.body(), ChangePasswordRequest.class);
        if (req.oldPassword() == null || req.newPassword() == null || req.newPassword().length() < 4) {
            ctx.status(400).json(new ErrorResponse("oldPassword and newPassword (4+ chars) required"));
            return;
        }
        if (auth.changePassword(user.get().id(), req.oldPassword(), req.newPassword())) {
            ctx.json(Map.of("status", "changed", "message", "Password updated."));
        } else {
            ctx.status(403).json(new ErrorResponse("Current password is incorrect"));
        }
    }

    /**
     * POST /api/auth/recover — reset steward password with recovery key.
     * No session token required — this IS the emergency access path.
     */
    private void handleRecover(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), RecoverRequest.class);
        if (req.recoveryKey() == null || req.newPassword() == null || req.newPassword().length() < 4) {
            ctx.status(400).json(new ErrorResponse("recoveryKey and newPassword (4+ chars) required"));
            return;
        }
        if (auth.recoverSteward(req.recoveryKey(), req.newPassword())) {
            ctx.json(Map.of("status", "recovered",
                "message", "Steward password has been reset. You can now login with the new password."));
        } else {
            ctx.status(403).json(new ErrorResponse("Invalid recovery key"));
        }
    }

    /**
     * POST /api/auth/reset-zone — factory reset with recovery key.
     * Wipes all accounts, sessions, invites, config. Server must be restarted.
     */
    private void handleResetZone(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), ResetZoneRequest.class);
        if (req.recoveryKey() == null) {
            ctx.status(400).json(new ErrorResponse("recoveryKey required"));
            return;
        }
        if (auth.factoryReset(req.recoveryKey())) {
            ctx.json(Map.of("status", "reset",
                "message", "Zone has been factory reset. Restart the server to begin fresh setup."));
        } else {
            ctx.status(403).json(new ErrorResponse("Invalid recovery key"));
        }
    }

    private AuthService.User requireSteward(Context ctx) {
        var token = extractToken(ctx);
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

    // --- Passkey handlers ---

    record PasskeyRegisterBeginRequest(
        @JsonProperty("user_id") String userId,
        @JsonProperty("user_name") String userName) {}

    record PasskeyRegisterCompleteRequest(
        @JsonProperty("challenge") String challengeBase64,
        @JsonProperty("credential_id") String credentialId,
        @JsonProperty("public_key") String publicKey,
        @JsonProperty("display_name") String displayName) {}

    record PasskeyAuthBeginRequest(@JsonProperty("user_id") String userId) {}

    record PasskeyAuthCompleteRequest(
        @JsonProperty("challenge") String challengeBase64,
        @JsonProperty("credential_id") String credentialId,
        @JsonProperty("sign_count") long signCount) {}

    private void handlePasskeyRegisterBegin(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), PasskeyRegisterBeginRequest.class);
        if (req.userId() == null || req.userName() == null) {
            ctx.status(400).json(new ErrorResponse("user_id and user_name required"));
            return;
        }
        var challenge = webAuthn.beginRegistration(req.userId(), req.userName());
        ctx.json(challenge);
    }

    private void handlePasskeyRegisterComplete(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), PasskeyRegisterCompleteRequest.class);
        var result = webAuthn.completeRegistration(req.challengeBase64(), req.credentialId(),
            req.publicKey(), req.displayName());
        if (result.success()) {
            ctx.json(result);
        } else {
            ctx.status(400).json(result);
        }
    }

    private void handlePasskeyAuthBegin(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), PasskeyAuthBeginRequest.class);
        if (req.userId() == null) {
            ctx.status(400).json(new ErrorResponse("user_id required"));
            return;
        }
        var challenge = webAuthn.beginAuthentication(req.userId());
        ctx.json(challenge);
    }

    private void handlePasskeyAuthComplete(Context ctx) throws Exception {
        var req = Json.mapper().readValue(ctx.body(), PasskeyAuthCompleteRequest.class);
        var result = webAuthn.completeAuthentication(req.challengeBase64(), req.credentialId(),
            req.signCount());
        if (result.success()) {
            ctx.json(result);
        } else {
            ctx.status(401).json(result);
        }
    }

    // public: shared Bearer-token extraction — used by sibling route classes
    // (HouseholdRoutes, ResidencyRoutes) and by Main for the W16
    // steward-gated /api/recipes/run registration.
    public static String extractToken(Context ctx) {
        var authHeader = ctx.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return ctx.queryParam("token");
    }

    // --- Test-only reset hook (see /api/auth/test-reset registration) ---

    record TestResetRequest(
        @JsonProperty("username") String username,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("description") String description) {}

    record TestResetResponse(boolean ok, String userId,
                             @JsonProperty("displayName") String displayName,
                             @JsonProperty("description") String description) {}

    private static boolean isTestResetEnabled() {
        if ("true".equalsIgnoreCase(System.getProperty("wyrdsekai.test.reset_enabled"))) {
            return true;
        }
        var env = System.getenv("WYRDSEKAI_TEST_RESET_ENABLED");
        return env != null && "true".equalsIgnoreCase(env);
    }

    private void handleTestReset(Context ctx) throws Exception {
        if (!isTestResetEnabled()) {
            ctx.status(404).json(new ErrorResponse("Not found."));
            return;
        }
        var req = Json.mapper().readValue(ctx.body(), TestResetRequest.class);
        if (req.username() == null || req.username().isBlank()) {
            ctx.status(400).json(new ErrorResponse("username required"));
            return;
        }
        var user = auth.findUserByUsername(req.username().trim());
        if (user.isEmpty()) {
            ctx.status(404).json(new ErrorResponse("user not found"));
            return;
        }
        var u = user.get();
        if (req.displayName() != null) {
            auth.updateDisplayName(u.id(), req.displayName());
        }
        if (req.description() != null) {
            auth.updateDescription(u.id(), req.description());
        }
        var refreshed = auth.findUser(u.id()).orElse(u);
        ctx.json(new TestResetResponse(true, refreshed.id(),
            refreshed.displayName(),
            refreshed.description() == null ? "" : refreshed.description()));
    }
}
