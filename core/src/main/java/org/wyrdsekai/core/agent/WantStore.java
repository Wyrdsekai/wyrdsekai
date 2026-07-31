package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * persistent backing for {@link Want}s.
 *
 * <p>Wants live in the {@code wants} table. Per-agent indexed. The store
 * supports the lifecycle described in the spec: ACTIVE → DEEPENED → SATISFIED,
 * with ABANDONED / RECONCILED as alternate terminal states.
 *
 * <p>All errors are logged + swallowed (mirrors {@link CapabilityGapStore}).
 * The OODA loop continues with its in-memory want set if persistence breaks;
 * the store provides durability across restarts, not a hard dependency.
 */
public final class WantStore {

    private static final Logger log = LoggerFactory.getLogger(WantStore.class);

    private final String jdbcUrl;

    public WantStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Insert a new want, or replace an existing one with the same {@code wantId}. */
    public void upsert(Want want) {
        if (want == null || want.wantId() == null || want.agentDid() == null) return;
        var sql = "INSERT INTO wants "
            + "(want_id, agent_did, text, drive_resonance, felt_weight, status, "
            + " born_at, last_visited_at, visit_count, satisfied_at, satisfaction_note, parent_want_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (want_id) DO UPDATE SET "
            + "  text = excluded.text, "
            + "  drive_resonance = excluded.drive_resonance, "
            + "  felt_weight = excluded.felt_weight, "
            + "  status = excluded.status, "
            + "  last_visited_at = excluded.last_visited_at, "
            + "  visit_count = excluded.visit_count, "
            + "  satisfied_at = excluded.satisfied_at, "
            + "  satisfaction_note = excluded.satisfaction_note, "
            + "  parent_want_id = excluded.parent_want_id";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, want.wantId());
            st.setString(2, want.agentDid());
            st.setString(3, want.text());
            st.setString(4, want.driveResonance());
            st.setDouble(5, want.feltWeight());
            st.setString(6, want.status().name());
            st.setLong(7, want.bornAt().toEpochMilli());
            st.setLong(8, want.lastVisitedAt().toEpochMilli());
            st.setInt(9, want.visitCount());
            if (want.satisfiedAt() != null) {
                st.setLong(10, want.satisfiedAt().toEpochMilli());
            } else {
                st.setNull(10, Types.BIGINT);
            }
            st.setString(11, want.satisfactionNote());
            st.setString(12, want.parentWantId());
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("WantStore.upsert({}) failed: {}", want.wantId(), e.getMessage());
        }
    }

    /** Fetch a single want by id, or empty if not found. */
    public Optional<Want> get(String wantId) {
        if (wantId == null) return Optional.empty();
        var sql = "SELECT * FROM wants WHERE want_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, wantId);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
            }
        } catch (SQLException e) {
            log.warn("WantStore.get({}) failed: {}", wantId, e.getMessage());
        }
        return Optional.empty();
    }

    /** All wants for an agent in a given status, ordered by most-recently-visited. */
    public List<Want> byAgentAndStatus(String agentDid, Want.Status status) {
        var out = new ArrayList<Want>();
        if (agentDid == null || status == null) return out;
        var sql = "SELECT * FROM wants WHERE agent_did = ? AND status = ? "
            + "ORDER BY last_visited_at DESC";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setString(2, status.name());
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            log.warn("WantStore.byAgentAndStatus({}, {}) failed: {}",
                agentDid, status, e.getMessage());
        }
        return out;
    }

    /** All live wants (ACTIVE + DEEPENED) for an agent — what the OODA tick loads. */
    public List<Want> loadLive(String agentDid) {
        var out = new ArrayList<Want>();
        if (agentDid == null) return out;
        var sql = "SELECT * FROM wants WHERE agent_did = ? "
            + "AND status IN ('ACTIVE', 'DEEPENED') "
            + "ORDER BY last_visited_at DESC";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
        } catch (SQLException e) {
            log.warn("WantStore.loadLive({}) failed: {}", agentDid, e.getMessage());
        }
        return out;
    }

    /** Count live wants for an agent. Used by the cheap pre-gate. */
    public int countLive(String agentDid) {
        if (agentDid == null) return 0;
        var sql = "SELECT COUNT(*) FROM wants WHERE agent_did = ? "
            + "AND status IN ('ACTIVE', 'DEEPENED')";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("WantStore.countLive({}) failed: {}", agentDid, e.getMessage());
        }
        return 0;
    }

    /** Delete a want outright — mostly for tests + GC. */
    public boolean delete(String wantId) {
        if (wantId == null) return false;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("DELETE FROM wants WHERE want_id = ?")) {
            st.setString(1, wantId);
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("WantStore.delete({}) failed: {}", wantId, e.getMessage());
            return false;
        }
    }

    /** Test seam — drop every row for an agent. */
    public void deleteAll(String agentDid) {
        if (agentDid == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("DELETE FROM wants WHERE agent_did = ?")) {
            st.setString(1, agentDid);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("WantStore.deleteAll({}) failed: {}", agentDid, e.getMessage());
        }
    }

    private static Want fromRow(ResultSet rs) throws SQLException {
        var satisfiedAtMs = rs.getLong("satisfied_at");
        Instant satisfiedAt = rs.wasNull() ? null : Instant.ofEpochMilli(satisfiedAtMs);
        return new Want(
            rs.getString("want_id"),
            rs.getString("agent_did"),
            rs.getString("text"),
            rs.getString("drive_resonance"),
            rs.getDouble("felt_weight"),
            Want.Status.valueOf(rs.getString("status")),
            Instant.ofEpochMilli(rs.getLong("born_at")),
            Instant.ofEpochMilli(rs.getLong("last_visited_at")),
            rs.getInt("visit_count"),
            satisfiedAt,
            rs.getString("satisfaction_note"),
            rs.getString("parent_want_id"));
    }
}
