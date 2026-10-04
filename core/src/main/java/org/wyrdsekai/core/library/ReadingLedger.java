package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where each companion is in each book: the bookmark. Kept in the world database, so a book she
 * started before a restart is still open at the same page after it.
 *
 * <p>The library was a search engine: every read returned the passage most like her words, never
 * the next page, and "finish reading" could neither land nor miss. One companion visited that want
 * 28 times in a day (household node, 2026-10-03). operator: "we need to build a means for them to be
 * able to read books from start to finish. also to move on to the next book. they need to learn."
 */
public final class ReadingLedger {

    private static final Logger log = LoggerFactory.getLogger(ReadingLedger.class);

    /** One bookmark. {@code part} is the last part read, 0 before the first; {@code total} the book's parts. */
    public record Bookmark(String agentDid, String book, String collection, String ownerDid, String studyCollection,
                           int part, int total, long startedAt, long lastReadAt, Long finishedAt) {
        public boolean finished() { return finishedAt != null; }
        /** The part to read next, from 1. */
        public int next() { return Math.min(part + 1, Math.max(total, 1)); }
        /** The book's name without a file ending: "The New Eve - Robert Lewis". */
        public String title() { return titleOf(book); }
    }

    private static volatile ReadingLedger SHARED;

    public static void install(ReadingLedger l) { SHARED = l; }
    public static ReadingLedger shared() { return SHARED; }

    private final String jdbcUrl;

    public ReadingLedger(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Migration 22: the table. Safe to run again. */
    public static void ensure(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS reading_bookmarks ("
                + "agent_did TEXT NOT NULL, book TEXT NOT NULL, collection TEXT NOT NULL, owner_did TEXT, "
                + "study_collection TEXT, part INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0, "
                + "started_at BIGINT NOT NULL, last_read_at BIGINT NOT NULL, finished_at BIGINT, "
                + "PRIMARY KEY (agent_did, book))");
        }
    }

    /** "The New Eve - Robert Lewis.epub" → "The New Eve - Robert Lewis". */
    public static String titleOf(String book) {
        if (book == null) return "";
        return book.replaceAll("\\.(epub|pdf|txt|md|html?|mobi|azw3?|docx?)$", "").strip();
    }

    /** Open a book, or come back to it: the bookmark as it stands, started now if it is new. */
    public Bookmark open(String agentDid, String book, String collection, String ownerDid, String studyCollection, int total) {
        long now = System.currentTimeMillis();
        var existing = find(agentDid, book);
        if (existing.isPresent()) {
            if (existing.get().total() != total && total > 0) update("UPDATE reading_bookmarks SET total = ? WHERE agent_did = ? AND book = ?", total, agentDid, book);
            return find(agentDid, book).orElse(existing.get());
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("INSERT INTO reading_bookmarks (agent_did, book, collection, owner_did, study_collection, "
                 + "part, total, started_at, last_read_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)")) {
            ps.setString(1, agentDid);
            ps.setString(2, book);
            ps.setString(3, collection);
            ps.setString(4, ownerDid);
            ps.setString(5, studyCollection);
            ps.setInt(6, total);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("reading ledger: could not open {} for {}: {}", book, agentDid, e.getMessage());
        }
        return find(agentDid, book).orElse(new Bookmark(agentDid, book, collection, ownerDid, studyCollection, 0, total, now, now, null));
    }

    /** She read this part. The last part finishes the book. */
    public Bookmark read(String agentDid, String book, int part) {
        long now = System.currentTimeMillis();
        var b = find(agentDid, book).orElse(null);
        if (b == null) return null;
        boolean done = b.total() > 0 && part >= b.total();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("UPDATE reading_bookmarks SET part = ?, last_read_at = ?, finished_at = "
                 + (done ? "COALESCE(finished_at, ?)" : "NULL") + " WHERE agent_did = ? AND book = ?")) {
            int i = 1;
            ps.setInt(i++, part);
            ps.setLong(i++, now);
            if (done) ps.setLong(i++, now);
            ps.setString(i++, agentDid);
            ps.setString(i, book);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("reading ledger: could not mark part {} of {}: {}", part, book, e.getMessage());
        }
        return find(agentDid, book).orElse(b);
    }

    public Optional<Bookmark> find(String agentDid, String book) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM reading_bookmarks WHERE agent_did = ? AND book = ?")) {
            ps.setString(1, agentDid);
            ps.setString(2, book);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(row(rs));
            }
        } catch (SQLException e) {
            log.warn("reading ledger: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /** The book she has open: the one read most recently that is not finished. */
    public Optional<Bookmark> current(String agentDid) {
        return all(agentDid).stream().filter(b -> !b.finished()).findFirst();
    }

    /** Every book of hers, the most recently read first. */
    public List<Bookmark> all(String agentDid) {
        var out = new ArrayList<Bookmark>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM reading_bookmarks WHERE agent_did = ? ORDER BY last_read_at DESC")) {
            ps.setString(1, agentDid);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
        } catch (SQLException e) {
            log.warn("reading ledger: {}", e.getMessage());
        }
        return out;
    }

    private static Bookmark row(ResultSet rs) throws SQLException {
        var b = new Bookmark(rs.getString("agent_did"), rs.getString("book"), rs.getString("collection"), rs.getString("owner_did"),
            rs.getString("study_collection"), rs.getInt("part"), rs.getInt("total"), rs.getLong("started_at"),
            rs.getLong("last_read_at"), null);
        // Read last: wasNull speaks of the column read last.
        long fin = rs.getLong("finished_at");
        return rs.wasNull() ? b : new Bookmark(b.agentDid(), b.book(), b.collection(), b.ownerDid(), b.studyCollection(),
            b.part(), b.total(), b.startedAt(), b.lastReadAt(), fin);
    }

    private void update(String sql, Object... args) {
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("reading ledger: {}", e.getMessage());
        }
    }
}
