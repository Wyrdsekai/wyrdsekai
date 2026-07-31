package org.wyrdsekai.core.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.PrivateJournalCipher;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.5a — {@code journal_private} entries are AES-256-GCM encrypted AT REST:
 * the index row holds ciphertext (a copied world.db/search dir no longer
 * exposes private entries), the owner's own reads decrypt, the sync wire to
 * the authenticated owner device carries plaintext OUT and seals plaintext
 * IN, and a ciphertext cannot be replayed onto another user (AAD binding).
 */
final class PrivateJournalEncryptionTest {

    private static Path tempDir;
    private static WyrdLuceneStore store;
    private static StudyService study;
    private static final String USER = "did:key:z6MkPrivate001";

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("study-enc-test-");
        store = new WyrdLuceneStore(tempDir.resolve("search"), 384);
        store.ensureAllCollections();
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
    void at_rest_row_is_ciphertext_but_owner_read_decrypts() {
        var secret = "I am scared about the biopsy on Thursday.";
        var id = study.writePrivateJournalEntry(USER, secret);

        // AT REST: the raw index row must NOT contain the plaintext.
        var raw = store.getById(SearchCollections.STUDY, id);
        assertNotNull(raw);
        assertTrue(PrivateJournalCipher.isEncrypted(raw.content()),
            "the stored row must be an encryption envelope");
        assertFalse(raw.content().contains("biopsy"),
            "plaintext must not appear in the stored row");

        // OWNER READ: the user's own search finds and decrypts it.
        var mine = study.searchAllJournal(USER, "biopsy Thursday", 5);
        assertTrue(mine.stream().anyMatch(r -> secret.equals(r.content())),
            "the owner's search must return the decrypted entry");

        // COMPANION READ: the shared-journal surface never sees it.
        var shared = study.searchJournal(USER, "biopsy", 5);
        assertTrue(shared.stream().noneMatch(r -> r.id().equals(id)));
    }

    @Test
    void sync_out_is_plaintext_for_the_owner_device_and_sync_in_is_sealed() {
        var secret = "Private sync note about the inheritance.";
        var id = study.writePrivateJournalEntry(USER, secret);

        // OUT: the authenticated owner device receives readable content.
        var delta = study.getDeltaForPeer(USER, Map.of());
        var wired = delta.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
        assertEquals(secret, wired.content(),
            "the owner's device must receive the entry readable, not ciphertext");

        // IN: a plaintext private entry arriving from a device is sealed
        // before it touches the index.
        var phoneEntry = new StudyService.StudyMergeItem(
            "journal_private:" + USER + ":phone1", USER, "journal_private",
            "(private entry)", "Written on the phone in the waiting room.",
            "journal", System.currentTimeMillis(), 1,
            Map.of("phone-device", 3), "phone-device", false);
        assertEquals(1, study.mergeFromPeer(USER, List.of(phoneEntry)));
        var landed = store.getById(SearchCollections.STUDY, phoneEntry.id());
        assertTrue(PrivateJournalCipher.isEncrypted(landed.content()),
            "a phone-written private entry must be sealed at rest on the server");
        assertFalse(landed.content().contains("waiting room"));
    }

    @Test
    void ciphertext_is_bound_to_its_owner() {
        var sealed = PrivateJournalCipher.encrypt(USER, "bound to me");
        assertEquals("bound to me", PrivateJournalCipher.decryptIfNeeded(USER, sealed));
        var other = PrivateJournalCipher.decryptIfNeeded("did:key:z6MkSomeoneElse", sealed);
        assertNotEquals("bound to me", other,
            "another user's key + AAD must not open this ciphertext");
    }

    @Test
    void shared_journal_stays_plaintext() {
        var id = study.writeJournalEntry(USER, "Shared: lovely walk by the river.");
        var raw = store.getById(SearchCollections.STUDY, id);
        assertFalse(PrivateJournalCipher.isEncrypted(raw.content()),
            "shared entries are companion-readable by design — no envelope");
    }
}
