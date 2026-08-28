package org.wyrdsekai.core.library;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pack indexing batches its I/O; a memory is still visible the instant
 * it is written.
 *
 * <h2>Why</h2>
 * 2026-08-25, the home node: publishing a 74,697-volume shelf ran at
 * ~170 chunks/s — a projected ~22 hours for its 13.7M chunks. The cost was
 * not Lucene: {@code upsert} refreshed the NRT searcher after EVERY insert
 * (a segment flush per document, dearer as the index grows) and committed
 * every 100th document, on top of the indexer's own commit every 500. The
 * fix is a bulk insert path that defers all visibility to the caller's
 * batch commit — while the normal single-insert path keeps its
 * see-it-immediately contract, because a companion's new memory must be
 * searchable at once.
 */
class APackIndexesInBulkNotAFlushPerChunkTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a bulk insert becomes visible at the commit, not per document")
    void bulkVisibilityArrivesAtTheCommit() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx"), 4)) {
            // Instantiate the searcher first: a lazily-created NRT searcher
            // would otherwise see whatever was buffered at creation time.
            assertThat(store.searchKnowledge("sleeve", null, 5)).isEmpty();

            store.insertKnowledgeBulk("bulk-1", "test-pack", "Altered Carbon",
                "Kovacs woke in a new sleeve.", "shelf", null, null, null);
            assertThat(store.searchKnowledge("sleeve", null, 5))
                .as("no per-document refresh on the bulk path")
                .isEmpty();

            store.commitAll();
            assertThat(store.searchKnowledge("sleeve", null, 5))
                .as("the batch commit publishes the batch")
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("a normal insert keeps the see-it-immediately contract")
    void aSingleInsertIsVisibleAtOnce() throws Exception {
        try (var store = new WyrdLuceneStore(dir.resolve("idx2"), 4)) {
            assertThat(store.searchKnowledge("bletchley", null, 5)).isEmpty();
            store.insertKnowledge("one-1", "test-pack", "Cryptonomicon",
                "Waterhouse at Bletchley.", "shelf", null, null, null);
            assertThat(store.searchKnowledge("bletchley", null, 5))
                .as("a new memory is searchable without any explicit commit")
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("the indexer's loops insert through the bulk path")
    void theIndexerUsesTheBulkPath() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/library/KnowledgePackIndexer.java";
        var fromCore = Path.of("..", rel);
        var src = Files.readString(Files.exists(fromCore) ? fromCore : Path.of(rel));
        assertThat(src)
            .as("both insert loops use the bulk variant")
            .doesNotContain("luceneStore.insertKnowledge(")
            .contains("luceneStore.insertKnowledgeBulk(");
        assertThat(src)
            .as("each batch boundary commits AND refreshes")
            .contains("luceneStore.commitAll();");
    }

    @Test
    @DisplayName("a shutdown mid-pack stops the run — it does not narrate every remaining chunk")
    void aClosedIndexStopsTheRun() throws Exception {
        var packDir = dir.resolve("packs/abort-pack");
        Files.createDirectories(packDir.resolve("chunks"));
        Files.writeString(packDir.resolve("pack.json"),
            "{\"name\":\"abort-pack\",\"title\":\"Abort\"}");
        var lines = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            lines.append("{\"id\":\"a").append(i)
                 .append("\",\"title\":\"T\",\"content\":\"body ").append(i).append("\"}\n");
        }
        Files.writeString(packDir.resolve("chunks/data.jsonl"), lines.toString());

        var store = new WyrdLuceneStore(dir.resolve("idx3"), 4);
        store.insertKnowledgeBulk("warm", "p", "t", "warm the writer open", "s", null, null, null);
        store.commitAll();
        store.close();   // the node shutting down under an in-flight index

        // Must return, not throw, and must not have indexed anything.
        var result = new KnowledgePackIndexer(store).indexPack(packDir);
        assertThat(result.chunksIndexed())
            .as("the loop stops at the closed writer instead of walking all 500")
            .isZero();
    }
}
