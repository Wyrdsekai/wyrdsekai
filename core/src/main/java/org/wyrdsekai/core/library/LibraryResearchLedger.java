package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many research runs the household's library accepted for each person on each day.
 *
 * <p>The library is reached with one household token, so it cannot tell household members
 * apart; the household counts for itself. One row per person (canonical id, or
 * {@link #OWN_TIME} and the companion's id for a companion's own time) and local calendar day,
 * in {@code world.db} (migration 14), so a restart does not reset the day. {@link LibraryConsent}
 * reads and adds.
 */
public final class LibraryResearchLedger {

    private static final Logger log = LoggerFactory.getLogger(LibraryResearchLedger.class);

    /** The row prefix for a companion's own time: the questions no person asked, one row per companion. */
    public static final String OWN_TIME = "(own time)";

    private final String jdbcUrl;
    /** Used when no database is wired (tests and bare boots): counts for the life of the process. */
    private final Map<String, Integer> memory;

    /** The ledger in {@code world.db}; creates its table when it is missing. */
    public LibraryResearchLedger(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.memory = null;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureTable(conn);
        } catch (SQLException e) {
            log.warn("[library] the research ledger's table could not be made: {}", e.getMessage());
        }
    }

    private LibraryResearchLedger() {
        this.jdbcUrl = null;
        this.memory = new ConcurrentHashMap<>();
    }

    /** A ledger kept in memory only. */
    public static LibraryResearchLedger inMemory() {
        return new LibraryResearchLedger();
    }

    /** The table (migration 14): the same statement on SQLite and PostgreSQL. */
    public static void ensureTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_research_asks (
                  who  TEXT NOT NULL,
                  day  TEXT NOT NULL,
                  asks INTEGER NOT NULL DEFAULT 0,
                  PRIMARY KEY (who, day))""");
        }
    }

    /** Research runs the library accepted for {@code who} on {@code day}. */
    public int asks(String who, LocalDate day) throws SQLException {
        if (who == null || day == null) return 0;
        if (memory != null) return memory.getOrDefault(who + "|" + day, 0);
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT asks FROM library_research_asks WHERE who = ? AND day = ?")) {
            stmt.setString(1, who);
            stmt.setString(2, day.toString());
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Research runs the library accepted on {@code day}, everyone's together. */
    public int total(LocalDate day) throws SQLException {
        if (day == null) return 0;
        if (memory != null) {
            var suffix = "|" + day;
            return memory.entrySet().stream().filter(e -> e.getKey().endsWith(suffix))
                .mapToInt(Map.Entry::getValue).sum();
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.prepareStatement(
                 "SELECT COALESCE(SUM(asks), 0) FROM library_research_asks WHERE day = ?")) {
            stmt.setString(1, day.toString());
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** One more accepted run for {@code who} on {@code day}. */
    public void add(String who, LocalDate day) throws SQLException {
        if (who == null || day == null) return;
        if (memory != null) {
            memory.merge(who + "|" + day, 1, Integer::sum);
            return;
        }
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            if (increment(conn, who, day) > 0) return;
            try (var ins = conn.prepareStatement(
                    "INSERT INTO library_research_asks (who, day, asks) VALUES (?, ?, 1)")) {
                ins.setString(1, who);
                ins.setString(2, day.toString());
                ins.executeUpdate();
            } catch (SQLException raced) {
                // Another writer made the day's row first: count on it.
                if (increment(conn, who, day) == 0) throw raced;
            }
        }
    }

    private static int increment(Connection conn, String who, LocalDate day) throws SQLException {
        try (var upd = conn.prepareStatement(
                "UPDATE library_research_asks SET asks = asks + 1 WHERE who = ? AND day = ?")) {
            upd.setString(1, who);
            upd.setString(2, day.toString());
            return upd.executeUpdate();
        }
    }
}
