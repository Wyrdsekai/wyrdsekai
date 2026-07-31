package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * #1036 — unit coverage for the substrate-pressure store.
 *
 * <p>Real SQLite under a {@code @TempDir}. Validates:</p>
 * <ul>
 *   <li>{@link SubstratePressureStore#recordSample} clamps to [0,1] and
 *       round-trips into {@link SubstratePressureStore#sampleCount}.</li>
 *   <li>{@link SubstratePressureStore#aggregateMean} respects the
 *       {@code windowDays} cutoff (rows outside the window are excluded).</li>
 *   <li>{@link SubstratePressureStore#aggregateP95} returns the value
 *       at the 95th-percentile bucket boundary.</li>
 *   <li>{@link SubstratePressureStore#pruneOlderThan} drops only stale rows.</li>
 *   <li>Aggregator falls back to {@code 0.0} on empty range
 *       (welfare-permissive default).</li>
 * </ul>
 */
class SubstratePressureStoreTest {

    private String jdbcUrl;
    private SubstratePressureStore store;
    private static final String DID = "did:test:bondholder";

    @BeforeEach
    void setUp(@TempDir Path tmp) throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tmp.resolve("substrate.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            // other_did added by Arc 3 (#1064) — the store's
            // recordSample now INSERTs it. The hand-rolled schema here must match
            // or every insert silently fails (caught + warned → sampleCount=0).
            st.execute("CREATE TABLE substrate_pressure_samples("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "did TEXT NOT NULL, other_did TEXT, ts_ms INTEGER NOT NULL,"
                + "head TEXT NOT NULL, score REAL NOT NULL,"
                + "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000))");
        }
        store = new SubstratePressureStore(jdbcUrl);
    }

    @Test
    void recordSample_round_trips_via_sampleCount() {
        store.recordSample(DID, SubstratePressureStore.DEFAULT_HEAD, 0.42);
        store.recordSample(DID, SubstratePressureStore.DEFAULT_HEAD, 0.55);
        assertThat(store.sampleCount(DID)).isEqualTo(2);
        // Different DID isolation
        assertThat(store.sampleCount("did:test:other")).isZero();
    }

    @Test
    void recordSample_clamps_out_of_range_to_unit_interval() {
        store.recordSample(DID, SubstratePressureStore.DEFAULT_HEAD, 1.7);
        store.recordSample(DID, SubstratePressureStore.DEFAULT_HEAD, -0.3);
        // Clamped values mean = (1.0 + 0.0) / 2 = 0.5
        assertThat(store.aggregateMean(DID, 30)).isEqualTo(0.5);
    }

    @Test
    void aggregateMean_averages_only_in_window() throws SQLException {
        long now = System.currentTimeMillis();
        long oneDayAgo = now - 24L * 3600L * 1000L;
        long fortyDaysAgo = now - 40L * 24L * 3600L * 1000L;
        // Inject samples at specific timestamps via direct SQL (the
        // public API uses System.currentTimeMillis(), so we need direct
        // SQL for the time-windowed test).
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            insertAt(conn, oneDayAgo, 0.40);
            insertAt(conn, oneDayAgo + 1000, 0.60);
            insertAt(conn, fortyDaysAgo, 0.99);  // outside 30-day window
        }
        // 30-day window: 0.40 + 0.60 = 0.50 mean (the 40-day-old 0.99 is excluded)
        assertThat(store.aggregateMean(DID, 30)).isEqualTo(0.5);
        // 60-day window: all three rows → (0.40+0.60+0.99)/3 = 0.6633…
        assertThat(store.aggregateMean(DID, 60)).isBetween(0.66, 0.67);
    }

    @Test
    void aggregateMean_returns_zero_when_no_samples_in_window() {
        // Welfare-permissive default: no data → 0.0 → gate passes.
        assertThat(store.aggregateMean(DID, 30)).isEqualTo(0.0);
    }

    @Test
    void aggregateP95_returns_high_percentile_score() {
        // 20 samples, scores 0.05, 0.10, ..., 1.00 (each 0.05 apart).
        for (int i = 1; i <= 20; i++) {
            store.recordSample(DID, SubstratePressureStore.DEFAULT_HEAD, i * 0.05);
        }
        // 95th percentile at n=20: floor(0.95*20)-1 = offset 18 → 19th sorted
        // value (0-indexed) → score 0.95.
        assertThat(store.aggregateP95(DID, 30))
            .isCloseTo(0.95, within(1e-9));
    }

    @Test
    void pruneOlderThan_drops_only_stale_rows() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            insertAt(conn, now, 0.4);                              // fresh
            insertAt(conn, now - 200L * 24L * 3600L * 1000L, 0.9); // 200d old
        }
        assertThat(store.sampleCount(DID)).isEqualTo(2);
        int pruned = store.pruneOlderThan(90);  // retention=90d
        assertThat(pruned).isEqualTo(1);
        assertThat(store.sampleCount(DID)).isEqualTo(1);
    }

    @Test
    void recentCount_honours_window_and_head() throws SQLException {
        long now = System.currentTimeMillis();
        long tenMinAgo = now - 10L * 60L * 1000L;
        long ninetyMinAgo = now - 90L * 60L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Three directed_harm hits inside a recent window…
            insertAt(conn, tenMinAgo, "directed_harm", 0.6);
            insertAt(conn, tenMinAgo + 1000, "directed_harm", 0.7);
            insertAt(conn, tenMinAgo + 2000, "directed_harm", 0.8);
            // …one older than the window (must roll off)…
            insertAt(conn, ninetyMinAgo, "directed_harm", 0.9);
            // …and a same-window sample under a DIFFERENT head (must not count).
            insertAt(conn, tenMinAgo, SubstratePressureStore.DEFAULT_HEAD, 0.5);
        }
        long since = now - 45L * 60L * 1000L;  // 45-minute window
        // Only the three fresh directed_harm rows clear both filters.
        assertThat(store.recentCount(DID, "directed_harm", since)).isEqualTo(3);
        // Other-head sample is invisible to the directed_harm reader.
        assertThat(store.recentCount(DID, SubstratePressureStore.DEFAULT_HEAD, since))
            .isEqualTo(1);
        // DID isolation + fail-safe on nulls.
        assertThat(store.recentCount("did:test:other", "directed_harm", since)).isZero();
        assertThat(store.recentCount(null, "directed_harm", since)).isZero();
        assertThat(store.recentCount(DID, null, since)).isZero();
    }

    private void insertAt(Connection conn, long ts, double score) throws SQLException {
        insertAt(conn, ts, SubstratePressureStore.DEFAULT_HEAD, score);
    }

    private void insertAt(Connection conn, long ts, String head, double score)
            throws SQLException {
        try (var ps = conn.prepareStatement(
            "INSERT INTO substrate_pressure_samples(did, ts_ms, head, score) "
                + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, DID);
            ps.setLong(2, ts);
            ps.setString(3, head);
            ps.setDouble(4, score);
            ps.executeUpdate();
        }
    }
}
