package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDBC store for per-companion
 * world-knowledge facts, factored out of {@code SoulManifest.worldKnowledge}.
 *
 * <p> canonical: world.db:world_knowledge.
 * One row per (did, key) — values are short config-style strings (e.g.
 * {@code starterKit=explorer}, channel credentials, etc.). The
 * {@code SoulManifest.worldKnowledge} field is a serialization shadow
 * during the transition; Phase 3 drops it and reads assemble on-demand.
 *
 * <p>Writes hook into {@link SqlSoulStore#store(SoulManifest)} via the
 * 5-arg constructor: every manifest persist call atomically replaces
 * the DID's world-knowledge rows in the canonical table FIRST, then
 * writes the manifest blob. Same hook-vs-service pattern that landed
 * for soul fragments in Phase 2.2 — one hook covers every writer
 * (Forge cycle, channel credential mutation, cross-zone arrival).
 */
public final class WorldKnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(WorldKnowledgeStore.class);

    private final String jdbcUrl;

    public WorldKnowledgeStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * Atomically replace every world-knowledge row for the given DID
     * with the provided map. Empty/null map removes all rows for the
     * DID (matches the semantics of a manifest with no knowledge).
     */
    public void replaceAll(String did, Map<String, String> knowledge) {
        if (did == null || did.isBlank()) {
            log.warn("WorldKnowledgeStore.replaceAll called with blank did — skipping");
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM world_knowledge WHERE did = ?")) {
                    del.setString(1, did);
                    del.executeUpdate();
                }
                if (knowledge != null && !knowledge.isEmpty()) {
                    var sql = "INSERT INTO world_knowledge "
                        + "(did, key, value, updated_at) VALUES (?, ?, ?, ?)";
                    try (var ins = conn.prepareStatement(sql)) {
                        long now = Instant.now().getEpochSecond();
                        for (var e : knowledge.entrySet()) {
                            if (e.getKey() == null || e.getKey().isBlank()) continue;
                            ins.setString(1, did);
                            ins.setString(2, e.getKey());
                            ins.setString(3, e.getValue());
                            ins.setLong(4, now);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            log.error("Failed to replaceAll world_knowledge for {}: {}", did, e.getMessage());
        }
    }

    /** Load every fact for a DID as an ordered map (manifest insertion order). */
    public Map<String, String> loadAll(String did) {
        var out = new LinkedHashMap<String, String>();
        if (did == null || did.isBlank()) return out;
        var sql = "SELECT key, value FROM world_knowledge WHERE did = ? ORDER BY key";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getString(2));
            }
        } catch (Exception e) {
            log.error("Failed to load world_knowledge for {}: {}", did, e.getMessage());
        }
        return out;
    }

    /** Read a single fact for a DID. */
    public String get(String did, String key) {
        if (did == null || did.isBlank() || key == null || key.isBlank()) return null;
        var sql = "SELECT value FROM world_knowledge WHERE did = ? AND key = ?";
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(sql)) {
            st.setString(1, did);
            st.setString(2, key);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            log.error("Failed to get world_knowledge {}/{}: {}", did, key, e.getMessage());
            return null;
        }
    }

    /** Total row count. Used for state dump + backfill. */
    public int count() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM world_knowledge");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("world_knowledge count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * One-shot idempotent backfill: walk every {@code soul_manifests} row's
     * embedded world-knowledge map and persist into {@code world_knowledge}
     * for DIDs that don't already have rows. Safe to run on every startup
     * until the manifest field is dropped (Phase 3).
     *
     * @return number of DIDs newly written
     */
    public int backfillFromManifests(SoulStore manifestStore) {
        int written = 0;
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement(
                 "SELECT DISTINCT did FROM soul_manifests WHERE archived = 0");
             var rs = st.executeQuery()) {
            while (rs.next()) {
                var did = rs.getString(1);
                if (didHasRows(conn, did)) continue;
                var manifestOpt = manifestStore.latest(did);
                if (manifestOpt.isEmpty()) continue;
                var knowledge = manifestOpt.get().worldKnowledge();
                if (knowledge == null || knowledge.isEmpty()) continue;
                replaceAll(did, knowledge);
                written++;
            }
        } catch (SQLException e) {
            log.warn("world_knowledge backfill query failed: {}", e.getMessage());
        }
        if (written > 0) {
            log.info("WorldKnowledgeStore: backfilled world-knowledge for {} did(s)", written);
        }
        return written;
    }

    private boolean didHasRows(Connection conn, String did) throws SQLException {
        try (var st = conn.prepareStatement(
                "SELECT 1 FROM world_knowledge WHERE did = ? LIMIT 1")) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }
}
