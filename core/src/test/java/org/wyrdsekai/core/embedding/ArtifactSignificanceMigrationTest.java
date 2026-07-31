package org.wyrdsekai.core.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targeted coverage for {@link ArtifactSignificanceEmbeddingMigrator}.
 *
 * <p>Special-case migrator: artifact_significance stores embeddings without
 * the source text, so we can't re-emit. Migration policy is "null out the
 * legacy bytes" so semantic-ack falls back to keyword scoring rather than
 * comparing vectors fingerprinted by two different models.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Bulk null-out on first run (rows preserved, embeddings zeroed).</li>
 *   <li>Idempotency — second run is a no-op, no new writes.</li>
 *   <li>Missing-table tolerance — fresh DB doesn't crash {@code --plan}.</li>
 *   <li>Embedder is never called (per migrator contract).</li>
 *   <li>Row count preserved (we don't drop rows, only NULL the column).</li>
 * </ul>
 */
class ArtifactSignificanceMigrationTest {

    private String jdbcUrl;
    private static final String MODEL_V = "multilingual-test-2026";

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "art-sig-mig-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
    }

    private void createTable() throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement()) {
            st.execute("""
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
        }
    }

    private void seed(String did, String id, byte[] embedding) throws SQLException {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "INSERT INTO artifact_significance(companion_did, artifact_id, "
                     + "created_at, seen, kind, embedding) VALUES(?, ?, ?, 0, 'artifact', ?)")) {
            ps.setString(1, did);
            ps.setString(2, id);
            ps.setLong(3, 1714000000L);
            if (embedding == null) ps.setNull(4, Types.BINARY);
            else ps.setBytes(4, embedding);
            ps.executeUpdate();
        }
    }

    private EmbeddingMigration framework(AtomicInteger embedderCalls) {
        EmbeddingMigrator.Embedder embedder = text -> {
            embedderCalls.incrementAndGet();
            return new float[]{1, 2, 3, 4};
        };
        return new EmbeddingMigration(jdbcUrl, embedder, MODEL_V,
            List.of(new ArtifactSignificanceEmbeddingMigrator()), 100);
    }

    @Test
    void run_nullsOutAllLegacyEmbeddings_butKeepsRows() throws SQLException {
        createTable();
        seed("did:key:a", "art-1", new byte[]{0x10, 0x20, 0x30});
        seed("did:key:a", "art-2", new byte[]{0x40, 0x50, 0x60});
        seed("did:key:b", "art-3", new byte[]{0x70, 0x71, 0x72});

        var calls = new AtomicInteger();
        var mig = framework(calls);
        var report = mig.run();

        // Embedder NEVER called — migrator returns null source text.
        assertThat(calls.get()).isZero();
        // Every row's embedding is now NULL.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artifact_significance "
                 + "WHERE embedding IS NOT NULL")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
        // Row count preserved — we never dropped anything.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artifact_significance")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(3);
        }
        // Migrator reported the table.
        assertThat(report.tables()).hasSize(1);
        assertThat(report.tables().get(0).tableName()).isEqualTo("artifact_significance");
    }

    @Test
    void run_isIdempotent_secondRunIsNoOp() throws SQLException {
        createTable();
        seed("did:key:a", "art-1", new byte[]{1, 2, 3});

        var calls = new AtomicInteger();
        framework(calls).run();
        // Second run hits the "already done" short-circuit.
        var report2 = framework(calls).run();
        assertThat(report2.tables().get(0).skipped()).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test
    void plan_handlesMissingTable_withoutCrashing() throws SQLException {
        // Fresh DB with no artifact_significance — production case for nodes
        // that haven't yet generated an artifact. Plan must not crash, and
        // estimate must come back as zero.
        var calls = new AtomicInteger();
        var mig = framework(calls);
        var plan = mig.plan();
        assertThat(plan.entries()).hasSize(1);
        assertThat(plan.entries().get(0).estimatedRows()).isZero();
        assertThat(plan.entries().get(0).alreadyComplete()).isFalse();
    }

    @Test
    void run_handlesMissingTable_byMarkingComplete() throws SQLException {
        // No artifact_significance table → migrator yields a single synthetic
        // "done" row, framework records cursor + completed_at, exits cleanly.
        var calls = new AtomicInteger();
        var mig = framework(calls);
        mig.run();
        assertThat(calls.get()).isZero();
        var status = mig.status();
        assertThat(status).hasSize(1);
        assertThat(status.get(0).completedAt()).isNotNull();
    }

    @Test
    void run_skipsRowsThatAreAlreadyNull() throws SQLException {
        createTable();
        seed("did:key:a", "art-1", null); // already null
        seed("did:key:a", "art-2", new byte[]{9, 9, 9});

        var calls = new AtomicInteger();
        framework(calls).run();

        // Total rows preserved.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artifact_significance")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        // No row remains with embedding != null.
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM artifact_significance "
                 + "WHERE embedding IS NOT NULL")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
