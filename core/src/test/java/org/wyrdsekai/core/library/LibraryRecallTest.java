package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What she cited from another library is only as good as its standing there. The recall
 * pass reads that library's notices and marks — never deletes — her findings whose cited
 * entry was retired, disputed or superseded.
 */
class LibraryRecallTest {

    @TempDir Path dir;
    @TempDir Path libraryRoot;
    private WyrdLuceneStore store;
    private static final String HER = "did:key:z6MkHer";

    /** A library that answers status and a scripted changes feed. */
    static final class Feed implements LibraryPatron.Transport {
        final List<Map<String, Object>> notices = new ArrayList<>();
        final List<String> sinceSeen = new ArrayList<>();
        @Override public String call(String tool, Map<String, Object> args) throws Exception {
            var m = new com.fasterxml.jackson.databind.ObjectMapper();
            var r = new LinkedHashMap<String, Object>();
            r.put("library_id", "lib-9f2c"); r.put("library_name", "The Stacks"); r.put("contract", "1.2");
            switch (tool) {
                case "library_status" -> { r.put("counts", Map.of()); }
                case "library_changes" -> {
                    var since = String.valueOf(args.get("since"));
                    sinceSeen.add(since);
                    int from = Integer.parseInt(since);
                    var page = notices.subList(Math.min(from, notices.size()), notices.size());
                    r.put("changes", page);
                    r.put("next_cursor", String.valueOf(notices.size()));
                    r.put("latest", String.valueOf(notices.size()));
                    r.put("more", false);
                }
                default -> throw new IllegalArgumentException("unknown tool " + tool);
            }
            return m.writeValueAsString(r);
        }
        void notice(String id, String event) {
            var n = new LinkedHashMap<String, Object>();
            n.put("seq", String.valueOf(notices.size() + 1)); n.put("at", "2026-09-03T20:00:00Z");
            n.put("kind", "finding"); n.put("id", id); n.put("event", event); n.put("detail", "review round 3");
            notices.add(n);
        }
    }

    @BeforeEach void setUp() { store = new WyrdLuceneStore(dir, 384); store.ensureAllCollections(); }
    @AfterEach void tearDown() throws Exception { store.close(); }

    @Test
    void a_recalled_citation_marks_her_finding_disputed_and_the_cursor_advances() throws Exception {
        var mine = FindingsLedger.recordFromLibrary(store, HER, "vel-shara",
            "The Stacks holds that a vel-shara is a speech with magical force.", "lib-9f2c",
            List.of(FindingsLedger.Source.external("lib-9f2c:F-0007-vel-shara", "Bantam 1992", "vel-shara")),
            "companion:her", FindingsLedger.ClaimType.SYNTHESIS);
        // recordFromLibrary keeps the first source's locator as the origin id; strip the library prefix for matching
        var f = FindingsLedger.get(store, mine).orElseThrow();
        assertTrue(f.federated());

        var feed = new Feed();
        var patron = new LibraryPatron(feed, new LibraryPatron.Patron(HER, "Her", "wyrdsekai"));
        feed.notice("F-0001-unrelated", "state:draft→accepted");
        var r1 = LibraryRecall.run(store, HER, patron, libraryRoot);
        assertEquals(1, r1.notices());
        assertEquals(0, r1.marked(), "an unrelated notice marks nothing");
        assertEquals(FindingsLedger.State.DRAFT, FindingsLedger.get(store, mine).orElseThrow().state());
        assertTrue(Files.isRegularFile(LibraryRecall.cursorFile(libraryRoot, "lib-9f2c")));

        feed.notice("F-0007-vel-shara", "state:accepted→retired");
        var r2 = LibraryRecall.run(store, HER, patron, libraryRoot);
        assertEquals("1", feed.sinceSeen.getLast(), "reads from the persisted cursor, not from 0");
        assertEquals(1, r2.marked());
        var after = FindingsLedger.get(store, mine).orElseThrow();
        assertEquals(FindingsLedger.State.DISPUTED, after.state());
        assertTrue(after.reviewNote().contains("The Stacks retired F-0007-vel-shara"), after.reviewNote());
        assertTrue(after.reviewNote().contains("review round 3"));

        var r3 = LibraryRecall.run(store, HER, patron, libraryRoot);
        assertEquals(0, r3.notices(), "nothing new, nothing re-marked");
    }

    @Test
    void the_event_grammar_is_read_defensively() {
        assertEquals("retired", new LibraryPatron.Change("1", null, "finding", "F-1", "state:accepted→retired", null).toState());
        assertEquals("disputed", new LibraryPatron.Change("1", null, "finding", "F-1", "state:accepted->disputed", null).toState());
        assertNull(new LibraryPatron.Change("1", null, "finding", "F-1", "edited", null).toState());
        assertNull(new LibraryPatron.Change("1", null, "finding", "F-1", "supersedes:F-0001", null).toState());
        assertEquals("F-0007-x", LibraryRecall.baseId("lib-9f2c:F-0007-x"));
    }
}
