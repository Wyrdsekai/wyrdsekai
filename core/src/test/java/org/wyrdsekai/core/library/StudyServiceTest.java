package org.wyrdsekai.core.library;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StudyService — journal, documents, pinboard, notes, voice memos.
 */
class StudyServiceTest {

    private static Path tempDir;
    private static WyrdLuceneStore store;
    private static StudyService study;
    private static final String USER = "did:key:z6MkTest001";

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("study-test-");
        store = new WyrdLuceneStore(tempDir.resolve("search"), 384);
        store.ensureAllCollections();
        // 0.5a — private-journal writes are encrypted fail-closed, so the
        // test JVM needs a zone master (prod originates one at first boot).
        var zoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zoneId)) {
            ZoneSecrets.service().generate(zoneId);
        }
        study = new StudyService(store);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (store != null) store.close();
    }

    @Test
    void write_and_search_shared_journal() {
        study.writeJournalEntry(USER, "Had a great day at the park with the kids. Weather was perfect.");
        study.writeJournalEntry(USER, "Meeting with the dentist tomorrow at 3pm. Don't forget.");

        var results = study.searchJournal(USER, "park kids", 5);
        assertFalse(results.isEmpty(), "Should find journal entry about the park");
        assertTrue(results.getFirst().content().contains("park"));
    }

    @Test
    void write_and_search_private_journal() {
        study.writePrivateJournalEntry(USER, "I'm worried about the test results. Need to talk to someone.");

        // searchAllJournal includes private entries (for the user)
        var all = study.searchAllJournal(USER, "worried test results", 5);
        assertFalse(all.isEmpty(), "User should find their own private entries");

        // searchJournal (shared only) should NOT include private entries
        var shared = study.searchJournal(USER, "worried test results", 5);
        // Private entries have type "journal_private" so they should NOT appear in shared search
        boolean foundPrivateInShared = shared.stream()
            .anyMatch(r -> r.metadata() != null &&
                "journal_private".equals(r.metadata().get("item_type")));
        assertFalse(foundPrivateInShared, "Private entries should not appear in shared journal search");
    }

    @Test
    void pin_and_list() {
        study.pin(USER, "Sourdough Bread Recipe", "wikihow:4821", "A step-by-step guide...");
        study.pin(USER, "Japanese Curry", "recipes:102", "A warm, comforting curry...");

        var pins = study.listPins(USER, 10);
        assertTrue(pins.size() >= 2, "Should have at least 2 pins");
    }

    @Test
    void add_note() {
        study.addNote(USER, "Pick up milk and bread on the way home");

        var results = study.searchAll(USER, "milk bread", 5);
        assertFalse(results.isEmpty(), "Should find the note");
    }

    @Test
    void add_note_from_companion_format() {
        // Matches the format used by CompanionActor.handleTellAgent() offline path
        study.addNote(USER, "[From Ember] I found 5 books about mythology in the Library.");

        var results = study.searchAll(USER, "mythology", 5);
        assertFalse(results.isEmpty(), "Should find companion note");
        assertTrue(results.getFirst().content().contains("[From Ember]"),
            "Note should preserve companion name prefix");
    }

    @Test
    void add_voice_memo() {
        study.addVoiceMemo(USER, "Reminder to call the electrician about the kitchen light",
            "voice_2026_03_25.wav");

        var results = study.searchAll(USER, "electrician kitchen", 5);
        assertFalse(results.isEmpty(), "Should find the voice memo transcript");
    }

    @Test
    void index_document() {
        study.indexDocument(USER, "taxes-2025", "Form 8829 Page 2",
            "To qualify for the home office deduction, you must use part of your home regularly and exclusively for business.",
            "/home/user/Documents/taxes/form-8829.pdf");
        study.commitDocuments();

        var results = study.searchDocuments(USER, "taxes-2025", "home office deduction", 5);
        assertFalse(results.isEmpty(), "Should find the tax document");
        assertTrue(results.getFirst().content().contains("home office"));
    }

    @Test
    void search_across_all_types() {
        // Already have journal, notes, voice memos, documents from previous tests
        var results = study.searchAll(USER, "home", 10);
        // Should find multiple types (document about home office, note about going home)
        assertFalse(results.isEmpty(), "Cross-type search should find results");
    }

    @Test
    void get_stats() {
        var stats = study.getStats(USER);
        long total = (long) stats.get("totalItems");
        assertTrue(total > 0, "Should have some items indexed");
    }

    @Test
    void delete_collection() {
        // Index something in a deletable collection
        study.indexDocument(USER, "temp-collection", "Temp Doc",
            "This is temporary content that should be deleted.",
            "/tmp/temp.pdf");
        study.commitDocuments();

        long deleted = study.deleteCollection(USER, "temp-collection");
        assertTrue(deleted > 0, "Should delete items from the collection");
    }

    @Test
    void different_users_isolated() {
        var otherUser = "did:key:z6MkOther001";
        study.writeJournalEntry(otherUser, "This is the other user's journal entry about cooking.");

        // Search as original user — should not find other user's entries
        var results = study.searchJournal(USER, "other user cooking", 5);
        boolean foundOther = results.stream()
            .anyMatch(r -> r.content() != null && r.content().contains("other user"));
        assertFalse(foundOther, "Should not find another user's journal entries");

        // Search as other user — should find their own
        var otherResults = study.searchJournal(otherUser, "cooking", 5);
        assertFalse(otherResults.isEmpty(), "Other user should find their own entries");
    }

    // --- Vector-clock CRDT sync (Tier 3c) ---

    @Test
    void server_writes_carry_a_vector_clock() {
        study.setServerDeviceId("srv-1");
        var u = "did:key:zSync001";
        study.writeJournalEntry(u, "clock-bearing entry");
        var summary = study.buildClockSummary(u);
        assertEquals(1, summary.getOrDefault("srv-1", 0),
            "a server write must tick the server's clock slot");
    }

    @Test
    void merge_inserts_unknown_item_then_getDelta_excludes_peer_that_has_it() {
        study.setServerDeviceId("srv-1");
        var u = "did:key:zSync002";
        var phoneClock = java.util.Map.of("phone-A", 1);
        var remote = new StudyService.StudyMergeItem(
            "note:zSync002:900", u, "note", "from phone", "hello from the phone",
            "notes", 900L, 1, phoneClock, "phone-A", false);
        assertEquals(1, study.mergeFromPeer(u, java.util.List.of(remote)), "unknown item is inserted");

        // A peer whose summary already covers phone-A@1 should get NO delta back.
        var delta = study.getDeltaForPeer(u, java.util.Map.of("phone-A", 1));
        assertTrue(delta.stream().noneMatch(i -> i.id().equals("note:zSync002:900")),
            "an item the peer already dominates must not be re-sent");

        // A peer that has never seen phone-A SHOULD get it.
        var deltaFresh = study.getDeltaForPeer(u, java.util.Map.of());
        assertTrue(deltaFresh.stream().anyMatch(i -> i.id().equals("note:zSync002:900")),
            "a peer missing the item must receive it");
    }

    @Test
    void merge_fast_forwards_on_dominates() {
        study.setServerDeviceId("srv-1");
        var u = "did:key:zSync003";
        var id = "note:zSync003:100";
        study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "v1", "original", "notes", 100L, 1,
            java.util.Map.of("phone-A", 1), "phone-A", false)));

        // remote DOMINATES (phone-A:2 > 1) → content fast-forwards
        assertEquals(1, study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "v2", "edited on phone", "notes", 200L, 2,
            java.util.Map.of("phone-A", 2), "phone-A", false))));
        var after = store.getById(org.wyrdsekai.core.search.SearchCollections.STUDY, id);
        assertNotNull(after);
        assertEquals("edited on phone", after.content(), "a dominating remote must fast-forward local");
    }

    @Test
    void merge_keeps_local_on_concurrent() {
        study.setServerDeviceId("srv-1");
        var u = "did:key:zSync005";
        var id = "note:zSync005:1";
        // seed a phone item {phone-A:1}
        study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "v1", "original", "notes", 100L, 1,
            java.util.Map.of("phone-A", 1), "phone-A", false)));
        // a SERVER edit ticks srv-1 → local becomes {phone-A:1, srv-1:1}, content "server edit"
        study.editItem(id, u, "server edit");
        // remote {phone-A:2} — neither dominates (remote higher on phone-A, local higher on srv-1)
        int merged = study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "v?", "phone edit", "notes", 300L, 2,
            java.util.Map.of("phone-A", 2), "phone-A", false)));
        assertEquals(0, merged, "a concurrent remote must NOT be applied");
        var after = store.getById(org.wyrdsekai.core.search.SearchCollections.STUDY, id);
        assertEquals("server edit", after.content(), "local content is kept on a concurrent conflict");
    }

    @Test
    void unknown_tombstone_is_a_noop_and_dominating_tombstone_deletes() {
        study.setServerDeviceId("srv-1");
        var u = "did:key:zSync004";
        var id = "note:zSync004:1";
        // tombstone for an id we never had → no-op
        assertEquals(0, study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "", "", "notes", 1L, 1, java.util.Map.of("phone-A", 1), "phone-A", true))),
            "a tombstone for an unknown item is a no-op");

        // insert, then a dominating tombstone deletes it
        study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "x", "here", "notes", 2L, 1, java.util.Map.of("phone-A", 1), "phone-A", false)));
        study.mergeFromPeer(u, java.util.List.of(new StudyService.StudyMergeItem(
            id, u, "note", "x", "here", "notes", 3L, 2, java.util.Map.of("phone-A", 2), "phone-A", true)));
        assertNull(store.getById(org.wyrdsekai.core.search.SearchCollections.STUDY, id),
            "a dominating tombstone must delete the local item");
    }
}
