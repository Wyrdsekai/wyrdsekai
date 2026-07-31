package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #1142 — the recipe-param override store: the "config surface"
 * that lets a steward or the {@code tune-recipe-params} tuner change a recipe
 * param default for future runs. Applied at {@link RecipeService#run} UNDER
 * caller-supplied params (an explicit per-run param still wins).
 *
 * <p>Backed by {@code recipe_param_overrides}. {@code agentDid == null} (stored
 * as {@code ''}) is a household-wide default; a non-null DID is per-companion.
 * The {@link RecipeService} merge prefers a per-agent override over the
 * household-wide one. Values are string-encoded — recipe params coerce on read.</p>
 *
 * <p>Safety lives in {@link RecipeParamTuner}, not here: this store will persist
 * any (recipe, param, value). The tuner — and the REST apply endpoint — refuse
 * to write a param referenced by a PERMANENT welfare gate, or one outside its
 * declared [min,max].</p>
 */
public final class SqlRecipeParamOverrides {

    private static final Logger log = LoggerFactory.getLogger(SqlRecipeParamOverrides.class);

    private final String jdbcUrl;

    public SqlRecipeParamOverrides(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    private static String agentKey(String agentDid) {
        return agentDid == null ? "" : agentDid;
    }

    /**
     * Create the table if it isn't there yet. Mirrors {@link SqlRecipeQueue}'s
     * self-bootstrapping pattern so a fresh DB (or a test temp file) works
     * without a separate migration pass. {@code CREATE ... IF NOT EXISTS} is a
     * no-op once the boot schema has run. Portable DDL — sqlite + postgres
     * both accept it.
     */
    private void ensureMigrated(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS recipe_param_overrides ("
                    + " recipe_id   TEXT NOT NULL,"
                    + " agent_did   TEXT NOT NULL DEFAULT '',"
                    + " param_name  TEXT NOT NULL,"
                    + " value       TEXT NOT NULL,"
                    + " updated_by  TEXT,"
                    + " updated_at  BIGINT,"
                    + " PRIMARY KEY (recipe_id, agent_did, param_name))")) {
            ps.executeUpdate();
        }
    }

    /** Upsert an override. {@code agentDid == null} → household-wide. */
    public void upsert(String recipeId, String agentDid, String paramName,
                       String value, String updatedBy) {
        if (recipeId == null || paramName == null || value == null) return;
        String sql = "INSERT INTO recipe_param_overrides"
            + "(recipe_id, agent_did, param_name, value, updated_by, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT(recipe_id, agent_did, param_name) DO UPDATE SET "
            + "value = excluded.value, updated_by = excluded.updated_by, "
            + "updated_at = excluded.updated_at";
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, recipeId);
                ps.setString(2, agentKey(agentDid));
                ps.setString(3, paramName);
                ps.setString(4, value);
                ps.setString(5, updatedBy);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("recipe param override upsert failed ({}/{}): {}",
                recipeId, paramName, e.getMessage());
        }
    }

    /** Remove one override (revert to manifest default). */
    public void clear(String recipeId, String agentDid, String paramName) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                 "DELETE FROM recipe_param_overrides "
                     + "WHERE recipe_id = ? AND agent_did = ? AND param_name = ?")) {
                ps.setString(1, recipeId);
                ps.setString(2, agentKey(agentDid));
                ps.setString(3, paramName);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("recipe param override clear failed ({}/{}): {}",
                recipeId, paramName, e.getMessage());
        }
    }

    /**
     * Effective overrides for a (recipe, agent): the household-wide defaults
     * overlaid by the per-agent overrides (per-agent wins). Empty on any error.
     */
    public Map<String, String> effectiveFor(String recipeId, String agentDid) {
        var out = new LinkedHashMap<String, String>();
        loadInto(out, recipeId, "");                 // household-wide first
        if (agentDid != null && !agentDid.isEmpty()) {
            loadInto(out, recipeId, agentDid);       // per-agent overlay wins
        }
        return out;
    }

    private void loadInto(Map<String, String> out, String recipeId, String agentKey) {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                 "SELECT param_name, value FROM recipe_param_overrides "
                     + "WHERE recipe_id = ? AND agent_did = ?")) {
                ps.setString(1, recipeId);
                ps.setString(2, agentKey);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("recipe param override read failed ({}): {}", recipeId, e.getMessage());
        }
    }
}
