package org.wyrdsekai.core.agent.affordance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * the agent-owned override layer. Empty by default →
 * every tool resolves to its {@link AffordanceSeed}. {@code tune-tool-affordance}
 * writes here; the seed is just the cold-start prior.
 *
 * <p>This stores RELEVANCE only (servedNeeds / whenToUse / baseSalience) — never
 * permission (§4). Self-migrating, portable DDL (no AUTOINCREMENT), best-effort:
 * if jdbc is null/unreachable, resolution silently falls back to the seed.</p>
 */
public final class ToolAffordanceStore {

    private final String jdbcUrl;

    public ToolAffordanceStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        init();
    }

    private Connection conn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void init() {
        if (jdbcUrl == null) return;
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS tool_affordance (
                    tool_name     TEXT PRIMARY KEY,
                    served_needs  TEXT NOT NULL,
                    when_to_use   TEXT,
                    base_salience DOUBLE PRECISION NOT NULL DEFAULT 0,
                    updated_at    TEXT
                )""");
        } catch (Exception ignored) { /* best-effort */ }
    }

    /** Override if the agent has tuned this tool, else the principled seed. */
    public ToolAffordance resolve(String toolName, String domain) {
        if (jdbcUrl != null) {
            try (var c = conn();
                 var ps = c.prepareStatement(
                     "SELECT served_needs, when_to_use, base_salience FROM tool_affordance WHERE tool_name = ?")) {
                ps.setString(1, toolName);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new ToolAffordance(toolName, decode(rs.getString(1)),
                            rs.getString(2) == null ? "" : rs.getString(2), rs.getDouble(3));
                    }
                }
            } catch (Exception ignored) { /* fall through to seed */ }
        }
        return AffordanceSeed.forTool(toolName, domain);
    }

    /** Persist an agent-tuned affordance (relevance only). */
    public void upsert(ToolAffordance a, Instant at) {
        if (jdbcUrl == null || a == null) return;
        try (var c = conn();
             var ps = c.prepareStatement(
                 "INSERT INTO tool_affordance(tool_name, served_needs, when_to_use, base_salience, updated_at) "
                 + "VALUES (?,?,?,?,?) ON CONFLICT(tool_name) DO UPDATE SET "
                 + "served_needs=excluded.served_needs, when_to_use=excluded.when_to_use, "
                 + "base_salience=excluded.base_salience, updated_at=excluded.updated_at")) {
            ps.setString(1, a.toolName());
            ps.setString(2, encode(a.servedNeeds()));
            ps.setString(3, a.whenToUse());
            ps.setDouble(4, a.baseSalience());
            ps.setString(5, at == null ? null : at.toString());
            ps.executeUpdate();
        } catch (Exception ignored) { /* best-effort */ }
    }

    // Compact, portable encoding for servedNeeds: "need=weight;need=weight".
    static String encode(Map<String, Double> needs) {
        if (needs == null || needs.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var e : needs.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    static Map<String, Double> decode(String s) {
        var m = new LinkedHashMap<String, Double>();
        if (s == null || s.isBlank()) return m;
        for (var part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try { m.put(part.substring(0, eq), Double.parseDouble(part.substring(eq + 1))); }
            catch (NumberFormatException ignored) { }
        }
        return m;
    }
}
