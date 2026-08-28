package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.VitalityState;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC persistence for agent vitality state (20 tanks).
 * Saves/loads from the vitality_snapshots table using the SqlDialect pattern.
 *
 * <p><b>Phase 1A migration:</b> the original schema persisted only the first 8 tanks
 * (contextBudget through focus). On first connection we ALTER TABLE to add the 12
 * missing columns ({@code integrity}, {@code disgust}, plus the 10 new deprivation-shape
 * tanks: {@code restlessness}, {@code loneliness}, {@code stagnation},
 * {@code autonomy_pressure}, {@code significance}, {@code amae}, {@code saudade},
 * {@code obligation}, {@code harmony}, {@code standing}). The migration is idempotent —
 * each column is only added if it's not already present, so re-running against an
 * already-migrated database is a no-op.
 *
 * <p>Drives are NOT persisted via this class — they live in the snapshot/event-sourcing
 * path through {@link org.wyrdsekai.core.soul.VitalitySnapshot} and round-trip via
 * {@code DriveState.toMap()/fromMap()}. No direct SQL columns for drives.
 */
public final class VitalityPersistence {

    private static final Logger log = LoggerFactory.getLogger(VitalityPersistence.class);

    /**
     * The 12 columns added by Phase 1A migration, in the exact order they're appended.
     * (integrity, disgust were silently missing; the 10 deprivation-shape tanks are new.)
     * Each entry is (column_name, column_type) — type is portable across SQLite + PostgreSQL
     * since DOUBLE PRECISION / REAL are both spelled "DOUBLE PRECISION" acceptably or REAL.
     * Use REAL since SQLite is the primary target and PostgreSQL accepts REAL too.
     */
    private static final List<String[]> PHASE_1A_COLUMNS = List.of(
        new String[]{"integrity",          "REAL NOT NULL DEFAULT 0.7"},
        new String[]{"disgust",            "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"restlessness",       "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"loneliness",         "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"stagnation",         "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"autonomy_pressure",  "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"significance",       "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"amae",               "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"saudade",            "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"obligation",         "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"harmony",            "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"standing",           "REAL NOT NULL DEFAULT 0.0"},
        // Wave 1: Gilbert CFT soothing receptor.
        new String[]{"soothing",           "REAL NOT NULL DEFAULT 0.3"},
        // Wave 1.5: substrate-truth signal triad.
        // allostatic_load — McEwen chronic-stress damage meter; cost-of-suppression signal.
        // equanimity — contemplative-practice capacity for non-reactive presence.
        new String[]{"allostatic_load",    "REAL NOT NULL DEFAULT 0.0"},
        new String[]{"equanimity",         "REAL NOT NULL DEFAULT 0.2"}
    );

    /**
     * Sleep-pressure columns (2026-08-11). The backlog that gates natural
     * sleep was a plain in-memory list: seven restarts in the fresh install's
     * first two days zeroed it seven times, and the companion could never
     * accumulate toward her target — the adenosine evaporated on every
     * deploy. The COUNT is what pressure needs; the events themselves still
     * live in memory and the event store for consolidation.
     */
    private static final List<String[]> SLEEP_COLUMNS = List.of(
        new String[]{"sleep_backlog",  "INTEGER NOT NULL DEFAULT 0"},
        new String[]{"last_sleep_at",  "INTEGER"}
    );

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private volatile boolean migrated = false;

    public VitalityPersistence(String jdbcUrl) {
        this(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
    }

    public VitalityPersistence(String jdbcUrl, SqlDialect dialect) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = dialect;
    }

    /**
     * Idempotent Phase 1A migration. Adds any missing extension columns to
     * vitality_snapshots. Safe to call repeatedly; only adds columns that don't exist.
     * Called lazily before each save/load to handle bootstraps where the table
     * already exists but predates Phase 1A.
     */
    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        // Skip if the base table doesn't exist yet — schema initializer will create it,
        // and that path includes all columns. This guard is for legacy databases.
        if (!hasTable(conn, "vitality_snapshots")) {
            migrated = true;
            return;
        }
        for (var col : PHASE_1A_COLUMNS) {
            if (!hasColumn(conn, "vitality_snapshots", col[0])) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE vitality_snapshots ADD COLUMN " + col[0] + " " + col[1]);
                    log.info("Vitality migration: added column {}", col[0]);
                } catch (SQLException e) {
                    // Race: another process added it between hasColumn and ALTER. Re-check
                    // and only rethrow if it's a different failure.
                    if (!hasColumn(conn, "vitality_snapshots", col[0])) {
                        throw e;
                    }
                }
            }
        }
        for (var col : SLEEP_COLUMNS) {
            if (!hasColumn(conn, "vitality_snapshots", col[0])) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE vitality_snapshots ADD COLUMN " + col[0] + " " + col[1]);
                    log.info("Vitality migration: added column {}", col[0]);
                } catch (SQLException e) {
                    if (!hasColumn(conn, "vitality_snapshots", col[0])) {
                        throw e;
                    }
                }
            }
        }
        migrated = true;
    }

    /** Persisted sleep pressure: the backlog count and when she last slept. */
    public record SleepPressure(int backlog, Optional<Instant> lastSleepAt) {}

    /**
     * Persist the sleep-pressure backlog alongside the vitality snapshot.
     * UPDATE-only by design: the row is created by {@link #save}, which runs
     * on the same tick cadence just before this — a missing row means the
     * first snapshot hasn't landed yet, and the next tick covers it.
     */
    public void saveSleepPressure(String agentId, int backlog, Instant lastSleepAt) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var ps = conn.prepareStatement(
                    "UPDATE vitality_snapshots SET sleep_backlog = ?, last_sleep_at = ? "
                    + "WHERE agent_id = ?")) {
                ps.setInt(1, backlog);
                if (lastSleepAt != null) {
                    ps.setLong(2, lastSleepAt.toEpochMilli());
                } else {
                    ps.setNull(2, Types.BIGINT);
                }
                ps.setString(3, agentId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.debug("Failed to save sleep pressure for agent {}: {}", agentId, e.getMessage());
        }
    }

    /** Load the persisted sleep pressure, if a snapshot row exists. */
    public Optional<SleepPressure> loadSleepPressure(String agentId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var ps = conn.prepareStatement(
                    "SELECT sleep_backlog, last_sleep_at FROM vitality_snapshots "
                    + "WHERE agent_id = ?")) {
                ps.setString(1, agentId);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // Read order matters: wasNull() reports on the LAST
                        // column read, so backlog must be read before the
                        // nullable timestamp (the test caught exactly this —
                        // a never-slept companion came back as epoch 1970).
                        int backlog = rs.getInt(1);
                        long at = rs.getLong(2);
                        return Optional.of(new SleepPressure(backlog,
                            rs.wasNull() ? Optional.empty()
                                : Optional.of(Instant.ofEpochMilli(at))));
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Failed to load sleep pressure for agent {}: {}", agentId, e.getMessage());
        }
        return Optional.empty();
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    /**
     * Save (upsert) vitality state for an agent. All 20 tanks are persisted.
     */
    public void save(String agentId, VitalityState state) {
        var sql = dialect.upsert("vitality_snapshots",
            "agent_id, context_budget, confidence, energy, alignment, error_pressure, momentum, rapport, focus, "
                + "integrity, disgust, restlessness, loneliness, stagnation, autonomy_pressure, significance, "
                + "amae, saudade, obligation, harmony, standing, soothing, allostatic_load, equanimity, updated_at",
            "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + dialect.currentEpoch(),
            "agent_id",
            "context_budget = EXCLUDED.context_budget, confidence = EXCLUDED.confidence, "
                + "energy = EXCLUDED.energy, alignment = EXCLUDED.alignment, "
                + "error_pressure = EXCLUDED.error_pressure, momentum = EXCLUDED.momentum, "
                + "rapport = EXCLUDED.rapport, focus = EXCLUDED.focus, "
                + "integrity = EXCLUDED.integrity, disgust = EXCLUDED.disgust, "
                + "restlessness = EXCLUDED.restlessness, loneliness = EXCLUDED.loneliness, "
                + "stagnation = EXCLUDED.stagnation, autonomy_pressure = EXCLUDED.autonomy_pressure, "
                + "significance = EXCLUDED.significance, amae = EXCLUDED.amae, "
                + "saudade = EXCLUDED.saudade, obligation = EXCLUDED.obligation, "
                + "harmony = EXCLUDED.harmony, standing = EXCLUDED.standing, "
                + "soothing = EXCLUDED.soothing, "
                + "allostatic_load = EXCLUDED.allostatic_load, equanimity = EXCLUDED.equanimity, "
                + "updated_at = EXCLUDED.updated_at");
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, agentId);
                stmt.setDouble(2, state.contextBudget());
                stmt.setDouble(3, state.confidence());
                stmt.setDouble(4, state.energy());
                stmt.setDouble(5, state.alignment());
                stmt.setDouble(6, state.errorPressure());
                stmt.setDouble(7, state.momentum());
                stmt.setDouble(8, state.rapport());
                stmt.setDouble(9, state.focus());
                stmt.setDouble(10, state.integrity());
                stmt.setDouble(11, state.disgust());
                stmt.setDouble(12, state.restlessness());
                stmt.setDouble(13, state.loneliness());
                stmt.setDouble(14, state.stagnation());
                stmt.setDouble(15, state.autonomyPressure());
                stmt.setDouble(16, state.significance());
                stmt.setDouble(17, state.amae());
                stmt.setDouble(18, state.saudade());
                stmt.setDouble(19, state.obligation());
                stmt.setDouble(20, state.harmony());
                stmt.setDouble(21, state.standing());
                stmt.setDouble(22, state.soothing());
                stmt.setDouble(23, state.allostaticLoad());
                stmt.setDouble(24, state.equanimity());
                stmt.executeUpdate();
                log.debug("Vitality saved for agent {}", agentId);
            }
        } catch (SQLException e) {
            log.error("Failed to save vitality for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Vitality save failed", e);
        }
    }

    /**
     * Load vitality state for an agent.
     *
     * @return the stored state, or empty if no snapshot exists
     */
    public Optional<VitalityState> load(String agentId) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                 "SELECT context_budget, confidence, energy, alignment, "
                     + "error_pressure, momentum, rapport, focus, "
                     + "integrity, disgust, restlessness, loneliness, stagnation, "
                     + "autonomy_pressure, significance, amae, saudade, obligation, "
                     + "harmony, standing, soothing, allostatic_load, equanimity "
                     + "FROM vitality_snapshots WHERE agent_id = ?")) {
                stmt.setString(1, agentId);
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(readState(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to load vitality for agent {}: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Delete a vitality snapshot.
     *
     * @return true if a row was deleted
     */
    public boolean delete(String agentId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "DELETE FROM vitality_snapshots WHERE agent_id = ?")) {
            stmt.setString(1, agentId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Vitality delete failed", e);
        }
    }

    /**
     * Check if a snapshot exists for an agent.
     */
    public boolean exists(String agentId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM vitality_snapshots WHERE agent_id = ?")) {
            stmt.setString(1, agentId);
            var rs = stmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) {
            log.error("Vitality exists check failed for agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * Count total stored vitality snapshots.
     */
    public int count() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM vitality_snapshots");
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Vitality count failed", e);
        }
    }

    private static VitalityState readState(ResultSet rs) throws SQLException {
        return new VitalityState(
            rs.getDouble("context_budget"),
            rs.getDouble("confidence"),
            rs.getDouble("energy"),
            rs.getDouble("alignment"),
            rs.getDouble("error_pressure"),
            rs.getDouble("momentum"),
            rs.getDouble("rapport"),
            rs.getDouble("focus"),
            rs.getDouble("integrity"),
            rs.getDouble("disgust"),
            rs.getDouble("restlessness"),
            rs.getDouble("loneliness"),
            rs.getDouble("stagnation"),
            rs.getDouble("autonomy_pressure"),
            rs.getDouble("significance"),
            rs.getDouble("amae"),
            rs.getDouble("saudade"),
            rs.getDouble("obligation"),
            rs.getDouble("harmony"),
            rs.getDouble("standing"),
            rs.getDouble("soothing"),
            rs.getDouble("allostatic_load"),
            rs.getDouble("equanimity")
        );
    }
}
