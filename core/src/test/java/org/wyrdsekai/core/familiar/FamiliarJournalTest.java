package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — journal entries for form lifecycle events.
 * Verifies that shaped/revised/retired events land in the agent's private
 * journal with tags searchable via {@link StudyService#searchAllJournal}.
 */
class FamiliarJournalTest {

    private static Path tempDir;
    private static WyrdLuceneStore store;
    private static StudyService study;
    private static FamiliarJournal journal;
    private static final String DID = "did:wyrd:zA:wyrd-primary";

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("familiar-journal-test-");
        store = new WyrdLuceneStore(tempDir.resolve("search"), 384);
        store.ensureAllCollections();
        // 0.5a — familiar journal entries are journal_private, which writes
        // encrypted FAIL-CLOSED; the test JVM needs a zone master (prod
        // originates one at first boot). Same setup as the Study tests.
        var zoneId = WyrdConfig.get().zoneId();
        if (!ZoneSecrets.service().has(zoneId)) {
            ZoneSecrets.service().generate(zoneId);
        }
        study = new StudyService(store);
        journal = new FamiliarJournal(store);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (store != null) store.close();
    }

    @Test
    void shaped_writes_searchable_entry() {
        var form = ThoughtForm.author(DID, "researcher-alpha",
            "Research the given topic and return 3 sources with URLs.",
            Set.of("web_search", "read_content"),
            "Must cite 3+ URLs.");
        journal.shaped(DID, form);

        var results = study.searchAllJournal(DID, "researcher-alpha", 5);
        assertFalse(results.isEmpty(), "entry should surface in journal search");
        var contents = results.stream()
            .map(WyrdLuceneStore.SearchResult::content)
            .filter(c -> c != null && c.contains("familiar.shaped"))
            .toList();
        assertFalse(contents.isEmpty(), "entry should carry familiar.shaped tag");
        assertTrue(contents.get(0).contains("web_search"), "entry should name the tool surface");
    }

    @Test
    void revised_writes_change_summary() {
        var before = ThoughtForm.author(DID, "gardener-beta",
            "Water the plants daily.", Set.of(), "");
        var afterProv = before.provenance().append(new Provenance.Edit(
            DID, Provenance.Action.REVISED, Instant.now(), "widened scope"));
        var after = new ThoughtForm(before.id(), before.name(), "1.1.0",
            afterProv, "Water the plants and note their colors.",
            before.toolSurface(), before.defaultTanks(), before.maxTanks(),
            before.maxTrials(), before.maxNestDepth(), before.evalCriteria(),
            before.createdAt(), Instant.now(),
            before.summonCount(), before.successCount(), before.failureCount(),
            before.bondCharge());

        journal.revised(DID, before, after, "widened scope");

        var results = study.searchAllJournal(DID, "gardener-beta", 5);
        var contents = results.stream()
            .map(WyrdLuceneStore.SearchResult::content)
            .filter(c -> c != null && c.contains("familiar.revised"))
            .toList();
        assertFalse(contents.isEmpty());
        assertTrue(contents.get(0).contains("1.0.0 → 1.1.0"));
        assertTrue(contents.get(0).contains("prompt updated"));
    }

    @Test
    void retired_writes_farewell_with_stats() {
        var form = ThoughtForm.author(DID, "oldcoder-gamma",
            "Ship JS utilities.", Set.of(), "");
        var used = form.incrementSummon().recordSuccess().recordSuccess();
        journal.retired(DID, used, "superseded by newer pattern");

        var results = study.searchAllJournal(DID, "oldcoder-gamma", 5);
        var contents = results.stream()
            .map(WyrdLuceneStore.SearchResult::content)
            .filter(c -> c != null && c.contains("familiar.retired"))
            .toList();
        assertFalse(contents.isEmpty());
        assertTrue(contents.get(0).contains("summons=1"));
        assertTrue(contents.get(0).contains("success=2"));
        assertTrue(contents.get(0).contains("superseded"));
    }

    @Test
    void familiar_returned_writes_status_appropriate_kind() {
        var form = ThoughtForm.author(DID, "echo-delta", "Echo back.", Set.of(), "");
        // Use a distinctive task phrase so the journal search finds this entry
        var fam = Familiar.summon(form, DID, "distinctive-phrase-xyzzy", Tanks.defaults())
            .terminate(Familiar.Status.DONE, "all good", "the answer");
        journal.familiarReturned(DID, fam, "completed cleanly");

        var results = study.searchAllJournal(DID, "xyzzy", 5);
        var returnEntry = results.stream()
            .map(WyrdLuceneStore.SearchResult::content)
            .filter(c -> c != null && c.contains("familiar.returned"))
            .findFirst();
        assertTrue(returnEntry.isPresent());
        assertTrue(returnEntry.get().contains("status=DONE"));
    }

    @Test
    void missing_store_is_noop_not_throw() {
        var quiet = new FamiliarJournal(null);
        assertFalse(quiet.available());
        // none of these should throw
        var form = ThoughtForm.author(DID, "quiet", "x", Set.of(), "");
        quiet.shaped(DID, form);
        quiet.retired(DID, form, null);
        quiet.write(DID, FamiliarJournal.Kind.SUMMONED, "test", "details");
    }
}
