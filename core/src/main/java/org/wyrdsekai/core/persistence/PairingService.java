package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Manages device pairing challenges and paired devices.
 * <p>
 * Flow: phone calls /api/pair/request → server creates 6-digit code
 * → steward reads code from log/REST → enters on phone → phone calls /api/pair/verify
 * → server returns device token + household credentials.
 * <p>
 * Uses the same JDBC pattern as {@link AuthService}.
 */
public final class PairingService {

    private static final Logger log = LoggerFactory.getLogger(PairingService.class);
    private static final int CODE_EXPIRY_SECONDS = 300; // 5 minutes
    private static final int MAX_ATTEMPTS = 3;

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final String householdId;
    private final String householdName;
    private final String serverDid;
    private final String natsUrl;
    private final String serverUrl;
    private volatile String relayUrl;
    private volatile String relayToken;
    private final SecureRandom random = new SecureRandom();

    // Records
    public record PairingChallenge(String challengeId, String code, Instant expiresAt) {}
    public record PairingResult(String token, String householdId, String householdName,
                                String serverDid, String natsUrl, String serverUrl,
                                String relayUrl, String relayToken) {
        /** Backward-compatible constructor without relay fields. */
        public PairingResult(String token, String householdId, String householdName,
                             String serverDid, String natsUrl, String serverUrl) {
            this(token, householdId, householdName, serverDid, natsUrl, serverUrl, null, null);
        }
    }
    public record PairedDevice(String id, String name, String type, String publicKey,
                               String userId, Instant pairedAt, Instant lastSeen, boolean revoked) {}
    public record HouseholdKey(String key, Instant createdAt) {}

    public PairingService(String jdbcUrl, SqlDialect dialect,
                          String householdId, String householdName,
                          String serverDid, String natsUrl, String serverUrl) {
        this(jdbcUrl, dialect, householdId, householdName, serverDid, natsUrl, serverUrl, null, null);
    }

    public PairingService(String jdbcUrl, SqlDialect dialect,
                          String householdId, String householdName,
                          String serverDid, String natsUrl, String serverUrl,
                          String relayUrl, String relayToken) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
        this.householdId = householdId;
        this.householdName = householdName;
        this.serverDid = serverDid;
        this.natsUrl = natsUrl;
        this.serverUrl = serverUrl;
        this.relayUrl = relayUrl;
        this.relayToken = relayToken;
    }

    /** Update relay configuration at runtime (e.g., after relay setup). */
    public void setRelayConfig(String relayUrl, String relayToken) {
        this.relayUrl = relayUrl;
        this.relayToken = relayToken;
    }

    // ─── Singleton accessor ──────────────────────────────────────────
    // Same pattern as WebSearchService / OraclePredictionCache: Main wires the
    // instance once at boot, anywhere else that needs read-only access (e.g.
    // ItemWorldApiProviderImpl for the Threshold furnishing) can call get().
    private static volatile PairingService INSTANCE;

    /** Register the singleton. Called once at boot from Main / TestServerBootstrap. */
    public static void register(PairingService service) {
        INSTANCE = service;
    }

    /** Returns the registered instance, or {@code null} if pairing isn't wired in this entry point. */
    public static PairingService get() {
        return INSTANCE;
    }

    /**
     * Initialize database tables. Call during server bootstrap.
     * Uses CREATE TABLE IF NOT EXISTS so it is idempotent.
     */
    public void initSchema() {
        try (var conn = getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pairing_challenges(
                      id              TEXT PRIMARY KEY,
                      code            TEXT NOT NULL,
                      device_name     TEXT NOT NULL DEFAULT '',
                      device_type     TEXT NOT NULL DEFAULT '',
                      device_public_key TEXT NOT NULL DEFAULT '',
                      state           TEXT NOT NULL DEFAULT 'pending',
                      attempts        INTEGER NOT NULL DEFAULT 0,
                      created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
                      expires_at      INTEGER NOT NULL
                    )""");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS paired_devices(
                      id              TEXT PRIMARY KEY,
                      name            TEXT NOT NULL DEFAULT '',
                      type            TEXT NOT NULL DEFAULT '',
                      public_key      TEXT NOT NULL DEFAULT '',
                      token           TEXT NOT NULL UNIQUE,
                      user_id         TEXT,
                      paired_at       INTEGER NOT NULL DEFAULT (unixepoch()),
                      last_seen       INTEGER NOT NULL DEFAULT (unixepoch()),
                      revoked         INTEGER NOT NULL DEFAULT 0
                    )""");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_paired_devices_token ON paired_devices(token)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_paired_devices_user ON paired_devices(user_id)");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS household_keys(
                      key             TEXT PRIMARY KEY,
                      created_at      INTEGER NOT NULL DEFAULT (unixepoch()),
                      revoked         INTEGER NOT NULL DEFAULT 0
                    )""");
            }
            log.debug("Pairing schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize pairing schema", e);
        }
    }

    /**
     * Create a new pairing challenge. Returns the challenge with a 6-digit code.
     * Any existing pending challenges are expired first.
     */
    public PairingChallenge createChallenge(String deviceName, String deviceType, String devicePublicKey) {
        var id = UUID.randomUUID().toString();
        var code = String.format("%06d", random.nextInt(1_000_000));
        var now = Instant.now();
        var expiresAt = now.plusSeconds(CODE_EXPIRY_SECONDS);

        try (var conn = getConnection()) {
            // Expire any existing pending challenges
            try (var stmt = conn.prepareStatement(
                    "UPDATE pairing_challenges SET state = 'expired' WHERE state = 'pending'")) {
                stmt.executeUpdate();
            }

            // Insert the new challenge
            var sql = "INSERT INTO pairing_challenges (id, code, device_name, device_type, device_public_key, state, attempts, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?, 'pending', 0, ?, ?)";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, id);
                stmt.setString(2, code);
                stmt.setString(3, deviceName != null ? deviceName : "");
                stmt.setString(4, deviceType != null ? deviceType : "");
                stmt.setString(5, devicePublicKey != null ? devicePublicKey : "");
                stmt.setLong(6, now.getEpochSecond());
                stmt.setLong(7, expiresAt.getEpochSecond());
                stmt.executeUpdate();
            }

            log.info("\n\n  PAIRING CODE: {} (expires in 5 minutes)\n", code);
            return new PairingChallenge(id, code, expiresAt);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create pairing challenge", e);
        }
    }

    /**
     * Verify a pairing code. Returns credentials if correct, empty if wrong/expired/max attempts.
     */
    public Optional<PairingResult> verifyCode(String challengeId, String code) {
        try (var conn = getConnection()) {
            // Load the challenge
            var sql = "SELECT code, state, attempts, expires_at, device_name, device_type, device_public_key"
                + " FROM pairing_challenges WHERE id = ?";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, challengeId);
                var rs = stmt.executeQuery();
                if (!rs.next()) {
                    log.debug("Pairing challenge not found: {}", challengeId);
                    return Optional.empty();
                }

                var storedCode = rs.getString("code");
                var state = rs.getString("state");
                var attempts = rs.getInt("attempts");
                var expiresAt = rs.getLong("expires_at");
                var deviceName = rs.getString("device_name");
                var deviceType = rs.getString("device_type");
                var devicePublicKey = rs.getString("device_public_key");

                // Check state
                if (!"pending".equals(state)) {
                    log.debug("Pairing challenge {} is {}, not pending", challengeId, state);
                    return Optional.empty();
                }

                // Check expiry
                if (Instant.now().getEpochSecond() > expiresAt) {
                    try (var upd = conn.prepareStatement(
                            "UPDATE pairing_challenges SET state = 'expired' WHERE id = ?")) {
                        upd.setString(1, challengeId);
                        upd.executeUpdate();
                    }
                    log.debug("Pairing challenge {} expired", challengeId);
                    return Optional.empty();
                }

                // Check attempts
                if (attempts >= MAX_ATTEMPTS) {
                    log.debug("Pairing challenge {} locked (max attempts)", challengeId);
                    return Optional.empty();
                }

                // Check code
                if (!storedCode.equals(code)) {
                    var newAttempts = attempts + 1;
                    var newState = newAttempts >= MAX_ATTEMPTS ? "locked" : "pending";
                    try (var upd = conn.prepareStatement(
                            "UPDATE pairing_challenges SET attempts = ?, state = ? WHERE id = ?")) {
                        upd.setInt(1, newAttempts);
                        upd.setString(2, newState);
                        upd.setString(3, challengeId);
                        upd.executeUpdate();
                    }
                    log.debug("Pairing code mismatch for {} (attempt {}/{})",
                        challengeId, newAttempts, MAX_ATTEMPTS);
                    return Optional.empty();
                }

                // Code matches — generate device token and register
                var deviceId = UUID.randomUUID().toString();
                var token = "wyrd_dev_" + generateHexToken(64);
                var now = Instant.now();

                // Insert paired device
                var insertSql = "INSERT INTO paired_devices (id, name, type, public_key, token, paired_at, last_seen, revoked)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
                try (var ins = conn.prepareStatement(insertSql)) {
                    ins.setString(1, deviceId);
                    ins.setString(2, deviceName);
                    ins.setString(3, deviceType);
                    ins.setString(4, devicePublicKey);
                    ins.setString(5, token);
                    ins.setLong(6, now.getEpochSecond());
                    ins.setLong(7, now.getEpochSecond());
                    ins.executeUpdate();
                }

                // Mark challenge completed
                try (var upd = conn.prepareStatement(
                        "UPDATE pairing_challenges SET state = 'completed' WHERE id = ?")) {
                    upd.setString(1, challengeId);
                    upd.executeUpdate();
                }

                log.info("Device paired: {} ({}) — token issued", deviceName, deviceType);
                return Optional.of(new PairingResult(
                    token, householdId, householdName, serverDid, natsUrl, serverUrl,
                    relayUrl, relayToken));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to verify pairing code", e);
        }
    }

    /**
     * Validate a device token. Returns device info if valid and not revoked.
     */
    public Optional<PairedDevice> validateDeviceToken(String token) {
        try (var conn = getConnection()) {
            var sql = "SELECT id, name, type, public_key, user_id, paired_at, last_seen, revoked"
                + " FROM paired_devices WHERE token = ? AND revoked = 0";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, token);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                return Optional.of(new PairedDevice(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getString("type"),
                    rs.getString("public_key"),
                    rs.getString("user_id"),
                    Instant.ofEpochSecond(rs.getLong("paired_at")),
                    Instant.ofEpochSecond(rs.getLong("last_seen")),
                    rs.getInt("revoked") != 0
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Device token validation failed", e);
        }
    }

    /**
     * List all paired devices.
     */
    public List<PairedDevice> listDevices() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, name, type, public_key, user_id, paired_at, last_seen, revoked"
                + " FROM paired_devices ORDER BY paired_at DESC";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var devices = new ArrayList<PairedDevice>();
                while (rs.next()) {
                    devices.add(new PairedDevice(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("type"),
                        rs.getString("public_key"),
                        rs.getString("user_id"),
                        Instant.ofEpochSecond(rs.getLong("paired_at")),
                        Instant.ofEpochSecond(rs.getLong("last_seen")),
                        rs.getInt("revoked") != 0
                    ));
                }
                return devices;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list paired devices", e);
        }
    }

    /**
     * Revoke a device by ID.
     */
    public void revokeDevice(String deviceId) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE paired_devices SET revoked = 1 WHERE id = ?")) {
                stmt.setString(1, deviceId);
                var rows = stmt.executeUpdate();
                if (rows > 0) {
                    log.info("Device revoked: {}", deviceId);
                } else {
                    log.debug("Device not found for revocation: {}", deviceId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke device", e);
        }
    }

    /**
     * Update last-seen timestamp for a device.
     */
    public void touchDevice(String token) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE paired_devices SET last_seen = ? WHERE token = ?")) {
                stmt.setLong(1, Instant.now().getEpochSecond());
                stmt.setString(2, token);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to touch device", e);
        }
    }

    /**
     * Get the current pending challenge (for displaying code to steward).
     */
    public Optional<PairingChallenge> getPendingChallenge() {
        try (var conn = getConnection()) {
            var sql = "SELECT id, code, expires_at FROM pairing_challenges"
                + " WHERE state = 'pending' AND expires_at > ?"
                + " ORDER BY created_at DESC LIMIT 1";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, Instant.now().getEpochSecond());
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();

                return Optional.of(new PairingChallenge(
                    rs.getString("id"),
                    rs.getString("code"),
                    Instant.ofEpochSecond(rs.getLong("expires_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get pending challenge", e);
        }
    }

    /**
     * Detailed pending pairing record — used by the Threshold furnishing in
     * Study so the steward can see who is knocking and decide without
     * leaving the world.
     */
    public record PendingPairing(
            String challengeId, String code,
            String deviceName, String deviceType,
            Instant createdAt, Instant expiresAt) {}

    /**
     * Every pending pairing challenge that hasn't expired, newest-first.
     * In practice {@link #createChallenge} expires older pendings, so this
     * list is 0–1 entries — but the caller should treat it as a list.
     */
    public List<PendingPairing> listPendingChallenges() {
        var out = new ArrayList<PendingPairing>();
        try (var conn = getConnection()) {
            var sql = "SELECT id, code, device_name, device_type, created_at, expires_at"
                + " FROM pairing_challenges WHERE state = 'pending' AND expires_at > ?"
                + " ORDER BY created_at DESC";
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, Instant.now().getEpochSecond());
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    out.add(new PendingPairing(
                        rs.getString("id"),
                        rs.getString("code"),
                        rs.getString("device_name"),
                        rs.getString("device_type"),
                        Instant.ofEpochSecond(rs.getLong("created_at")),
                        Instant.ofEpochSecond(rs.getLong("expires_at"))));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to list pending challenges: {}", e.getMessage());
        }
        return out;
    }

    // ── Household Key ─────────────────────────────────────────────────

    /**
     * Generate a new household key. This is a long-lived pre-shared key
     * that allows devices to pair without the interactive 6-digit code.
     * <p>
     * Use case: headless machines, scripted deployments, or config files.
     * <pre>wyrdsekai join --key wyrd_hk_a8f3e2...</pre>
     * <p>
     * Any existing active keys are NOT revoked — multiple keys can be active.
     * Revoke explicitly via {@link #revokeHouseholdKey(String)}.
     */
    public String generateHouseholdKey() {
        var key = "wyrd_hk_" + generateHexToken(64);
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO household_keys (key, created_at, revoked) VALUES (?, ?, 0)")) {
                stmt.setString(1, key);
                stmt.setLong(2, Instant.now().getEpochSecond());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to generate household key", e);
        }
        log.info("Household key generated: {}...{}", key.substring(0, 16), key.substring(key.length() - 4));
        return key;
    }

    /**
     * Pair a device using a household key (no code required).
     * Validates the key, then registers the device and returns credentials.
     *
     * @return PairingResult if key is valid and not revoked, empty otherwise
     */
    public Optional<PairingResult> pairWithKey(String householdKey, String deviceName,
                                                String deviceType, String devicePublicKey) {
        // Validate key — same predicate the household-join enrollment path reuses.
        if (!validateHouseholdKey(householdKey)) {
            log.warn("Invalid or revoked household key presented by device: {}", deviceName);
            return Optional.empty();
        }
        try (var conn = getConnection()) {
            // Key valid — register device (same as code-based pairing)
            var deviceId = UUID.randomUUID().toString();
            var token = "wyrd_dev_" + generateHexToken(64);
            var now = Instant.now();

            var sql = "INSERT INTO paired_devices (id, name, type, public_key, token, paired_at, last_seen, revoked)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
            try (var ins = conn.prepareStatement(sql)) {
                ins.setString(1, deviceId);
                ins.setString(2, deviceName != null ? deviceName : "");
                ins.setString(3, deviceType != null ? deviceType : "");
                ins.setString(4, devicePublicKey != null ? devicePublicKey : "");
                ins.setString(5, token);
                ins.setLong(6, now.getEpochSecond());
                ins.setLong(7, now.getEpochSecond());
                ins.executeUpdate();
            }

            log.info("Device paired via household key: {} ({})", deviceName, deviceType);
            return Optional.of(new PairingResult(
                token, householdId, householdName, serverDid, natsUrl, serverUrl,
                relayUrl, relayToken));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to pair with household key", e);
        }
    }

    /**
     * Direct mint for an AUTHENTICATED account's own device — the hermod
     * consent path: flipping "lend compute" mints the device identity, no
     * ceremony, because the logged-in session IS the proof of belonging.
     * Idempotent per (user, device name): re-consent returns the existing
     * unrevoked device's token instead of breeding registry rows, so the
     * capability table keeps ONE stable id per physical device.
     */
    public PairingResult pairForUser(String userId, String deviceName, String deviceType) {
        var name = deviceName != null ? deviceName : "Unknown Device";
        var type = deviceType != null ? deviceType : "phone";
        try (var conn = getConnection()) {
            var find = "SELECT token FROM paired_devices"
                + " WHERE user_id = ? AND name = ? AND revoked = 0 LIMIT 1";
            try (var sel = conn.prepareStatement(find)) {
                sel.setString(1, userId);
                sel.setString(2, name);
                try (var rs = sel.executeQuery()) {
                    if (rs.next()) {
                        return new PairingResult(rs.getString("token"),
                            householdId, householdName, serverDid, natsUrl, serverUrl,
                            relayUrl, relayToken);
                    }
                }
            }
            var deviceId = UUID.randomUUID().toString();
            var token = "wyrd_dev_" + generateHexToken(64);
            var now = Instant.now();
            var sql = "INSERT INTO paired_devices"
                + " (id, name, type, public_key, token, user_id, paired_at, last_seen, revoked)"
                + " VALUES (?, ?, ?, '', ?, ?, ?, ?, 0)";
            try (var ins = conn.prepareStatement(sql)) {
                ins.setString(1, deviceId);
                ins.setString(2, name);
                ins.setString(3, type);
                ins.setString(4, token);
                ins.setString(5, userId);
                ins.setLong(6, now.getEpochSecond());
                ins.setLong(7, now.getEpochSecond());
                ins.executeUpdate();
            }
            log.info("Device identity minted for account {}: {} ({})", userId, name, type);
            return new PairingResult(token, householdId, householdName,
                serverDid, natsUrl, serverUrl, relayUrl, relayToken);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to mint device for user", e);
        }
    }

    /**
     * Validate a presented household key against the active (non-revoked) keys.
     * This is the EXACT predicate {@link #pairWithKey} uses to admit a device;
     * the household-join enrollment path ({@code POST /api/household/join})
     * reuses it so a peer node can only be enrolled with a real, unrevoked key.
     *
     * @return true if the key matches an active, non-revoked household key
     */
    public boolean validateHouseholdKey(String householdKey) {
        if (householdKey == null || householdKey.isBlank()) return false;
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT key FROM household_keys WHERE key = ? AND revoked = 0")) {
            stmt.setString(1, householdKey);
            try (var rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to validate household key", e);
        }
    }

    /**
     * Get the current active household key (most recent, non-revoked).
     * Returns empty if no key has been generated yet.
     */
    public Optional<HouseholdKey> getActiveHouseholdKey() {
        try (var conn = getConnection()) {
            var sql = "SELECT key, created_at FROM household_keys WHERE revoked = 0"
                + " ORDER BY created_at DESC LIMIT 1";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();
                return Optional.of(new HouseholdKey(
                    rs.getString("key"),
                    Instant.ofEpochSecond(rs.getLong("created_at"))
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get household key", e);
        }
    }

    /** Revoke a household key. Existing paired devices are NOT affected. */
    public void revokeHouseholdKey(String key) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE household_keys SET revoked = 1 WHERE key = ?")) {
                stmt.setString(1, key);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke household key", e);
        }
        log.info("Household key revoked");
    }

    /**
     * Link a paired device to a user account.
     * @return true if the device was found and linked
     */
    public boolean linkDeviceToUser(String deviceToken, String userId) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                    "UPDATE paired_devices SET user_id = ? WHERE token = ? AND revoked = 0")) {
                stmt.setString(1, userId);
                stmt.setString(2, deviceToken);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("Failed to link device to user: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Unlink a device from its user account (e.g., on logout).
     * @return true if the device was found and unlinked
     */
    public boolean unlinkDevice(String deviceToken) {
        return linkDeviceToUser(deviceToken, null);
    }

    /**
     * Find the user ID linked to a device token.
     * @return the user ID if the device is linked, empty otherwise
     */
    public Optional<String> findUserForDevice(String deviceToken) {
        var device = validateDeviceToken(deviceToken);
        return device.flatMap(d -> Optional.ofNullable(d.userId()));
    }

    private String generateHexToken(int hexChars) {
        var bytes = new byte[hexChars / 2];
        random.nextBytes(bytes);
        var sb = new StringBuilder(hexChars);
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
