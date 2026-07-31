package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.wyrdsekai.core.agent.ModelAttribution;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * JDBC store for soul fragments
 * factored out of {@code SoulManifest.soulFragments}.
 *
 * <p> canonical: world.db:soul_fragments.
 * One row per fragment, composite PK on {@code (did, fragment_id)}.
 * Manifest field becomes a serialization-time projection assembled
 * from this store; Phase 3 drops the field entirely.
 *
 * <p>Embeddings are stored as a BLOB/BYTEA column — a packed sequence
 * of IEEE 754 little-endian float32 values. The on-disk format
 * mirrors the Lucene HNSW index byte layout, so future copies between
 * stores don't need conversion.
 *
 * <p>Writes are routed through {@code SqlSoulStore.store()}: every
 * manifest persist call dual-writes the canonical table FIRST (so a
 * crash mid-write leaves the table authoritative), then writes the
 * manifest blob. {@link #replaceAll} runs in a single connection with
 * autoCommit off so the per-DID delete + bulk insert is atomic.
 */
public final class SoulFragmentStore {

    private static final Logger log = LoggerFactory.getLogger(SoulFragmentStore.class);

    private final String jdbcUrl;
    private volatile boolean migrated = false;

    public SoulFragmentStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * + — idempotent
     * column migrations to add {@code kind} (§17.6) and {@code scene_id} (§14)
     * to pre-existing {@code soul_fragments} tables. Fresh schema initializers
     * create both columns up-front; legacy tables get them ALTER-ADDed.
     * Safe to call repeatedly. Both migrations are NULL-safe — pre-existing
     * rows hydrate as {@code kind=NARRATIVE} / {@code sceneId=null}.
     */
    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        try (var rs = conn.getMetaData().getTables(null, null, "soul_fragments", null)) {
            if (!rs.next()) {
                migrated = true; // schema initializer will create with both columns
                return;
            }
        }
        boolean hasKind;
        try (var rs = conn.getMetaData().getColumns(null, null, "soul_fragments", "kind")) {
            hasKind = rs.next();
        }
        if (!hasKind) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE soul_fragments ADD COLUMN "
                    + "kind TEXT NOT NULL DEFAULT 'NARRATIVE'");
                log.info("SoulFragmentStore §17.6 migration: added kind column");
            } catch (SQLException e) {
                // Race: another process added it. Re-check.
                try (var rs = conn.getMetaData().getColumns(null, null,
                        "soul_fragments", "kind")) {
                    if (!rs.next()) throw e;
                }
            }
            // Create the per-kind index if missing.
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_soul_fragments_kind "
                    + "ON soul_fragments(did, kind)");
            } catch (SQLException ignored) {
                // index creation is best-effort; primary table is what matters
            }
        }
        // sceneId column. Nullable: only scene-derived
        // fragments (NARRATIVE via fromScene, EPISODIC via fromEpisodicScene)
        // carry it; identity / personality / DEXTERITY / CONVENTION /
        // STRUCTURAL fragments leave it null.
        boolean hasSceneId;
        try (var rs = conn.getMetaData().getColumns(null, null, "soul_fragments", "scene_id")) {
            hasSceneId = rs.next();
        }
        if (!hasSceneId) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE soul_fragments ADD COLUMN scene_id TEXT");
                log.info("SoulFragmentStore §14 migration: added scene_id column");
            } catch (SQLException e) {
                try (var rs = conn.getMetaData().getColumns(null, null,
                        "soul_fragments", "scene_id")) {
                    if (!rs.next()) throw e;
                }
            }
            // Index for cross-perspective lookup by sceneId.
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_soul_fragments_scene_id "
                    + "ON soul_fragments(did, scene_id)");
            } catch (SQLException ignored) {}
        }
        // Data-durability (2026-07-09) — authoring_model column: which LLM authored the
        // fragment. Same self-migration pattern as kind/scene_id so callers that reach a
        // pre-migration table (or a bare test db) don't fail the whole replaceAll.
        boolean hasAuthoringModel;
        try (var rs = conn.getMetaData().getColumns(null, null,
                "soul_fragments", "authoring_model")) {
            hasAuthoringModel = rs.next();
        }
        if (!hasAuthoringModel) {
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE soul_fragments ADD COLUMN authoring_model TEXT");
                log.info("SoulFragmentStore migration: added authoring_model column");
            } catch (SQLException e) {
                try (var rs = conn.getMetaData().getColumns(null, null,
                        "soul_fragments", "authoring_model")) {
                    if (!rs.next()) throw e;
                }
            }
        }
        migrated = true;
    }

    /**
     * Atomically replace every fragment row for the given DID with the
     * provided list. If {@code fragments} is null or empty, all rows for
     * the DID are removed (matches the semantics of an empty manifest list).
     */
    public void replaceAll(String did, List<SoulFragment> fragments) {
        if (did == null || did.isBlank()) {
            log.warn("SoulFragmentStore.replaceAll called with blank did — skipping");
            return;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM soul_fragments WHERE did = ?")) {
                    del.setString(1, did);
                    del.executeUpdate();
                }
                if (fragments != null && !fragments.isEmpty()) {
                    var sql = "INSERT INTO soul_fragments "
                        + "(did, fragment_id, category, label, fragment_text, "
                        + " embedding, embedding_model, formative, confidence, "
                        + " reinforcement_count, first_observed, last_confirmed, "
                        + " valid_from, superseded_at, superseded_by, ordinal, updated_at, "
                        + " kind, scene_id, authoring_model) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                    try (var ins = conn.prepareStatement(sql)) {
                        long now = Instant.now().getEpochSecond();
                        int ordinal = 0;
                        for (var f : fragments) {
                            if (f == null || f.id() == null || f.id().isBlank()) continue;
                            ins.setString(1, did);
                            ins.setString(2, f.id());
                            ins.setString(3, f.category() != null ? f.category() : "memory");
                            ins.setString(4, f.label());
                            ins.setString(5, f.text());
                            if (f.embedding() != null && f.embedding().length > 0) {
                                ins.setBytes(6, encodeEmbedding(f.embedding()));
                            } else {
                                ins.setNull(6, Types.BINARY);
                            }
                            ins.setString(7, f.embeddingModel());
                            ins.setInt(8, f.formative() ? 1 : 0);
                            ins.setFloat(9, f.confidence() != null ? f.confidence() : 0.5f);
                            ins.setInt(10, f.reinforcementCount() != null
                                ? f.reinforcementCount() : 0);
                            setEpochOrNull(ins, 11, f.firstObserved());
                            setEpochOrNull(ins, 12, f.lastConfirmed());
                            setEpochOrNull(ins, 13, f.validFrom());
                            setEpochOrNull(ins, 14, f.supersededAt());
                            ins.setString(15, f.supersededBy());
                            ins.setInt(16, ordinal++);
                            ins.setLong(17, now);
                            // §17.6 kind — defensive in case a legacy caller
                            // somehow produces a fragment with null kind.
                            ins.setString(18,
                                (f.kind() != null ? f.kind() : FragmentKind.DEFAULT).name());
                            // §14 sceneId — nullable; only scene-derived fragments
                            // (fromScene / fromEpisodicScene) carry it. Without
                            // this column persisting, EPISODIC fragments would
                            // silently lose sceneId on store→load round-trip.
                            if (f.sceneId() != null && !f.sceneId().isBlank()) {
                                ins.setString(19, f.sceneId());
                            } else {
                                ins.setNull(19, Types.VARCHAR);
                            }
                            // Model attribution (2026-07-09) — which LLM authored this
                            // fragment; nulls on pre-attribution rows are honest history.
                            ins.setString(20, ModelAttribution.current());
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
            log.error("Failed to replaceAll soul_fragments for {}: {}", did, e.getMessage());
        }
    }

    /** Load every fragment for a DID, ordered by manifest position. */
    public List<SoulFragment> loadAll(String did) {
        return loadInternal(did, null);
    }

    /**
     * load every fragment for a DID
     * filtered by Forge kind, ordered by manifest position. The per-kind
     * load is the load-bearing read pattern for kind-aware consolidation
     * (Coding Familiar dexterity-fragment list; CONVENTION → Coding DNA
     * cultural compartment; STRUCTURAL → re-bootstrap trigger).
     */
    public List<SoulFragment> loadByKind(String did, FragmentKind kind) {
        return loadInternal(did, kind == null ? FragmentKind.DEFAULT : kind);
    }

    private List<SoulFragment> loadInternal(String did, FragmentKind kindFilter) {
        if (did == null || did.isBlank()) return List.of();
        var sql = "SELECT fragment_id, category, label, fragment_text, embedding, "
            + " embedding_model, formative, confidence, reinforcement_count, "
            + " first_observed, last_confirmed, valid_from, superseded_at, superseded_by, "
            + " kind, scene_id "
            + "FROM soul_fragments WHERE did = ?"
            + (kindFilter != null ? " AND kind = ?" : "")
            + " ORDER BY ordinal ASC";
        var out = new ArrayList<SoulFragment>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, did);
                if (kindFilter != null) st.setString(2, kindFilter.name());
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        var emb = rs.getBytes(5);
                        out.add(new SoulFragment(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            emb != null ? decodeEmbedding(emb) : null,
                            rs.getString(6),
                            rs.getInt(7) != 0,
                            rs.getFloat(8),
                            rs.getInt(9),
                            epochOrNull(rs, 10),
                            epochOrNull(rs, 11),
                            epochOrNull(rs, 12),
                            epochOrNull(rs, 13),
                            rs.getString(14),
                            FragmentKind.parse(rs.getString(15)),
                            rs.getString(16)
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load soul_fragments for {} (kind={}): {}",
                did, kindFilter, e.getMessage());
        }
        return out;
    }

    /**
     * load every fragment for a DID whose
     * stored {@code scene_id} matches the given value. Used by cross-perspective
     * lookup: a journal-mirror entry stamped with the same {@link
     * org.wyrdsekai.core.story.StoryStore#SCENE_ID_MARKER_PREFIX} marker can
     * be paired with the companion's EPISODIC fragment via this lookup.
     */
    public List<SoulFragment> loadBySceneId(String did, String sceneId) {
        if (did == null || did.isBlank() || sceneId == null || sceneId.isBlank()) return List.of();
        var sql = "SELECT fragment_id, category, label, fragment_text, embedding, "
            + " embedding_model, formative, confidence, reinforcement_count, "
            + " first_observed, last_confirmed, valid_from, superseded_at, superseded_by, "
            + " kind, scene_id "
            + "FROM soul_fragments WHERE did = ? AND scene_id = ? ORDER BY ordinal ASC";
        var out = new ArrayList<SoulFragment>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, did);
                st.setString(2, sceneId);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        var emb = rs.getBytes(5);
                        out.add(new SoulFragment(
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            emb != null ? decodeEmbedding(emb) : null,
                            rs.getString(6),
                            rs.getInt(7) != 0,
                            rs.getFloat(8),
                            rs.getInt(9),
                            epochOrNull(rs, 10),
                            epochOrNull(rs, 11),
                            epochOrNull(rs, 12),
                            epochOrNull(rs, 13),
                            rs.getString(14),
                            FragmentKind.parse(rs.getString(15)),
                            rs.getString(16)
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load soul_fragments for {} (sceneId={}): {}",
                did, sceneId, e.getMessage());
        }
        return out;
    }

    /**
     * count fragments per kind for a DID.
     * Returns a map of every {@link FragmentKind} value (zero-entries for
     * absent kinds) so callers can render histograms without checking nulls.
     */
    public Map<FragmentKind, Integer> countByKind(String did) {
        var out = new EnumMap<FragmentKind, Integer>(FragmentKind.class);
        for (var k : FragmentKind.values()) out.put(k, 0);
        if (did == null || did.isBlank()) return out;
        var sql = "SELECT kind, COUNT(*) FROM soul_fragments WHERE did = ? GROUP BY kind";
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var st = conn.prepareStatement(sql)) {
                st.setString(1, did);
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        out.put(FragmentKind.parse(rs.getString(1)), rs.getInt(2));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("countByKind({}) failed: {}", did, e.getMessage());
        }
        return out;
    }

    /** Total fragment row count. Used by backfill / state dump. */
    public int count() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.prepareStatement("SELECT COUNT(*) FROM soul_fragments");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("soul_fragments count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * One-shot idempotent backfill: walk every {@code soul_manifests}
     * row's embedded fragment list and persist into {@code soul_fragments}
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
                var fragments = manifestOpt.get().soulFragments();
                if (fragments == null || fragments.isEmpty()) continue;
                replaceAll(did, fragments);
                written++;
            }
        } catch (SQLException e) {
            log.warn("soul_fragments backfill query failed: {}", e.getMessage());
        }
        if (written > 0) {
            log.info("SoulFragmentStore: backfilled fragments for {} did(s)", written);
        }
        return written;
    }

    private boolean didHasRows(Connection conn, String did) throws SQLException {
        try (var st = conn.prepareStatement(
                "SELECT 1 FROM soul_fragments WHERE did = ? LIMIT 1")) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static byte[] encodeEmbedding(float[] v) {
        var buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var f : v) buf.putFloat(f);
        return buf.array();
    }

    private static float[] decodeEmbedding(byte[] bytes) {
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getFloat();
        return out;
    }

    private static void setEpochOrNull(PreparedStatement st, int idx,
                                        Instant t) throws SQLException {
        if (t == null) st.setNull(idx, Types.BIGINT);
        else st.setLong(idx, t.getEpochSecond());
    }

    private static Instant epochOrNull(ResultSet rs, int idx) throws SQLException {
        long v = rs.getLong(idx);
        return rs.wasNull() ? null : Instant.ofEpochSecond(v);
    }
}
