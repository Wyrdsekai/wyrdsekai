package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A document's stored vector is its own, on a shelf where only some documents have one.
 *
 * <p>The Study gains vectors one search at a time, so most of its documents have none. The read
 * passed a document's number where Lucene wants the vector's position: on the household node it
 * ran past the end 81 times in two days, and elsewhere returned another document's vector without
 * a word (2026-10-04).</p>
 */
class AStoredVectorIsReadAsItsOwnTest {

    private static final int DIM = 384;
    @TempDir Path tmp;
    private WyrdLuceneStore store;

    @BeforeEach void setUp() { store = new WyrdLuceneStore(tmp, DIM); }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    private static List<Float> vec(float lead) {
        var v = new ArrayList<Float>(DIM);
        v.add(lead);
        for (int i = 1; i < DIM; i++) v.add(0.0f);
        return v;
    }

    @Test
    void on_a_sparse_shelf_each_document_reads_back_its_own_vector_and_the_others_none() {
        // Twelve passages in one segment, only three with a vector (c3, c7, c10), as on the Study.
        for (int i = 0; i < 12; i++) {
            List<Float> v = (i == 3 || i == 7 || i == 10) ? vec(i) : null;
            store.insertStudyItem("c" + i, "did:key:zOwner", "note", "A Title", "passage number " + i + " of the book",
                SearchCollections.STUDY, System.currentTimeMillis(), 1, v);
        }
        store.forceMergeCollection(SearchCollections.STUDY);

        for (var i : List.of(3, 7, 10)) {
            var v = store.storedVector(SearchCollections.STUDY, "c" + i);
            assertThat(v).as("c" + i + " has its vector").isNotNull();
            assertThat(v.get(0)).as("c" + i + " reads back its own, not a neighbour's").isEqualTo((float) i);
        }
        for (var id : List.of("c0", "c1", "c2", "c4", "c11")) {
            assertThat(store.storedVector(SearchCollections.STUDY, id)).as(id + " has none").isNull();
        }
    }

    @Test
    void a_reembed_gives_every_passage_that_carries_a_vector_its_own_again_and_leaves_the_rest_alone() {
        // As after the August pass: three passages carry a vector, and two of them carry the wrong one.
        for (int i = 0; i < 12; i++) {
            List<Float> v = i == 3 ? vec(7) : i == 7 ? vec(10) : i == 10 ? vec(10) : null;
            store.insertStudyItem("c" + i, "did:key:zOwner", "note", "A Title", "passage " + i,
                SearchCollections.STUDY, System.currentTimeMillis(), 1, v);
        }
        store.forceMergeCollection(SearchCollections.STUDY);
        var progress = new java.util.ArrayList<Long>();
        // The embedder here reads the passage number back out of its text.
        long n = store.reembedStoredVectors(SearchCollections.STUDY, texts -> {
            var out = new java.util.ArrayList<List<Float>>();
            for (var t : texts) out.add(vec(Integer.parseInt(t.replace("passage ", "").trim())));
            return out;
        }, 2, progress::add);
        assertThat(n).isEqualTo(3);
        assertThat(progress).isNotEmpty().last().isEqualTo(3L);
        for (var i : List.of(3, 7, 10)) {
            assertThat(store.storedVector(SearchCollections.STUDY, "c" + i).get(0)).as("c" + i + " carries its own again").isEqualTo((float) i);
        }
        assertThat(store.storedVector(SearchCollections.STUDY, "c5")).as("a passage with none stays for the search to embed").isNull();
        assertThat(store.reembedStoredVectors(SearchCollections.STUDY, texts -> null, 8, null)).as("an embedder that answers nothing stops it, harmlessly").isZero();
        assertThat(store.storedVector(SearchCollections.STUDY, "c7").get(0)).isEqualTo(7.0f);
        // Each write replaced its passage, and the old copies wait in their segments for a merge. The
        // count the steward reads is of passages, not of copies: second-node's read 14,032 for 10,338 after
        // the first re-embed (2026-10-04).
        assertThat(store.countWithVectors(SearchCollections.STUDY)).as("passages that carry a vector").isEqualTo(3);
    }
}
