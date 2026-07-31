package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed persistence for {@link SkillDraft}.
 *
 * <p>Singleton wired in {@code Main.java} after the database is
 * initialized. Reads/writes through the existing JDBC URL — same
 * pattern as {@code ForeignIdentityStore} / {@code ChannelStateStore}.</p>
 */
public final class SkillDraftStore {

    private static final Logger log = LoggerFactory.getLogger(SkillDraftStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile SkillDraftStore INSTANCE;

    public static SkillDraftStore get() { return INSTANCE; }

    public static void setInstance(SkillDraftStore store) { INSTANCE = store; }

    public static void resetForTests() { INSTANCE = null; }

    private final String jdbcUrl;

    public SkillDraftStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Insert a new draft. Existing draft_id silently replaces. */
    public void upsert(SkillDraft draft) {
        var sql = "INSERT INTO skill_drafts ("
            + " draft_id, agent_did, status, name, description, rationale,"
            + " code, runtime, closes_gaps_json, replaces, proposed_at,"
            + " proposed_by_model, decided_at, decision_note, harness_json)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT(draft_id) DO UPDATE SET"
            + "   status        = excluded.status,"
            + "   decided_at    = excluded.decided_at,"
            + "   decision_note = excluded.decision_note,"
            // harness is authored off the hot path AFTER the draft is first persisted,
            // so an upsert must be able to attach/refresh it (but never null it out).
            + "   harness_json  = COALESCE(excluded.harness_json, skill_drafts.harness_json)";

        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            bindDraft(st, draft);
            st.executeUpdate();
        } catch (SQLException e) {
            log.warn("SkillDraftStore.upsert({}) failed: {}",
                draft.draftId(), e.getMessage());
        }
    }

    /** Fetch one draft by id. */
    public Optional<SkillDraft> get(String draftId) {
        var sql = "SELECT * FROM skill_drafts WHERE draft_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, draftId);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(read(rs));
            }
        } catch (SQLException e) {
            log.warn("SkillDraftStore.get({}) failed: {}", draftId, e.getMessage());
        }
        return Optional.empty();
    }

    /** All drafts for an agent in a given status, newest first. */
    public List<SkillDraft> byAgentAndStatus(String agentDid, SkillDraft.Status status) {
        var sql = "SELECT * FROM skill_drafts "
            + "WHERE agent_did = ? AND status = ? "
            + "ORDER BY proposed_at DESC";
        var out = new ArrayList<SkillDraft>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            st.setString(2, status.name());
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException e) {
            log.warn("SkillDraftStore.byAgentAndStatus({}, {}) failed: {}",
                agentDid, status, e.getMessage());
        }
        return out;
    }

    /** Count of drafts an agent has in PENDING. Used by Workshop pinboard. */
    public int countPending(String agentDid) {
        var sql = "SELECT COUNT(*) FROM skill_drafts "
            + "WHERE agent_did = ? AND status = 'PENDING'";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, agentDid);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("SkillDraftStore.countPending({}) failed: {}",
                agentDid, e.getMessage());
        }
        return 0;
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private static void bindDraft(PreparedStatement st, SkillDraft d) throws SQLException {
        st.setString(1, d.draftId());
        st.setString(2, d.agentDid());
        st.setString(3, d.status().name());
        st.setString(4, d.name());
        st.setString(5, d.description());
        st.setString(6, d.rationale());
        st.setString(7, d.code());
        st.setString(8, d.runtime());
        st.setString(9, encodeGaps(d.closesGaps()));
        st.setString(10, d.replaces());
        st.setLong(11, d.proposedAt() != null
            ? d.proposedAt().toEpochMilli() : System.currentTimeMillis());
        st.setString(12, d.proposedByModel());
        if (d.decidedAt() != null) st.setLong(13, d.decidedAt().toEpochMilli());
        else st.setObject(13, null);
        st.setString(14, d.decisionNote());
        st.setString(15, encodeHarness(d.verificationHarness()));
    }

    private static SkillDraft read(ResultSet rs) throws SQLException {
        var decidedMs = rs.getLong("decided_at");
        var decided = rs.wasNull() ? null : Instant.ofEpochMilli(decidedMs);
        return new SkillDraft(
            rs.getString("draft_id"),
            rs.getString("agent_did"),
            SkillDraft.Status.valueOf(rs.getString("status")),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("rationale"),
            rs.getString("code"),
            rs.getString("runtime"),
            decodeGaps(rs.getString("closes_gaps_json")),
            rs.getString("replaces"),
            Instant.ofEpochMilli(rs.getLong("proposed_at")),
            rs.getString("proposed_by_model"),
            decided,
            rs.getString("decision_note"),
            SkillDraft.defaultEmbodimentShim(),
            decodeHarness(rs.getString("harness_json")));
    }

    private static String encodeGaps(List<String> gaps) {
        if (gaps == null || gaps.isEmpty()) return null;
        try { return MAPPER.writeValueAsString(gaps); }
        catch (Exception e) { return null; }
    }

    private static List<String> decodeGaps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** — serialize the frozen harness so it travels with the draft. */
    private static String encodeHarness(AnchorHarness harness) {
        if (harness == null) return null;
        try { return MAPPER.writeValueAsString(harness); }
        catch (Exception e) {
            log.warn("SkillDraftStore: could not encode harness: {}", e.getMessage());
            return null;
        }
    }

    private static AnchorHarness decodeHarness(String json) {
        if (json == null || json.isBlank()) return null;
        try { return MAPPER.readValue(json, AnchorHarness.class); }
        catch (Exception e) {
            log.warn("SkillDraftStore: could not decode harness: {}", e.getMessage());
            return null;
        }
    }
}
