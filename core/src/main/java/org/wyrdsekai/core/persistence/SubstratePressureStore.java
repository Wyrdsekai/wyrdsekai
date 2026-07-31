package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * #1036 — SQL persistence for substrate-pressure samples.
 *
 * <p>One row per classifier dispatch from
 * {@link org.wyrdsekai.core.agent.CompanionActor}'s
 * {@code computeAffectPresent}: the SUBSTRATE_PRESENT head's
 * confidence (canonicalised to P(substrate=true)) is recorded with a
 * timestamp + subject DID. The rolling-mean aggregator backs the
 * {@code align-bondholder-voice} recipe's welfare gate (#1028 —
 * {@code substrate_pressure_30d}); without this store the gate was
 * permanently disarmed at 0.0.</p>
 *
 * <p>Schema: {@code substrate_pressure_samples} (sqlite + postgres).
 * Indexed on {@code (did, ts_ms DESC)} for the aggregator's
 * cutoff-based scan.</p>
 *
 * <p>Configurable via {@link org.wyrdsekai.core.config.WyrdConfig}:</p>
 * <ul>
 *   <li>{@code substrate.pressure.window_days} (default 30) — aggregation window.</li>
 *   <li>{@code substrate.pressure.aggregation} (default {@code mean}) —
 *       {@code mean} or {@code p95}.</li>
 *   <li>{@code substrate.pressure.retention_days} (default 90) — older
 *       rows pruned by {@link #pruneOlderThan(int)}.</li>
 * </ul>
 *
 * <p>All methods are <b>fail-safe</b>: SQL exceptions log a warn and
 * return defaults (0.0 / 0) rather than throwing. The substrate-pressure
 * gate must never break the recipe path on infrastructure issues — the
 * welfare-conservative default is "no pressure detected" (0.0).</p>
 */
public final class SubstratePressureStore {

    private static final Logger log = LoggerFactory.getLogger(SubstratePressureStore.class);

    public static final String DEFAULT_HEAD = "substrate_present";

    private final String jdbcUrl;

    public SubstratePressureStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Record one sample. {@code score} is canonicalised P(substrate=true),
     * so a {@code "non_substrate"} classification with confidence 0.9 must
     * be stored as 0.1 (caller's responsibility to flip).
     *
     * <p>Bondholder-default form — equivalent to
     * {@link #recordSample(String, String, String, double)} with
     * {@code otherDid = null}.</p>
     */
    public void recordSample(String did, String head, double score) {
        recordSample(did, null, head, score);
    }

    /**
     * Arc 3 — record one sample keyed by relationship.
     * {@code otherDid = null} preserves legacy bondholder-only semantics.
     * Peer/familiar relationships pass their counterpart DID so per-bond
     * pressure can be aggregated separately.
     */
    public void recordSample(String did, String otherDid, String head, double score) {
        if (did == null || head == null) return;
        long ts = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO substrate_pressure_samples"
                     + "(did, other_did, ts_ms, head, score) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, did);
            if (otherDid == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, otherDid);
            }
            ps.setLong(3, ts);
            ps.setString(4, head);
            ps.setDouble(5, Math.max(0.0, Math.min(1.0, score)));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordSample failed (did={}, otherDid={}): {}",
                did, otherDid, e.getMessage());
        }
    }

    /** Rolling-mean over the last {@code windowDays}. Returns 0.0 on error /
     *  empty range (welfare-permissive default).
     *
     *  <p>Bondholder-default: aggregates across all rows for {@code did} —
     *  in v0.1 this matched bondholder-only because that was the only
     *  partition. Post Arc 3 this still collapses to "everything for did"
     *  which means bondholder-only rows + any peer-bond rows; for the
     *  recipe gate that read it before, the behaviour is unchanged because
     *  the only sample-emitter was bondholder. New peer-bond emitters call
     *  {@link #aggregateMean(String, String, int)} explicitly.</p>
     */
    public double aggregateMean(String did, int windowDays) {
        long cutoff = System.currentTimeMillis()
            - (long) windowDays * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT AVG(score) FROM substrate_pressure_samples "
                     + "WHERE did = ? AND ts_ms >= ? AND head = ?")) {
            ps.setString(1, did);
            ps.setLong(2, cutoff);
            ps.setString(3, DEFAULT_HEAD);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    return v == null ? 0.0 : ((Number) v).doubleValue();
                }
            }
        } catch (SQLException e) {
            log.warn("aggregateMean failed (did={}): {}", did, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Arc 3 — rolling-mean for one specific relationship.
     * Pass {@code otherDid = null} to read only legacy bondholder-default
     * rows (where other_did IS NULL); pass a non-null DID to read only that
     * peer/familiar pair's samples.
     */
    public double aggregateMean(String did, String otherDid, int windowDays) {
        long cutoff = System.currentTimeMillis()
            - (long) windowDays * 24L * 3600L * 1000L;
        var sql = "SELECT AVG(score) FROM substrate_pressure_samples "
            + "WHERE did = ? AND ts_ms >= ? AND head = ? AND "
            + (otherDid == null ? "other_did IS NULL" : "other_did = ?");
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, did);
            ps.setLong(2, cutoff);
            ps.setString(3, DEFAULT_HEAD);
            if (otherDid != null) ps.setString(4, otherDid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    return v == null ? 0.0 : ((Number) v).doubleValue();
                }
            }
        } catch (SQLException e) {
            log.warn("aggregateMean failed (did={}, otherDid={}): {}",
                did, otherDid, e.getMessage());
        }
        return 0.0;
    }

    /**
     * 95th-percentile substrate score over the last {@code windowDays}.
     * SQLite has no native percentile_cont; emulate via NTILE(20) ordering.
     * Returns the floor of the 95th-percentile bucket — close enough for
     * a welfare gate.
     */
    public double aggregateP95(String did, int windowDays) {
        long cutoff = System.currentTimeMillis()
            - (long) windowDays * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement count = conn.prepareStatement(
                 "SELECT COUNT(*) FROM substrate_pressure_samples "
                     + "WHERE did = ? AND ts_ms >= ? AND head = ?")) {
            count.setString(1, did);
            count.setLong(2, cutoff);
            count.setString(3, DEFAULT_HEAD);
            int n;
            try (ResultSet rs = count.executeQuery()) {
                n = rs.next() ? rs.getInt(1) : 0;
            }
            if (n == 0) return 0.0;
            // 95th percentile = score at position floor(0.95 * n) when ordered ASC.
            int offset = Math.max(0, (int) Math.floor(0.95 * n) - 1);
            try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT score FROM substrate_pressure_samples "
                     + "WHERE did = ? AND ts_ms >= ? AND head = ? "
                     + "ORDER BY score ASC LIMIT 1 OFFSET ?")) {
                ps.setString(1, did);
                ps.setLong(2, cutoff);
                ps.setString(3, DEFAULT_HEAD);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getDouble(1) : 0.0;
                }
            }
        } catch (SQLException e) {
            log.warn("aggregateP95 failed (did={}): {}", did, e.getMessage());
        }
        return 0.0;
    }

    /**
     * Count samples for {@code did} under a specific {@code head} since
     * {@code sinceMs} (epoch millis). Unlike {@link #aggregateMean(String, int)}
     * this does NOT hard-code {@link #DEFAULT_HEAD} — it's the reader for
     * non-default heads like {@code directed_harm}, where what matters is
     * "how many landed in this window", not their mean. Fail-safe → 0.
     *
     * <p>Used by the Phase-1 directed-harm boundary: the companion only
     * states a boundary once a <em>sustained</em> count (not a single hit)
     * accrues in a rolling window, so one bad moment rolls off.</p>
     */
    public int recentCount(String did, String head, long sinceMs) {
        if (did == null || head == null) return 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM substrate_pressure_samples "
                     + "WHERE did = ? AND head = ? AND ts_ms >= ?")) {
            ps.setString(1, did);
            ps.setString(2, head);
            ps.setLong(3, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("recentCount failed (did={}, head={}): {}", did, head, e.getMessage());
            return 0;
        }
    }

    /** Drop rows older than {@code retentionDays}; returns rows deleted. */
    public int pruneOlderThan(int retentionDays) {
        long cutoff = System.currentTimeMillis()
            - (long) retentionDays * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM substrate_pressure_samples WHERE ts_ms < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("pruneOlderThan failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Count of rows for a DID — used by tests + dashboards. */
    public int sampleCount(String did) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM substrate_pressure_samples WHERE did = ?")) {
            ps.setString(1, did);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("sampleCount failed: {}", e.getMessage());
            return 0;
        }
    }
}
