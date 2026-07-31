package org.wyrdsekai.core.agent.affordance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * the instrument. One row per surfacing pass:
 * {@code (agent_did, dominant_need, want_verb, surfaced top-K, emitted_tool)}. This
 * is what lets the agent SEE its own selection quality — without it the
 * {@code tune-tool-affordance} recipe has nothing to mine. Best-effort, portable DDL.
 */
public final class ToolAffordanceLog {

    /** One surfacing pass. {@code surfaced} = the names offered (post-rank top-K);
     *  {@code emittedTool} = what the model actually called (null until known). */
    public record Row(String agentDid, Instant at, String dominantNeed,
                      String wantVerb, List<String> surfaced, String emittedTool) {}

    private final String jdbcUrl;

    public ToolAffordanceLog(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        init();
    }

    private Connection conn() throws SQLException { return DriverManager.getConnection(jdbcUrl); }

    private void init() {
        if (jdbcUrl == null) return;
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tool_affordance_log (
                    agent_did     TEXT,
                    at            TEXT,
                    dominant_need TEXT,
                    want_verb     TEXT,
                    surfaced      TEXT,
                    emitted_tool  TEXT
                )""");
        } catch (Exception ignored) { }
    }

    public void record(String agentDid, Instant at, String dominantNeed, String wantVerb,
                       List<String> surfaced, String emittedTool) {
        if (jdbcUrl == null) return;
        try (var c = conn();
             var ps = c.prepareStatement(
                 "INSERT INTO tool_affordance_log(agent_did, at, dominant_need, want_verb, surfaced, emitted_tool) "
                 + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, agentDid);
            ps.setString(2, at == null ? Instant.now().toString() : at.toString());
            ps.setString(3, dominantNeed);
            ps.setString(4, wantVerb);
            ps.setString(5, surfaced == null ? "" : String.join(",", surfaced));
            ps.setString(6, emittedTool);
            ps.executeUpdate();
        } catch (Exception ignored) { }
    }

    /** Recent passes for the report / tuner, newest first. */
    public List<Row> recent(String agentDidOrNull, int limit) {
        var out = new ArrayList<Row>();
        if (jdbcUrl == null) return out;
        var sql = "SELECT agent_did, at, dominant_need, want_verb, surfaced, emitted_tool FROM tool_affordance_log "
            + (agentDidOrNull != null ? "WHERE agent_did = ? " : "")
            + "ORDER BY at DESC LIMIT " + Math.max(1, limit);
        try (var c = conn(); var ps = c.prepareStatement(sql)) {
            if (agentDidOrNull != null) ps.setString(1, agentDidOrNull);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    var s = rs.getString(5);
                    var surfaced = s == null || s.isBlank() ? List.<String>of() : List.of(s.split(","));
                    Instant at = null;
                    try { at = rs.getString(2) == null ? null : Instant.parse(rs.getString(2)); }
                    catch (Exception ignored) { }
                    out.add(new Row(rs.getString(1), at, rs.getString(3), rs.getString(4),
                        surfaced, rs.getString(6)));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
