package org.wyrdsekai.core.embedding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Migrator for {@code soul_fragments.embedding} (canonical store per
 * ). Source text is {@code fragment_text}.
 *
 * <p>Cursor format: {@code "didfragment_id"} — composite primary key
 * joined by ASCII Unit Separator (0x1F). Stable ordering matches the SQL
 * {@code ORDER BY did, fragment_id} clause used in {@link #listBatchAfter}.
 */
public final class SoulFragmentEmbeddingMigrator implements EmbeddingMigrator {

    private static final char SEP = '';

    @Override
    public String tableName() { return "soul_fragments"; }

    @Override
    public int estimateRowCount(Connection conn) throws SQLException {
        // Only rows that already carry an embedding are in scope. Rows with
        // null embedding have either not been embedded yet (a different
        // concern, handled by SoulMaintenanceCycle on next sweep) or are
        // text-only and don't need vectors.
        try (var st = conn.prepareStatement(
                "SELECT COUNT(*) FROM soul_fragments "
                    + "WHERE fragment_text IS NOT NULL AND embedding IS NOT NULL");
             var rs = st.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public List<Row> listBatchAfter(Connection conn, String afterCursor, int batchSize)
            throws SQLException {
        var out = new ArrayList<Row>(batchSize);
        var sql = afterCursor == null
            ? "SELECT did, fragment_id, fragment_text FROM soul_fragments "
                + "WHERE embedding IS NOT NULL "
                + "ORDER BY did, fragment_id LIMIT ?"
            : "SELECT did, fragment_id, fragment_text FROM soul_fragments "
                + "WHERE embedding IS NOT NULL "
                + "AND ((did > ?) OR (did = ? AND fragment_id > ?)) "
                + "ORDER BY did, fragment_id LIMIT ?";
        try (var ps = conn.prepareStatement(sql)) {
            if (afterCursor == null) {
                ps.setInt(1, batchSize);
            } else {
                var split = parseCursor(afterCursor);
                ps.setString(1, split[0]);
                ps.setString(2, split[0]);
                ps.setString(3, split[1]);
                ps.setInt(4, batchSize);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(
                        cursorOf(rs.getString(1), rs.getString(2)),
                        rs.getString(3)));
                }
            }
        }
        return out;
    }

    @Override
    public void writeEmbedding(Connection conn, String cursor, float[] embedding,
                               String modelVersion) throws SQLException {
        var split = parseCursor(cursor);
        try (var ps = conn.prepareStatement(
                "UPDATE soul_fragments SET embedding = ?, embedding_model = ? "
                    + "WHERE did = ? AND fragment_id = ?")) {
            ps.setBytes(1, EmbeddingMigrator.encode(embedding));
            ps.setString(2, modelVersion);
            ps.setString(3, split[0]);
            ps.setString(4, split[1]);
            ps.executeUpdate();
        }
    }

    private static String cursorOf(String did, String fragmentId) {
        return did + SEP + fragmentId;
    }

    private static String[] parseCursor(String cursor) {
        int idx = cursor.indexOf(SEP);
        if (idx < 0) {
            // Defensive: callers should never construct cursors directly.
            return new String[]{cursor, ""};
        }
        return new String[]{cursor.substring(0, idx), cursor.substring(idx + 1)};
    }
}
