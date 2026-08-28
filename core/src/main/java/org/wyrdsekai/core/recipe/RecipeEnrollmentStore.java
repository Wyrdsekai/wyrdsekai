package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Track-C C4 — JDBC store for {@link RecipeEnrollment}.
 *
 * <p>Same shape as {@link SqlRecipeQueue}: one {@code jdbcUrl} field,
 * every public method opens its own connection, lazy
 * {@link #ensureMigrated(Connection)} on first use. C9 ship-default
 * enrollment writes one row per (companion, retrain-classifier-head)
 * pair at first boot; the cron + gap triggers consult this store on
 * every tick.</p>
 */
public final class RecipeEnrollmentStore {

    private static final Logger log = LoggerFactory.getLogger(RecipeEnrollmentStore.class);

    private final String jdbcUrl;
    private volatile boolean migrated;

    public RecipeEnrollmentStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // -- writes ---------------------------------------------------------

    /** Upsert by (recipe_id, agent_did). */
    public void upsert(RecipeEnrollment e) {
        // agent_did_key is written EXPLICITLY, not left to its '' default plus the
        // sync trigger. With the default, the INSERT never conflicts — '' is unique —
        // so the row lands and the AFTER INSERT trigger then rewrites the key to the
        // DID, colliding with the row already there. The conflict is manufactured after
        // the insert succeeds, which is too late for ON CONFLICT to see it, so the whole
        // statement aborts with SQLITE_CONSTRAINT_PRIMARYKEY and the upsert silently
        // does nothing. Live on a household node every boot since the table was created
        // (found 2026-08-18): four warnings a start, and no enrollment could ever be
        // updated in place — gap keys added by a later release would never reach an
        // existing install. Setting the key up front makes the conflict happen at insert
        // time, where the ON CONFLICT clause can handle it.
        var sql = "INSERT INTO recipe_enrollments ("
            + " recipe_id, agent_did, agent_did_key, cadence_tier,"
            + " consecutive_successes, enrolled_at, enabled, gap_keys)"
            + " VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT(recipe_id, agent_did_key) DO UPDATE SET"
            + "   cadence_tier          = excluded.cadence_tier,"
            + "   consecutive_successes = excluded.consecutive_successes,"
            + "   enabled               = excluded.enabled,"
            + "   gap_keys              = excluded.gap_keys";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, e.recipeId());
                if (e.agentDid() == null) st.setNull(2, Types.VARCHAR);
                else st.setString(2, e.agentDid());
                st.setString(3, e.agentDid() == null ? "" : e.agentDid());
                st.setString(4, e.cadenceTier().name());
                st.setInt(5, e.consecutiveSuccesses());
                st.setLong(6, e.enrolledAt().toEpochMilli());
                st.setInt(7, e.enabled() ? 1 : 0);
                st.setString(8, String.join(",", e.gapKeys()));
                st.executeUpdate();
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.upsert({},{}) failed: {}",
                e.recipeId(), e.agentDid(), ex.getMessage());
        }
    }

    public boolean setEnabled(String recipeId, String agentDid, boolean enabled) {
        var sql = "UPDATE recipe_enrollments SET enabled = ? "
            + "WHERE recipe_id = ? AND agent_did_key = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setInt(1, enabled ? 1 : 0);
                st.setString(2, recipeId);
                st.setString(3, agentDid == null ? "" : agentDid);
                return st.executeUpdate() > 0;
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.setEnabled({},{}) failed: {}",
                recipeId, agentDid, ex.getMessage());
            return false;
        }
    }

    // -- reads ----------------------------------------------------------

    public Optional<RecipeEnrollment> find(String recipeId, String agentDid) {
        var sql = "SELECT * FROM recipe_enrollments "
            + "WHERE recipe_id = ? AND agent_did_key = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, recipeId);
                st.setString(2, agentDid == null ? "" : agentDid);
                try (var rs = st.executeQuery()) {
                    if (rs.next()) return Optional.of(read(rs));
                }
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.find({},{}) failed: {}",
                recipeId, agentDid, ex.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Every row in the table, regardless of {@code enabled}. Used by the
     * Track-C C6 {@code wyrd recipes list} CLI + C7 Study furnishing
     * so stewards see paused recipes too — {@link #listEnabled} hides
     * them.
     */
    public List<RecipeEnrollment> listAll() {
        var sql = "SELECT * FROM recipe_enrollments "
            + "ORDER BY recipe_id, agent_did_key";
        var out = new ArrayList<RecipeEnrollment>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql);
                 var rs = st.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.listAll failed: {}", ex.getMessage());
        }
        return out;
    }

    /** All enabled enrollments — input for the cron trigger pass. */
    public List<RecipeEnrollment> listEnabled() {
        var sql = "SELECT * FROM recipe_enrollments WHERE enabled = 1 "
            + "ORDER BY recipe_id, agent_did_key";
        var out = new ArrayList<RecipeEnrollment>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql);
                 var rs = st.executeQuery()) {
                while (rs.next()) out.add(read(rs));
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.listEnabled failed: {}", ex.getMessage());
        }
        return out;
    }

    /** Enrollments matching a gap key — input for the gap trigger. */
    public List<RecipeEnrollment> listByGapKey(String gapKey) {
        if (gapKey == null || gapKey.isBlank()) return List.of();
        // SQLite has no array contains; gap_keys is a comma-joined string.
        // Match with leading/trailing comma to avoid prefix collisions
        // ("foo" matching "foobar").
        var sql = "SELECT * FROM recipe_enrollments WHERE enabled = 1 AND "
            + "(',' || gap_keys || ',') LIKE ?";
        var out = new ArrayList<RecipeEnrollment>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, "%," + gapKey + ",%");
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException ex) {
            log.warn("RecipeEnrollmentStore.listByGapKey({}) failed: {}",
                gapKey, ex.getMessage());
        }
        return out;
    }

    // -- migration ------------------------------------------------------

    void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "recipe_enrollments")) {
            log.info("Creating recipe_enrollments table (Track-C C4 migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS recipe_enrollments(
                      recipe_id              TEXT NOT NULL,
                      agent_did              TEXT,
                      -- composite-key fallback: agent_did is nullable but SQL
                      -- ON CONFLICT can't see NULL=NULL, so we mirror into a
                      -- non-null key column for the PK.
                      agent_did_key          TEXT NOT NULL DEFAULT '',
                      cadence_tier           TEXT NOT NULL DEFAULT 'WARMUP',
                      consecutive_successes  INTEGER NOT NULL DEFAULT 0,
                      enrolled_at            INTEGER NOT NULL,
                      enabled                INTEGER NOT NULL DEFAULT 1,
                      gap_keys               TEXT NOT NULL DEFAULT '',
                      PRIMARY KEY (recipe_id, agent_did_key)
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_recipe_enrollments_enabled "
                    + "ON recipe_enrollments(enabled)");
                // SQLite trigger to keep agent_did_key in sync with agent_did
                // (so callers can ignore the synthetic column on writes).
                stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_recipe_enrollments_ai_key
                    AFTER INSERT ON recipe_enrollments
                    BEGIN
                      UPDATE recipe_enrollments
                      SET agent_did_key = COALESCE(NEW.agent_did, '')
                      WHERE rowid = NEW.rowid AND agent_did_key = '';
                    END
                    """);
                stmt.execute("""
                    CREATE TRIGGER IF NOT EXISTS trg_recipe_enrollments_au_key
                    AFTER UPDATE OF agent_did ON recipe_enrollments
                    BEGIN
                      UPDATE recipe_enrollments
                      SET agent_did_key = COALESCE(NEW.agent_did, '')
                      WHERE rowid = NEW.rowid;
                    END
                    """);
            }
        }
        migrated = true;
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    private static RecipeEnrollment read(ResultSet rs) throws SQLException {
        var rawKeys = rs.getString("gap_keys");
        Set<String> keys;
        if (rawKeys == null || rawKeys.isBlank()) {
            keys = Set.of();
        } else {
            keys = new LinkedHashSet<>();
            for (var part : rawKeys.split(",")) {
                var t = part.trim();
                if (!t.isEmpty()) keys.add(t);
            }
        }
        CadenceTier tier;
        try { tier = CadenceTier.valueOf(rs.getString("cadence_tier")); }
        catch (Exception e) { tier = CadenceTier.WARMUP; }
        return new RecipeEnrollment(
            rs.getString("recipe_id"),
            rs.getString("agent_did"),
            tier,
            rs.getInt("consecutive_successes"),
            Instant.ofEpochMilli(rs.getLong("enrolled_at")),
            rs.getInt("enabled") != 0,
            keys);
    }
}
