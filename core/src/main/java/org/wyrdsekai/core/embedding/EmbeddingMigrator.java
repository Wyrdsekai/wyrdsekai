package org.wyrdsekai.core.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

/**
 * Per-table strategy for {@link EmbeddingMigration}.
 *
 * <p>A migrator owns one logical table that stores embeddings. The framework drives
 * batched, resumable iteration; each migrator only needs to know how to:
 * <ol>
 *   <li>Report a stable {@link #tableName()} (used as the primary key in
 *       {@code embedding_migrations}).</li>
 *   <li>Estimate {@link #estimateRowCount(Connection)} for {@code --plan} output.</li>
 *   <li>Walk rows whose primary cursor is greater than a checkpoint and yield
 *       (cursor, source-text) pairs in stable cursor order.</li>
 *   <li>Write a freshly-computed embedding back to the row identified by the cursor,
 *       reusing the existing storage column.</li>
 * </ol>
 *
 * <p>Cursors are opaque strings — typically a SQL primary key or composite key
 * serialized as {@code "did|fragment_id"}. The framework only requires that
 * {@code listBatchAfter} returns rows in monotonically-increasing cursor order
 * so resume works after an interrupt.
 *
 * <p>Implementations should NOT manage transactions themselves — the framework
 * owns commit boundaries (one commit per batch) for crash-safe resume.
 */
public interface EmbeddingMigrator {

    /** Stable identifier for state tracking ({@code embedding_migrations.table_name}). */
    String tableName();

    /** Approximate row count — used by {@code --plan} for the friendly summary. */
    int estimateRowCount(Connection conn) throws SQLException;

    /**
     * Read up to {@code batchSize} rows whose cursor is strictly greater than
     * {@code afterCursor} (or all rows when {@code afterCursor} is null).
     *
     * <p>Order MUST be stable and ascending by cursor — the framework persists
     * the last cursor in {@code embedding_migrations.last_processed_id} and
     * resumes from there.
     */
    List<Row> listBatchAfter(Connection conn, String afterCursor, int batchSize)
        throws SQLException;

    /**
     * Write the new embedding back to the row identified by {@code cursor}.
     * Implementations should reuse the existing storage column rather than
     * creating a parallel one.
     */
    void writeEmbedding(Connection conn, String cursor, float[] embedding,
                        String modelVersion) throws SQLException;

    /**
     * One batch row.
     *
     * @param cursor opaque, comparable string used for resume
     * @param sourceText text to feed to the embedder; null/blank rows are skipped
     */
    record Row(String cursor, String sourceText) {}

    /** Convenience: encode a float[] into the on-disk little-endian bytes used by all stores. */
    static byte[] encode(float[] v) {
        if (v == null || v.length == 0) return null;
        var buf = ByteBuffer.allocate(v.length * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
        for (var f : v) buf.putFloat(f);
        return buf.array();
    }

    /** Convenience: convert {@code List<Float>} from {@link org.wyrdsekai.core.search.EmbeddingService}. */
    static float[] toArray(List<Float> embedding) {
        var out = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) out[i] = embedding.get(i);
        return out;
    }

    /** Convenience for tests / stubs that want to swap the embedder. */
    @FunctionalInterface
    interface Embedder extends Function<String, float[]> {}
}
