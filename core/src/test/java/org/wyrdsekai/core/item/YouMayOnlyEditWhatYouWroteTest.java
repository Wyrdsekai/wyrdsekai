package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An item may edit the library entries it wrote, and no others.
 *
 * <h2>What was unguarded</h2>
 * {@code world.library.tag} and {@code world.library.delete} took any chunk id
 * and acted on it. No ownership check of any kind — and {@code library.delete}
 * sits in {@code ItemCapabilitySet.CRAFTED_ALLOW}, so ANY crafted item holding
 * it could erase ANY chunk in the household's knowledge base: a bundled pack, a
 * dictionary, or a passage from the steward's 13.6M-chunk published shelf. The
 * manifest validator rates {@code library.delete} tier 5 — the most dangerous
 * rung it has — and nothing downstream of that rating asked whose chunk it was.
 *
 * <p>The rule matches the rest of the item surface after the identity work of
 * 2026-08-25: an item carries exactly the authority of whoever is using it. You
 * may edit what you wrote. The steward may curate anything, because they can
 * already do that with {@code wyrd library} — their own authority, not an
 * escalation.</p>
 */
class YouMayOnlyEditWhatYouWroteTest {

    @TempDir
    Path dir;

    private static final String ALICE = "companion-alice";
    private static final String MALLORY = "companion-mallory";

    private ItemWorldApiProviderImpl providerFor(WyrdLuceneStore store, String who) {
        return new ItemWorldApiProviderImpl(store, null, null, null, who, who,
            null, null, null, null, null);
    }

    @Test
    @DisplayName("you may delete the entry you added")
    void yourOwnEntryIsYours() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("i1"), 4)) {
            var alice = providerFor(store, ALICE);
            var added = alice.libraryAdd("The banshee wails before a death.",
                Map.of("title", "Banshee"));
            var id = String.valueOf(added.get("id"));

            assertThat(alice.libraryDelete(id))
                .as("her own entry").containsEntry("ok", true);
        }
    }

    @Test
    @DisplayName("you may not delete someone else's entry")
    void anotherAgentsEntryIsRefused() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("i2"), 4)) {
            var id = String.valueOf(providerFor(store, ALICE)
                .libraryAdd("Alice wrote this.", Map.of()).get("id"));

            var refusal = providerFor(store, MALLORY).libraryDelete(id);
            assertThat(refusal).containsEntry("ok", false)
                .containsEntry("reason", "not_yours");
            assertThat(store.getById(
                org.wyrdsekai.core.search.SearchCollections.KNOWLEDGE, id))
                .as("and the entry is still there").isNotNull();
        }
    }

    @Test
    @DisplayName("a crafted item cannot erase the household's shelf")
    void aPublishedShelfIsNotDeletable() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("i3"), 4)) {
            // A passage as `wyrd library publish` writes it: pack-owned, with
            // no `lib:<author>:` id, because no item authored it.
            store.insertKnowledgeBulk("study-share-books:doc:steward:books:abc",
                "study-share-books", "Altered Carbon",
                "Takeshi Kovacs woke in a new sleeve.", "study-share", null, null, null);
            store.commitAll();

            var mallory = providerFor(store, MALLORY);
            assertThat(mallory.libraryDelete("study-share-books:doc:steward:books:abc"))
                .as("the steward's books are not a crafted item's to delete")
                .containsEntry("ok", false)
                .containsEntry("reason", "not_yours");
            assertThat(mallory.libraryTag(
                "study-share-books:doc:steward:books:abc", List.of("junk")))
                .as("nor to retag")
                .containsEntry("ok", false);
        }
    }

    @Test
    @DisplayName("a refusal does not reveal whether the chunk exists")
    void refusalIsNotAnExistenceProbe() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("i4"), 4)) {
            var id = String.valueOf(providerFor(store, ALICE)
                .libraryAdd("Alice wrote this.", Map.of()).get("id"));
            var mallory = providerFor(store, MALLORY);

            var real = mallory.libraryDelete(id);
            var imaginary = mallory.libraryDelete("lib:" + ALICE + ":no-such-uuid");
            assertThat(real.get("reason"))
                .as("same answer for a real chunk and an invented one")
                .isEqualTo(imaginary.get("reason"));
        }
    }

    @Test
    @DisplayName("a colon-bearing author cannot be prefix-matched by a shorter one")
    void didAuthorsAreNotPrefixConfusable() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("i5"), 4)) {
            // Person DIDs contain colons, so a naive startsWith("lib:" + me + ":")
            // would let "did" match "did:key:z6Mk...".
            var full = "did:key:z6MkSomebodyWithAVeryRealIdentity000";
            var id = String.valueOf(providerFor(store, full)
                .libraryAdd("Written by a person.", Map.of()).get("id"));

            assertThat(providerFor(store, "did").libraryDelete(id))
                .as("a prefix of an author is not that author")
                .containsEntry("ok", false)
                .containsEntry("reason", "not_yours");
            assertThat(providerFor(store, full).libraryDelete(id))
                .as("the real author still may")
                .containsEntry("ok", true);
        }
    }
}
