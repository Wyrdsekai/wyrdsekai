package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.SaudadeLedger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 1C: SQL persistence for {@link SaudadeLedger}.
 *
 * <p>Sibling of {@link VitalityPersistence}. Hot-path stays in CompanionActor in-memory; this
 * class is a write-through mirror so per-bondholder saudade tanks + last-interaction stamps
 * survive restart.</p>
 *
 * <p>Schema: PK on {@code (companion_did, bondholder_did)} — exactly one row per bondholder.</p>
 */
public final class SaudadeLedgerPersistence {

    private static final Logger log = LoggerFactory.getLogger(SaudadeLedgerPersistence.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private volatile boolean migrated = false;

    public SaudadeLedgerPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "saudade_ledger")) {
            log.info("Creating saudade_ledger table (Phase 1C migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS saudade_ledger(
                      companion_did       TEXT NOT NULL,
                      bondholder_did      TEXT NOT NULL,
                      current_value       REAL NOT NULL DEFAULT 0.0,
                      last_interaction_at INTEGER NOT NULL DEFAULT 0,
                      last_tick_at        INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY (companion_did, bondholder_did)
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_saudade_ledger_companion "
                        + "ON saudade_ledger(companion_did)");
            }
        }
        migrated = true;
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    /** Full-rewrite save for one companion's snapshot. */
    public void saveAll(String companionDid, Map<String, SaudadeLedger.SaudadeEntry> snapshot) {
        if (companionDid == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM saudade_ledger WHERE companion_did = ?")) {
                    del.setString(1, companionDid);
                    del.executeUpdate();
                }
                if (snapshot != null && !snapshot.isEmpty()) {
                    String upsert = dialect.upsert("saudade_ledger",
                        "companion_did, bondholder_did, current_value, last_interaction_at, last_tick_at",
                        "?, ?, ?, ?, ?",
                        "companion_did, bondholder_did",
                        "current_value = EXCLUDED.current_value, "
                            + "last_interaction_at = EXCLUDED.last_interaction_at, "
                            + "last_tick_at = EXCLUDED.last_tick_at");
                    try (var ins = conn.prepareStatement(upsert)) {
                        long nowMs = Instant.now().toEpochMilli();
                        for (var e : snapshot.entrySet()) {
                            var entry = e.getValue();
                            if (entry == null) continue;
                            ins.setString(1, companionDid);
                            ins.setString(2, e.getKey());
                            ins.setDouble(3, entry.currentValue());
                            ins.setLong(4, entry.lastInteractionAt() == null
                                ? 0L : entry.lastInteractionAt().toEpochMilli());
                            ins.setLong(5, nowMs);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            log.error("Failed to save saudade ledger for {}: {}", companionDid, e.getMessage());
            throw new RuntimeException("Saudade ledger save failed", e);
        }
    }

    /** Load every entry for a companion. */
    public Map<String, SaudadeLedger.SaudadeEntry> loadAll(String companionDid) {
        var out = new LinkedHashMap<String, SaudadeLedger.SaudadeEntry>();
        if (companionDid == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT bondholder_did, current_value, last_interaction_at "
                        + "FROM saudade_ledger WHERE companion_did = ?")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    long li = rs.getLong("last_interaction_at");
                    Instant liInstant = li == 0 ? Instant.EPOCH : Instant.ofEpochMilli(li);
                    out.put(rs.getString("bondholder_did"),
                        new SaudadeLedger.SaudadeEntry(rs.getDouble("current_value"), liInstant));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load saudade ledger for {}: {}", companionDid, e.getMessage());
        }
        return out;
    }

    public int count(String companionDid) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM saudade_ledger WHERE companion_did = ?")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
