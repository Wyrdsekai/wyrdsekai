package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.ObligationLedger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1C: SQL persistence for {@link ObligationLedger}.
 *
 * <p>Sibling of {@link VitalityPersistence}. Hot-path stays in CompanionActor in-memory; this
 * class is a write-through mirror so debts survive actor / process restart.</p>
 *
 * <p>Schema: composite PK on {@code (companion_did, bondholder_did, entry_id)}. The persistence
 * layer mints {@code entry_id} on save and re-mints on every {@link #saveAll(String, Map)}
 * (full-rewrite semantics keeps the implementation trivial — companions accumulate debts in
 * the dozens, not thousands).</p>
 *
 * <p>Migration is idempotent — see {@link #ensureMigrated(Connection)}.</p>
 */
public final class ObligationLedgerPersistence {

    private static final Logger log = LoggerFactory.getLogger(ObligationLedgerPersistence.class);

    private final String jdbcUrl;
    private volatile boolean migrated = false;

    public ObligationLedgerPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Idempotent migration. Creates the {@code obligation_ledger} table if it doesn't exist.
     * Safe to call repeatedly.
     */
    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "obligation_ledger")) {
            log.info("Creating obligation_ledger table (Phase 1C migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS obligation_ledger(
                      companion_did       TEXT NOT NULL,
                      bondholder_did      TEXT NOT NULL,
                      entry_id            TEXT NOT NULL,
                      original_magnitude  REAL NOT NULL,
                      current_magnitude   REAL NOT NULL,
                      created_at          INTEGER NOT NULL,
                      last_compounded_at  INTEGER NOT NULL,
                      PRIMARY KEY (companion_did, bondholder_did, entry_id)
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_obligation_ledger_companion "
                        + "ON obligation_ledger(companion_did)");
            }
        }
        migrated = true;
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    /**
     * Full-rewrite save: deletes all rows for the companion, then inserts the current
     * snapshot. Used by CompanionActor as a write-through after every debt mutation.
     */
    public void saveAll(String companionDid,
                        Map<String, List<ObligationLedger.DebtEntry>> snapshot) {
        if (companionDid == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM obligation_ledger WHERE companion_did = ?")) {
                    del.setString(1, companionDid);
                    del.executeUpdate();
                }
                if (snapshot != null && !snapshot.isEmpty()) {
                    try (var ins = conn.prepareStatement(
                            "INSERT INTO obligation_ledger("
                                + "companion_did, bondholder_did, entry_id, "
                                + "original_magnitude, current_magnitude, "
                                + "created_at, last_compounded_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        long nowMs = Instant.now().toEpochMilli();
                        for (var e : snapshot.entrySet()) {
                            String bondholder = e.getKey();
                            for (var entry : e.getValue()) {
                                ins.setString(1, companionDid);
                                ins.setString(2, bondholder);
                                ins.setString(3, entry.entryId());
                                ins.setDouble(4, entry.originalMagnitude());
                                // current_magnitude stored at save time as the original; runtime
                                // recomputes via Debt.currentMagnitude(now) on every read.
                                ins.setDouble(5, entry.originalMagnitude());
                                ins.setLong(6, entry.createdAt().toEpochMilli());
                                ins.setLong(7, nowMs);
                                ins.addBatch();
                            }
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
            log.error("Failed to save obligation ledger for {}: {}", companionDid, e.getMessage());
            throw new RuntimeException("Obligation ledger save failed", e);
        }
    }

    /** Load every entry for a companion, grouped by bondholder. */
    public Map<String, List<ObligationLedger.DebtEntry>> loadAll(String companionDid) {
        var out = new LinkedHashMap<String, List<ObligationLedger.DebtEntry>>();
        if (companionDid == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT bondholder_did, entry_id, original_magnitude, created_at "
                        + "FROM obligation_ledger WHERE companion_did = ? "
                        + "ORDER BY bondholder_did, created_at ASC")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    String bondholder = rs.getString("bondholder_did");
                    var entry = new ObligationLedger.DebtEntry(
                        rs.getString("entry_id"),
                        rs.getDouble("original_magnitude"),
                        Instant.ofEpochMilli(rs.getLong("created_at"))
                    );
                    out.computeIfAbsent(bondholder, k -> new ArrayList<>()).add(entry);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load obligation ledger for {}: {}", companionDid, e.getMessage());
        }
        return out;
    }

    /** Test/admin: count rows for a companion. */
    public int count(String companionDid) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM obligation_ledger WHERE companion_did = ?")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
