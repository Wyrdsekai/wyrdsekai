package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The record of a companion's asking: every question she put to the library's shelves and whether
 * the shelves held anything, and every question she handed the research desk and what came back.
 * Her findings are the record of what she established; this is the record of what she asked, so a
 * reading turn can say how often a subject has come back thin, and when an answer is in.
 *
 * <p>Until 0.5.16 nothing kept the question: a finding was filed without the words that found it,
 * and a desk answer landed in the library with nothing to tell her it had.</p>
 */
public final class LibraryAsks {

    private static final Logger log = LoggerFactory.getLogger(LibraryAsks.class);

    /** One search of the shelves: what was asked, when, and whether anything was found. */
    public record Ask(String agentDid, String query, long askedAt, boolean found, int sources) {}

    /** One question handed to the desk: its job, and the entry that answered it once it has. */
    public record DeskAsk(String agentDid, String question, String jobId, long askedAt, Long answeredAt,
                          String entryId, String title, boolean opened) {
        public boolean answered() { return answeredAt != null; }
    }

    private static volatile LibraryAsks SHARED;

    public static void install(LibraryAsks l) { SHARED = l; }
    public static LibraryAsks shared() { return SHARED; }

    private final String jdbcUrl;

    public LibraryAsks(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /** Migration 24: the two tables. Safe to run again. */
    public static void ensure(Connection conn) throws SQLException {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS library_asks ("
                + "agent_did TEXT NOT NULL, query TEXT NOT NULL, asked_at BIGINT NOT NULL, "
                + "found INTEGER NOT NULL DEFAULT 0, sources INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_library_asks_agent_time ON library_asks(agent_did, asked_at)");
            st.execute("CREATE TABLE IF NOT EXISTS library_desk_asks ("
                + "job_id TEXT PRIMARY KEY, agent_did TEXT NOT NULL, question TEXT NOT NULL, asked_at BIGINT NOT NULL, "
                + "answered_at BIGINT, entry_id TEXT, title TEXT, opened INTEGER NOT NULL DEFAULT 0)");
        }
    }

    // ── The shelves ───────────────────────────────────────────────────────────

    /** A search she made: kept whatever it found. */
    public void record(String agentDid, String query, boolean found, int sources) {
        if (agentDid == null || query == null || query.isBlank()) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("INSERT INTO library_asks (agent_did, query, asked_at, found, sources) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, agentDid);
            ps.setString(2, query.strip());
            ps.setLong(3, System.currentTimeMillis());
            ps.setInt(4, found ? 1 : 0);
            ps.setInt(5, Math.max(0, sources));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("library ask not recorded: {}", e.getMessage());
        }
    }

    /** Her searches since {@code since}, newest first. */
    public List<Ask> since(String agentDid, Instant since) {
        var out = new ArrayList<Ask>();
        if (agentDid == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT agent_did, query, asked_at, found, sources FROM library_asks "
                 + "WHERE agent_did = ? AND asked_at >= ? ORDER BY asked_at DESC LIMIT 500")) {
            ps.setString(1, agentDid);
            ps.setLong(2, since == null ? 0 : since.toEpochMilli());
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Ask(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) != 0, rs.getInt(5)));
                }
            }
        } catch (SQLException e) {
            log.debug("library asks not read: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Whether two questions are about the same thing: at least two of the same words, making up at
     * least half the words of the shorter one. A question reworded is the same question.
     */
    public static boolean sameSubject(String a, String b) {
        var words = Reading.words(a);
        var theirs = Reading.words(b);
        if (words.size() < 2 || theirs.size() < 2) return false;
        int shared = 0;
        for (var w : words) if (theirs.contains(w)) shared++;
        return shared >= 2 && shared * 2 >= Math.min(words.size(), theirs.size());
    }

    /** The asks among {@code asks} about the same thing as {@code query}. */
    public static List<Ask> about(String query, List<Ask> asks) {
        var out = new ArrayList<Ask>();
        if (query == null || asks == null) return out;
        for (var a : asks) if (sameSubject(query, a.query())) out.add(a);
        return out;
    }

    /** How many of {@code asks} found nothing on the shelves. */
    public static int thin(List<Ask> asks) {
        int n = 0;
        if (asks != null) for (var a : asks) if (!a.found()) n++;
        return n;
    }

    // ── The desk ──────────────────────────────────────────────────────────────

    /** A question handed to the desk, by its job. */
    public void deskAsked(String agentDid, String question, String jobId) {
        if (agentDid == null || jobId == null || jobId.isBlank()) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("INSERT OR IGNORE INTO library_desk_asks (job_id, agent_did, question, asked_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, jobId.strip());
            ps.setString(2, agentDid);
            ps.setString(3, question == null ? "" : question.strip());
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("desk ask not recorded: {}", e.getMessage());
        }
    }

    /** The desk answered: the entry its answer became. */
    public void deskAnswered(String jobId, String entryId, String title) {
        if (jobId == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("UPDATE library_desk_asks SET answered_at = ?, entry_id = ?, title = ? WHERE job_id = ? AND answered_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, entryId);
            ps.setString(3, title);
            ps.setString(4, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("desk answer not recorded: {}", e.getMessage());
        }
    }

    /** The desk could not answer (the run failed, or the library forgot it): closed, with no entry. */
    public void deskGaveUp(String jobId) {
        deskAnswered(jobId, null, null);
    }

    /** She opened the answer. */
    public void deskAnswerOpened(String agentDid, String entryId) {
        if (agentDid == null || entryId == null) return;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("UPDATE library_desk_asks SET opened = 1 WHERE agent_did = ? AND entry_id = ?")) {
            ps.setString(1, agentDid);
            ps.setString(2, entryId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("desk answer not marked opened: {}", e.getMessage());
        }
    }

    /** Her questions the desk has not yet answered. */
    public List<DeskAsk> deskPending(String agentDid) {
        return deskAsks("agent_did = ? AND answered_at IS NULL ORDER BY asked_at", agentDid);
    }

    /** Her answered questions she has not yet opened, newest first. */
    public List<DeskAsk> deskUnopened(String agentDid) {
        return deskAsks("agent_did = ? AND answered_at IS NOT NULL AND entry_id IS NOT NULL AND opened = 0 ORDER BY answered_at DESC", agentDid);
    }

    public Optional<DeskAsk> deskAsk(String jobId) {
        var l = deskAsks("job_id = ?", jobId);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.getFirst());
    }

    private List<DeskAsk> deskAsks(String where, String arg) {
        var out = new ArrayList<DeskAsk>();
        if (arg == null) return out;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT agent_did, question, job_id, asked_at, answered_at, entry_id, title, opened "
                 + "FROM library_desk_asks WHERE " + where)) {
            ps.setString(1, arg);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long answered = rs.getLong(5);
                    out.add(new DeskAsk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.wasNull() ? null : answered, rs.getString(6), rs.getString(7), rs.getInt(8) != 0));
                }
            }
        } catch (SQLException e) {
            log.debug("desk asks not read: {}", e.getMessage());
        }
        return out;
    }
}
