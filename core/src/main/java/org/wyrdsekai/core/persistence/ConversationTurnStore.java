package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * #1037 — SQL persistence for per-turn conversation history
 * used by the {@code align-bondholder-voice} recipe's pair-mining step.
 *
 * <p>One row per turn observed by {@link
 * org.wyrdsekai.core.agent.ConversationTracker}: when the bondholder
 * speaks, write a {@code HEARD} row; when the companion replies, write
 * a {@code SPOKEN} row. The pair-mining step in
 * {@code RecipeBondholderRoutes.handlePairs} reads bondholder
 * ({@code HEARD}) turns from this table and pairs each with a neutral
 * baseline negative to feed repeng extraction.</p>
 *
 * <p>Schema: {@code conversation_turns(companion_did, bondholder_did,
 * turn_role, content, ts_ms, room_id)} — both sqlite + postgres.
 * Indexed on {@code (companion_did, bondholder_did, ts_ms DESC)} for
 * the lookback query.</p>
 *
 * <p>Configurable retention via
 * {@code bondholder.pairs.retention_days} (default 180 days). Older
 * rows pruned by {@link #pruneOlderThan(int)} — called on sleep-pass.</p>
 *
 * <p>All methods are fail-safe: SQL errors log a warn and return
 * defaults rather than throwing. Conversation tracking must never
 * break the chat path.</p>
 */
public final class ConversationTurnStore {

    private static final Logger log = LoggerFactory.getLogger(ConversationTurnStore.class);

    public static final String ROLE_SPOKEN = "SPOKEN";
    public static final String ROLE_HEARD = "HEARD";

    private final String jdbcUrl;

    public ConversationTurnStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void recordTurn(String companionDid, String bondholderDid, String role,
                           String content, String roomId) {
        if (companionDid == null || bondholderDid == null
                || role == null || content == null || content.isBlank()) {
            return;
        }
        long ts = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO conversation_turns"
                     + "(companion_did, bondholder_did, turn_role, content, "
                     + " ts_ms, room_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, companionDid);
            ps.setString(2, bondholderDid);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setLong(5, ts);
            if (roomId == null) ps.setNull(6, Types.VARCHAR);
            else                ps.setString(6, roomId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("recordTurn failed (companion={}, role={}): {}",
                companionDid, role, e.getMessage());
        }
    }

    /** Materialised turn row (for pair-mining). */
    public record Turn(long id, String role, String content, long tsMs, String roomId) {}

    /**
     * Bondholder's recent turns ({@code HEARD}) for the companion, filtered
     * to {@code minChars} length, ordered most-recent-first, capped at
     * {@code maxRows}. Used by the pair-mining step to build positives.
     */
    public List<Turn> recentBondholderTurns(String companionDid, String bondholderDid,
                                            int lookbackDays, int minChars, int maxRows) {
        long cutoff = System.currentTimeMillis()
            - (long) lookbackDays * 24L * 3600L * 1000L;
        var out = new ArrayList<Turn>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, turn_role, content, ts_ms, room_id "
                     + "FROM conversation_turns "
                     + "WHERE companion_did = ? AND bondholder_did = ? "
                     + "  AND turn_role = ? AND ts_ms >= ? "
                     + "  AND length(content) >= ? "
                     + "ORDER BY ts_ms DESC LIMIT ?")) {
            ps.setString(1, companionDid);
            ps.setString(2, bondholderDid);
            ps.setString(3, ROLE_HEARD);
            ps.setLong(4, cutoff);
            ps.setInt(5, minChars);
            ps.setInt(6, Math.max(1, maxRows));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Turn(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            log.warn("recentBondholderTurns failed (companion={}, bondholder={}): {}",
                companionDid, bondholderDid, e.getMessage());
        }
        return out;
    }

    /**
     * the bondholder's most recent turns across all
     * companions and both roles, newest first. Feeds the issue context
     * bundle: "what was being said when the user typed /issue".
     */
    public List<Turn> recentTurns(String bondholderDid, int maxRows) {
        var out = new ArrayList<Turn>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT id, turn_role, content, ts_ms, room_id "
                     + "FROM conversation_turns "
                     + "WHERE bondholder_did = ? "
                     + "ORDER BY ts_ms DESC LIMIT ?")) {
            ps.setString(1, bondholderDid);
            ps.setInt(2, Math.max(1, maxRows));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Turn(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            log.warn("recentTurns failed (bondholder={}): {}",
                bondholderDid, e.getMessage());
        }
        return out;
    }

    /** Count of all rows for a (companion, bondholder) pair. */
    public int turnCount(String companionDid, String bondholderDid) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(*) FROM conversation_turns "
                     + "WHERE companion_did = ? AND bondholder_did = ?")) {
            ps.setString(1, companionDid);
            ps.setString(2, bondholderDid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("turnCount failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Distinct bondholder-day count — proxy for "distinct conversational
     *  days," consumed by the eligibility check's {@code distinct_sessions}
     *  condition when bondholder_engagement is sparse. */
    public int distinctDays(String companionDid, String bondholderDid, int lookbackDays) {
        long cutoff = System.currentTimeMillis()
            - (long) lookbackDays * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT COUNT(DISTINCT ts_ms / (24*3600*1000)) "
                     + "FROM conversation_turns "
                     + "WHERE companion_did = ? AND bondholder_did = ? "
                     + "  AND ts_ms >= ?")) {
            ps.setString(1, companionDid);
            ps.setString(2, bondholderDid);
            ps.setLong(3, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("distinctDays failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Drop rows older than {@code retentionDays}; returns rows deleted. */
    public int pruneOlderThan(int retentionDays) {
        long cutoff = System.currentTimeMillis()
            - (long) retentionDays * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM conversation_turns WHERE ts_ms < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("pruneOlderThan failed: {}", e.getMessage());
            return 0;
        }
    }
}
