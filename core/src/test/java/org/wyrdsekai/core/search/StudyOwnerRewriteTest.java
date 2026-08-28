package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index-side half of a person rebind.
 *
 * <p>Re-ingesting is not available as a mechanism — a migrating household will
 * not still have the source files, and nobody re-extracts their library because
 * an identity key changed. So the owner is rewritten in place.</p>
 */
class StudyOwnerRewriteTest {

    private static final String OLD = "operator";
    private static final String NEW = "did:key:zPerson";

    @TempDir Path tmp;
    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, 1024);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    private void seed(int n, String owner) {
        for (int i = 0; i < n; i++) {
            store.insertStudyItem("item-" + owner + "-" + i, owner, "book",
                "Title " + i, "content body " + i, "books", 1L, 1, null);
        }
        store.commitAll();
    }

    /** Content follows the person, and stays findable. */
    @Test
    void rewrites_owner_and_content_remains_searchable() {
        seed(12, OLD);
        assertFalse(store.searchStudy(OLD, "content", 20).isEmpty());

        var n = store.rewriteStudyOwner(OLD, NEW, 5, null);
        assertEquals(12, n);

        assertTrue(store.searchStudy(OLD, "content", 20).isEmpty(),
            "nothing may remain under the old owner");
        assertEquals(12, store.searchStudy(NEW, "content", 20).size(),
            "everything must be readable under the new owner");
    }

    /** Other people's content must not be touched. */
    @Test
    void leaves_other_owners_alone() {
        seed(6, OLD);
        seed(4, "someone-else");

        store.rewriteStudyOwner(OLD, NEW, 5, null);

        assertEquals(4, store.searchStudy("someone-else", "content", 20).size(),
            "a rebind must move exactly one person's content");
        assertEquals(6, store.searchStudy(NEW, "content", 20).size());
    }

    /** Re-running must be a no-op — the interrupted-and-resumed case. */
    @Test
    void is_idempotent_and_resumable() {
        seed(9, OLD);

        var first = store.rewriteStudyOwner(OLD, NEW, 4, null);
        var second = store.rewriteStudyOwner(OLD, NEW, 4, null);

        assertEquals(9, first);
        assertEquals(0, second, "already-rewritten documents must not be matched again");
        assertEquals(9, store.searchStudy(NEW, "content", 20).size());
    }

    /** Progress is reported so an operator can see a long run advancing. */
    @Test
    void reports_progress_per_batch() {
        seed(10, OLD);
        var last = new AtomicLong();
        var calls = new AtomicLong();

        store.rewriteStudyOwner(OLD, NEW, 3, t -> { last.set(t); calls.incrementAndGet(); });

        assertEquals(10, last.get(), "progress must end at the true total");
        assertTrue(calls.get() >= 2, "a batched run must report more than once");
    }

    /** Stored fields other than the owner must survive intact. */
    @Test
    void preserves_the_rest_of_the_document() {
        store.insertStudyItem("item-1", OLD, "book", "The Greenhouse",
            "the door sticks unless you lift it", "books", 1L, 1, null);
        store.commitAll();

        store.rewriteStudyOwner(OLD, NEW, 10, null);

        var hits = store.searchStudy(NEW, "greenhouse door", 5);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).content().contains("door sticks"),
            "content must survive the owner rewrite unchanged");
    }

    /** A no-op rebind must not churn the index. */
    @Test
    void same_owner_is_a_noop() {
        seed(5, OLD);
        assertEquals(0, store.rewriteStudyOwner(OLD, OLD, 10, null));
        assertEquals(0, store.rewriteStudyOwner(null, NEW, 10, null));
        assertEquals(5, store.searchStudy(OLD, "content", 20).size());
    }

    /** An owner with nothing indexed is harmless. */
    @Test
    void unknown_owner_rewrites_nothing() {
        seed(3, OLD);
        assertEquals(0, store.rewriteStudyOwner("nobody", NEW, 10, null));
        assertEquals(3, store.searchStudy(OLD, "content", 20).size());
    }

    /**
     * PRIVATE journal entries must be re-encrypted, not merely re-labelled.
     *
     * <p>{@code PrivateJournalCipher} uses the owner string as both the HKDF
     * purpose and the GCM AAD, so an entry encrypted under one identity can only
     * ever be decrypted as that identity. Moving the owner without re-encrypting
     * would leave a person's most private writing permanently unreadable — and
     * unlike books, it is recoverable from nowhere.</p>
     *
     * <p>When the zone key is unavailable the rewrite must fail CLOSED: leave the
     * entry alone rather than write plaintext or something nobody can open.</p>
     */
    @Test
    void private_journal_entries_are_not_silently_orphaned() {
        store.insertStudyItem("journal_private:" + OLD + ":1", OLD, "journal_private",
            "(private entry)", "sealed-content", "journal", 1L, 1, null);
        store.insertStudyItem("item-book-1", OLD, "book",
            "A Book", "ordinary content", "books", 1L, 1, null);
        store.commitAll();

        store.rewriteStudyOwner(OLD, NEW, 10, null);

        // The ordinary item always moves and stays searchable.
        assertEquals(1, store.searchStudyByType(NEW, "book", "ordinary", 10).size(),
            "non-private content must follow the person and remain findable");

        // A re-encrypted private entry is deliberately NOT full-text searchable
        // (the index holds ciphertext), so it must be checked by LISTING, not by
        // search — otherwise "it moved and was sealed" is indistinguishable from
        // "it vanished".
        var moved = store.listStudyByTypeRecent(NEW, "journal_private", 10);
        var stayed = store.listStudyByTypeRecent(OLD, "journal_private", 10);
        assertEquals(1, moved.size() + stayed.size(),
            "a private entry must be exactly one of: re-encrypted under the new identity, "
                + "or left untouched under the old — never lost");

        // Whichever happened, it must not be sitting under the NEW owner still
        // readable as the OLD one's plaintext.
        assertTrue(store.searchStudyByType(NEW, "journal_private", "sealed", 10).isEmpty(),
            "a moved private entry must be sealed to the new identity, not left in plaintext");
    }
}
