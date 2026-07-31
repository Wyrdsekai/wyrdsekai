package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;

/**
 * B1 — append-only ledger of agent-authored recipes.
 *
 * <p>{@code shape_recipe} (ITEM D) writes a YAML file, not a {@code recipe_queue}
 * row, so without this it would be invisible to the provenance instrument — yet
 * authoring a new capability is the <i>purest</i> self-actualization act. Each
 * successful author appends one row; {@link RecipeProvenanceReport} folds the
 * windowed count into {@code agentInitiated}.</p>
 *
 * <p>Same self-bootstrapping shape as {@link SqlRecipeQueue} /
 * {@code SqlRecipeParamOverrides}: one {@code jdbcUrl}, lazy
 * {@code CREATE TABLE IF NOT EXISTS}, every method opens its own connection.
 * All writes are best-effort (a logging failure must never block authoring).</p>
 */
public final class AuthoredRecipeLog {

    private static final Logger log = LoggerFactory.getLogger(AuthoredRecipeLog.class);

    private final String jdbcUrl;
    private volatile boolean migrated;

    public AuthoredRecipeLog(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Record one successful author. Best-effort. */
    public void record(String agentDid, String recipeName, Instant authoredAt) {
        var sql = "INSERT INTO authored_recipes_log (agent_did, recipe_name, authored_at)"
            + " VALUES (?,?,?)";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                if (agentDid == null) st.setNull(1, Types.VARCHAR);
                else st.setString(1, agentDid);
                st.setString(2, recipeName);
                st.setLong(3, authoredAt.toEpochMilli());
                st.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("AuthoredRecipeLog.record({}) failed: {}", recipeName, e.getMessage());
        }
    }

    /** Count authored recipes since {@code since}, optionally scoped to one agent. */
    public int countSince(Instant since, String agentDidOrNull) {
        var sql = "SELECT COUNT(*) FROM authored_recipes_log "
            + "WHERE authored_at >= ? "
            + (agentDidOrNull == null ? "" : "AND agent_did = ? ");
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setLong(1, since.toEpochMilli());
                if (agentDidOrNull != null) st.setString(2, agentDidOrNull);
                try (var rs = st.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            log.warn("AuthoredRecipeLog.countSince({}) failed: {}", since, e.getMessage());
            return 0;
        }
    }

    void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        try (var stmt = conn.createStatement()) {
            // No surrogate key — append-only, we only COUNT. Keeps the DDL
            // portable across SQLite + Postgres (no AUTOINCREMENT/SERIAL split).
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS authored_recipes_log(
                  agent_did   TEXT,
                  recipe_name TEXT NOT NULL,
                  authored_at INTEGER NOT NULL
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_authored_recipes_at"
                + " ON authored_recipes_log(authored_at)");
        }
        migrated = true;
    }
}
