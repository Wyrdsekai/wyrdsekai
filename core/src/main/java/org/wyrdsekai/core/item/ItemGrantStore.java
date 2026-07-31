package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DB-backed store of steward consents
 * granted to a specific scripted item, keyed by {@code (itemId, capability)}.
 *
 * <p>The runtime composes the active capability set per execution as the
 * intersection of (a) the capabilities declared in the item's manifest and
 * (b) the rows persisted here. Steward {@code tear &lt;item&gt; from board}
 * deletes rows; the next execution sees a smaller cap set.</p>
 *
 * <p>Schema is created lazily via {@link #initSchema()} and uses the same
 * SQLite/PostgreSQL dual-storage pattern as {@code PairingService}. Tables:
 * {@code item_grants(item_id, capability, granted_by_did, granted_at,
 * scope_json)}.</p>
 */
public final class ItemGrantStore {

    private static final Logger log = LoggerFactory.getLogger(ItemGrantStore.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public ItemGrantStore(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public ItemGrantStore(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    public record Grant(String itemId, String capability, String grantedByDid,
                         Instant grantedAt, String scopeJson) {}

    /** Idempotent — creates the table on first call. */
    public void initSchema() {
        try (var conn = getConnection()) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS item_grants(
                      item_id          TEXT NOT NULL,
                      capability       TEXT NOT NULL,
                      granted_by_did   TEXT NOT NULL,
                      granted_at       INTEGER NOT NULL,
                      scope_json       TEXT,
                      PRIMARY KEY (item_id, capability)
                    )""");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_item_grants_item ON item_grants(item_id)");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize item_grants schema: " + e.getMessage(), e);
        }
    }

    /** Issue (or replace) a grant. */
    public void issue(String itemId, String capability, String grantedByDid, String scopeJson) {
        try (var conn = getConnection()) {
            var sql = dialect.upsert("item_grants",
                "item_id, capability, granted_by_did, granted_at, scope_json",
                "?, ?, ?, ?, ?",
                "item_id, capability",
                "granted_by_did = excluded.granted_by_did, "
                    + "granted_at = excluded.granted_at, "
                    + "scope_json = excluded.scope_json");
            try (var ps = conn.prepareStatement(sql)) {
                ps.setString(1, itemId);
                ps.setString(2, capability);
                ps.setString(3, grantedByDid == null ? "" : grantedByDid);
                ps.setLong(4, Instant.now().getEpochSecond());
                if (scopeJson == null) ps.setNull(5, Types.VARCHAR);
                else ps.setString(5, scopeJson);
                ps.executeUpdate();
            }
            log.info("Issued item grant: item={} cap={} by={}", itemId, capability, grantedByDid);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to issue item grant: " + e.getMessage(), e);
        }
    }

    /** Revoke a single capability for an item. Returns true when a row was removed. */
    public boolean revoke(String itemId, String capability) {
        try (var conn = getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM item_grants WHERE item_id = ? AND capability = ?")) {
            ps.setString(1, itemId);
            ps.setString(2, capability);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Revoked item grant: item={} cap={}", itemId, capability);
            }
            return rows > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke item grant: " + e.getMessage(), e);
        }
    }

    /** Steward tear: remove ALL grants for an item. */
    public int revokeAll(String itemId) {
        try (var conn = getConnection();
             var ps = conn.prepareStatement(
                 "DELETE FROM item_grants WHERE item_id = ?")) {
            ps.setString(1, itemId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Revoked all {} grants for item {}", rows, itemId);
            }
            return rows;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to revoke all item grants: " + e.getMessage(), e);
        }
    }

    /** Set of capabilities currently granted to {@code itemId}. */
    public Set<String> capabilitiesFor(String itemId) {
        var out = new HashSet<String>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement(
                 "SELECT capability FROM item_grants WHERE item_id = ?")) {
            ps.setString(1, itemId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("capabilitiesFor({}) failed: {}", itemId, e.getMessage());
        }
        return out;
    }

    /** All grants for an item, with metadata. */
    public List<Grant> listForItem(String itemId) {
        var out = new ArrayList<Grant>();
        try (var conn = getConnection();
             var ps = conn.prepareStatement(
                 "SELECT capability, granted_by_did, granted_at, scope_json "
                 + "FROM item_grants WHERE item_id = ? ORDER BY granted_at DESC")) {
            ps.setString(1, itemId);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Grant(itemId, rs.getString(1), rs.getString(2),
                        Instant.ofEpochSecond(rs.getLong(3)), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            log.warn("listForItem({}) failed: {}", itemId, e.getMessage());
        }
        return out;
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}
