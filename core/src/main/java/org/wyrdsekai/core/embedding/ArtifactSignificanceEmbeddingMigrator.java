package org.wyrdsekai.core.embedding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Migrator for {@code artifact_significance.embedding}.
 *
 * <p><b>Special case:</b> the artifact_significance table stores an embedding
 * but NOT the source text — only the artifact ID and embedding bytes. The
 * source content lived in the producing actor's transient state and was
 * embedded at production time (see {@code CompanionActor.noteArtifactProduced}).
 * That means this column cannot be re-embedded from on-disk data.
 *
 * <p>Migration policy: NULL out stale embeddings rather than re-emit them with
 * the wrong model. Leaving an old-model vector behind would defeat the
 * standardization goal — different rows with different model fingerprints
 * silently corrupt cosine similarity comparisons. The semantic-ack path
 * gracefully degrades to the keyword fallback when embedding is null, and
 * the next artifact production cycle re-populates with the new model.
 *
 * <p>For framework purposes, this migrator returns rows with {@code null}
 * source text. The framework treats null/blank source as "advance cursor,
 * skip writeEmbedding". To still null-out old vectors, this migrator
 * actively writes null in {@link #listBatchAfter} via a side-channel: it
 * issues a single bulk UPDATE on first call when {@code afterCursor} is null,
 * then returns an empty batch. Idempotent — second call sees no non-null
 * embeddings and exits cleanly.
 *
 * <p>Cursor is unused (single bulk operation), but the framework needs
 * something for resume. We use the magic value {@code "DONE"} to mean the
 * bulk null-out has run.
 */
public final class ArtifactSignificanceEmbeddingMigrator implements EmbeddingMigrator {

    private static final String DONE_CURSOR = "DONE";

    @Override
    public String tableName() { return "artifact_significance"; }

    @Override
    public int estimateRowCount(Connection conn) throws SQLException {
        // Even though the table may not exist yet (it's created lazily by
        // ArtifactSignificancePersistence on first save), we tolerate that —
        // the migration framework calls this before run() and we'd rather not
        // CREATE the table just for an estimate.
        if (!tableExists(conn)) return 0;
        try (var st = conn.prepareStatement(
                "SELECT COUNT(*) FROM artifact_significance WHERE embedding IS NOT NULL");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public List<Row> listBatchAfter(Connection conn, String afterCursor, int batchSize)
            throws SQLException {
        if (DONE_CURSOR.equals(afterCursor)) return List.of();
        if (!tableExists(conn)) {
            // Nothing to do — table will be created with the new schema by
            // ArtifactSignificancePersistence when the next artifact is produced.
            return List.of(new Row(DONE_CURSOR, null));
        }

        // Bulk null-out — see class javadoc. Done in one go (not batched)
        // because there's no per-row work to amortize.
        try (var ps = conn.prepareStatement(
                "UPDATE artifact_significance SET embedding = NULL "
                    + "WHERE embedding IS NOT NULL")) {
            ps.executeUpdate();
        }

        // Return one synthetic row carrying the terminal cursor and null
        // source — framework will skip the writeEmbedding call (null/blank
        // source) and persist the cursor checkpoint.
        var out = new ArrayList<Row>(1);
        out.add(new Row(DONE_CURSOR, null));
        return out;
    }

    @Override
    public void writeEmbedding(Connection conn, String cursor, float[] embedding,
                               String modelVersion) throws SQLException {
        // Never invoked — listBatchAfter returns rows with null source text,
        // which the framework filters before calling writeEmbedding.
        throw new UnsupportedOperationException(
            "ArtifactSignificance embeddings are nulled in bulk, not re-emitted; "
                + "writeEmbedding should never be called.");
    }

    private static boolean tableExists(Connection conn) throws SQLException {
        try (var rs = conn.getMetaData().getTables(null, null, "artifact_significance", null)) {
            return rs.next();
        }
    }
}
