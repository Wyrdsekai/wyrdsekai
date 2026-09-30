package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.mcp.transport.McpToolException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The reports researched after a person's {@code research yes}, which no member under parental
 * controls is shown.
 *
 * <p>Such a report goes into the shared household library like any other, so the household keeps
 * its own list: the job id the library gave the yes, and, once they are known, the report's id
 * ({@code I-…}) and the claims it filed ({@code F-…}). One row per job in {@code world.db}
 * (migration 16), so the list survives a restart. Since migration 17 a row also names who asked
 * (their canonical person id and language) and the neutral wording that was sent, so they can be
 * told once the report is in ({@link #takeReadyToTell}), and whether they have been. The person's
 * own words are never kept.
 *
 * <p>The ids are learned three ways: from any {@code library_job} or {@code library_get} answer
 * that passes through {@link LibraryConsent} (no extra call), by asking the library about every
 * job not yet settled before a child's library call is answered ({@link #settle}), and when the
 * library's webhook says a write-up landed. A job is settled once it ended: its report and
 * claims are known, or it ended without one.
 *
 * <p>{@link #forChild} takes those ids out of a library answer: an entry, a hit, a job row or a
 * claim about one of them is dropped, and an answer that is only about one of them reads as the
 * library holding nothing. Nothing in what is left says that anything was taken out.
 */
public final class LibraryYesReports {

    private static final Logger log = LoggerFactory.getLogger(LibraryYesReports.class);
    private static final ObjectMapper M = new ObjectMapper();

    /** How the library is asked about a job and an entry: tool and arguments → the tool's text. */
    @FunctionalInterface
    public interface Lookup {
        String call(String tool, Map<String, Object> args) throws Exception;
    }

    /**
     * One yes: its job, what it produced once known, who asked (canonical person id, their
     * language), the neutral wording sent (null when their own question went, our check having
     * matched it), and whether they were told the report is in.
     */
    public record Row(String jobId, String reportId, List<String> claimIds, boolean settled,
                      String asker, String locale, String wording, boolean told) {
        Row withTold() {
            return new Row(jobId, reportId, claimIds, settled, asker, locale, wording, true);
        }
    }

    /** An entry id without its slug and library prefix: {@code lib:I-0009-gears} → {@code I-0009}. */
    private static final Pattern BASE_ID = Pattern.compile("(?:^|:)([A-Z]-\\d+)(?:-[^\\s]*)?$");
    private static final Set<String> STILL_GOING = Set.of("queued", "running", "offered", "waiting");
    /** Where a library answer names what an object is about. */
    private static final List<String> ABOUT = List.of("id", "job_id", "investigation", "report", "of");

    private final String jdbcUrl;
    /** Rows kept here: every row in memory mode, and in database mode the ones a write failed to keep. */
    private final Map<String, Row> memory = new ConcurrentHashMap<>();

    /** The list in {@code world.db}; creates its table when it is missing. */
    public LibraryYesReports(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            ensureTable(conn);
            ensureAsker(conn);
        } catch (SQLException e) {
            log.warn("[library] the list of reports kept from children could not make its table: {}", e.getMessage());
        }
    }

    private LibraryYesReports() {
        this.jdbcUrl = null;
    }

    /** A list kept in memory only (tests and bare boots). */
    public static LibraryYesReports inMemory() {
        return new LibraryYesReports();
    }

    /** The table (migration 16): the same statement on SQLite and PostgreSQL. */
    public static void ensureTable(Connection conn) throws SQLException {
        try (var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS library_yes_reports (
                  job_id    TEXT PRIMARY KEY,
                  report_id TEXT,
                  claim_ids TEXT NOT NULL DEFAULT '',
                  settled   INTEGER NOT NULL DEFAULT 0,
                  added_at  TEXT NOT NULL)""");
        }
    }

    /**
     * Migration 17: who asked, in which language, the wording sent, and whether they were told.
     * Plain {@code ALTER TABLE … ADD COLUMN} on SQLite and PostgreSQL, each only when missing.
     */
    public static void ensureAsker(Connection conn) throws SQLException {
        for (var column : List.of("asker TEXT", "locale TEXT", "wording TEXT", "told INTEGER NOT NULL DEFAULT 0")) {
            var name = column.substring(0, column.indexOf(' '));
            if (hasColumn(conn, "library_yes_reports", name)) continue;
            try (var stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE library_yes_reports ADD COLUMN " + column);
            }
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    // ── the rows ────────────────────────────────────────────────────────────

    /** A job the library accepted after a yes, with no one to tell (a job row seen carrying the yes). */
    public void addJob(String jobId) {
        addJob(jobId, null, null, null);
    }

    /**
     * A job the library accepted after a yes: {@code asker} (canonical person id) is told when
     * its report is in, in {@code locale}, with the neutral {@code wording} that was sent (null
     * when it was their own question).
     */
    public void addJob(String jobId, String asker, String locale, String wording) {
        if (jobId == null || jobId.isBlank()) return;
        var id = jobId.strip();
        try {
            if (row(id) != null) return;
        } catch (RuntimeException unreadable) {
            // Could not read the list: try to add the row anyway.
        }
        var row = new Row(id, null, List.of(), false, blankToNull(asker), blankToNull(locale), blankToNull(wording), false);
        if (jdbcUrl == null) {
            memory.putIfAbsent(id, row);
            return;
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ins = conn.prepareStatement(
                 "INSERT INTO library_yes_reports (job_id, settled, added_at, asker, locale, wording, told) "
                     + "VALUES (?, 0, ?, ?, ?, ?, 0)")) {
            ins.setString(1, id);
            ins.setString(2, Instant.now().toString());
            ins.setString(3, row.asker());
            ins.setString(4, row.locale());
            ins.setString(5, row.wording());
            ins.executeUpdate();
        } catch (SQLException e) {
            try {
                if (row(id) != null) return;      // another writer kept it first
            } catch (RuntimeException unreadable) {
                // fall through: keep it in memory
            }
            log.warn("[library] a yes job could not be kept in world.db; kept in memory until restart: {}", e.getMessage());
            memory.putIfAbsent(id, row);
        }
    }

    /** What a job produced: its report (may be null), its claims, and whether it has ended. */
    void learned(String jobId, String reportId, Collection<String> claimIds, boolean settled) {
        var old = row(jobId);
        if (old == null) return;
        var report = reportId == null || reportId.isBlank() ? old.reportId() : reportId.strip();
        var claims = new LinkedHashSet<>(old.claimIds());
        if (claimIds != null) for (var c : claimIds) if (c != null && !c.isBlank()) claims.add(c.strip());
        var row = new Row(old.jobId(), report, List.copyOf(claims), settled || old.settled(),
            old.asker(), old.locale(), old.wording(), old.told());
        if (row.equals(old)) return;
        if (jdbcUrl == null || memory.containsKey(jobId)) {
            memory.put(jobId, row);
            if (jdbcUrl == null) return;
        }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var upd = conn.prepareStatement(
                 "UPDATE library_yes_reports SET report_id = ?, claim_ids = ?, settled = ? WHERE job_id = ?")) {
            upd.setString(1, row.reportId());
            upd.setString(2, String.join(" ", row.claimIds()));
            upd.setInt(3, row.settled() ? 1 : 0);
            upd.setString(4, jobId);
            upd.executeUpdate();
        } catch (SQLException e) {
            log.warn("[library] what a yes job produced could not be kept in world.db; kept in memory: {}", e.getMessage());
            memory.put(jobId, row);
        }
    }

    /** Every row: world.db's, read now (other nodes on a shared database write too), and any kept in memory. */
    public List<Row> rows() {
        var out = new LinkedHashMap<String, Row>();
        if (jdbcUrl != null) {
            try (var conn = DriverManager.getConnection(jdbcUrl);
                 var stmt = conn.prepareStatement(
                     "SELECT job_id, report_id, claim_ids, settled, asker, locale, wording, told "
                         + "FROM library_yes_reports ORDER BY added_at");
                 var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var claims = rs.getString(3);
                    out.put(rs.getString(1), new Row(rs.getString(1), rs.getString(2),
                        claims == null || claims.isBlank() ? List.of() : List.of(claims.strip().split("\\s+")),
                        rs.getInt(4) != 0, rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8) != 0));
                }
            } catch (SQLException e) {
                log.warn("[library] the list of reports kept from children could not be read: {}", e.getMessage());
                throw new IllegalStateException("the list of reports kept from children could not be read", e);
            }
        }
        out.putAll(memory);
        return List.copyOf(out.values());
    }

    private Row row(String jobId) {
        for (var r : rows()) if (r.jobId().equals(jobId)) return r;
        return null;
    }

    /**
     * The yes jobs whose report is in and whose asker has not been told yet, each marked told as
     * it is taken, so a job is told once whichever path learned of its report first (the webhook,
     * a job lookup, a job row read) and however often. A row this node cannot mark is left for
     * the next time.
     */
    public List<Row> takeReadyToTell() {
        var out = new ArrayList<Row>();
        for (var r : rows()) {
            if (r.told() || r.reportId() == null || r.asker() == null) continue;
            if (markTold(r.jobId())) out.add(r.withTold());
        }
        return out;
    }

    /** Marks a job told; true only for the one call that changed it. */
    private boolean markTold(String jobId) {
        boolean inDb = false;
        if (jdbcUrl != null) {
            try (var conn = DriverManager.getConnection(jdbcUrl);
                 var upd = conn.prepareStatement(
                     "UPDATE library_yes_reports SET told = 1 WHERE job_id = ? AND told = 0")) {
                upd.setString(1, jobId);
                inDb = upd.executeUpdate() == 1;
            } catch (SQLException e) {
                log.warn("[library] research run {} could not be marked told: {}", jobId, e.getMessage());
                return false;
            }
        }
        // A copy kept in memory is marked too; for a row world.db refused, it is the only mark.
        var inMemory = new boolean[1];
        memory.computeIfPresent(jobId, (k, r) -> {
            if (r.told()) return r;
            inMemory[0] = true;
            return r.withTold();
        });
        if (jdbcUrl == null) return inMemory[0];
        if (inDb) return true;
        if (!inMemory[0]) return false;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var q = conn.prepareStatement("SELECT 1 FROM library_yes_reports WHERE job_id = ?")) {
            q.setString(1, jobId);
            try (var rs = q.executeQuery()) {
                return !rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Every id on the list, as base ids ({@code J-12}, {@code I-0009}, {@code F-0412}). */
    public Set<String> ids() {
        var out = new LinkedHashSet<String>();
        for (var r : rows()) {
            add(out, r.jobId());
            add(out, r.reportId());
            for (var c : r.claimIds()) add(out, c);
        }
        return out;
    }

    private static void add(Set<String> out, String id) {
        var b = base(id);
        if (b != null) out.add(b);
    }

    /** Whether {@code id} (with or without its slug or a {@code library:} prefix) is on the list. */
    public boolean hides(String id) {
        var b = base(id);
        return b != null && ids().contains(b);
    }

    /** {@link #hides} over the list as it is now, read once. */
    public Predicate<String> snapshot() {
        var ids = ids();
        return id -> {
            var b = base(id);
            return b != null && ids.contains(b);
        };
    }

    /** Whether every yes job has ended and what it produced is known. */
    public boolean allSettled() {
        for (var r : rows()) if (!r.settled()) return false;
        return true;
    }

    /** {@code lib-9f2c:I-0009-gears} → {@code I-0009}; null when it is not an entry or job id. */
    static String base(String id) {
        if (id == null || id.isBlank()) return null;
        var m = BASE_ID.matcher(id.strip());
        return m.find() ? m.group(1) : null;
    }

    // ── learning the ids ────────────────────────────────────────────────────

    /**
     * Learn from an answer the library gave anyone, with no call of its own: a job row for a yes
     * (on the list, or carrying {@code allow: ["self-harm"]}) names its report; a report on the
     * list names its claims.
     */
    void learnFrom(String tool, String data) {
        if (data == null) return;
        try {
            var node = json(data);
            if (node == null) return;
            if ("library_job".equals(tool)) {
                var jobs = new ArrayList<JsonNode>();
                if (node.path("job").isObject()) jobs.add(node.path("job"));
                if (node.has("job_id")) jobs.add(node);
                for (var list : List.of("active", "finished")) for (var j : node.path(list)) jobs.add(j);
                for (var j : jobs) {
                    var id = j.path("job_id").asText("");
                    if (id.isBlank()) continue;
                    if (allowsSelfHarm(j)) addJob(id);
                    var row = row(id);
                    if (row == null) continue;
                    var report = j.path("investigation").asText("");
                    boolean ended = ended(j);
                    if (!report.isBlank()) learned(id, report, List.of(), false);
                    else if (ended) learned(id, null, List.of(), true);
                }
            } else if ("library_get".equals(tool)) {
                var entry = node.path("entry");
                var id = entry.path("id").asText("");
                for (var r : rows()) {
                    if (r.settled() || r.reportId() == null || !sameEntry(r.reportId(), id)) continue;
                    if (entry.has("findings")) learned(r.jobId(), null, texts(entry.path("findings")), true);
                }
            }
        } catch (RuntimeException e) {
            log.debug("[library] nothing learned from a {} answer: {}", tool, e.toString());
        }
    }

    /**
     * Ask the library about every job not yet settled: whether it ended, its report, and the
     * claims the report filed. Throws when the library could not say, so a child's call is not
     * answered with a report the household could not rule out.
     */
    public void settle(Lookup lookup) throws Exception {
        for (var row : rows()) {
            if (row.settled()) continue;
            var report = row.reportId();
            if (report == null) {
                JsonNode job;
                try {
                    job = json(lookup.call("library_job", Map.of("job_id", row.jobId())));
                } catch (McpToolException e) {
                    if (!"not_found".equals(e.dataCode())) throw e;
                    learned(row.jobId(), null, List.of(), true);      // the library no longer knows it
                    continue;
                }
                if (job == null) throw new IOException("the library's answer about a research run was not JSON");
                var j = job.path("job").isObject() ? job.path("job") : job;
                report = j.path("investigation").asText("");
                if (report.isBlank()) {
                    if (ended(j)) learned(row.jobId(), null, List.of(), true);
                    continue;                                          // still running: nothing to read yet
                }
                learned(row.jobId(), report, List.of(), false);
            }
            var got = json(lookup.call("library_get", Map.of("id", report, "max_chars", 200)));
            var entry = got == null ? null : got.path("entry");
            if (entry == null || !entry.isObject()) throw new IOException("the library did not return report " + base(report));
            learned(row.jobId(), null, texts(entry.path("findings")), true);
        }
    }

    // ── what a child is shown ───────────────────────────────────────────────

    /**
     * The same answer without what is on the list. An answer that is only about an entry on the
     * list comes back as the library holding nothing ({@code {"holds_nothing": true}}). A text
     * answer that names one is treated the same way. Returns {@code data} untouched when nothing
     * on the list is in it.
     */
    public String forChild(String data) {
        if (data == null || data.isBlank()) return data;
        var hidden = ids();
        if (hidden.isEmpty()) return data;
        var node = json(data);
        if (node == null) return mentions(data, hidden) ? NOTHING : data;
        if (node.isObject() && (about(node, hidden) || about(node.path("entry"), hidden) || about(node.path("job"), hidden))) {
            return NOTHING;
        }
        if (!prune(node, hidden, 0)) return data;
        if (node.isObject()) {
            var o = (ObjectNode) node;
            // A summary written over what was there may carry what was taken out.
            o.remove("rendered");
            o.remove("open_threads");
            if (o.path("entries").isArray() && o.path("entries").isEmpty() && !o.path("peers").elements().hasNext()) {
                o.put("holds_nothing", true);
            }
        }
        try {
            return M.writeValueAsString(node);
        } catch (Exception e) {
            return NOTHING;
        }
    }

    /** What a child is given for an answer that was only about a kept report. */
    static final String NOTHING = "{\"holds_nothing\":true}";

    /** The first argument of a child's call that names something on the list, or null. */
    public String namedIn(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return null;
        var hidden = ids();
        if (hidden.isEmpty()) return null;
        for (var v : args.values()) {
            if (v instanceof String s && hidden.contains(base(s))) return s;
            if (v instanceof Collection<?> c) {
                for (var e : c) if (e instanceof String s && hidden.contains(base(s))) return s;
            }
        }
        return null;
    }

    /** Drops every array element about a hidden id; true when anything was dropped. */
    private static boolean prune(JsonNode n, Set<String> hidden, int depth) {
        if (n == null || depth > 16) return false;
        boolean changed = false;
        if (n.isArray()) {
            var arr = (ArrayNode) n;
            for (int i = arr.size() - 1; i >= 0; i--) {
                var e = arr.get(i);
                if ((e.isTextual() && hidden.contains(base(e.asText()))) || (e.isObject() && about(e, hidden))) {
                    arr.remove(i);
                    changed = true;
                } else {
                    changed |= prune(e, hidden, depth + 1);
                }
            }
        } else if (n.isObject()) {
            for (var it = n.elements(); it.hasNext(); ) changed |= prune(it.next(), hidden, depth + 1);
        }
        return changed;
    }

    /** Whether an object is about a hidden id: its own id, job, report, what it explains, or a yes's allow. */
    private static boolean about(JsonNode o, Set<String> hidden) {
        if (o == null || !o.isObject()) return false;
        for (var f : ABOUT) {
            var v = o.path(f);
            if (v.isTextual() && hidden.contains(base(v.asText()))) return true;
        }
        return o.has("job_id") && allowsSelfHarm(o);
    }

    private static boolean mentions(String text, Set<String> hidden) {
        for (var h : hidden) {
            if (Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(h) + "(?![0-9])").matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean allowsSelfHarm(JsonNode job) {
        for (var a : job.path("allow")) if (LibraryAllowPolicy.SELF_HARM.equals(a.asText())) return true;
        return false;
    }

    private static boolean ended(JsonNode job) {
        var state = job.path("state").asText("");
        return !state.isBlank() && !STILL_GOING.contains(state);
    }

    private static boolean sameEntry(String a, String b) {
        var x = base(a);
        return x != null && x.equals(base(b));
    }

    private static List<String> texts(JsonNode arr) {
        var out = new ArrayList<String>();
        for (var e : arr) if (e.isTextual() && !e.asText().isBlank()) out.add(e.asText());
        return out;
    }

    private static JsonNode json(String s) {
        if (s == null) return null;
        var t = s.strip();
        if (!t.startsWith("{") && !t.startsWith("[")) return null;
        try {
            return M.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }
}
