package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Track-C C3 — read-only budget snapshot from
 * {@code recipe_queue}. Two metrics feed {@link WelfareGate}:
 *
 * <ul>
 *   <li><b>Daily GPU time</b>: sum of {@code completed_at - attempted_at}
 *       across SUCCEEDED+FAILED rows whose {@code completed_at} falls in
 *       the current local-day window. Caller's wall-clock UTC offset
 *       (passed in) determines the window — production wires the
 *       household's configured zone; tests pass {@link ZoneId#of(String)
 *       "UTC"}.</li>
 *   <li><b>Monthly run count</b>: COUNT(*) of SUCCEEDED+FAILED rows whose
 *       {@code completed_at} falls in the current calendar month.</li>
 * </ul>
 *
 * <p>Stateless — every call hits the DB. Cheap enough for the per-tick
 * scheduler poll (60min default cadence); if that ever changes,
 * memoise here. Same construction shape as
 * {@link SqlRecipeQueue}: one {@code jdbcUrl} field, every public
 * method opens its own connection.</p>
 */
public final class RecipeBudgetTracker {

    private static final Logger log = LoggerFactory.getLogger(RecipeBudgetTracker.class);

    private final String jdbcUrl;

    public RecipeBudgetTracker(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Sum of (completed_at - attempted_at) across all terminal rows
     * whose completed_at falls in the local-day window containing
     * {@code now}. Rows without attempted_at (shouldn't happen — CAS
     * sets it) contribute zero. Returns {@link Duration#ZERO} on any
     * DB error so the gate fails open.
     */
    public Duration gpuUsedToday(Instant now, ZoneId zone) {
        var bounds = dayBounds(now, zone);
        var sql = "SELECT COALESCE(SUM(completed_at - attempted_at), 0) "
            + "FROM recipe_queue "
            + "WHERE status IN ('SUCCEEDED', 'FAILED') "
            + "  AND attempted_at IS NOT NULL "
            + "  AND completed_at >= ? AND completed_at < ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, bounds[0]);
            st.setLong(2, bounds[1]);
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    return Duration.ofMillis(rs.getLong(1));
                }
            }
        } catch (Exception e) {
            log.warn("RecipeBudgetTracker.gpuUsedToday failed: {}", e.getMessage());
        }
        return Duration.ZERO;
    }

    /**
     * Count of SUCCEEDED+FAILED rows whose completed_at falls in the
     * current calendar month. Returns 0 on any DB error (fails open).
     */
    public int runsThisMonth(Instant now, ZoneId zone) {
        var bounds = monthBounds(now, zone);
        var sql = "SELECT COUNT(*) FROM recipe_queue "
            + "WHERE status IN ('SUCCEEDED', 'FAILED') "
            + "  AND completed_at >= ? AND completed_at < ?";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setLong(1, bounds[0]);
            st.setLong(2, bounds[1]);
            try (var rs = st.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (Exception e) {
            log.warn("RecipeBudgetTracker.runsThisMonth failed: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * Count of consecutive non-SUCCESS terminal rows for the given
     * (recipe, agent) pair, walking the queue newest-first and stopping
     * at the first SUCCESS. Maps {@link CadenceLadder.Outcome} to the
     * gate's deploy-ceiling check — covers FAILED (gate, step) AND
     * ROLLBACK_FIRED (step + rollback in outcomes) uniformly because
     * both land as {@code status='FAILED'} in the queue row.
     */
    /**
     * Most recent SUCCEEDED completion per distinct value of one run param.
     *
     * <p>Lets a caller ask "which of these candidates has gone longest without a
     * successful run?" in a single query. Used to pick which classifier head the
     * scheduled path should retrain: staleness is the only sound basis for that
     * choice, because the recorded per-head accuracies are NOT comparable — two of
     * the shipped heads report train-set accuracy with {@code validation_examples: 0},
     * so "retrain the weakest" would rank the unmeasured heads best and starve the
     * honestly-measured ones.
     *
     * @return value → last success instant. Candidates absent from the map have never
     *         succeeded, and are therefore the stalest of all.
     */
    public Map<String, Instant> lastSuccessByParam(String recipeId, String agentDid,
            String paramName) {
        var out = new LinkedHashMap<String, Instant>();
        if (recipeId == null || paramName == null) return out;
        var sql = "SELECT json_extract(params_json, '$.' || ?) AS pval, "
            + "       MAX(completed_at) AS last_at "
            + "FROM recipe_queue "
            + "WHERE recipe_id = ? AND status = 'SUCCEEDED' "
            + "  AND completed_at IS NOT NULL "
            + "  AND ((agent_did = ?) OR (? IS NULL AND agent_did IS NULL)) "
            + "  AND json_extract(params_json, '$.' || ?) IS NOT NULL "
            + "GROUP BY pval";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, paramName);
            st.setString(2, recipeId);
            if (agentDid == null) {
                st.setNull(3, Types.VARCHAR);
                st.setNull(4, Types.VARCHAR);
            } else {
                st.setString(3, agentDid);
                st.setString(4, agentDid);
            }
            st.setString(5, paramName);
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    var v = rs.getString("pval");
                    if (v != null) out.put(v, Instant.ofEpochMilli(rs.getLong("last_at")));
                }
            }
        } catch (Exception e) {
            log.warn("RecipeBudgetTracker.lastSuccessByParam({},{},{}) failed: {}",
                recipeId, agentDid, paramName, e.getMessage());
        }
        return out;
    }

    public int consecutiveDeployFailures(String recipeId, String agentDid) {
        if (recipeId == null) return 0;
        var sql = "SELECT status FROM recipe_queue "
            + "WHERE recipe_id = ? "
            + "  AND status IN ('SUCCEEDED', 'FAILED') "
            + "  AND ((agent_did = ?) OR (? IS NULL AND agent_did IS NULL)) "
            + "ORDER BY completed_at DESC";
        int run = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, recipeId);
            if (agentDid == null) {
                st.setNull(2, Types.VARCHAR);
                st.setNull(3, Types.VARCHAR);
            } else {
                st.setString(2, agentDid);
                st.setString(3, agentDid);
            }
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    if ("SUCCEEDED".equals(rs.getString(1))) break;
                    run++;
                }
            }
        } catch (Exception e) {
            log.warn("RecipeBudgetTracker.consecutiveDeployFailures({},{}) failed: {}",
                recipeId, agentDid, e.getMessage());
        }
        return run;
    }

    /**
     * Most recent terminal (SUCCEEDED or FAILED) completed_at for the
     * given (recipe, agent) pair, or {@code null}. Used for cooldown
     * gate so the next fire respects the current tier's period.
     */
    public Instant lastTerminalAt(String recipeId, String agentDid) {
        var sql = "SELECT completed_at FROM recipe_queue "
            + "WHERE recipe_id = ? "
            + "  AND status IN ('SUCCEEDED', 'FAILED') "
            + "  AND ((agent_did = ?) OR (? IS NULL AND agent_did IS NULL)) "
            + "ORDER BY completed_at DESC LIMIT 1";
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, recipeId);
            if (agentDid == null) {
                st.setNull(2, Types.VARCHAR);
                st.setNull(3, Types.VARCHAR);
            } else {
                st.setString(2, agentDid);
                st.setString(3, agentDid);
            }
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    var ms = rs.getLong(1);
                    if (!rs.wasNull() && ms > 0) return Instant.ofEpochMilli(ms);
                }
            }
        } catch (Exception e) {
            log.warn("RecipeBudgetTracker.lastTerminalAt({},{}) failed: {}",
                recipeId, agentDid, e.getMessage());
        }
        return null;
    }

    // -- date helpers ---------------------------------------------------

    /** {@code [startOfDay, startOfNextDay)} in epoch-millis in {@code zone}. */
    private static long[] dayBounds(Instant now, ZoneId zone) {
        var z = zone == null ? ZoneId.systemDefault() : zone;
        var start = now.atZone(z).toLocalDate().atStartOfDay(z).toInstant();
        var end = start.plus(Duration.ofDays(1));
        return new long[] { start.toEpochMilli(), end.toEpochMilli() };
    }

    /** {@code [startOfMonth, startOfNextMonth)} in epoch-millis in {@code zone}. */
    private static long[] monthBounds(Instant now, ZoneId zone) {
        var z = zone == null ? ZoneId.systemDefault() : zone;
        var date = now.atZone(z).toLocalDate().withDayOfMonth(1);
        var start = date.atStartOfDay(z).toInstant();
        var end = date.plusMonths(1).atStartOfDay(z).toInstant();
        return new long[] { start.toEpochMilli(), end.toEpochMilli() };
    }
}
