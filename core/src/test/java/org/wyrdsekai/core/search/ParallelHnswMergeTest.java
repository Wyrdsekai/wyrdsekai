package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the merge-workers property set, a force-merge builds the HNSW graph on several
 * threads and writes the same on-disk format: a store opened WITHOUT the property reads
 * every vector back and finds the right neighbour.
 */
class ParallelHnswMergeTest {

    @AfterEach
    void clear() { System.clearProperty(WyrdLuceneStore.HNSW_MERGE_WORKERS_PROP); }

    @Test
    void parallel_merge_writes_an_index_a_plain_store_reads(@TempDir Path dir) throws Exception {
        int dim = 32, docs = 300;
        System.setProperty(WyrdLuceneStore.HNSW_MERGE_WORKERS_PROP, "4");
        var store = new WyrdLuceneStore(dir, dim);
        try {
            store.ensureAllCollections();
            for (int i = 0; i < docs; i++) {
                var vec = new ArrayList<Float>(dim);
                for (int d = 0; d < dim; d++) vec.add(d == i % dim ? 1f : 0.01f * (i % 7));
                store.insertKnowledge("p:" + i, "p", "t" + i, "chunk " + i, "p", "s", vec);
                if (i % 50 == 49) store.commitAll();   // several segments to merge
            }
            store.commitAll();
            assertTrue(store.segmentCount(SearchCollections.KNOWLEDGE) > 2, "several segments to start");
            assertTrue(store.forceMergeCollection(SearchCollections.KNOWLEDGE, 2), "staged merge ran");
            assertTrue(store.segmentCount(SearchCollections.KNOWLEDGE) <= 2, "down to two");
            assertTrue(store.forceMergeCollection(SearchCollections.KNOWLEDGE), "final merge ran");
            assertEquals(1, store.segmentCount(SearchCollections.KNOWLEDGE));
        } finally {
            store.close();
        }
        System.clearProperty(WyrdLuceneStore.HNSW_MERGE_WORKERS_PROP);
        var plain = new WyrdLuceneStore(dir, dim);
        try {
            plain.ensureAllCollections();
            assertEquals(docs, plain.countWithVectors(SearchCollections.KNOWLEDGE));
            var q = new ArrayList<Float>(dim);
            for (int d = 0; d < dim; d++) q.add(d == 5 ? 1f : 0f);
            var hits = plain.searchKnowledge("chunk", q, 3);
            assertTrue(!hits.isEmpty(), "dense search answers");
            assertEquals(5, Integer.parseInt(hits.get(0).id().substring(2)) % dim, "nearest is a doc whose 1 sits at dimension 5: " + hits.get(0).id());
        } finally {
            plain.close();
        }
    }
}
