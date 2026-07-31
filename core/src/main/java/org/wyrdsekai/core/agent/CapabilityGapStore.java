package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * persistent backing store for the per-agent
 * capability-gap counter used by {@link SkillUsageTracker}. Prior to this,
 * gaps lived only in a {@code ConcurrentHashMap} that vanished on every server
 * restart, which made the threshold-3 gate functionally unreachable.
 *
 * <p>The store is intentionally tiny: upsert (occurrence increments),
 * load-by-agent (rehydrate the tracker), and clearTriggered (drop rows that
 * have already produced a draft).
 *
 * <p>All errors are logged + swallowed; the tracker continues to function
 * out of its in-memory map even if persistence breaks. The point of the
 * store is durability across restarts, not a hard dependency.
 */
public final class CapabilityGapStore {

    private static final Logger log = LoggerFactory.getLogger(CapabilityGapStore.class);

    private final String jdbcUrl;

    public CapabilityGapStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Insert-or-increment for a (agentDid, description) pair.
     *
     * <p>Uses {@code ON CONFLICT … DO UPDATE} which works on both SQLite (since
     * 3.24) and Postgres (9.5+).
     */
    public void recordGap(String agentDid, String description) {
        if (agentDid == null || agentDid.isBlank()
            || description == null || description.isBlank()) return;
        var now = Instant.now().toEpochMilli();
        var sql = "INSERT INTO capability_gaps "
            + "(agent_did, description, first_detected_at, last_detected_at, occurrences) "
            + "VALUES (?, ?, ?, ?, 1) "
            + "ON CONFLICT (agent_did, description) DO UPDATE SET "
            + "  last_detected_at = excluded.last_detected_at, "
            + "  occurrences = capability_gaps.occurrences + 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setString(2, description);
            st.setLong(3, now);
            st.setLong(4, now);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("CapabilityGapStore.recordGap({}/{}) failed: {}",
                agentDid, description, e.getMessage());
        }
    }

    /**
     * Load all gaps for an agent. Returned map is keyed by description so the
     * caller can merge it into the in-memory map cleanly.
     */
    public Map<String, SkillUsageTracker.CapabilityGap> loadGaps(String agentDid) {
        var out = new LinkedHashMap<String, SkillUsageTracker.CapabilityGap>();
        if (agentDid == null || agentDid.isBlank()) return out;
        var sql = "SELECT description, first_detected_at, occurrences "
            + "FROM capability_gaps WHERE agent_did = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    var desc = rs.getString("description");
                    var firstAt = Instant.ofEpochMilli(rs.getLong("first_detected_at"));
                    var count = rs.getInt("occurrences");
                    out.put(desc, new SkillUsageTracker.CapabilityGap(desc, firstAt, count));
                }
            }
        } catch (SQLException e) {
            log.warn("CapabilityGapStore.loadGaps({}) failed: {}", agentDid, e.getMessage());
        }
        return out;
    }

    /**
     * Delete rows that have already crossed the threshold — called after the
     * tracker hands off triggered gaps to the SkillProposer. Mirrors the
     * tracker's {@code clearTriggeredGaps} so memory + storage stay aligned.
     */
    public void clearTriggered(String agentDid, int threshold) {
        if (agentDid == null || agentDid.isBlank()) return;
        var sql = "DELETE FROM capability_gaps WHERE agent_did = ? AND occurrences >= ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setInt(2, threshold);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("CapabilityGapStore.clearTriggered({}/{}) failed: {}",
                agentDid, threshold, e.getMessage());
        }
    }

    /** Test seam — drop every row for an agent. */
    public void deleteAll(String agentDid) {
        if (agentDid == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "DELETE FROM capability_gaps WHERE agent_did = ?")) {
            st.setString(1, agentDid);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("CapabilityGapStore.deleteAll({}) failed: {}",
                agentDid, e.getMessage());
        }
    }
}
