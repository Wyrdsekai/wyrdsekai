package org.wyrdsekai.core.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.search.EmbeddingService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One-shot re-embed migration tool. Walks every store with persisted embeddings
 * and rewrites the embedding column using the currently bundled
 * {@link EmbeddingService} model.
 *
 * <p>Invoked via {@code wyrd embed-migrate} (CLI in {@code EmbeddingMigrationMain}),
 * never automatically. Operator runs it manually after reviewing the plan.
 *
 * <h2>Safety guarantees</h2>
 * <ul>
 *   <li><b>Resumable:</b> {@code embedding_migrations} table tracks per-table
 *       cursor. An interrupt mid-batch leaves at most {@code batchSize} rows
 *       to redo on resume.</li>
 *   <li><b>Idempotent:</b> a table marked {@code completed_at != null} for
 *       the current model version is skipped on re-run.</li>
 *   <li><b>Atomic per batch:</b> each batch commits cursor + embeddings in one
 *       transaction. Crash mid-batch rolls back to last committed cursor.</li>
 *   <li><b>Single column write:</b> embeddings overwrite the existing column.
 *       No parallel column or shadow table.</li>
 * </ul>
 *
 * <h2>State table schema</h2>
 * <pre>{@code
 * CREATE TABLE embedding_migrations(
 *   table_name        TEXT PRIMARY KEY,
 *   model_version     TEXT NOT NULL,
 *   last_processed_id TEXT,
 *   started_at        INTEGER NOT NULL,
 *   completed_at      INTEGER,
 *   processed_count   INTEGER NOT NULL DEFAULT 0
 * )
 * }</pre>
 *
 * <p>{@code (table_name, model_version)} is the effective uniqueness key —
 * but {@code table_name} alone is the PK because at most one model version is
 * "current" at a time. {@code reset} clears the row, forcing a re-run on the
 * next {@code --run} invocation.
 */
public final class EmbeddingMigration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMigration.class);

    /** Default batch size for chunked migration commits. */
    public static final int DEFAULT_BATCH_SIZE = 100;

    private final String jdbcUrl;
    private final SqlDialect dialect;
    private final EmbeddingMigrator.Embedder embedder;
    private final String modelVersion;
    private final List<EmbeddingMigrator> migrators;
    private final int batchSize;

    public EmbeddingMigration(String jdbcUrl,
                              EmbeddingMigrator.Embedder embedder,
                              String modelVersion,
                              List<EmbeddingMigrator> migrators) {
        this(jdbcUrl, embedder, modelVersion, migrators, DEFAULT_BATCH_SIZE);
    }

    public EmbeddingMigration(String jdbcUrl,
                              EmbeddingMigrator.Embedder embedder,
                              String modelVersion,
                              List<EmbeddingMigrator> migrators,
                              int batchSize) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        this.embedder = embedder;
        this.modelVersion = modelVersion;
        this.migrators = List.copyOf(migrators);
        this.batchSize = batchSize;
    }

    /** Default factory using bundled {@link EmbeddingService} + production migrators. */
    public static EmbeddingMigration createDefault(String jdbcUrl) {
        var svc = EmbeddingService.init();
        if (svc == null) {
            throw new IllegalStateException(
                "EmbeddingService unavailable — bundled model missing or failed to load. "
                    + "Migration cannot proceed.");
        }
        EmbeddingMigrator.Embedder embedder = text -> {
            var v = svc.embed(text);
            return EmbeddingMigrator.toArray(v);
        };
        var migrators = List.<EmbeddingMigrator>of(
            new SoulFragmentEmbeddingMigrator(),
            new ArtifactSignificanceEmbeddingMigrator()
        );
        return new EmbeddingMigration(jdbcUrl, embedder,
            EmbeddingService.currentModelVersion(), migrators);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Build a plan of pending work without writing anything. */
    public Plan plan() {
        var entries = new ArrayList<PlanEntry>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureStateTable(conn);
            for (var m : migrators) {
                int approx = safeEstimate(conn, m);
                var state = loadState(conn, m.tableName());
                boolean alreadyDone = state.isPresent()
                    && state.get().modelVersion().equals(modelVersion)
                    && state.get().completedAt() != null;
                entries.add(new PlanEntry(m.tableName(), approx,
                    state.map(MigrationState::lastProcessedId).orElse(null),
                    alreadyDone));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Plan failed: " + e.getMessage(), e);
        }
        return new Plan(modelVersion, entries);
    }

    /** Run the full migration. Returns a per-table summary. */
    public RunReport run(ProgressCallback progress) {
        var summaries = new ArrayList<TableSummary>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureStateTable(conn);
            for (var m : migrators) {
                summaries.add(migrateOne(conn, m, progress));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Migration failed: " + e.getMessage(), e);
        }
        return new RunReport(modelVersion, summaries);
    }

    /** {@link #run} convenience for tests / scripts that don't need progress. */
    public RunReport run() {
        return run((table, processed, total) -> {});
    }

    /** Read current state from {@code embedding_migrations}. */
    public List<MigrationState> status() {
        var out = new ArrayList<MigrationState>();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureStateTable(conn);
            try (var ps = conn.prepareStatement(
                    "SELECT table_name, model_version, last_processed_id, "
                        + "started_at, completed_at, processed_count "
                        + "FROM embedding_migrations ORDER BY table_name");
                 var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long started = rs.getLong("started_at");
                    long completed = rs.getLong("completed_at");
                    boolean completedNull = rs.wasNull();
                    out.add(new MigrationState(
                        rs.getString("table_name"),
                        rs.getString("model_version"),
                        rs.getString("last_processed_id"),
                        Instant.ofEpochSecond(started),
                        completedNull ? null : Instant.ofEpochSecond(completed),
                        rs.getInt("processed_count")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Status query failed: " + e.getMessage(), e);
        }
        return out;
    }

    /**
     * Clear the state row for one table. Next {@code run} will re-migrate that
     * table from the beginning.
     *
     * @return true if a row was deleted
     */
    public boolean reset(String tableName) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureStateTable(conn);
            try (var ps = conn.prepareStatement(
                    "DELETE FROM embedding_migrations WHERE table_name = ?")) {
                ps.setString(1, tableName);
                int n = ps.executeUpdate();
                return n > 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Reset failed: " + e.getMessage(), e);
        }
    }

    /** List of registered migrator table names — used by CLI {@code --reset} validation. */
    public List<String> registeredTables() {
        return migrators.stream().map(EmbeddingMigrator::tableName).toList();
    }

    // ── Per-table loop ──────────────────────────────────────────────────

    private TableSummary migrateOne(Connection conn, EmbeddingMigrator migrator,
                                    ProgressCallback progress) throws SQLException {
        var table = migrator.tableName();
        var stateOpt = loadState(conn, table);

        // Idempotency short-circuit: same model version already completed.
        if (stateOpt.isPresent()
            && stateOpt.get().modelVersion().equals(modelVersion)
            && stateOpt.get().completedAt() != null) {
            log.info("Embedding migration: {} already done for {} ({} rows)",
                table, modelVersion, stateOpt.get().processedCount());
            return new TableSummary(table, 0, true);
        }

        // Different model version → reset cursor to the beginning. Operator
        // intent: standardize on the new model, so partial progress against a
        // stale model isn't credited.
        String cursor = null;
        int already = 0;
        if (stateOpt.isPresent() && stateOpt.get().modelVersion().equals(modelVersion)) {
            cursor = stateOpt.get().lastProcessedId();
            already = stateOpt.get().processedCount();
        } else if (stateOpt.isPresent()) {
            log.info("Embedding migration: {} state was for {} — restarting for {}",
                table, stateOpt.get().modelVersion(), modelVersion);
        }

        // Mark started (or resumed)
        upsertState(conn, table, cursor, already, false);

        int totalEstimate = safeEstimate(conn, migrator);
        int processed = already;

        while (true) {
            var batch = migrator.listBatchAfter(conn, cursor, batchSize);
            if (batch.isEmpty()) break;

            // Per-batch transaction: the cursor checkpoint and the embeddings
            // commit together. If we crash mid-batch, the next run resumes
            // from the previous cursor and redoes this batch idempotently
            // (writeEmbedding is an UPDATE, not an INSERT — safe to repeat).
            conn.setAutoCommit(false);
            try {
                String lastCursor = cursor;
                for (var row : batch) {
                    if (row.sourceText() == null || row.sourceText().isBlank()) {
                        // Nothing to embed — advance cursor without writing.
                        lastCursor = row.cursor();
                        continue;
                    }
                    float[] vec = embedder.apply(row.sourceText());
                    migrator.writeEmbedding(conn, row.cursor(), vec, modelVersion);
                    lastCursor = row.cursor();
                    processed++;
                }
                upsertState(conn, table, lastCursor, processed, false);
                conn.commit();
                cursor = lastCursor;
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException(
                    "Migration batch failed at table=" + table
                        + " cursor=" + cursor + ": " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(true);
            }

            progress.onProgress(table, processed, totalEstimate);

            // Smaller-than-batch result means we drained the table.
            if (batch.size() < batchSize) break;
        }

        // Mark completed
        upsertState(conn, table, cursor, processed, true);
        log.info("Embedding migration: {} complete — {} rows re-embedded with {}",
            table, processed, modelVersion);
        return new TableSummary(table, processed, false);
    }

    // ── State table I/O ─────────────────────────────────────────────────

    private void ensureStateTable(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS embedding_migrations(
                  table_name        TEXT PRIMARY KEY,
                  model_version     TEXT NOT NULL,
                  last_processed_id TEXT,
                  started_at        INTEGER NOT NULL,
                  completed_at      INTEGER,
                  processed_count   INTEGER NOT NULL DEFAULT 0
                )
                """);
        }
    }

    private Optional<MigrationState> loadState(Connection conn, String tableName)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT table_name, model_version, last_processed_id, "
                    + "started_at, completed_at, processed_count "
                    + "FROM embedding_migrations WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                long started = rs.getLong("started_at");
                long completed = rs.getLong("completed_at");
                boolean completedNull = rs.wasNull();
                return Optional.of(new MigrationState(
                    rs.getString("table_name"),
                    rs.getString("model_version"),
                    rs.getString("last_processed_id"),
                    Instant.ofEpochSecond(started),
                    completedNull ? null : Instant.ofEpochSecond(completed),
                    rs.getInt("processed_count")
                ));
            }
        }
    }

    private void upsertState(Connection conn, String tableName, String cursor,
                             int processed, boolean done) throws SQLException {
        long now = Instant.now().getEpochSecond();
        // Read existing started_at if any so we don't reset it on resume.
        Long existingStartedAt = null;
        try (var ps = conn.prepareStatement(
                "SELECT started_at FROM embedding_migrations WHERE table_name = ?")) {
            ps.setString(1, tableName);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) existingStartedAt = rs.getLong(1);
            }
        }
        long startedAt = existingStartedAt != null ? existingStartedAt : now;
        Long completedAt = done ? now : null;

        String sql = dialect.upsert(
            "embedding_migrations",
            "table_name, model_version, last_processed_id, started_at, completed_at, processed_count",
            "?, ?, ?, ?, ?, ?",
            "table_name",
            "model_version = EXCLUDED.model_version, "
                + "last_processed_id = EXCLUDED.last_processed_id, "
                + "started_at = EXCLUDED.started_at, "
                + "completed_at = EXCLUDED.completed_at, "
                + "processed_count = EXCLUDED.processed_count");

        try (var ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, modelVersion);
            if (cursor == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, cursor);
            ps.setLong(4, startedAt);
            if (completedAt == null) ps.setNull(5, Types.BIGINT);
            else ps.setLong(5, completedAt);
            ps.setInt(6, processed);
            ps.executeUpdate();
        }
    }

    private static int safeEstimate(Connection conn, EmbeddingMigrator m) {
        try {
            return m.estimateRowCount(conn);
        } catch (SQLException e) {
            return -1; // unknown
        }
    }

    // ── Reporting types ─────────────────────────────────────────────────

    /** Progress callback — called once per batch with (table, processed, total). */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String tableName, int processed, int total);
    }

    public record MigrationState(
        String tableName,
        String modelVersion,
        String lastProcessedId,
        Instant startedAt,
        Instant completedAt,
        int processedCount
    ) {}

    public record PlanEntry(
        String tableName,
        int estimatedRows,
        String lastProcessedId,
        boolean alreadyComplete
    ) {}

    public record Plan(String modelVersion, List<PlanEntry> entries) {
        public int totalRows() {
            return entries.stream()
                .filter(e -> !e.alreadyComplete && e.estimatedRows > 0)
                .mapToInt(PlanEntry::estimatedRows)
                .sum();
        }
    }

    public record TableSummary(String tableName, int rowsMigrated, boolean skipped) {}

    public record RunReport(String modelVersion, List<TableSummary> tables) {
        public int totalRows() {
            return tables.stream().mapToInt(TableSummary::rowsMigrated).sum();
        }
    }
}
