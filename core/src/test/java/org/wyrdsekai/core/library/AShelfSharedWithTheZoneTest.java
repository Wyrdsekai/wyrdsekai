package org.wyrdsekai.core.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person's shelf, shared with the whole zone, without silent loss.
 *
 * <h2>Why</h2>
 * 2026-08-25, the steward: "what happens if I want my full epub collection
 * shared across the zone?" The pieces existed — an epub converter, a pack
 * exporter, the indexer — and no join. Worse, the old exporter fetched via a
 * search capped at 10,000 items: his 74,697-volume shelf would have shared
 * two-thirds silently missing. {@code shareCollection} streams off the index
 * with no cap and indexes the pack in the same pass.
 */
class AShelfSharedWithTheZoneTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("the scan walks every document — paging, not a search cap")
    void theScanHasNoCap() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx"), 4)) {
            // More documents than one scan page (500), so paging is exercised.
            for (int i = 0; i < 1203; i++) {
                store.insertStudyItem("b" + i, "did:person:steward", "document",
                    "Book " + i, "content of volume " + i, "books",
                    1000L + i, 1, null);
            }
            var seen = new AtomicInteger();
            int total = store.scanStudyCollection("did:person:steward", "books",
                r -> seen.incrementAndGet());
            assertThat(total).isEqualTo(1203);
            assertThat(seen.get()).isEqualTo(1203);
        }
    }

    @Test
    @DisplayName("a published shelf answers zone-wide searches as a pack")
    void aPublishedShelfAnswersAsAPack() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx2"), 4)) {
            store.insertStudyItem("k1", "did:person:steward", "document",
                "Altered Carbon", "Takeshi Kovacs woke in a new sleeve.",
                "books", 1000L, 1, null);
            store.insertStudyItem("k2", "did:person:steward", "document",
                "Cryptonomicon", "Waterhouse studied the intercepts at Bletchley.",
                "books", 1001L, 1, null);

            var svc = new StudyService(store, null);
            int shared = svc.shareCollection("did:person:steward", "books",
                dir.resolve("packs"), new KnowledgePackIndexer(store));
            assertThat(shared).isEqualTo(2);

            // The zone-wide KNOWLEDGE surface — what every companion, item and
            // visitor searches — now finds the books with no study leg at all.
            var hits = store.searchKnowledge("Kovacs", null, 5);
            assertThat(hits).anyMatch(r -> r.content().contains("Kovacs"));

            // The pack file exists on disk, restorable and inspectable.
            assertThat(Files.exists(
                dir.resolve("packs/study-share-books/pack.json"))).isTrue();

            // Re-publishing replaces, never accretes.
            int again = svc.shareCollection("did:person:steward", "books",
                dir.resolve("packs"), new KnowledgePackIndexer(store));
            assertThat(again).isEqualTo(2);
            var after = store.searchKnowledge("Kovacs", null, 10);
            var kovacs = new ArrayList<>(after.stream()
                .filter(r -> r.content().contains("Kovacs")).toList());
            assertThat(kovacs).hasSize(1);
        }
    }
}
