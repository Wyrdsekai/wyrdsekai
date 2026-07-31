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

/**
 * #1037 — unit coverage for the conversation-turns store.
 *
 * <p>Validates round-trip, lookback windowing, min-chars filter,
 * distinct-day aggregation, and retention pruning. Same harness shape
 * as {@link SubstratePressureStoreTest}.</p>
 */
class ConversationTurnStoreTest {

    private static final String COMPANION = "did:test:companion";
    private static final String BONDHOLDER = "did:test:bondholder";

    private String jdbcUrl;
    private ConversationTurnStore store;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tmp.resolve("turns.db").toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE conversation_turns("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "companion_did TEXT NOT NULL, bondholder_did TEXT NOT NULL,"
                + "turn_role TEXT NOT NULL, content TEXT NOT NULL,"
                + "ts_ms INTEGER NOT NULL, room_id TEXT,"
                + "created_at INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000))");
        }
        store = new ConversationTurnStore(jdbcUrl);
    }

    @Test
    void recordTurn_round_trips_via_turnCount() {
        store.recordTurn(COMPANION, BONDHOLDER, ConversationTurnStore.ROLE_HEARD,
            "Hello, world", "study");
        store.recordTurn(COMPANION, BONDHOLDER, ConversationTurnStore.ROLE_SPOKEN,
            "Hi back", "study");
        assertThat(store.turnCount(COMPANION, BONDHOLDER)).isEqualTo(2);
        assertThat(store.turnCount(COMPANION, "did:other:bondholder")).isZero();
    }

    @Test
    void recordTurn_skips_blank_or_null_content() {
        store.recordTurn(COMPANION, BONDHOLDER,
            ConversationTurnStore.ROLE_HEARD, null, "study");
        store.recordTurn(COMPANION, BONDHOLDER,
            ConversationTurnStore.ROLE_HEARD, "   ", "study");
        assertThat(store.turnCount(COMPANION, BONDHOLDER)).isZero();
    }

    @Test
    void recentBondholderTurns_filters_by_role_and_lookback_and_minChars() throws SQLException {
        long now = System.currentTimeMillis();
        long oneDayAgo = now - 24L * 3600L * 1000L;
        long oneHundredDaysAgo = now - 100L * 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // HEARD turn from bondholder, within window, long enough.
            insertAt(conn, ConversationTurnStore.ROLE_HEARD,
                "I'm thinking carefully about this approach", oneDayAgo);
            // HEARD turn but too short (minChars=10).
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "hi", oneDayAgo + 1);
            // SPOKEN turn (excluded — only HEARD for bondholder pair mining).
            insertAt(conn, ConversationTurnStore.ROLE_SPOKEN,
                "I see what you mean", oneDayAgo + 2);
            // HEARD turn but outside 90-day window.
            insertAt(conn, ConversationTurnStore.ROLE_HEARD,
                "That was a long time ago, friend", oneHundredDaysAgo);
        }
        var rows = store.recentBondholderTurns(
            COMPANION, BONDHOLDER, /*lookbackDays*/ 90,
            /*minChars*/ 10, /*maxRows*/ 50);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).content()).startsWith("I'm thinking");
        assertThat(rows.get(0).role()).isEqualTo(ConversationTurnStore.ROLE_HEARD);
    }

    @Test
    void recentBondholderTurns_returns_empty_when_no_history() {
        var rows = store.recentBondholderTurns(
            COMPANION, BONDHOLDER, 90, 10, 50);
        assertThat(rows).isEmpty();
    }

    @Test
    void distinctDays_counts_unique_day_buckets() throws SQLException {
        long now = System.currentTimeMillis();
        long dayMs = 24L * 3600L * 1000L;
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            // Three turns today, two yesterday, one 3 days ago = 3 distinct days
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "today1", now);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "today2", now + 100);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "today3", now + 200);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "yest1", now - dayMs);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "yest2", now - dayMs + 100);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "3dago", now - 3 * dayMs);
        }
        assertThat(store.distinctDays(COMPANION, BONDHOLDER, 90)).isEqualTo(3);
    }

    @Test
    void pruneOlderThan_drops_only_stale_rows() throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "fresh", now);
            insertAt(conn, ConversationTurnStore.ROLE_HEARD, "stale",
                now - 200L * 24L * 3600L * 1000L);
        }
        assertThat(store.turnCount(COMPANION, BONDHOLDER)).isEqualTo(2);
        int pruned = store.pruneOlderThan(90);
        assertThat(pruned).isEqualTo(1);
        assertThat(store.turnCount(COMPANION, BONDHOLDER)).isEqualTo(1);
    }

    private void insertAt(Connection conn, String role, String content, long ts) throws SQLException {
        try (var ps = conn.prepareStatement(
            "INSERT INTO conversation_turns(companion_did, bondholder_did, "
                + "turn_role, content, ts_ms, room_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, COMPANION);
            ps.setString(2, BONDHOLDER);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setLong(5, ts);
            ps.setString(6, "study");
            ps.executeUpdate();
        }
    }
}
