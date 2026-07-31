package org.wyrdsekai.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * §Q3 — Forge-time consolidation of the entity index
 * and graph edges. Runs during the sleep cycle, alongside
 * {@code SkillCostMatrix.forgeConsolidate()} and {@code MemoryConsolidator}.
 *
 * <p>Operations (all DID-scoped, all safe — never delete unique facts):</p>
 * <ol>
 *   <li><b>Entity dedup</b> — collapse rows that share (did, type, role, value).
 *       Same value = same truth; keep the newest row (highest id), drop older
 *       duplicates. This happens naturally because plant-time extraction can
 *       re-extract the same fact across turns (e.g. a user restating "my cat
 *       is Mochi" twice).</li>
 *   <li><b>Edge dedup</b> — collapse rows that share (did, subject, predicate,
 *       object). Same triple = same edge; keep newest.</li>
 *   <li><b>Dangling edge prune</b> — edges referencing a memory_id that has no
 *       corresponding entity row are orphaned; removed to keep graph consistent.</li>
 * </ol>
 *
 * <p>Stale-row pruning (rows older than N days) is <b>not</b> done here — the
 * spec treats the entity index as append-only, and Forge is already handling
 * working-memory decay via {@link MemoryConsolidator}. Entity rows remain
 * forever unless explicitly forgotten (future: {@code ForgetRequest}).</p>
 */
public final class MemoryEntityForge {

    private static final Logger log = LoggerFactory.getLogger(MemoryEntityForge.class);

    public record ConsolidationResult(
            int entityDuplicatesDropped,
            int edgeDuplicatesDropped,
            int danglingEdgesDropped,
            int staleEntitiesDropped,
            int staleEdgesDropped) {
        public int totalDropped() {
            return entityDuplicatesDropped + edgeDuplicatesDropped
                    + danglingEdgesDropped + staleEntitiesDropped + staleEdgesDropped;
        }

        /** Backwards-compat constructor pre-stale-prune. */
        public ConsolidationResult(int entityDuplicatesDropped,
                                    int edgeDuplicatesDropped,
                                    int danglingEdgesDropped) {
            this(entityDuplicatesDropped, edgeDuplicatesDropped,
                 danglingEdgesDropped, 0, 0);
        }
    }

    /**
     * Stale-entity TTL. Rows older than this are dropped by the Forge pass
     * UNLESS they are the newest for their (type, role) group — we never
     * delete the only remembered value. Default 90 days; override via env.
     * 0 disables stale pruning entirely.
     */
    private static final long STALE_TTL_MS = Long.parseLong(
            System.getenv().getOrDefault("WYRDSEKAI_ENTITY_TTL_DAYS", "90"))
            * 24L * 60L * 60L * 1000L;

    private MemoryEntityForge() {}

    /**
     * Run a full consolidation pass for one agent DID. Idempotent.
     *
     * @return row-count deltas for logging / metrics
     */
    public static ConsolidationResult consolidate(String jdbcUrl, String did) {
        if (jdbcUrl == null || did == null)
            return new ConsolidationResult(0, 0, 0, 0, 0);
        int entityDedup = 0;
        int edgeDedup = 0;
        int dangling = 0;
        int staleEntities = 0;
        int staleEdges = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try {
                entityDedup = dedupEntities(conn, did);
                edgeDedup = dedupEdges(conn, did);
                // Stale-entity prune (opt-out with WYRDSEKAI_ENTITY_TTL_DAYS=0).
                // Protects the newest row per (type, role) so we never lose
                // the one remembered value, even if the fact is ancient.
                if (STALE_TTL_MS > 0) {
                    long cutoff = System.currentTimeMillis() - STALE_TTL_MS;
                    staleEntities = pruneStaleEntities(conn, did, cutoff);
                }
                // Dangling edge prune must follow stale entity prune so that
                // edges referencing just-pruned memory_ids are collected too.
                dangling = pruneDanglingEdges(conn, did);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                log.warn("MemoryEntityForge consolidate rolled back for did={}: {}",
                        did, e.getMessage());
                return new ConsolidationResult(0, 0, 0, 0, 0);
            }
        } catch (SQLException e) {
            log.warn("MemoryEntityForge consolidate failed to open connection: {}",
                    e.getMessage());
            return new ConsolidationResult(0, 0, 0, 0, 0);
        }
        int total = entityDedup + edgeDedup + dangling + staleEntities + staleEdges;
        if (total > 0) {
            log.info("MemoryEntityForge[{}] consolidated: -{} entity dupes, "
                    + "-{} edge dupes, -{} dangling edges, -{} stale entities",
                    did, entityDedup, edgeDedup, dangling, staleEntities);
        }
        return new ConsolidationResult(entityDedup, edgeDedup, dangling,
                staleEntities, staleEdges);
    }

    /**
     * Drop entity rows that share (did, type, COALESCE(role,''), value) with a
     * newer row. Keeps the row with the highest id for each group.
     */
    private static int dedupEntities(Connection conn, String did) throws SQLException {
        // SQLite + Postgres compatible: delete rows whose id is NOT the max id
        // in their (type, role, value) group.
        // COALESCE guards NULL roles so they group together correctly.
        var sql = "DELETE FROM memory_entities "
                + "WHERE did = ? AND id NOT IN ("
                + "  SELECT MAX(id) FROM memory_entities "
                + "  WHERE did = ? "
                + "  GROUP BY entity_type, COALESCE(entity_role, ''), entity_value"
                + ")";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, did);
            return stmt.executeUpdate();
        }
    }

    /**
     * Drop edge rows that share (did, subject, predicate, object) with a newer row.
     */
    private static int dedupEdges(Connection conn, String did) throws SQLException {
        var sql = "DELETE FROM memory_edges "
                + "WHERE did = ? AND id NOT IN ("
                + "  SELECT MAX(id) FROM memory_edges "
                + "  WHERE did = ? "
                + "  GROUP BY subject, predicate, object"
                + ")";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, did);
            return stmt.executeUpdate();
        }
    }

    /**
     * Drop rows older than {@code cutoff} epoch-ms UNLESS the row is the
     * newest for its (type, role, value) group — we never want to lose the
     * sole remaining remembered value just because it's old. In practice the
     * newest row is kept regardless of age; only strictly older rows with
     * the same or newer peers get pruned.
     *
     * <p>Note: dedup already keeps only the newest row per (type, role,
     * value), so after dedup, only distinct-valued rows remain. This prune
     * drops old distinct-valued rows where a NEWER row exists with the
     * SAME (type, role) but DIFFERENT value — i.e. superseded facts.
     * Example: occupation:current=analyst (t=2023) gets dropped when
     * occupation:current=engineer (t=2024) is younger AND the analyst row
     * is past the TTL.</p>
     */
    private static int pruneStaleEntities(Connection conn, String did,
                                           long cutoffEpochMs) throws SQLException {
        var sql = "DELETE FROM memory_entities "
                + "WHERE did = ? AND timestamp < ? AND id NOT IN ("
                + "  SELECT MAX(id) FROM memory_entities "
                + "  WHERE did = ? "
                + "  GROUP BY entity_type, COALESCE(entity_role, '')"
                + ")";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setLong(2, cutoffEpochMs);
            stmt.setString(3, did);
            return stmt.executeUpdate();
        }
    }

    /**
     * Drop edges whose memory_id no longer exists in memory_entities for this DID.
     * Preserves edges that reference memories still tracked; removes orphans from
     * expired/forgotten facts.
     */
    private static int pruneDanglingEdges(Connection conn, String did) throws SQLException {
        var sql = "DELETE FROM memory_edges "
                + "WHERE did = ? AND memory_id NOT IN ("
                + "  SELECT DISTINCT memory_id FROM memory_entities WHERE did = ?"
                + ")";
        try (var stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, did);
            stmt.setString(2, did);
            return stmt.executeUpdate();
        }
    }
}
