package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed persistence for {@link PlayerAccount} records.
 *
 * <p>Uses the same JDBC pattern as {@link org.wyrdsekai.core.persistence.AuthService}
 * and other core persistence classes. Schema tables: player_accounts, device_logins.</p>
 *
 * <p>Thread-safe — each operation opens its own connection. For SQLite WAL mode
 * this is fine for household-scale traffic (1-20 users).</p>
 */
public class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public AccountStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        initSchema();
    }

    // --- Schema ---

    private void initSchema() {
        try (var conn = getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_accounts(
                      did                            TEXT PRIMARY KEY,
                      display_name                   TEXT NOT NULL,
                      created_at                     INTEGER NOT NULL DEFAULT (unixepoch()),
                      last_seen                      INTEGER NOT NULL DEFAULT (unixepoch()),
                      primary_node_id                TEXT,
                      preferred_language             TEXT,
                      cultural_register_preference   TEXT
                    )
                    """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS device_logins(
                      device_id  TEXT PRIMARY KEY,
                      account_did TEXT NOT NULL REFERENCES player_accounts(did) ON DELETE CASCADE
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_device_logins_did ON device_logins(account_did)");
                // the user's zone bank as sovereign
                // account state on their home zone, synced across their own
                // devices. Stored as an opaque JSON blob keyed by the caller's
                // stable account identifier (the same id the session token
                // resolves to — UUID user-id OR DID, whichever the wiring uses;
                // NO foreign key, so it is correct regardless of which
                // identifier space the home zone keys accounts by). Per-entry
                // last-write-wins merge happens client-side.
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS account_zonebank(
                      account_id TEXT PRIMARY KEY,
                      bank_json  TEXT NOT NULL,
                      updated_at INTEGER NOT NULL DEFAULT (unixepoch())
                    )
                    """);
                // the steward's inbox of access
                // requests ("knocks") from people who discovered this zone in the
                // directory and want in. Zone-local; the requester need not have an
                // account here yet (that's the point), so no identity FK. A steward
                // lists pending requests and approves out-of-band (mints an invite).
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS zone_access_request(
                      id                TEXT PRIMARY KEY,
                      zone_id           TEXT NOT NULL,
                      requester_name    TEXT NOT NULL,
                      requester_contact TEXT,
                      reason            TEXT,
                      status            TEXT NOT NULL DEFAULT 'pending',
                      created_at        INTEGER NOT NULL DEFAULT (unixepoch())
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_zone_access_request_zone "
                    + "ON zone_access_request(zone_id, status)");
            }
            // idempotent migration for older
            // databases that pre-date the preferred_language /
            // cultural_register_preference columns. Probes information_schema-
            // equivalent via PRAGMA table_info on SQLite; on libSQL/PostgreSQL
            // the upstream dialect also accepts ADD COLUMN IF NOT EXISTS, but
            // SQLite doesn't, so we check then ALTER. Errors during ALTER are
            // tolerated (column already exists from a CREATE-fresh path).
            ensureColumn(conn, "player_accounts", "preferred_language", "TEXT");
            ensureColumn(conn, "player_accounts", "cultural_register_preference", "TEXT");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize player account schema", e);
        }
    }

    /**
     * Idempotent ADD COLUMN guard. Reads existing columns via PRAGMA table_info
     * (SQLite-native; on libSQL the same pragma is honored) and only issues an
     * ALTER when the column is missing. Tolerant of dialect differences — any
     * SQLException during the ADD is swallowed since "column already exists" is
     * the expected race-with-CREATE outcome.
     */
    private static void ensureColumn(Connection conn, String table, String column, String type) {
        boolean present = false;
        try (var stmt = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            var rs = stmt.executeQuery();
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    present = true;
                    break;
                }
            }
        } catch (SQLException ignore) {
            // PRAGMA not supported on this dialect — fall through to ALTER attempt.
        }
        if (present) return;
        try (var stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("Phase 1A migration: added column {}.{}", table, column);
        } catch (SQLException e) {
            // Tolerated — most likely the column was added concurrently or the
            // table was created with the column present already.
            log.debug("ALTER TABLE add {}.{} skipped: {}", table, column, e.getMessage());
        }
    }

    // --- CRUD ---

    /**
     * Save or update a player account.
     */
    public void save(PlayerAccount account) {
        try (var conn = getConnection()) {
            var sql = dialect.upsert("player_accounts",
                "did, display_name, created_at, last_seen, primary_node_id, "
                    + "preferred_language, cultural_register_preference",
                "?, ?, ?, ?, ?, ?, ?",
                "did",
                "display_name = excluded.display_name, last_seen = excluded.last_seen, "
                    + "primary_node_id = excluded.primary_node_id, "
                    + "preferred_language = excluded.preferred_language, "
                    + "cultural_register_preference = excluded.cultural_register_preference");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, account.did());
                stmt.setString(2, account.displayName());
                stmt.setLong(3, account.createdAt().getEpochSecond());
                stmt.setLong(4, account.lastSeen().getEpochSecond());
                stmt.setString(5, account.primaryNodeId());
                stmt.setString(6, account.preferredLanguage());
                stmt.setString(7, account.culturalRegisterPreference());
                stmt.executeUpdate();
            }
            // Sync device IDs: clear old, insert current
            syncDevices(conn, account.did(), account.deviceIds());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save player account: " + account.did(), e);
        }
    }

    /**
     * Find an account by DID.
     */
    public Optional<PlayerAccount> findByDid(String did) {
        try (var conn = getConnection()) {
            return loadAccount(conn, "did = ?", did);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find account by DID: " + did, e);
        }
    }

    /**
     * Find an account by display name (case-insensitive).
     */
    public Optional<PlayerAccount> findByName(String name) {
        try (var conn = getConnection()) {
            var sql = "SELECT " + ACCOUNT_COLUMNS
                + " FROM player_accounts WHERE " + dialect.caseInsensitiveEquals("display_name", "?");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, name);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();
                var account = rowToAccount(conn, rs);
                return Optional.of(account);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find account by name: " + name, e);
        }
    }

    /**
     * List all accounts.
     */
    public List<PlayerAccount> listAll() {
        try (var conn = getConnection()) {
            var sql = "SELECT " + ACCOUNT_COLUMNS
                + " FROM player_accounts ORDER BY created_at";
            try (var stmt = conn.prepareStatement(sql)) {
                var rs = stmt.executeQuery();
                var result = new ArrayList<PlayerAccount>();
                while (rs.next()) {
                    result.add(rowToAccount(conn, rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list accounts", e);
        }
    }

    /** Shared SELECT column list — keep aligned with {@link #rowToAccount}. */
    private static final String ACCOUNT_COLUMNS =
        "did, display_name, created_at, last_seen, primary_node_id, "
        + "preferred_language, cultural_register_preference";

    /**
     * Update the last_seen timestamp for an account.
     */
    public void updateLastSeen(String did, Instant lastSeen) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                "UPDATE player_accounts SET last_seen = ? WHERE did = ?")) {
                stmt.setLong(1, lastSeen.getEpochSecond());
                stmt.setString(2, did);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update last seen: " + did, e);
        }
    }

    // --- Device auto-login ---

    /**
     * Register a device for auto-login to an account.
     */
    public void registerDevice(String did, String deviceId) {
        try (var conn = getConnection()) {
            // Use upsert so re-registering the same device just updates the account
            var sql = dialect.upsert("device_logins",
                "device_id, account_did", "?, ?",
                "device_id", "account_did = excluded.account_did");
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, deviceId);
                stmt.setString(2, did);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register device: " + deviceId, e);
        }
    }

    /**
     * Find the account DID associated with a device for auto-login.
     */
    public Optional<String> findAccountForDevice(String deviceId) {
        try (var conn = getConnection()) {
            try (var stmt = conn.prepareStatement(
                "SELECT account_did FROM device_logins WHERE device_id = ?")) {
                stmt.setString(1, deviceId);
                var rs = stmt.executeQuery();
                if (!rs.next()) return Optional.empty();
                return Optional.of(rs.getString("account_did"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find account for device: " + deviceId, e);
        }
    }

    // --- Helpers ---

    private Optional<PlayerAccount> loadAccount(Connection conn, String whereClause, String param)
        throws SQLException {
        var sql = "SELECT " + ACCOUNT_COLUMNS
            + " FROM player_accounts WHERE " + whereClause;
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, param);
            var rs = stmt.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(rowToAccount(conn, rs));
        }
    }

    private PlayerAccount rowToAccount(Connection conn, ResultSet rs) throws SQLException {
        var did = rs.getString("did");
        var displayName = rs.getString("display_name");
        var createdAt = Instant.ofEpochSecond(rs.getLong("created_at"));
        var lastSeen = Instant.ofEpochSecond(rs.getLong("last_seen"));
        var primaryNodeId = rs.getString("primary_node_id");
        // read getString and tolerate either
        // missing column (older schema, defensive against pre-migration probe
        // timing) or null (column present, value unset). Both flow through as
        // null on PlayerAccount.
        String preferredLanguage = readNullableColumn(rs, "preferred_language");
        String culturalRegisterPreference = readNullableColumn(rs, "cultural_register_preference");
        var deviceIds = loadDeviceIds(conn, did);
        return new PlayerAccount(did, displayName, createdAt, lastSeen, primaryNodeId,
            deviceIds, preferredLanguage, culturalRegisterPreference);
    }

    private static String readNullableColumn(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            // Older schemas may lack the column entirely — return null so callers
            // see a missing-preference signal rather than a hard failure.
            return null;
        }
    }

    private List<String> loadDeviceIds(Connection conn, String did) throws SQLException {
        try (var stmt = conn.prepareStatement(
            "SELECT device_id FROM device_logins WHERE account_did = ?")) {
            stmt.setString(1, did);
            var rs = stmt.executeQuery();
            var ids = new ArrayList<String>();
            while (rs.next()) {
                ids.add(rs.getString("device_id"));
            }
            return Collections.unmodifiableList(ids);
        }
    }

    private void syncDevices(Connection conn, String did, List<String> deviceIds) throws SQLException {
        // Remove all existing device mappings for this account
        try (var stmt = conn.prepareStatement("DELETE FROM device_logins WHERE account_did = ?")) {
            stmt.setString(1, did);
            stmt.executeUpdate();
        }
        // Insert current device IDs
        if (!deviceIds.isEmpty()) {
            try (var stmt = conn.prepareStatement(
                "INSERT INTO device_logins (device_id, account_did) VALUES (?, ?)")) {
                for (var deviceId : deviceIds) {
                    stmt.setString(1, deviceId);
                    stmt.setString(2, did);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }
    }

    // --- Zone bank ---

    /** The user's synced zone-bank JSON, or empty if they've never written one.
     *  {@code accountId} is the caller's stable session identifier (same value
     *  used for get + put). */
    public Optional<ZoneBankRecord> getZoneBank(String accountId) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT bank_json, updated_at FROM account_zonebank WHERE account_id = ?")) {
            stmt.setString(1, accountId);
            var rs = stmt.executeQuery();
            if (!rs.next()) return Optional.empty();
            return Optional.of(new ZoneBankRecord(rs.getString("bank_json"), rs.getLong("updated_at")));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read zone bank for " + accountId, e);
        }
    }

    /** Upsert the user's zone-bank JSON. Returns the stored updatedAt. */
    public long putZoneBank(String accountId, String bankJson, long updatedAt) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "INSERT INTO account_zonebank(account_id, bank_json, updated_at) VALUES(?, ?, ?) "
                 + "ON CONFLICT(account_id) DO UPDATE SET bank_json = excluded.bank_json, "
                 + "updated_at = excluded.updated_at")) {
            stmt.setString(1, accountId);
            stmt.setString(2, bankJson);
            stmt.setLong(3, updatedAt);
            stmt.executeUpdate();
            return updatedAt;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write zone bank for " + accountId, e);
        }
    }

    /** A stored zone bank: opaque client JSON + the server's last-write timestamp. */
    public record ZoneBankRecord(String bankJson, long updatedAt) {}

    // --- Access requests / steward knock ---

    /** Record a pending access request ("knock") for this zone. Returns the id. */
    public String addAccessRequest(String id, String zoneId, String requesterName,
                                   String requesterContact, String reason, long createdAt) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "INSERT INTO zone_access_request"
                 + "(id, zone_id, requester_name, requester_contact, reason, status, created_at) "
                 + "VALUES(?, ?, ?, ?, ?, 'pending', ?)")) {
            stmt.setString(1, id);
            stmt.setString(2, zoneId);
            stmt.setString(3, requesterName);
            stmt.setString(4, requesterContact);
            stmt.setString(5, reason);
            stmt.setLong(6, createdAt);
            stmt.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record access request for " + zoneId, e);
        }
    }

    /** List access requests for a zone, optionally filtered by status, newest first. */
    public List<AccessRequest> listAccessRequests(String zoneId, String status, int limit) {
        var sql = new StringBuilder(
            "SELECT id, zone_id, requester_name, requester_contact, reason, status, created_at "
            + "FROM zone_access_request WHERE zone_id = ?");
        if (status != null) sql.append(" AND status = ?");
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(sql.toString())) {
            int i = 1;
            stmt.setString(i++, zoneId);
            if (status != null) stmt.setString(i++, status);
            stmt.setInt(i, Math.max(1, Math.min(200, limit)));
            var rs = stmt.executeQuery();
            var out = new ArrayList<AccessRequest>();
            while (rs.next()) {
                out.add(new AccessRequest(
                    rs.getString("id"), rs.getString("zone_id"),
                    rs.getString("requester_name"), rs.getString("requester_contact"),
                    rs.getString("reason"), rs.getString("status"), rs.getLong("created_at")));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list access requests for " + zoneId, e);
        }
    }

    /** Set an access request's status (approved / denied). Returns true if updated. */
    public boolean setAccessRequestStatus(String id, String status) {
        try (var conn = getConnection();
             var stmt = conn.prepareStatement(
                 "UPDATE zone_access_request SET status = ? WHERE id = ?")) {
            stmt.setString(1, status);
            stmt.setString(2, id);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update access request " + id, e);
        }
    }

    /** A steward-visible access request. */
    public record AccessRequest(
        String id, String zoneId, String requesterName, String requesterContact,
        String reason, String status, long createdAt) {}

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
