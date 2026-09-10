package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The recall pass — re-check what she cited from another library (contract 1.1,
 * {@code library_changes}).
 *
 * <p>A finding of hers that came from library X citing entry F-0412 is only as good as
 * F-0412's standing there. At sleep, read that library's recall notices since the last
 * cursor; when a cited entry was retired, disputed or superseded, mark HER finding disputed
 * with the reason. Mark, never delete: her record stays hers, and the library's verdict
 * arrives as information. The cursor is kept beside the reading log, one file per library.
 */
public final class LibraryRecall {

    private static final Logger log = LoggerFactory.getLogger(LibraryRecall.class);
    private static final ObjectMapper M = new ObjectMapper();

    private LibraryRecall() {}

    public record Result(int notices, int marked, String cursor) {}

    public static Path cursorFile(Path libraryRoot, String libraryId) {
        if (libraryRoot == null || libraryId == null) return null;
        var safe = libraryId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return libraryRoot.resolve("library-recall-" + safe + ".json");
    }

    static String readCursor(Path f) {
        try {
            if (f == null || !Files.isRegularFile(f)) return "0";
            var n = M.readTree(Files.readString(f));
            var c = n.path("cursor").asText("0");
            return c.isBlank() ? "0" : c;
        } catch (Exception e) {
            return "0";
        }
    }

    static void writeCursor(Path f, String cursor, String libraryId) {
        if (f == null) return;
        try {
            var m = new LinkedHashMap<String, Object>();
            m.put("library_id", libraryId);
            m.put("cursor", cursor);
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(m));
        } catch (Exception e) {
            log.debug("[recall] could not persist cursor {}: {}", f, e.toString());
        }
    }

    /**
     * Run one recall pass for {@code ownerDid}'s findings that came from the library behind
     * {@code patron}. Reads notices from the persisted cursor, marks what was recalled,
     * advances the cursor. Quiet when the library predates 1.1 or is unreachable.
     */
    public static Result run(WyrdLuceneStore store, String ownerDid, LibraryPatron patron,
                             Path libraryRoot) {
        if (store == null || ownerDid == null || patron == null) return new Result(0, 0, "0");
        var st = patron.status().orElse(null);
        if (st == null) {
            log.info("[recall] the configured library did not answer status — recall skipped this sleep");
            return new Result(0, 0, "0");
        }
        var file = cursorFile(libraryRoot, st.libraryId());
        var cursor = readCursor(file);

        // Her findings from THIS library, by origin id.
        var mine = new LinkedHashMap<String, FindingsLedger.Finding>();
        for (var f : FindingsLedger.list(store, ownerDid, null, 1000)) {
            if (f.federated() && st.libraryId().equals(f.originLibrary()) && f.originId() != null) {
                mine.put(f.originId(), f);
            }
        }

        int notices = 0, marked = 0;
        String next = cursor;
        for (int page = 0; page < 20; page++) {
            var ch = patron.changes(next, 200).orElse(null);
            if (ch == null) break;
            notices += ch.changes().size();
            for (var c : ch.changes()) {
                var to = c.toState();
                if (to == null || c.id() == null) continue;
                var f = mine.get(c.id());
                if (f == null) f = mine.get(baseId(c.id()));
                if (f == null || f.state() == FindingsLedger.State.RETIRED) continue;
                var lower = to.toLowerCase(Locale.ROOT);
                if (lower.equals("retired") || lower.equals("disputed") || lower.equals("superseded")) {
                    if (f.state() != FindingsLedger.State.DISPUTED) {
                        FindingsLedger.setState(store, f.id(), FindingsLedger.State.DISPUTED,
                            st.libraryName() + " " + lower + " " + c.id()
                                + (c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail()),
                            null);
                        marked++;
                    }
                }
            }
            if (ch.nextCursor() != null && !ch.nextCursor().isBlank()) next = ch.nextCursor();
            if (!ch.more()) break;
        }
        if (!next.equals(cursor)) writeCursor(file, next, st.libraryId());
        // Every run says so: a week of silence on a library that changes nightly once meant
        // "nobody knows whether this ran" — it had not been distinguishable from a quiet feed.
        log.info("[recall] {}: {} notice(s) since cursor {}, {} of her {} finding(s) from it marked disputed",
            st.libraryName(), notices, cursor, marked, mine.size());
        return new Result(notices, marked, next);
    }

    /** "lib:F-0412-slug" and "F-0412-slug" both name F-0412's entry; match on the entry id. */
    static String baseId(String id) {
        if (id == null) return null;
        int c = id.indexOf(':');
        return c > 0 && id.startsWith("lib") ? id.substring(c + 1) : id;
    }

    /** For tests and the CLI: notices as plain maps. */
    static List<Map<String, Object>> describe(List<LibraryPatron.Change> changes) {
        return changes.stream().map(c -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", c.id()); m.put("event", c.event()); m.put("to", c.toState());
            return (Map<String, Object>) m;
        }).toList();
    }
}
