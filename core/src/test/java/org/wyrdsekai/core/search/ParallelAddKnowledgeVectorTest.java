package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The offline embed job adds vectors from many threads at once; the store must take that
 * without a caller-side lock (the lock left one thread writing while the rest waited,
 * 2026-09-05). Every document ends up with exactly its vector and nothing is lost.
 */
class ParallelAddKnowledgeVectorTest {

    @Test
    void vectors_are_added_from_many_threads_without_loss(@TempDir Path dir) throws Exception {
        int dim = 64, docs = 400, threads = 8;
        var store = new WyrdLuceneStore(dir, dim);
        try {
            store.ensureAllCollections();
            for (int i = 0; i < docs; i++) {
                store.insertKnowledge("p:" + i, "p", "title " + i, "chunk " + i + " text", "p", "s", null);
            }
            store.commitAll();
            assertEquals(0, store.countWithVectors(SearchCollections.KNOWLEDGE));

            var pool = Executors.newFixedThreadPool(threads);
            var futures = new ArrayList<Future<Integer>>();
            for (int t = 0; t < threads; t++) {
                int from = t;
                futures.add(pool.submit(() -> {
                    int n = 0;
                    for (int i = from; i < docs; i += threads) {
                        var vec = new ArrayList<Float>(dim);
                        for (int d = 0; d < dim; d++) vec.add(d == i % dim ? 1f : 0f);
                        if (store.addKnowledgeVector("p:" + i, vec)) n++;
                    }
                    return n;
                }));
            }
            int added = 0;
            for (var f : futures) added += f.get();
            pool.shutdown();
            store.commitKnowledge();

            assertEquals(docs, added);
            assertEquals(docs, store.countWithVectors(SearchCollections.KNOWLEDGE), "every document carries a vector");
            assertTrue(store.knowledgeIdsWithoutVectors(null, docs).isEmpty(), "nothing left text-only");
            var one = store.getById(SearchCollections.KNOWLEDGE, "p:7");
            assertEquals("chunk 7 text", one.content(), "the text survived the re-index");
            assertEquals("title 7", String.valueOf(one.metadata().get("title")), "the stored fields survived");
        } finally {
            store.close();
        }
    }
}
