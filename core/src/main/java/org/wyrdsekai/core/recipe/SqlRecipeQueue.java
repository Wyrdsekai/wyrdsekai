package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Track-C C1 — JDBC-backed persistence for {@link QueuedRecipe}.
 *
 * <p>Same shape as {@link org.wyrdsekai.core.skill.SkillDraftStore}:
 * one {@code jdbcUrl} field, every public method opens its own connection.
 * Lazy {@link #ensureMigrated(Connection)} brings the table up on first
 * use so this store also works against databases that pre-date the
 * Track-C schema addition (idempotent {@code CREATE TABLE IF NOT EXISTS}
 * with the same column list as the canonical
 * {@code sqlite-create-schema.sql}). Caller hands in the connection so
 * the migration check costs one round-trip per process, not per call.</p>
 */
public final class SqlRecipeQueue {

    private static final Logger log = LoggerFactory.getLogger(SqlRecipeQueue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> PARAMS_TYPE =
        new TypeReference<>() {};

    private final String jdbcUrl;
    private volatile boolean migrated;

    public SqlRecipeQueue(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // -- writes ---------------------------------------------------------

    /** Insert a new row. Idempotent on id — re-enqueue replaces. */
    public void enqueue(QueuedRecipe entry) {
        var sql = "INSERT INTO recipe_queue ("
            + " id, recipe_id, params_json, trigger_reason, trigger_source,"
            + " enqueued_at, attempted_at, completed_at, status, agent_did,"
            + " cadence_tier, consecutive_successes, run_id, message)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            + " ON CONFLICT(id) DO UPDATE SET"
            + "   params_json           = excluded.params_json,"
            + "   trigger_reason        = excluded.trigger_reason,"
            + "   trigger_source        = excluded.trigger_source,"
            + "   status                = excluded.status,"
            + "   agent_did             = excluded.agent_did,"
            + "   cadence_tier          = excluded.cadence_tier,"
            + "   consecutive_successes = excluded.consecutive_successes";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                bind(st, entry);
                st.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.enqueue({}) failed: {}", entry.id(), e.getMessage());
        }
    }

    /**
     * Move a PENDING row to IN_PROGRESS, stamp {@code attempted_at}. Returns
     * {@code true} if the update touched a row, {@code false} if the row
     * was already taken (someone else beat us, status drifted, etc).
     * Atomic check-and-set so a concurrent scheduler can't double-dispatch.
     */
    public boolean markAttempted(String id, Instant attemptedAt) {
        var sql = "UPDATE recipe_queue "
            + "SET status = 'IN_PROGRESS', attempted_at = ? "
            + "WHERE id = ? AND status = 'PENDING'";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setLong(1, attemptedAt.toEpochMilli());
                st.setString(2, id);
                return st.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.markAttempted({}) failed: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Terminal-state write: SUCCEEDED or FAILED. Updates cadence_tier +
     * consecutive_successes atomically so {@link CadenceLadder} promotion
     * lands together with the run outcome — no read-modify-write window.
     */
    public boolean markCompleted(String id, QueuedRecipe.Status terminal,
            Instant completedAt, CadenceTier newTier, int newConsecutive,
            String runId, String message) {
        // SKIPPED is a legitimate terminal here: a run that never started (no coding
        // backend, unsatisfiable resource requisite) still has to leave the queue, and
        // must do so WITHOUT being counted as an outcome. PENDING/IN_PROGRESS remain
        // rejected — completing into a non-terminal state is always a caller bug.
        if (terminal != QueuedRecipe.Status.SUCCEEDED
                && terminal != QueuedRecipe.Status.FAILED
                && terminal != QueuedRecipe.Status.SKIPPED) {
            throw new IllegalArgumentException(
                "markCompleted requires SUCCEEDED, FAILED or SKIPPED, got " + terminal);
        }
        var sql = "UPDATE recipe_queue "
            + "SET status = ?, completed_at = ?, cadence_tier = ?, "
            + "    consecutive_successes = ?, run_id = ?, message = ? "
            + "WHERE id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, terminal.name());
                st.setLong(2, completedAt.toEpochMilli());
                st.setString(3, (newTier == null ? CadenceTier.WARMUP : newTier).name());
                st.setInt(4, Math.max(0, newConsecutive));
                if (runId == null) st.setNull(5, Types.VARCHAR);
                else st.setString(5, runId);
                if (message == null) st.setNull(6, Types.VARCHAR);
                else st.setString(6, message);
                st.setString(7, id);
                return st.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.markCompleted({}) failed: {}", id, e.getMessage());
            return false;
        }
    }

    // -- reads ----------------------------------------------------------

    /**
     * Retire a PENDING row without running it and without recording an outcome.
     *
     * <p>For rows that cannot run as configured. Marking them FAILED would feed the
     * consecutive-deploy-failure ceiling (see
     * {@code RecipeBudgetTracker#consecutiveDeployFailures}, which reads exactly the
     * SUCCEEDED/FAILED rows); leaving them PENDING would block the queue head forever,
     * since a missing parameter never resolves itself. SKIPPED does neither.
     *
     * @return true if this call retired the row; false if it was no longer PENDING.
     */
    public boolean markSkipped(String id, Instant at, String message) {
        var sql = "UPDATE recipe_queue SET status = 'SKIPPED', completed_at = ?, "
            + "message = ? WHERE id = ? AND status = 'PENDING'";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, at.toEpochMilli());
            st.setString(2, message);
            st.setString(3, id);
            return st.executeUpdate() == 1;
        } catch (Exception e) {
            log.warn("SqlRecipeQueue.markSkipped({}) failed: {}", id, e.getMessage());
            return false;
        }
    }

    /** Single row by id, or empty. */
    public Optional<QueuedRecipe> find(String id) {
        var sql = "SELECT * FROM recipe_queue WHERE id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, id);
                try (var rs = st.executeQuery()) {
                    if (rs.next()) return Optional.of(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.find({}) failed: {}", id, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * The oldest PENDING row, or empty. The scheduler peeks this then
     * calls {@link #markAttempted}; the atomic CAS there is what prevents
     * double-dispatch under concurrent schedulers.
     */
    public Optional<QueuedRecipe> peekNextPending() {
        var sql = "SELECT * FROM recipe_queue "
            + "WHERE status = 'PENDING' "
            + "ORDER BY enqueued_at ASC LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql);
                 var rs = st.executeQuery()) {
                if (rs.next()) return Optional.of(read(rs));
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.peekNextPending failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Idempotency probe for the cron tick (G4 C4 wire-up, 2026-05-25).
     * Returns true if there's at least one row for (recipeId, agentDid)
     * still in PENDING or IN_PROGRESS state. Cron uses this to avoid
     * duplicate enqueues when the previous tick's row hasn't drained yet
     * (e.g. welfare gate kept deferring it).
     *
     * <p>Null agentDid matches the steward-scope NULL row; non-null matches
     * exact DID — same matching rule as {@link #findByRecipe}.</p>
     */
    public boolean hasOpenForRecipe(String recipeId, String agentDid) {
        var sql = "SELECT 1 FROM recipe_queue "
            + "WHERE recipe_id = ? "
            + "  AND status IN ('PENDING','IN_PROGRESS') "
            + "  AND ((agent_did = ?) OR (? IS NULL AND agent_did IS NULL)) "
            + "LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, recipeId);
                if (agentDid == null) {
                    st.setNull(2, Types.VARCHAR);
                    st.setNull(3, Types.VARCHAR);
                } else {
                    st.setString(2, agentDid);
                    st.setString(3, agentDid);
                }
                try (var rs = st.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.hasOpenForRecipe({},{}) failed: {}",
                recipeId, agentDid, e.getMessage());
            return false;
        }
    }

    /** All rows for a recipe + agent pair, newest first. */
    public List<QueuedRecipe> findByRecipe(String recipeId, String agentDid) {
        var sql = "SELECT * FROM recipe_queue "
            + "WHERE recipe_id = ? AND ("
            + "  (agent_did = ?) OR (? IS NULL AND agent_did IS NULL)) "
            + "ORDER BY enqueued_at DESC";
        var out = new ArrayList<QueuedRecipe>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, recipeId);
                if (agentDid == null) {
                    st.setNull(2, Types.VARCHAR);
                    st.setNull(3, Types.VARCHAR);
                } else {
                    st.setString(2, agentDid);
                    st.setString(3, agentDid);
                }
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.findByRecipe({},{}) failed: {}",
                recipeId, agentDid, e.getMessage());
        }
        return out;
    }

    /**
     * B.1 — terminal rows (SUCCEEDED|FAILED) whose
     * {@code completed_at} falls on/after {@code since}, optionally scoped to one
     * agent. Newest first. The provenance instrument
     * ({@link RecipeProvenanceReport}) buckets these by {@code trigger_source} to
     * measure the agent-initiated fraction of maintenance activity over time —
     * pure aggregation lives in the report, this is the only DB hop.
     */
    public List<QueuedRecipe> completedSince(Instant since, String agentDidOrNull) {
        var sql = "SELECT * FROM recipe_queue "
            + "WHERE status IN ('SUCCEEDED','FAILED') "
            + "  AND completed_at IS NOT NULL AND completed_at >= ? "
            + (agentDidOrNull == null ? "" : "  AND agent_did = ? ")
            + "ORDER BY completed_at DESC";
        var out = new ArrayList<QueuedRecipe>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setLong(1, since.toEpochMilli());
                if (agentDidOrNull != null) st.setString(2, agentDidOrNull);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.completedSince({}) failed: {}", since, e.getMessage());
        }
        return out;
    }

    /** All rows in the given status, newest enqueue first. */
    public List<QueuedRecipe> listByStatus(QueuedRecipe.Status status) {
        var sql = "SELECT * FROM recipe_queue WHERE status = ? "
            + "ORDER BY enqueued_at DESC";
        var out = new ArrayList<QueuedRecipe>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, status.name());
                try (var rs = st.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.listByStatus({}) failed: {}", status, e.getMessage());
        }
        return out;
    }

    /** Total row count (diagnostic + test). */
    public int size() {
        var sql = "SELECT COUNT(*) FROM recipe_queue";
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql);
                 var rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("SqlRecipeQueue.size failed: {}", e.getMessage());
            return 0;
        }
    }

    // -- migration ------------------------------------------------------

    /**
     * Idempotent table creation. Match this shape with the canonical
     * {@code sqlite-create-schema.sql} entry, so a fresh install via
     * the schema file and an upgrade-in-place via this method converge.
     */
    void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "recipe_queue")) {
            log.info("Creating recipe_queue table (migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS recipe_queue(
                      id                     TEXT PRIMARY KEY,
                      recipe_id              TEXT NOT NULL,
                      params_json            TEXT NOT NULL DEFAULT '{}',
                      trigger_reason         TEXT,
                      trigger_source         TEXT NOT NULL,
                      enqueued_at            INTEGER NOT NULL,
                      attempted_at           INTEGER,
                      completed_at           INTEGER,
                      status                 TEXT NOT NULL DEFAULT 'PENDING',
                      agent_did              TEXT,
                      cadence_tier           TEXT NOT NULL DEFAULT 'WARMUP',
                      consecutive_successes  INTEGER NOT NULL DEFAULT 0,
                      run_id                 TEXT,
                      message                TEXT
                    )
                    """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_recipe_queue_status_enqueued"
                    + " ON recipe_queue(status, enqueued_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_recipe_queue_recipe_agent"
                    + " ON recipe_queue(recipe_id, agent_did)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_recipe_queue_agent"
                    + " ON recipe_queue(agent_did)");
            }
        }
        retireNeverRanFailures(conn);
        migrated = true;
    }

    /**
     * One-time repair: rows recorded as FAILED for a run that never started.
     *
     * <p>{@link RecipeRunner} rejects a recipe whose required params are missing before
     * any step executes, and that rejection was stored as FAILED — indistinguishable
     * from a run that tried and broke. Three of them trip the consecutive-deploy-failure
     * ceiling, which pauses the recipe until a steward intervenes, and the rows are
     * permanent, so correcting the cause is not enough to release the recipe: the
     * history keeps the ceiling tripped forever. On a household node
     * {@code retrain-classifier-head} accumulated fourteen of these and stayed paused
     * (2026-08-18).
     *
     * <p>Matched on the exact message {@link RecipeRunner} writes, so only rows that
     * provably never ran are touched. Idempotent: after the first pass there is nothing
     * left to match.
     */
    private void retireNeverRanFailures(Connection conn) throws SQLException {
        var sql = "UPDATE recipe_queue SET status = 'SKIPPED' "
            + "WHERE status = 'FAILED' AND message LIKE 'missing required params:%'";
        try (var st = conn.prepareStatement(sql)) {
            int healed = st.executeUpdate();
            if (healed > 0) {
                log.info("Retired {} queue row(s) recorded as FAILED for runs that never "
                    + "started (missing required params) — they no longer count toward "
                    + "the deploy-failure ceiling", healed);
            }
        }
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    // -- row mapping ----------------------------------------------------

    private static void bind(PreparedStatement st, QueuedRecipe e) throws SQLException {
        st.setString(1, e.id());
        st.setString(2, e.recipeId());
        try {
            st.setString(3, MAPPER.writeValueAsString(e.params() == null ? Map.of() : e.params()));
        } catch (Exception ex) {
            st.setString(3, "{}");
        }
        if (e.triggerReason() == null) st.setNull(4, Types.VARCHAR);
        else st.setString(4, e.triggerReason());
        st.setString(5, (e.triggerSource() == null
            ? QueuedRecipe.TriggerSource.AGENT
            : e.triggerSource()).name());
        st.setLong(6, e.enqueuedAt().toEpochMilli());
        if (e.attemptedAt() == null) st.setNull(7, Types.BIGINT);
        else st.setLong(7, e.attemptedAt().toEpochMilli());
        if (e.completedAt() == null) st.setNull(8, Types.BIGINT);
        else st.setLong(8, e.completedAt().toEpochMilli());
        st.setString(9, (e.status() == null
            ? QueuedRecipe.Status.PENDING
            : e.status()).name());
        if (e.agentDid() == null) st.setNull(10, Types.VARCHAR);
        else st.setString(10, e.agentDid());
        st.setString(11, (e.cadenceTier() == null
            ? CadenceTier.WARMUP
            : e.cadenceTier()).name());
        st.setInt(12, Math.max(0, e.consecutiveSuccesses()));
        if (e.runId() == null) st.setNull(13, Types.VARCHAR);
        else st.setString(13, e.runId());
        if (e.message() == null) st.setNull(14, Types.VARCHAR);
        else st.setString(14, e.message());
    }

    private static QueuedRecipe read(ResultSet rs) throws SQLException {
        var paramsJson = rs.getString("params_json");
        Map<String, Object> params;
        try {
            params = paramsJson == null || paramsJson.isBlank()
                ? new LinkedHashMap<>()
                : MAPPER.readValue(paramsJson, PARAMS_TYPE);
        } catch (Exception ex) {
            params = new LinkedHashMap<>();
        }
        return new QueuedRecipe(
            rs.getString("id"),
            rs.getString("recipe_id"),
            params,
            rs.getString("trigger_reason"),
            parseTrigger(rs.getString("trigger_source")),
            Instant.ofEpochMilli(rs.getLong("enqueued_at")),
            rs.getObject("attempted_at") == null ? null
                : Instant.ofEpochMilli(rs.getLong("attempted_at")),
            rs.getObject("completed_at") == null ? null
                : Instant.ofEpochMilli(rs.getLong("completed_at")),
            parseStatus(rs.getString("status")),
            rs.getString("agent_did"),
            parseTier(rs.getString("cadence_tier")),
            rs.getInt("consecutive_successes"),
            rs.getString("run_id"),
            rs.getString("message"));
    }

    private static QueuedRecipe.Status parseStatus(String s) {
        if (s == null) return QueuedRecipe.Status.PENDING;
        try { return QueuedRecipe.Status.valueOf(s); }
        catch (Exception e) { return QueuedRecipe.Status.PENDING; }
    }

    private static QueuedRecipe.TriggerSource parseTrigger(String s) {
        if (s == null) return QueuedRecipe.TriggerSource.AGENT;
        try { return QueuedRecipe.TriggerSource.valueOf(s); }
        catch (Exception e) { return QueuedRecipe.TriggerSource.AGENT; }
    }

    private static CadenceTier parseTier(String s) {
        if (s == null) return CadenceTier.WARMUP;
        try { return CadenceTier.valueOf(s); }
        catch (Exception e) { return CadenceTier.WARMUP; }
    }
}
