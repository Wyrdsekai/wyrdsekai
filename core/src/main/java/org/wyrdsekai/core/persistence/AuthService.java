package org.wyrdsekai.core.persistence;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.wyrdsekai.core.home.ResidencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Session-based authentication backed by SQLite or PostgreSQL.
 * M0 identity: username + bcrypt password, session tokens.
 * Household security: open registration disabled by default (Wave 1).
 * First user auto-becomes steward; subsequent users require invite codes.
 */
public final class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int BCRYPT_COST = 12;
    private static final long SESSION_DURATION_SECONDS = 7 * 24 * 3600; // 7 days

    // #16 (2026-07-19 OSS hardening) — a real bcrypt hash at the same cost as
    // user password hashes. login() verifies the supplied password against THIS
    // for unknown usernames so an unknown user costs the same wall-clock as a
    // known one, closing the username-enumeration timing oracle.
    private static final String DUMMY_BCRYPT_HASH =
        BCrypt.withDefaults().hashToString(BCRYPT_COST, "timing-equalizer".toCharArray());

    /** Household config key for open registration. */
    public static final String CONFIG_OPEN_REGISTRATION = "open_registration";

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public record User(String id, String username, String displayName, String role,
                       String description, Instant createdAt) {
        /** Backward-compatible constructor without description. */
        public User(String id, String username, String displayName, String role, Instant createdAt) {
            this(id, username, displayName, role, null, createdAt);
        }
    }
    public record Session(String token, String userId, Instant createdAt, Instant expiresAt) {}

    public AuthService(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public AuthService(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /**
     * Register a new user. Auto-determines role: first user becomes steward, subsequent users are members.
     * @return session token on success, empty if username taken
     */
    public Optional<Session> register(String username, String password, String displayName) {
        var role = countUsers() == 0 ? "steward" : "member";
        return register(username, password, displayName, role);
    }

    /**
     * Register a new user with an explicit role.
     * @return session token on success, empty if username taken
     */
    public Optional<Session> register(String username, String password, String displayName, String role) {
        var userId = UUID.randomUUID().toString();
        var hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray());
        var effectiveRole = role != null ? role : "member";

        try (var conn = getConnection()) {
            var sql = "INSERT INTO users (id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, username);
                stmt.setString(3, hash);
                stmt.setString(4, displayName != null ? displayName : username);
                stmt.setString(5, effectiveRole);
                stmt.executeUpdate();
            }
            log.info("User registered: {} ({}) role={}", username, userId, effectiveRole);
            // a locally created account is a resident of
            // this zone. Granting here (not per-surface) means SSH/telnet
            // bootstrap and invite redemption land in the Study, not the
            // Docks. No-op when ResidencyStore isn't initialised.
            ResidencyStore.grantLocal(userId, effectiveRole, "account-create");
            return Optional.of(createSession(conn, userId));
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                log.debug("Registration failed — username taken: {}", username);
                return Optional.empty();
            }
            throw new RuntimeException("Registration failed", e);
        }
    }

    /**
     * Authenticate with username and password.
     * @return session on success, empty if credentials invalid
     */
    public Optional<Session> login(String username, String password) {
        try (var conn = getConnection()) {
            var sql = "SELECT id, password_hash FROM users WHERE username = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                var rs = stmt.executeQuery();
                if (!rs.next()) {
                    // #16 — spend the same bcrypt time on a missing user so login
                    // timing doesn't reveal whether the username exists.
                    BCrypt.verifyer().verify(password.toCharArray(), DUMMY_BCRYPT_HASH);
                    return Optional.empty();
                }

                var userId = rs.getString("id");
                var hash = rs.getString("password_hash");
                var result = BCrypt.verifyer().verify(password.toCharArray(), hash);
                if (!result.verified) return Optional.empty();

                return Optional.of(createSession(conn, userId));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Login failed", e);
        }
    }

    /**
     * Change a logged-in user's password: verify the current password, then write a fresh bcrypt
     * hash. The authenticated-user rotate path (distinct from {@link #recoverSteward}, the
     * recovery-key emergency reset). Existing sessions are intentionally left valid.
     *
     * @return true on success; false if the user is missing or the current password didn't verify.
     */
    public boolean changePassword(String userId, String currentPassword, String newPassword) {
        try (var conn = getConnection()) {
            String hash;
            try (var stmt = conn.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
                stmt.setString(1, userId);
                var rs = stmt.executeQuery();
                if (!rs.next()) return false;
                hash = rs.getString("password_hash");
            }
            if (hash == null
                    || !BCrypt.verifyer().verify(currentPassword.toCharArray(), hash).verified) {
                return false;
            }
            var newHash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray());
            try (var stmt = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
                stmt.setString(1, newHash);
                stmt.setString(2, userId);
                stmt.executeUpdate();
            }
            log.info("Password changed for user {}", userId);
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Change password failed", e);
        }
    }

    /**
     * Validate a session token.
     * @return user if session is valid and not expired
     */
    public Optional<User> validateSession(String token) {
        try (var conn = getConnection()) {
            var sql = "SELECT u.id, u.username, u.display_name, u.role, u.created_at"
                + " FROM sessions s JOIN users u ON s.user_id = u.id"
                + " WHERE s.token = ? AND s.expires_at > " + dialect.currentEpoch();
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, token);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                var role = rs.getString("role");
                return Optional.of(new User(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    role != null ? role : "member",
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Session validation failed", e);
        }
    }

    /**
     * Invalidate a session token (logout).
     */
    public void logout(String token) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
                stmt.setString(1, token);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Logout failed", e);
        }
    }

    /**
     * Look up a user by ID.
     */
    public Optional<User> findUser(String userId) {
        try (var conn = getConnection()) {
            var sql = "SELECT id, username, display_name, role, description, created_at FROM users WHERE id = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                var role = rs.getString("role");
                return Optional.of(new User(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    role != null ? role : "member",
                    rs.getString("description"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("User lookup failed", e);
        }
    }

    /**
     * Count total registered users.
     */
    public int countUsers() {
        try (var conn = getConnection()) {
            try (var stmt = conn.createStatement()) {
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("User count failed", e);
        }
    }

    /**
     * Check if no users exist yet (first-user registration).
     */
    public boolean isFirstUser() {
        return countUsers() == 0;
    }

    /**
     * Find a user by username.
     */
    public Optional<User> findUserByUsername(String username) {
        try (var conn = getConnection()) {
            var sql = "SELECT id, username, display_name, role, description, created_at FROM users WHERE username = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                var role = rs.getString("role");
                return Optional.of(new User(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("display_name"),
                    role != null ? role : "member",
                    rs.getString("description"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("User lookup by username failed", e);
        }
    }

    /**
     * Register a new user by an admin (steward). Caller must be steward.
     * @return session for the new user on success, empty if caller is not steward or username taken
     */
    public Optional<Session> registerByAdmin(String callerUserId, String username, String password,
                                              String displayName, String role) {
        var caller = findUser(callerUserId);
        if (caller.isEmpty() || !"steward".equals(caller.get().role())) {
            return Optional.empty();
        }
        return register(username, password, displayName, role != null ? role : "member");
    }

    /**
     * Set the role of a target user. Caller must be steward.
     * @return true if role was changed, false if caller is not steward or target not found
     */
    public boolean setRole(String callerUserId, String targetUserId, String newRole) {
        var caller = findUser(callerUserId);
        if (caller.isEmpty() || !"steward".equals(caller.get().role())) {
            return false;
        }
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("UPDATE users SET role = ? WHERE id = ?")) {
                stmt.setString(1, newRole);
                stmt.setString(2, targetUserId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Role changed: user {} → {}", targetUserId, newRole);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Set role failed", e);
        }
    }

    /**
     * List all registered users.
     */
    public List<User> listUsers() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, username, display_name, role, created_at FROM users ORDER BY created_at ASC";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var users = new ArrayList<User>();
                while (rs.next()) {
                    var role = rs.getString("role");
                    users.add(new User(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        role != null ? role : "member",
                        Instant.ofEpochSecond(rs.getLong("created_at"))
                    ));
                }
                return users;
            }
        } catch (SQLException e) {
            throw new RuntimeException("List users failed", e);
        }
    }

    /**
     * Update a user's description.
     * @return true if the user was found and updated
     */
    public boolean updateDescription(String userId, String description) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("UPDATE users SET description = ? WHERE id = ?")) {
                stmt.setString(1, description);
                stmt.setString(2, userId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Description updated for user {}", userId);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Update description failed", e);
        }
    }

    /**
     * Rename: change the display_name of a user. Username stays — only the
     * shown name changes. Mirrors {@link #updateDescription}. The new name is
     * used for room rendering, who-lists, tell-from labels, narration, etc.
     * Returns true iff the user existed and the row was updated.
     *
     * <p>. Caller is responsible for permission
     * checks (self / steward / bondholder) and for blank validation.</p>
     */
    public boolean updateDisplayName(String userId, String newDisplayName) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("UPDATE users SET display_name = ? WHERE id = ?")) {
                stmt.setString(1, newDisplayName);
                stmt.setString(2, userId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Display name updated for user {} -> '{}'", userId, newDisplayName);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Update display name failed", e);
        }
    }

    // ── Recovery Key ──

    /** Household config key for recovery key hash. */
    public static final String CONFIG_RECOVERY_KEY_HASH = "recovery_key_hash";

    /**
     * Generate a recovery key during steward setup.
     * Returns the plaintext key (show to user ONCE). Stores only the bcrypt hash.
     */
    public String generateRecoveryKey() {
        // 24-word key from InviteService word list (reuse the passphrase generator)
        var words = new String[8]; // 8 words = plenty of entropy for recovery
        var rng = new SecureRandom();
        var wordList = InviteService.getWordList();
        for (int i = 0; i < 8; i++) {
            words[i] = wordList[rng.nextInt(wordList.length)];
        }
        var key = String.join("-", words);
        var hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, key.toCharArray());
        setConfig(CONFIG_RECOVERY_KEY_HASH, hash, "system");
        log.info("Recovery key generated and hash stored");
        return key;
    }

    /**
     * Verify a recovery key against the stored hash.
     */
    public boolean verifyRecoveryKey(String key) {
        var hash = getConfig(CONFIG_RECOVERY_KEY_HASH);
        if (hash == null) return false;
        return BCrypt.verifyer().verify(key.toCharArray(), hash).verified;
    }

    /**
     * Reset the steward's password using a recovery key.
     * @return true if reset was successful
     */
    public boolean recoverSteward(String recoveryKey, String newPassword) {
        if (!verifyRecoveryKey(recoveryKey)) return false;
        var steward = findSteward();
        if (steward.isEmpty()) return false;

        var hash = BCrypt.withDefaults().hashToString(BCRYPT_COST, newPassword.toCharArray());
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ?")) {
                stmt.setString(1, hash);
                stmt.setString(2, steward.get().id());
                stmt.executeUpdate();
            }
            log.info("Steward password reset via recovery key");
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Recovery failed", e);
        }
    }

    /**
     * Factory reset — wipe all users, sessions, invites, config.
     * Requires recovery key.
     * @return true if reset was successful
     */
    public boolean factoryReset(String recoveryKey) {
        if (!verifyRecoveryKey(recoveryKey)) return false;
        try (var conn = getConnection()) {
            conn.createStatement().executeUpdate("DELETE FROM sessions");
            conn.createStatement().executeUpdate("DELETE FROM invites");
            conn.createStatement().executeUpdate("DELETE FROM household_config");
            conn.createStatement().executeUpdate("DELETE FROM users");
            log.info("========================================");
            log.info("  FACTORY RESET COMPLETE");
            log.info("  All accounts, sessions, and config wiped.");
            log.info("  Restart the server to begin fresh setup.");
            log.info("========================================");
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Factory reset failed", e);
        }
    }

    // ── Open Registration & Household Config ──

    /**
     * Whether anonymous account creation is allowed right now.
     *
     * <p>: this is now true if and only if no users
     * exist yet (first-steward bootstrap window). Once any user exists, all
     * subsequent accounts must come through {@code wyrd invite}. The previously
     * supported "steward toggles open_registration" mode is removed because
     * once zones become public/federated, every door multiplies the attack
     * surface across the federation. New users join via invite; bootstrap
     * happens in the irreducible first-user window.</p>
     *
     * <p>The {@code CONFIG_OPEN_REGISTRATION} key is no longer consulted.</p>
     */
    public boolean isOpenRegistrationAllowed() {
        return isFirstUser();
    }

    /**
     * Get a household config value.
     */
    public String getConfig(String key) {
        try (var conn = getConnection()) {
            var sql = "SELECT value FROM household_config WHERE key = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getString("value") : null;
            }
        } catch (SQLException e) {
            log.debug("Config lookup failed for '{}': {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Set a household config value.
     */
    public void setConfig(String key, String value, String updatedBy) {
        try (var conn = getConnection()) {
            // Delete then insert (works for both SQLite and PostgreSQL)
            try (var del = conn.prepareStatement("DELETE FROM household_config WHERE key = ?")) {
                del.setString(1, key);
                del.executeUpdate();
            }
            var sql = "INSERT INTO household_config (key, value, updated_at, updated_by) VALUES (?, ?, "
                + dialect.currentEpoch() + ", ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, key);
                stmt.setString(2, value);
                stmt.setString(3, updatedBy);
                stmt.executeUpdate();
            }
            log.info("Config '{}' set to '{}' by {}", key, value, updatedBy);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set config " + key, e);
        }
    }

    /**
     * Find the steward user. Returns the first user with role=steward.
     */
    public Optional<User> findSteward() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, username, display_name, role, description, created_at"
                + " FROM users WHERE role = 'steward' ORDER BY created_at ASC LIMIT 1";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();
                return Optional.of(new User(
                    rs.getString("id"), rs.getString("username"),
                    rs.getString("display_name"), rs.getString("role"),
                    rs.getString("description"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Steward lookup failed", e);
        }
    }

    /**
     * Migrate existing installation: promote first user to steward if no steward exists,
     * disable open registration. Called on server startup.
     * @return true if migration was performed
     */
    public boolean migrateToHouseholdSecurity() {
        if (isFirstUser()) return false; // no users yet, nothing to migrate

        var steward = findSteward();
        if (steward.isPresent()) {
            // Steward already exists; nothing to migrate. The
            // open_registration config key is no longer consulted (F4).
            return false;
        }

        // No steward — promote first user
        try (var conn = getConnection()) {
            var sql = "SELECT id, username FROM users ORDER BY created_at ASC LIMIT 1";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                if (!rs.next()) return false;

                var firstId = rs.getString("id");
                var firstName = rs.getString("username");

                try (var updateStmt = conn.prepareStatement(
                        "UPDATE users SET role = 'steward' WHERE id = ?")) {
                    updateStmt.setString(1, firstId);
                    updateStmt.executeUpdate();
                }

                log.info("========================================");
                log.info("  MIGRATION: {} promoted to steward", firstName);
                log.info("  Open registration is now closed (no other users).");
                log.info("  Use 'wyrd invite' to add new members.");
                log.info("========================================");
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Migration failed", e);
        }
    }

    /**
     * Remove a user account. Steward-only operation.
     * @return true if user was removed
     */
    public boolean removeUser(String callerUserId, String targetUserId) {
        var caller = findUser(callerUserId);
        if (caller.isEmpty() || !"steward".equals(caller.get().role())) return false;
        if (callerUserId.equals(targetUserId)) return false; // can't remove yourself

        try (var conn = getConnection()) {
            // Delete sessions first (cascade should handle, but be explicit)
            try (var stmt = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                stmt.setString(1, targetUserId);
                stmt.executeUpdate();
            }
            try (var stmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                stmt.setString(1, targetUserId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("User {} removed by steward {}", targetUserId, callerUserId);
                }
                return rows > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Remove user failed", e);
        }
    }

    /**
     * Register a user with a pre-computed password hash (for replication from another node).
     * Does NOT create a session — the user will login on their own.
     */
    public void registerWithHash(String userId, String username, String passwordHash,
                                  String displayName, String role) {
        try (var conn = getConnection()) {
            var sql = "INSERT INTO users (id, username, password_hash, display_name, role) VALUES (?, ?, ?, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, userId);
                stmt.setString(2, username);
                stmt.setString(3, passwordHash);
                stmt.setString(4, displayName != null ? displayName : username);
                stmt.setString(5, role != null ? role : "member");
                stmt.executeUpdate();
            }
            log.info("Replicated user: {} ({}) role={}", username, userId, role);
            // Replication is within-zone (household node sync) — the account
            // is a resident here too. Residency is per-node state, so the
            // replica node must grant its own row.
            ResidencyStore.grantLocal(userId, role != null ? role : "member", "account-replication");
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                log.debug("User {} already exists locally, skipping replication", username);
                return;
            }
            throw new RuntimeException("Replication registration failed", e);
        }
    }

    /**
     * List all users with password hashes (for node-to-node sync).
     * Returns a map of userId → {username, passwordHash, displayName, role}.
     */
    public List<Map<String, String>> listUsersForSync() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, username, password_hash, display_name, role FROM users ORDER BY created_at ASC";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var result = new ArrayList<Map<String, String>>();
                while (rs.next()) {
                    result.add(Map.of(
                        "id", rs.getString("id"),
                        "username", rs.getString("username"),
                        "passwordHash", rs.getString("password_hash") != null ? rs.getString("password_hash") : "",
                        "displayName", rs.getString("display_name") != null ? rs.getString("display_name") : "",
                        "role", rs.getString("role") != null ? rs.getString("role") : "member"
                    ));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException("List users for sync failed", e);
        }
    }

    /**
     * Get all household config values (for node-to-node sync).
     */
    public Map<String, String> getAllConfig() {
        try (var conn = getConnection()) {
            var sql = "SELECT key, value FROM household_config";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var result = new HashMap<String, String>();
                while (rs.next()) {
                    result.put(rs.getString("key"), rs.getString("value"));
                }
                return result;
            }
        } catch (SQLException e) {
            log.debug("Config listing failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Get password hash for a user (needed for replication to other nodes).
     */
    public String getPasswordHash(String userId) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
                stmt.setString(1, userId);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getString("password_hash") : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Remove a user directly (no caller check — used by replication).
     */
    public void removeUserDirect(String userId) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
                stmt.setString(1, userId);
                stmt.executeUpdate();
            }
            try (var stmt = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
                stmt.setString(1, userId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Failed to remove replicated user {}: {}", userId, e.getMessage());
        }
    }

    private Session createSession(Connection conn, String userId) throws SQLException {
        var token = UUID.randomUUID().toString();
        var now = Instant.now();
        var expires = now.plusSeconds(SESSION_DURATION_SECONDS);

        var sql = "INSERT INTO sessions (token, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, token);
            stmt.setString(2, userId);
            stmt.setLong(3, now.getEpochSecond());
            stmt.setLong(4, expires.getEpochSecond());
            stmt.executeUpdate();
        }

        return new Session(token, userId, now, expires);
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    // ── Per-account SSH public keys ( / SSH security) ──────
    // SECURITY: SSH pubkey auth must bind a key to the ONE account that owns it,
    // resolved LIVE on every connection. The old model (a global flat
    // authorized_keys accept-list, matched key-only + loaded once at sshd start)
    // let ANY registered key log in as ANY username (impersonate the steward),
    // and required a restart to pick up a new key. These methods store keys per
    // user_id and resolve owner→key at auth time. The key is an opaque OpenSSH
    // line ("<type> <base64>"); the server (SshAdapter) owns encoding/parsing so
    // core keeps no ssh dependency.

    /** Lazily create the per-account SSH-key table (idempotent; also in the schema files). */
    private void ensureSshKeyTable(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            // #17 (2026-07-19 OSS hardening) — the PK is (user_id, key_line), NOT
            // key_line alone. With key_line as a GLOBAL primary key, an attacker
            // who knew a victim's (public) SSH key could `key add` it to their own
            // account first; the victim's later insert-ignore then silently no-op'd
            // and the victim could never register their own key (a squat DoS).
            // Possession of the private key is proven by the SSH handshake itself,
            // and auth resolves the owner by matching the connecting username
            // (findUsersBySshKey), so a duplicate key_line row is harmless — only
            // the account that can actually present the key authenticates.
            // (New installs get this schema; existing installs keep the old PK,
            // where a key_line is still unique so behaviour is unchanged.)
            st.execute("CREATE TABLE IF NOT EXISTS user_ssh_keys("
                + "key_line TEXT NOT NULL, "
                + "user_id TEXT NOT NULL, "
                + "comment TEXT DEFAULT '', "
                + "added_at " + (dialect instanceof SqlDialect.PostgreSQL ? "BIGINT" : "INTEGER") + " NOT NULL, "
                + "PRIMARY KEY (user_id, key_line))");
        }
    }

    /**
     * Bind an SSH public key to an account so future logins are keyless AND scoped
     * to that account. {@code keyLine} is the canonical "&lt;type&gt; &lt;base64&gt;"
     * (no comment). Idempotent — re-adding the same key is a no-op. Returns true if
     * a new binding was written.
     */
    public boolean addSshKey(String userId, String keyLine, String comment) {
        if (userId == null || keyLine == null || keyLine.isBlank()) return false;
        try (var conn = getConnection()) {
            ensureSshKeyTable(conn);
            var sql = dialect.insertIgnore("user_ssh_keys",
                "key_line, user_id, comment, added_at", "?,?,?,?");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, keyLine.trim());
                stmt.setString(2, userId);
                stmt.setString(3, comment != null ? comment : "");
                stmt.setLong(4, Instant.now().getEpochSecond());
                int n = stmt.executeUpdate();
                if (n > 0) log.info("SSH key bound to user {} ({})", userId, comment);
                return n > 0;
            }
        } catch (SQLException e) {
            log.warn("addSshKey failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /** One bound SSH key: its canonical line, comment/label, and when it was added. */
    public record SshKeyRef(String keyLine, String comment, Instant addedAt) {}

    /** List the SSH keys bound to an account (newest first). */
    public List<SshKeyRef> listSshKeys(String userId) {
        var out = new ArrayList<SshKeyRef>();
        if (userId == null) return out;
        try (var conn = getConnection()) {
            ensureSshKeyTable(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT key_line, comment, added_at FROM user_ssh_keys "
                        + "WHERE user_id = ? ORDER BY added_at DESC")) {
                stmt.setString(1, userId);
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    out.add(new SshKeyRef(rs.getString("key_line"), rs.getString("comment"),
                        Instant.ofEpochSecond(rs.getLong("added_at"))));
                }
            }
        } catch (SQLException e) {
            log.warn("listSshKeys failed for user {}: {}", userId, e.getMessage());
        }
        return out;
    }

    /**
     * Remove an SSH key binding — SCOPED to the owning user so a caller can only
     * drop their own keys (the user_id predicate prevents removing another
     * account's key). Returns true if a row was deleted.
     */
    public boolean removeSshKey(String userId, String keyLine) {
        if (userId == null || keyLine == null || keyLine.isBlank()) return false;
        try (var conn = getConnection()) {
            ensureSshKeyTable(conn);
            try (var stmt = conn.prepareStatement(
                    "DELETE FROM user_ssh_keys WHERE user_id = ? AND key_line = ?")) {
                stmt.setString(1, userId);
                stmt.setString(2, keyLine.trim());
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("removeSshKey failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * Resolve an offered SSH key to its owning account, LIVE (queried per auth
     * attempt — a newly-added key works with no restart). Empty if the key is
     * bound to no account (→ auth must be refused, or fall to first-user
     * bootstrap).
     */
    public Optional<User> findUserBySshKey(String keyLine) {
        var owners = findUsersBySshKey(keyLine);
        return owners.isEmpty() ? Optional.empty() : Optional.of(owners.get(0));
    }

    /**
     * #17 (2026-07-19 OSS hardening) — resolve ALL accounts that have bound this
     * key line. Since the PK is now (user_id, key_line), more than one account
     * can carry the same key; the caller (SshAdapter) picks the one whose
     * connecting username matches. Possession is proven by the SSH handshake, so
     * a squatted duplicate row is inert — only the account that presents the key
     * and matches its username authenticates.
     */
    public List<User> findUsersBySshKey(String keyLine) {
        var out = new ArrayList<User>();
        if (keyLine == null || keyLine.isBlank()) return out;
        try (var conn = getConnection()) {
            ensureSshKeyTable(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT user_id FROM user_ssh_keys WHERE key_line = ?")) {
                stmt.setString(1, keyLine.trim());
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    findUser(rs.getString("user_id")).ifPresent(out::add);
                }
            }
        } catch (SQLException e) {
            log.warn("findUsersBySshKey failed: {}", e.getMessage());
        }
        return out;
    }
}
