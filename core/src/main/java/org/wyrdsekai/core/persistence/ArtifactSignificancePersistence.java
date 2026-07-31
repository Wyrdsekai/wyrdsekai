package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.ArtifactSignificanceTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1C: SQL persistence for
 * {@link ArtifactSignificanceTracker}.
 *
 * <p>Sibling of {@link VitalityPersistence}. Stores per-artifact records so the &gt;24h
 * aging rule and seen/unseen state survive actor / process restart.</p>
 *
 * <p>Schema: PK on {@code (companion_did, artifact_id)}. {@code seen} stored as 0/1 INTEGER.</p>
 */
public final class ArtifactSignificancePersistence {

    private static final Logger log = LoggerFactory.getLogger(ArtifactSignificancePersistence.class);

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private volatile boolean migrated = false;

    public ArtifactSignificancePersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    private void ensureMigrated(Connection conn) throws SQLException {
        if (migrated) return;
        if (!hasTable(conn, "artifact_significance")) {
            log.info("Creating artifact_significance table (Phase 1C migration)");
            try (var stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS artifact_significance(
                      companion_did   TEXT NOT NULL,
                      artifact_id     TEXT NOT NULL,
                      created_at      INTEGER NOT NULL,
                      seen            INTEGER NOT NULL DEFAULT 0,
                      seen_at         INTEGER,
                      kind            TEXT NOT NULL DEFAULT 'artifact',
                      embedding       BLOB,
                      PRIMARY KEY (companion_did, artifact_id)
                    )
                    """);
                stmt.execute(
                    "CREATE INDEX IF NOT EXISTS idx_artifact_sig_companion "
                        + "ON artifact_significance(companion_did, seen)");
            }
        } else {
            // Phase 1D idempotent migration: add embedding column on pre-existing
            // databases. ALTER TABLE ADD COLUMN with IF NOT EXISTS isn't portable
            // (libSQL/SQLite don't support it), so we probe via metadata and
            // catch the duplicate-column error as a no-op.
            if (!hasColumn(conn, "artifact_significance", "embedding")) {
                log.info("Adding embedding column to artifact_significance (Phase 1D migration)");
                try (var stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE artifact_significance ADD COLUMN embedding BLOB");
                } catch (SQLException dup) {
                    // Race or driver that ignores hasColumn — column already there.
                    log.debug("ALTER TABLE add embedding skipped: {}", dup.getMessage());
                }
            }
        }
        migrated = true;
    }

    private static boolean hasColumn(Connection conn, String table, String column)
            throws SQLException {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    private static boolean hasTable(Connection conn, String table) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        }
    }

    /** Insert or upsert one artifact row. Idempotent on (companion_did, artifact_id). */
    public void saveOne(String companionDid, ArtifactSignificanceTracker.Artifact a) {
        if (companionDid == null || a == null) return;
        String upsert = dialect.upsert("artifact_significance",
            "companion_did, artifact_id, created_at, seen, seen_at, kind, embedding",
            "?, ?, ?, ?, ?, ?, ?",
            "companion_did, artifact_id",
            "seen = EXCLUDED.seen, seen_at = EXCLUDED.seen_at, kind = EXCLUDED.kind, "
                + "embedding = EXCLUDED.embedding");
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var ps = conn.prepareStatement(upsert)) {
                ps.setString(1, companionDid);
                ps.setString(2, a.artifactId());
                ps.setLong(3, a.createdAt().toEpochMilli());
                ps.setInt(4, a.seen() ? 1 : 0);
                if (a.seen() && a.seenAt() != null) {
                    ps.setLong(5, a.seenAt().toEpochMilli());
                } else {
                    ps.setNull(5, Types.BIGINT);
                }
                ps.setString(6, a.kind());
                var embBytes = encodeEmbedding(a.embedding());
                if (embBytes != null) {
                    ps.setBytes(7, embBytes);
                } else {
                    ps.setNull(7, Types.BLOB);
                }
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("Failed to save artifact {} for {}: {}",
                a.artifactId(), companionDid, e.getMessage());
            throw new RuntimeException("Artifact significance save failed", e);
        }
    }

    /** Encode a float[] embedding as raw little-endian bytes (4B per float). Null-safe. */
    private static byte[] encodeEmbedding(float[] e) {
        if (e == null || e.length == 0) return null;
        var buf = ByteBuffer.allocate(e.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (var v : e) buf.putFloat(v);
        return buf.array();
    }

    /** Decode bytes back to float[]. Null-safe — returns null on missing/wrong-shaped. */
    private static float[] decodeEmbedding(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % 4 != 0) return null;
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getFloat();
        return out;
    }

    /**
     * Full-rewrite save for one companion's snapshot. Used as the simple write-through path
     * after a bulk mutation (e.g. an ack scan touched multiple rows).
     */
    public void saveAll(String companionDid, List<ArtifactSignificanceTracker.Artifact> artifacts) {
        if (companionDid == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            conn.setAutoCommit(false);
            try {
                try (var del = conn.prepareStatement(
                        "DELETE FROM artifact_significance WHERE companion_did = ?")) {
                    del.setString(1, companionDid);
                    del.executeUpdate();
                }
                if (artifacts != null && !artifacts.isEmpty()) {
                    try (var ins = conn.prepareStatement(
                            "INSERT INTO artifact_significance("
                                + "companion_did, artifact_id, created_at, seen, seen_at, kind, embedding) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        for (var a : artifacts) {
                            if (a == null) continue;
                            ins.setString(1, companionDid);
                            ins.setString(2, a.artifactId());
                            ins.setLong(3, a.createdAt().toEpochMilli());
                            ins.setInt(4, a.seen() ? 1 : 0);
                            if (a.seen() && a.seenAt() != null) {
                                ins.setLong(5, a.seenAt().toEpochMilli());
                            } else {
                                ins.setNull(5, Types.BIGINT);
                            }
                            ins.setString(6, a.kind());
                            var embBytes = encodeEmbedding(a.embedding());
                            if (embBytes != null) {
                                ins.setBytes(7, embBytes);
                            } else {
                                ins.setNull(7, Types.BLOB);
                            }
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
        } catch (SQLException e) {
            log.error("Failed to save artifact significance for {}: {}",
                companionDid, e.getMessage());
            throw new RuntimeException("Artifact significance save failed", e);
        }
    }

    /** Load all artifacts for a companion. */
    public List<ArtifactSignificanceTracker.Artifact> loadAll(String companionDid) {
        var out = new ArrayList<ArtifactSignificanceTracker.Artifact>();
        if (companionDid == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT artifact_id, created_at, seen, seen_at, kind, embedding "
                        + "FROM artifact_significance WHERE companion_did = ? "
                        + "ORDER BY created_at ASC")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                while (rs.next()) {
                    long createdMs = rs.getLong("created_at");
                    int seenInt = rs.getInt("seen");
                    long seenMs = rs.getLong("seen_at");
                    boolean seenNull = rs.wasNull();
                    Instant seenAt = (seenInt == 1 && !seenNull)
                        ? Instant.ofEpochMilli(seenMs) : null;
                    var embBytes = rs.getBytes("embedding");
                    var embedding = rs.wasNull() ? null : decodeEmbedding(embBytes);
                    out.add(new ArtifactSignificanceTracker.Artifact(
                        rs.getString("artifact_id"),
                        Instant.ofEpochMilli(createdMs),
                        rs.getString("kind"),
                        seenInt == 1,
                        seenAt,
                        embedding));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load artifact significance for {}: {}",
                companionDid, e.getMessage());
        }
        return out;
    }

    public int count(String companionDid) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureMigrated(conn);
            try (var stmt = conn.prepareStatement(
                    "SELECT COUNT(*) FROM artifact_significance WHERE companion_did = ?")) {
                stmt.setString(1, companionDid);
                var rs = stmt.executeQuery();
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
