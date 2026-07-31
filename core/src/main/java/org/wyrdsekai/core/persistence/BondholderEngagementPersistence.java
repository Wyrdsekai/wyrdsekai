package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.BondholderEngagementHistory;
import org.wyrdsekai.core.soul.BondholderEngagementHistory.EngagementEvent;
import org.wyrdsekai.core.soul.BondholderEngagementHistory.EventType;
import org.wyrdsekai.core.soul.BondholderEngagementHistory.ExplicitAbsence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wave 3.6: SQL persistence for
 * {@link BondholderEngagementHistory}.
 *
 * <p>Write-through mirror of the in-memory event history maintained by
 * {@code CompanionActor.engagementHistory}. Without persistence the classifier
 * loses its baseline pattern on every restart, which effectively re-triggers
 * cold-start logic and defeats the pattern-based classification.
 *
 * <p>Schema: one row per engagement event. PK is composite (companion_did,
 * bondholder_did, event_ts). Insert-only on the hot path; opportunistic
 * prune of events older than {@link BondholderEngagementHistory#RETENTION}
 * happens alongside each insert to keep the table bounded.
 *
 * <p>EXPLICIT_ABSENCE events carry a non-null {@code declared_until_at}
 * column that the {@link BondholderEngagementHistory#declareAbsence} side
 * tracks. On load, active declared-absences (declared_until still in the
 * future) are re-instated via the {@code declareAbsence()} API.
 */
public final class BondholderEngagementPersistence {

    private static final Logger log = LoggerFactory.getLogger(BondholderEngagementPersistence.class);

    private final String jdbcUrl;
    private volatile boolean migrated = false;

    public BondholderEngagementPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "bondholder_engagement")) {
            log.info("Creating bondholder_engagement table (Wave 3.6 migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS bondholder_engagement(
                      companion_did       TEXT NOT NULL,
                      bondholder_did      TEXT NOT NULL,
                      event_ts            INTEGER NOT NULL,
                      substance           REAL NOT NULL DEFAULT 1.0,
                      event_type          TEXT NOT NULL,
                      declared_until_at   INTEGER,
                      PRIMARY KEY (companion_did, bondholder_did, event_ts)
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_companion "
                        + "ON bondholder_engagement(companion_did)");
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_bondholder_engagement_lookup "
                        + "ON bondholder_engagement(companion_did, bondholder_did, event_ts)");
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
     * Record a single engagement event. Opportunistically prunes rows older
     * than the retention window for this (companion, bondholder) tuple to
     * keep the table bounded.
     *
     * @param declaredUntilAt only non-null for EXPLICIT_ABSENCE events
     */
    public void recordEvent(String companionDid, String bondholderDid, Instant ts,
                            double substance, EventType type, Instant declaredUntilAt) {
        if (companionDid == null || bondholderDid == null || ts == null || type == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var ins = conn.prepareStatement(
                    "INSERT INTO bondholder_engagement"
                        + "(companion_did, bondholder_did, event_ts, substance, event_type, declared_until_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (companion_did, bondholder_did, event_ts) DO UPDATE SET "
                        + "  substance = excluded.substance, "
                        + "  event_type = excluded.event_type, "
                        + "  declared_until_at = excluded.declared_until_at")) {
                ins.setString(1, companionDid);
                ins.setString(2, bondholderDid);
                ins.setLong(3, ts.toEpochMilli());
                ins.setDouble(4, substance);
                ins.setString(5, type.name());
                if (declaredUntilAt != null) {
                    ins.setLong(6, declaredUntilAt.toEpochMilli());
                } else {
                    ins.setNull(6, Types.BIGINT);
                }
                ins.executeUpdate();
            }
            // Prune anything past retention for this tuple. Bounded write per insert.
            long cutoffMs = ts.minus(BondholderEngagementHistory.RETENTION).toEpochMilli();
            try (var del = conn.prepareStatement(
                    "DELETE FROM bondholder_engagement "
                        + "WHERE companion_did = ? AND bondholder_did = ? AND event_ts < ?")) {
                del.setString(1, companionDid);
                del.setString(2, bondholderDid);
                del.setLong(3, cutoffMs);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to record engagement event for {} → {}: {}",
                companionDid, bondholderDid, e.getMessage());
        }
    }

    /**
     * Load every event for this companion, grouped by bondholder DID. Each
     * inner list is sorted oldest → newest. Callers feed these into
     * {@link BondholderEngagementHistory#loadEvents}.
     */
    public Map<String, List<EngagementEvent>> loadAllForCompanion(String companionDid) {
        var out = new LinkedHashMap<String, List<EngagementEvent>>();
        if (companionDid == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT bondholder_did, event_ts, substance, event_type "
                        + "FROM bondholder_engagement WHERE companion_did = ? "
                        + "ORDER BY bondholder_did, event_ts")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    var bondholderDid = rs.getString("bondholder_did");
                    var ts = Instant.ofEpochMilli(rs.getLong("event_ts"));
                    var substance = rs.getDouble("substance");
                    EventType type;
                    try {
                        type = EventType.valueOf(rs.getString("event_type"));
                    } catch (IllegalArgumentException invalid) {
                        // Unknown event type from a newer release — skip silently.
                        continue;
                    }
                    out.computeIfAbsent(bondholderDid, k -> new ArrayList<>())
                        .add(new EngagementEvent(ts, substance, type));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load engagement for {}: {}", companionDid, e.getMessage());
        }
        return out;
    }

    /**
     * Load any explicit-absence declarations still active (declared_until_at
     * is in the future). Returns per-bondholder {@link ExplicitAbsence} that
     * caller threads into {@link BondholderEngagementHistory#declareAbsence}.
     */
    public Map<String, ExplicitAbsence> loadActiveDeclaredAbsences(String companionDid,
                                                                    Instant now) {
        var out = new LinkedHashMap<String, ExplicitAbsence>();
        if (companionDid == null) return out;
        var t = now == null ? Instant.now() : now;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT bondholder_did, event_ts, declared_until_at "
                        + "FROM bondholder_engagement "
                        + "WHERE companion_did = ? AND event_type = ? "
                        + "AND declared_until_at IS NOT NULL AND declared_until_at > ? "
                        + "ORDER BY bondholder_did, event_ts DESC")) {
                stmt.setString(1, companionDid);
                stmt.setString(2, EventType.EXPLICIT_ABSENCE.name());
                stmt.setLong(3, t.toEpochMilli());
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    var bondholderDid = rs.getString("bondholder_did");
                    // Keep only the most-recent active declaration per bondholder
                    // (ORDER BY event_ts DESC + putIfAbsent).
                    if (out.containsKey(bondholderDid)) continue;
                    var declaredAt = Instant.ofEpochMilli(rs.getLong("event_ts"));
                    var declaredUntil = Instant.ofEpochMilli(rs.getLong("declared_until_at"));
                    out.put(bondholderDid, new ExplicitAbsence(declaredAt, declaredUntil));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load declared absences for {}: {}", companionDid, e.getMessage());
        }
        return out;
    }

    /** Test/diagnostic — row count for a companion. */
    public int count(String companionDid) {
        if (companionDid == null) return 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM bondholder_engagement WHERE companion_did = ?")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
