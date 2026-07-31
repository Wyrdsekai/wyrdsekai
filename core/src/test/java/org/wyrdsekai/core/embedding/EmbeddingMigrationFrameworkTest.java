package org.wyrdsekai.core.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Framework-level coverage for {@link EmbeddingMigration} using stub embedder
 * + stub migrators. Verifies the four safety claims documented in the framework
 * javadoc:
 * <ul>
 *   <li>Resumable — interrupt mid-migration leaves a recoverable cursor.</li>
 *   <li>Idempotent — second {@code run()} is a no-op.</li>
 *   <li>Atomic per batch — failed batch rolls back to last committed cursor.</li>
 *   <li>Single column write — writeEmbedding overwrites the source column.</li>
 * </ul>
 *
 * <p>Plus CLI surface coverage: {@code plan()}, {@code status()}, {@code reset()}.
 */
class EmbeddingMigrationFrameworkTest {

    private String jdbcUrl;
    private static final String MODEL_V = "test-model-v1";
    private static final String MODEL_V2 = "test-model-v2";

    @BeforeEach
    void setUp() throws SQLException {
        // In-memory SQLite with a shared cache so multiple connections see
        // the same DB. UUID-suffixed name keeps tests isolated.
        var dbName = "embed-mig-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        // Hold one open connection for the duration of the test so the
        // shared in-memory DB doesn't get GC'd between framework calls.
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var st = keepAlive.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS test_items(
                  id TEXT PRIMARY KEY,
                  source_text TEXT,
                  embedding BLOB,
                  embedding_model TEXT
                )
                """);
        }
    }

    private void seed(String... pairs) throws SQLException {
        // Each pair is (id, source_text). Old "embedding" is filler bytes
        // representing the legacy model output we want to overwrite.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO test_items(id, source_text, embedding, embedding_model) "
                     + "VALUES(?, ?, ?, ?)")) {
            for (int i = 0; i < pairs.length; i += 2) {
                ps.setString(1, pairs[i]);
                ps.setString(2, pairs[i + 1]);
                ps.setBytes(3, new byte[]{0x01, 0x02, 0x03}); // pretend old vector
                ps.setString(4, "legacy-model");
                ps.executeUpdate();
            }
        }
    }

    private List<EmbeddingMigrator> oneMigrator(AtomicInteger embedderCalls) {
        return List.of(new TestItemMigrator());
    }

    private EmbeddingMigrator.Embedder constantEmbedder(AtomicInteger calls) {
        // Deterministic 4-d vector so we can byte-compare round trips.
        return text -> {
            calls.incrementAndGet();
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        };
    }

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    void run_migratesAllRows_andMarksComplete() throws SQLException {
        seed("a", "alpha", "b", "bravo", "c", "charlie");
        var calls = new AtomicInteger();
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls),
            MODEL_V, oneMigrator(calls), 10);

        var report = mig.run();

        assertThat(report.totalRows()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(3);
        // Every row carries the new model version + overwritten embedding.
        assertModelStamped("a", MODEL_V);
        assertModelStamped("b", MODEL_V);
        assertModelStamped("c", MODEL_V);
        // State row says complete.
        var states = mig.status();
        assertThat(states).hasSize(1);
        assertThat(states.get(0).completedAt()).isNotNull();
        assertThat(states.get(0).processedCount()).isEqualTo(3);
        assertThat(states.get(0).modelVersion()).isEqualTo(MODEL_V);
    }

    @Test
    void run_isIdempotent_whenAlreadyDone() throws SQLException {
        seed("a", "alpha", "b", "bravo");
        var calls = new AtomicInteger();
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls),
            MODEL_V, oneMigrator(calls), 10);

        mig.run();
        int firstCallCount = calls.get();
        // Second run on same model version must short-circuit.
        var report2 = mig.run();
        assertThat(calls.get()).isEqualTo(firstCallCount);
        assertThat(report2.tables()).hasSize(1);
        assertThat(report2.tables().get(0).skipped()).isTrue();
        assertThat(report2.tables().get(0).rowsMigrated()).isEqualTo(0);
    }

    @Test
    void run_skipsBlankSourceText_andAdvancesCursor() throws SQLException {
        // Row "b" has null source — embedder should not be called for it,
        // but cursor must still advance past it so resume works.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO test_items(id, source_text, embedding, embedding_model) "
                     + "VALUES(?, ?, ?, ?)")) {
            ps.setString(1, "a"); ps.setString(2, "alpha");
            ps.setBytes(3, new byte[]{0}); ps.setString(4, "legacy");
            ps.executeUpdate();
            ps.setString(1, "b"); ps.setNull(2, Types.VARCHAR);
            ps.setBytes(3, new byte[]{0}); ps.setString(4, "legacy");
            ps.executeUpdate();
            ps.setString(1, "c"); ps.setString(2, "charlie");
            ps.setBytes(3, new byte[]{0}); ps.setString(4, "legacy");
            ps.executeUpdate();
        }

        var calls = new AtomicInteger();
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls),
            MODEL_V, oneMigrator(calls), 10);
        mig.run();

        // Embedder skipped the null row.
        assertThat(calls.get()).isEqualTo(2);
        // a + c re-stamped, b kept the legacy stamp (no source to embed).
        assertModelStamped("a", MODEL_V);
        assertModelStamped("c", MODEL_V);
        assertModelStamped("b", "legacy");
    }

    // ── Resumability ────────────────────────────────────────────────────

    @Test
    void run_resumesFromCursor_afterSimulatedInterrupt() throws SQLException {
        seed("a", "alpha", "b", "bravo", "c", "charlie", "d", "delta", "e", "echo");

        // First run: blow up after the third row to simulate a kill -9.
        var calls1 = new AtomicInteger();
        EmbeddingMigrator.Embedder failingEmbedder = text -> {
            int n = calls1.incrementAndGet();
            if (n == 4) throw new RuntimeException("simulated crash");
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        };
        var batchSize = 1; // commit per row → cursor advances at row granularity
        var mig1 = new EmbeddingMigration(jdbcUrl, failingEmbedder,
            MODEL_V, oneMigrator(calls1), batchSize);
        try { mig1.run(); } catch (RuntimeException ignored) { /* expected */ }

        var status1 = mig1.status();
        assertThat(status1).hasSize(1);
        // Three rows committed, one failed, one not yet attempted.
        assertThat(status1.get(0).processedCount()).isEqualTo(3);
        assertThat(status1.get(0).completedAt()).isNull();
        assertThat(status1.get(0).lastProcessedId()).isEqualTo("c");

        // Second run: resume cleanly with a healthy embedder.
        var calls2 = new AtomicInteger();
        var mig2 = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls2),
            MODEL_V, oneMigrator(calls2), batchSize);
        var report = mig2.run();

        // Only 2 rows left after resume (d, e). Embedder called twice.
        assertThat(calls2.get()).isEqualTo(2);
        assertThat(report.totalRows()).isEqualTo(5); // total is cumulative count
        assertModelStamped("d", MODEL_V);
        assertModelStamped("e", MODEL_V);
        assertThat(mig2.status().get(0).completedAt()).isNotNull();
    }

    // ── Idempotency / reset ─────────────────────────────────────────────

    @Test
    void reset_clearsState_andForcesReMigration() throws SQLException {
        seed("a", "alpha", "b", "bravo");
        var calls = new AtomicInteger();
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls),
            MODEL_V, oneMigrator(calls), 10);
        mig.run();
        assertThat(calls.get()).isEqualTo(2);

        // Reset → next run sees fresh state and re-embeds.
        var removed = mig.reset("test_items");
        assertThat(removed).isTrue();
        mig.run();
        assertThat(calls.get()).isEqualTo(4);

        // Resetting an unknown table is reported as "no row deleted".
        assertThat(mig.reset("does_not_exist")).isFalse();
    }

    @Test
    void modelVersionBump_restartsFromBeginning() throws SQLException {
        seed("a", "alpha", "b", "bravo");

        var calls1 = new AtomicInteger();
        new EmbeddingMigration(jdbcUrl, constantEmbedder(calls1),
            MODEL_V, oneMigrator(calls1), 10).run();
        assertThat(calls1.get()).isEqualTo(2);

        // New model version → state row mismatches, framework restarts.
        var calls2 = new AtomicInteger();
        var mig2 = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls2),
            MODEL_V2, oneMigrator(calls2), 10);
        var rep = mig2.run();
        assertThat(calls2.get()).isEqualTo(2);
        assertThat(rep.totalRows()).isEqualTo(2);
        assertModelStamped("a", MODEL_V2);
        assertModelStamped("b", MODEL_V2);
    }

    // ── Plan / status ───────────────────────────────────────────────────

    @Test
    void plan_listsPendingTables_withoutWriting() throws SQLException {
        seed("a", "alpha", "b", "bravo");
        var calls = new AtomicInteger();
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(calls),
            MODEL_V, oneMigrator(calls), 10);
        var plan = mig.plan();

        // Plan touches nothing → embedder uncalled, embedding columns unchanged.
        assertThat(calls.get()).isZero();
        assertThat(plan.entries()).hasSize(1);
        assertThat(plan.entries().get(0).tableName()).isEqualTo("test_items");
        assertThat(plan.entries().get(0).estimatedRows()).isEqualTo(2);
        assertThat(plan.entries().get(0).alreadyComplete()).isFalse();
        assertThat(plan.totalRows()).isEqualTo(2);

        // After a run, plan flips alreadyComplete=true.
        mig.run();
        var planAfter = mig.plan();
        assertThat(planAfter.entries().get(0).alreadyComplete()).isTrue();
        // Row count drops out of the pending sum.
        assertThat(planAfter.totalRows()).isZero();
    }

    @Test
    void registeredTables_listsAllMigrators() {
        var mig = new EmbeddingMigration(jdbcUrl, constantEmbedder(new AtomicInteger()),
            MODEL_V, List.of(new TestItemMigrator(), new SecondTableMigrator()), 10);
        assertThat(mig.registeredTables())
            .containsExactlyInAnyOrder("test_items", "test_items_two");
    }

    // ── Helpers / stubs ─────────────────────────────────────────────────

    private void assertModelStamped(String id, String expectedModel) throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT embedding, embedding_model FROM test_items WHERE id = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("embedding_model")).isEqualTo(expectedModel);
                if (!"legacy".equals(expectedModel) && !"legacy-model".equals(expectedModel)) {
                    var bytes = rs.getBytes("embedding");
                    assertThat(bytes).hasSize(4 * 4); // 4 floats × 4 bytes
                    var decoded = new float[4];
                    var buf = ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < 4; i++) decoded[i] = buf.getFloat();
                    assertThat(decoded).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
                }
            }
        }
    }

    /** Migrates the {@code test_items} table created in {@link #setUp}. */
    private static final class TestItemMigrator implements EmbeddingMigrator {
        @Override public String tableName() { return "test_items"; }

        @Override public int estimateRowCount(Connection conn) throws SQLException {
            try (var st = conn.prepareStatement(
                    "SELECT COUNT(*) FROM test_items WHERE source_text IS NOT NULL");
                 var rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }

        @Override public List<Row> listBatchAfter(Connection conn, String afterCursor,
                                                  int batchSize) throws SQLException {
            var sql = afterCursor == null
                ? "SELECT id, source_text FROM test_items ORDER BY id LIMIT ?"
                : "SELECT id, source_text FROM test_items WHERE id > ? "
                    + "ORDER BY id LIMIT ?";
            var out = new ArrayList<Row>();
            try (var ps = conn.prepareStatement(sql)) {
                if (afterCursor == null) ps.setInt(1, batchSize);
                else { ps.setString(1, afterCursor); ps.setInt(2, batchSize); }
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Row(rs.getString(1), rs.getString(2)));
                    }
                }
            }
            return out;
        }

        @Override public void writeEmbedding(Connection conn, String cursor,
                                             float[] embedding, String modelVersion)
                throws SQLException {
            try (var ps = conn.prepareStatement(
                    "UPDATE test_items SET embedding = ?, embedding_model = ? WHERE id = ?")) {
                ps.setBytes(1, EmbeddingMigrator.encode(embedding));
                ps.setString(2, modelVersion);
                ps.setString(3, cursor);
                ps.executeUpdate();
            }
        }
    }

    /** Empty second migrator just to verify multi-table registration. */
    private static final class SecondTableMigrator implements EmbeddingMigrator {
        @Override public String tableName() { return "test_items_two"; }
        @Override public int estimateRowCount(Connection conn) { return 0; }
        @Override public List<Row> listBatchAfter(Connection c, String a, int n) {
            return List.of();
        }
        @Override public void writeEmbedding(Connection c, String cur, float[] v, String m) {}
    }

    /** Sanity check on the encode/decode pair (matches SoulFragmentStore's format). */
    @Test
    void encode_roundTripsBitExact() {
        float[] in = {0.1f, -0.5f, 1.0f, Float.MIN_NORMAL};
        var bytes = EmbeddingMigrator.encode(in);
        assertThat(bytes).hasSize(in.length * 4);
        var buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[in.length];
        for (int i = 0; i < in.length; i++) out[i] = buf.getFloat();
        assertThat(out).containsExactly(in);
        // Defensive: empty/null in → null out (callers expect setNull on null bytes).
        assertThat(EmbeddingMigrator.encode(null)).isNull();
        assertThat(EmbeddingMigrator.encode(new float[0])).isNull();
    }

    @Test
    void toArray_convertsListFloatToFloatArray() {
        var list = Arrays.asList(0.5f, -0.25f, 1.0f);
        var arr = EmbeddingMigrator.toArray(list);
        assertThat(arr).containsExactly(0.5f, -0.25f, 1.0f);
    }
}
