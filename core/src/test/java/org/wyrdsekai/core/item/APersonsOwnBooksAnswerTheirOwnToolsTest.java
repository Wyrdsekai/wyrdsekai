package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person searching from their own hands searches their own shelves.
 *
 * <h2>What went wrong</h2>
 * Home node, 2026-08-25: the steward asked his fairy-tale tool about
 * Cryptonomicon and Kovacs — names that live in his 74,697-volume Study and
 * nowhere else. Every search logged {@code "Study leg skipped: no bondholder
 * resolves to a person"} and returned {@code 0 study} — because the study leg
 * only knew one question, "who is my companion's bondholder", and the caller
 * was not a companion. The person whose books they are was the one asking.
 * Generic pack rows answered instead, which is why the "glass tide" tale was
 * about a farmer and a river.
 */
class APersonsOwnBooksAnswerTheirOwnToolsTest {

    @Test
    @DisplayName("a provider built for a person reads that person's own shelves")
    void ownerSelfComesFirst() throws Exception {
        // THIS TEST USED TO ASSERT THE ORDER OF TWO LINES IN THE SOURCE:
        // that the self-check appeared before the bondholder-check. It passed
        // for months while the feature was dead, because a person's search
        // never reached that code — it was forwarded to a shared provider
        // carrying a placeholder identity. A shape assertion downstream of the
        // break proves the shape, not the behaviour. So: run it.
        var dir = Files.createTempDirectory("own-books");
        var person = "did:key:z6MkTheStewardWhoOwnsTheseVolumes000000";
        try (var store = new WyrdLuceneStore(dir.resolve("idx"), 4)) {
            var svc = new StudyService(store, null);
            svc.indexDocumentChunk(person, "books", "Cryptonomicon.epub",
                "Waterhouse studied the intercepts at Bletchley Park.", "/s/cn.epub", 0);
            svc.commitDocuments();
            HouseholdResources.register(store);
            try {
                var provider = new ItemWorldApiProviderImpl(store, null, null, null,
                    person, person, null, null, null, null, null);
                var hits = provider.searchKnowledge("Waterhouse Bletchley", 5);
                assertThat(hits)
                    .as("his own book, found from his own hands, with no grant needed")
                    .isNotEmpty();
            } finally {
                HouseholdResources.resetForTests();
            }
        }
    }

    @Test
    @DisplayName("a caller who is nobody reads nothing")
    void aPlaceholderIdentityReachesNoShelf() throws Exception {
        var dir = Files.createTempDirectory("own-books-none");
        var person = "did:key:z6MkTheStewardWhoOwnsTheseVolumes000000";
        try (var store = new WyrdLuceneStore(dir.resolve("idx2"), 4)) {
            var svc = new StudyService(store, null);
            svc.indexDocumentChunk(person, "books", "Cryptonomicon.epub",
                "Waterhouse studied the intercepts at Bletchley Park.", "/s/cn.epub", 0);
            svc.commitDocuments();
            HouseholdResources.register(store);
            try {
                // The identity Main used to share with everyone.
                var shared = new ItemWorldApiProviderImpl(store, null, null, null,
                    "household", "household", null, null, null, null, null);
                assertThat(shared.searchKnowledge("Waterhouse Bletchley", 5))
                    .as("a placeholder must not read a person's shelf")
                    .isEmpty();
            } finally {
                HouseholdResources.resetForTests();
            }
        }
    }
}
