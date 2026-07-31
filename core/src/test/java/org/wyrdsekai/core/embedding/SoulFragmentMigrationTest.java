package org.wyrdsekai.core.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@link SoulFragmentEmbeddingMigrator} against the
 * real {@code soul_fragments} schema (mirrored from the production DDL in
 * {@code SqlSoulStore} / {@code sqlite-create-schema.sql}).
 *
 * <p>This is the load-bearing migrator — soul_fragments is the canonical store
 * for personality vectors used by hybrid retrieval. Wrong-model embeddings
 * here silently corrupt every prompt assembly. Tests verify that:
 * <ul>
 *   <li>All rows with non-null embeddings get re-stamped with the new model.</li>
 *   <li>{@code embedding_model} column is updated alongside the bytes.</li>
 *   <li>Composite cursor (did|fragment_id) handles multi-DID resume correctly.</li>
 *   <li>Rows with null source text are skipped (no embedder call).</li>
 *   <li>Row count is preserved (we UPDATE, never INSERT or DELETE).</li>
 * </ul>
 */
class SoulFragmentMigrationTest {

    private String jdbcUrl;
    private static final String NEW_MODEL = "multilingual-MiniLM-L12-v2-setfit-2026-05-25";
    private static final String OLD_MODEL = "all-MiniLM-L6-v2-legacy";

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "soul-mig-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var st = keepAlive.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS soul_fragments(
                  did                  TEXT NOT NULL,
                  fragment_id          TEXT NOT NULL,
                  category             TEXT NOT NULL DEFAULT 'memory',
                  label                TEXT,
                  fragment_text        TEXT,
                  embedding            BLOB,
                  embedding_model      TEXT,
                  formative            INTEGER NOT NULL DEFAULT 0,
                  confidence           REAL NOT NULL DEFAULT 0.5,
                  reinforcement_count  INTEGER NOT NULL DEFAULT 0,
                  first_observed       INTEGER,
                  last_confirmed       INTEGER,
                  valid_from           INTEGER,
                  superseded_at        INTEGER,
                  superseded_by        TEXT,
                  ordinal              INTEGER NOT NULL DEFAULT 0,
                  updated_at           INTEGER NOT NULL DEFAULT (unixepoch()),
                  PRIMARY KEY (did, fragment_id)
                )
                """);
        }
    }

    private void seedFragment(String did, String fragId, String text,
                              float[] oldEmbedding) throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO soul_fragments(did, fragment_id, category, label, "
                     + "fragment_text, embedding, embedding_model) "
                     + "VALUES(?, ?, 'memory', ?, ?, ?, ?)")) {
            ps.setString(1, did);
            ps.setString(2, fragId);
            ps.setString(3, "lbl-" + fragId);
            ps.setString(4, text);
            ps.setBytes(5, encode(oldEmbedding));
            ps.setString(6, OLD_MODEL);
            ps.executeUpdate();
        }
    }

    private static byte[] encode(float[] v) {
        if (v == null) return null;
        var buf = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var f : v) buf.putFloat(f);
        return buf.array();
    }

    private static float[] decode(byte[] bytes) {
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buf.getFloat();
        return out;
    }

    @Test
    void migration_reembedsAllRows_withNewModel() throws SQLException {
        seedFragment("did:key:alice", "f1", "I am calm.", new float[]{1, 0, 0});
        seedFragment("did:key:alice", "f2", "I greet warmly.", new float[]{0, 1, 0});
        seedFragment("did:key:bob", "f1", "I prefer silence.", new float[]{0, 0, 1});

        var calls = new AtomicInteger();
        EmbeddingMigrator.Embedder embedder = text -> {
            calls.incrementAndGet();
            // Different magic vector per text so we can verify per-row write.
            return new float[]{(float) text.length(), 0.5f, -0.5f};
        };
        var mig = new EmbeddingMigration(jdbcUrl, embedder, NEW_MODEL,
            List.of(new SoulFragmentEmbeddingMigrator()), 10);
        mig.run();

        assertThat(calls.get()).isEqualTo(3);
        // Row count preserved.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM soul_fragments")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
        // Every row's embedding_model is the new one.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery(
                 "SELECT COUNT(*) FROM soul_fragments WHERE embedding_model = '"
                     + NEW_MODEL + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
        // Spot-check a known fragment's embedding bytes match what the
        // embedder emitted for that source text.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT fragment_text, embedding FROM soul_fragments "
                     + "WHERE did = ? AND fragment_id = ?")) {
            ps.setString(1, "did:key:alice");
            ps.setString(2, "f1");
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                var text = rs.getString(1);
                var decoded = decode(rs.getBytes(2));
                assertThat(decoded).hasSize(3);
                assertThat(decoded[0]).isEqualTo((float) text.length());
            }
        }
    }

    @Test
    void migration_skipsNullSourceText_butStillRunsRestOfTable() throws SQLException {
        seedFragment("did:key:alice", "f1", "I am calm.", new float[]{1, 0});
        seedFragment("did:key:alice", "f2", null, new float[]{0, 1});
        seedFragment("did:key:alice", "f3", "Third fragment.", new float[]{0, 0});

        var calls = new AtomicInteger();
        EmbeddingMigrator.Embedder embedder = text -> {
            calls.incrementAndGet();
            return new float[]{0.1f, 0.2f};
        };
        new EmbeddingMigration(jdbcUrl, embedder, NEW_MODEL,
            List.of(new SoulFragmentEmbeddingMigrator()), 10).run();

        assertThat(calls.get()).isEqualTo(2);
        // f2 still has the OLD model stamp — no source means no re-embed.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT embedding_model FROM soul_fragments "
                     + "WHERE did = ? AND fragment_id = ?")) {
            ps.setString(1, "did:key:alice");
            ps.setString(2, "f2");
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(OLD_MODEL);
            }
        }
    }

    @Test
    void migration_resumesAcrossDidBoundary() throws SQLException {
        // Three DIDs, two fragments each — six rows total. With batch size 1
        // we commit per-row, so an interrupt mid-DID still has a stable cursor.
        seedFragment("did:key:a", "f1", "alpha-one", new float[]{1, 0});
        seedFragment("did:key:a", "f2", "alpha-two", new float[]{2, 0});
        seedFragment("did:key:b", "f1", "bravo-one", new float[]{3, 0});
        seedFragment("did:key:b", "f2", "bravo-two", new float[]{4, 0});
        seedFragment("did:key:c", "f1", "charlie-one", new float[]{5, 0});
        seedFragment("did:key:c", "f2", "charlie-two", new float[]{6, 0});

        // First run: blow up after the third successful embed (mid did:b).
        var calls1 = new AtomicInteger();
        EmbeddingMigrator.Embedder failing = text -> {
            int n = calls1.incrementAndGet();
            if (n == 4) throw new RuntimeException("crash");
            return new float[]{0.5f, 0.5f};
        };
        var mig1 = new EmbeddingMigration(jdbcUrl, failing, NEW_MODEL,
            List.of(new SoulFragmentEmbeddingMigrator()), 1);
        try { mig1.run(); } catch (RuntimeException ignored) {}

        // Three rows successfully migrated, three not.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM soul_fragments "
                 + "WHERE embedding_model = '" + NEW_MODEL + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }

        // Resume — this time a healthy embedder finishes the rest.
        var calls2 = new AtomicInteger();
        EmbeddingMigrator.Embedder healthy = text -> {
            calls2.incrementAndGet();
            return new float[]{0.5f, 0.5f};
        };
        new EmbeddingMigration(jdbcUrl, healthy, NEW_MODEL,
            List.of(new SoulFragmentEmbeddingMigrator()), 1).run();

        // Only the remaining 3 rows got embedded.
        assertThat(calls2.get()).isEqualTo(3);
        // All 6 rows now carry the new model stamp.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM soul_fragments "
                 + "WHERE embedding_model = '" + NEW_MODEL + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(6);
        }
    }

    @Test
    void migration_skipsRowsWithoutEmbedding() throws SQLException {
        // Rows that never had an embedding shouldn't be in scope — the
        // SoulMaintenanceCycle owns initial embedding, the migrator owns
        // re-embedding existing vectors. Verify the SQL filter excludes them.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO soul_fragments(did, fragment_id, category, label, "
                     + "fragment_text, embedding, embedding_model) "
                     + "VALUES(?, ?, 'memory', ?, ?, NULL, NULL)")) {
            ps.setString(1, "did:key:a");
            ps.setString(2, "no-embed");
            ps.setString(3, "lbl");
            ps.setString(4, "this never got embedded");
            ps.executeUpdate();
        }
        seedFragment("did:key:a", "has-embed", "this one is embedded",
            new float[]{1, 1});

        var calls = new AtomicInteger();
        EmbeddingMigrator.Embedder embedder = text -> {
            calls.incrementAndGet();
            return new float[]{0.5f, 0.5f};
        };
        new EmbeddingMigration(jdbcUrl, embedder, NEW_MODEL,
            List.of(new SoulFragmentEmbeddingMigrator()), 10).run();

        // Only the row that already had an embedding gets re-embedded.
        assertThat(calls.get()).isEqualTo(1);
    }
}
