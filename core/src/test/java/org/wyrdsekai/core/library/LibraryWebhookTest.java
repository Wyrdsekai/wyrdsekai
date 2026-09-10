package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A librarian's pushed change (contract 1.5): verified in constant time, read, and applied
 * to the findings that cite the changed entry — the recall pass, without waiting for sleep.
 */
class LibraryWebhookTest {

    @TempDir Path dir;
    private WyrdLuceneStore store;
    private static final String OWNER = "did:key:zMia";
    private static final String LIB = "lib_f039e3a6ec8e5be7";

    @BeforeEach void setUp() { store = new WyrdLuceneStore(dir, 384); store.ensureAllCollections(); }
    @AfterEach void tearDown() throws Exception { store.close(); }

    static byte[] body(String kind, String id, String event, String detail) {
        return ("{\"library_id\":\"" + LIB + "\",\"library_name\":\"The Stacks\",\"change\":{\"seq\":41,\"at\":\"2026-09-08T10:00:00Z\","
            + "\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"event\":\"" + event + "\",\"detail\":\"" + detail + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void the_signature_is_checked_the_way_the_librarian_signs() {
        var b = body("finding", "F-0007-vel-shara", "state:accepted→retired", "superseded by F-0031");
        var sig = "sha256=" + LibraryWebhook.hmac("s3cret", b);
        assertTrue(LibraryWebhook.verify("s3cret", b, sig));
        assertTrue(LibraryWebhook.verify("s3cret", b, sig.toUpperCase().replace("SHA256=", "sha256=")), "hex case is not a secret");
        assertFalse(LibraryWebhook.verify("other", b, sig));
        assertFalse(LibraryWebhook.verify("s3cret", b, "sha256=" + "0".repeat(64)));
        assertFalse(LibraryWebhook.verify("", b, sig));
        assertFalse(LibraryWebhook.verify("s3cret", b, null));
        assertEquals("WYRDSEKAI_LIBRARY_WEBHOOK_SECRET_RESEARCHZOSHO", LibraryWebhook.secretEnvVar("researchzosho"));
    }

    @Test
    void a_retirement_marks_her_finding_from_that_library_disputed_and_nothing_else() {
        var mine = FindingsLedger.recordFromLibrary(store, OWNER, "vel-sharas", "A vel-shara is a speech with magical force.", LIB,
            List.of(FindingsLedger.Source.external(LIB + ":F-0007-vel-shara", "Bantam 1992", "vel-shara")), "companion:mia", FindingsLedger.ClaimType.SYNTHESIS);
        var other = FindingsLedger.recordFromLibrary(store, OWNER, "keigo", "Keigo flattens in translation.", LIB,
            List.of(FindingsLedger.Source.external(LIB + ":F-0031-keigo", null, "keigo")), "companion:mia", FindingsLedger.ClaimType.SYNTHESIS);
        var local = FindingsLedger.recordDraft(store, OWNER, "obsidian", "Obsidian is volcanic glass.", List.of("wiki:1"), "companion:mia", FindingsLedger.ClaimType.EXTRACTION);
        store.commitAll();
        assertNotNull(mine); assertNotNull(other); assertNotNull(local);

        var c = LibraryWebhook.parse(body("finding", "F-0007-vel-shara", "state:accepted→retired", "superseded by F-0040"));
        assertNotNull(c);
        assertTrue(c.isRecall());
        assertEquals("retired", c.toState());
        var applied = LibraryWebhook.apply(store, List.of(OWNER), c);
        assertEquals(1, applied.marked());
        assertEquals(List.of(mine), applied.markedIds());
        assertEquals(FindingsLedger.State.DISPUTED, FindingsLedger.get(store, mine).orElseThrow().state());
        assertTrue(FindingsLedger.get(store, mine).orElseThrow().reviewNote().contains("The Stacks retired F-0007-vel-shara"));
        assertEquals(FindingsLedger.State.DRAFT, FindingsLedger.get(store, other).orElseThrow().state(), "a different entry: untouched");
        assertEquals(FindingsLedger.State.DRAFT, FindingsLedger.get(store, local).orElseThrow().state(), "not from that library: untouched");

        // the same recall again is a no-op, and an 'edited' event is not a recall
        assertEquals(0, LibraryWebhook.apply(store, List.of(OWNER), c).marked());
        var edited = LibraryWebhook.parse(body("finding", "F-0031-keigo", "edited", "reviewed content changed"));
        assertFalse(edited.isRecall());
        assertEquals(0, LibraryWebhook.apply(store, List.of(OWNER), edited).marked());
    }

    @Test
    void a_landed_investigation_is_recognised_with_its_writer() {
        var c = LibraryWebhook.parse(body("investigation", "I-0014-how-were-the-gear-teeth-cut", "added", "draft by patron:did:key:zHousehold"));
        assertTrue(c.isLandedInvestigation());
        assertEquals("patron:did:key:zHousehold", c.writer());
        assertFalse(c.isRecall());
        assertNull(LibraryWebhook.parse("{\"nope\":1}".getBytes(StandardCharsets.UTF_8)));
        assertNull(LibraryWebhook.parse("not json".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void a_source_notice_annotates_her_finding_and_keeps_its_state() {
        var mine = FindingsLedger.recordFromLibrary(store, OWNER, "vel-sharas", "A vel-shara is a speech with magical force.", LIB,
            List.of(FindingsLedger.Source.external(LIB + ":F-0412", null, "vel-shara")), "companion:mia", FindingsLedger.ClaimType.SYNTHESIS);
        var before = FindingsLedger.get(store, mine).orElseThrow().state();
        var revised = new LibraryWebhook.Change(LIB, "The Stacks", 12, "2026-09-08T20:00:00Z", "finding", "F-0412", "revised", "arXiv:2401.00001v3 supersedes v2");
        assertTrue(revised.isSourceNotice());
        assertFalse(revised.isRecall());
        var applied = LibraryWebhook.apply(store, List.of(OWNER), revised);
        assertEquals(0, applied.marked(), "a revised source is not a dispute");
        assertEquals(List.of(mine), applied.notedIds());
        var after = FindingsLedger.get(store, mine).orElseThrow();
        assertEquals(before, after.state(), "the state is kept");
        assertTrue(String.valueOf(after.reviewNote()).contains("The Stacks says a source behind F-0412 was revised: arXiv:2401.00001v3 supersedes v2"), after.reviewNote());

        var supplied = new LibraryWebhook.Change(LIB, "The Stacks", 13, "2026-09-08T20:01:00Z", "source", "F-0412", "supplied", "the person supplied the PDF");
        assertTrue(supplied.isSourceNotice());
        assertEquals(1, LibraryWebhook.apply(store, List.of(OWNER), supplied).noted());
    }
}
