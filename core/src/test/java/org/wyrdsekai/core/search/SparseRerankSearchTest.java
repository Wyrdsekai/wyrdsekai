package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;

/**
 * SPARSE_RERANK — two-stage retrieval over vector-less chunks (2026-08-05).
 *
 * <p>A full-text ingest (the Calibre run) adds millions of BM25-only chunks with
 * no stored vectors. These tests pin the contract that matters: the mode must
 * return results from such chunks, and it must DEGRADE rather than fail when the
 * semantic leg is unavailable — because the alternative is a library search that
 * silently returns nothing.
 */
class SparseRerankSearchTest {

    @TempDir Path tmp;
    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, 1024);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (store != null) store.close();
    }

    /** Chunks indexed without vectors must still be findable via the rerank mode. */
    @Test
    void rerank_returns_results_for_chunks_with_no_vectors() {
        store.insertStudyItem("s1", "did:key:zTest", "book", "Greenhouse",
            "The greenhouse door sticks unless you lift it, and the third pane is cracked.",
            "books", 1L, 1, null);
        store.insertStudyItem("s2", "did:key:zTest", "book", "Signalling",
            "Railway signalling in the nineteenth century relied on mechanical interlocking.",
            "books", 1L, 1, null);
        store.commitAll();

        var hits = store.searchStudyByCollection("did:key:zTest", "books",
            "greenhouse door", null, 5);
        assertFalse(hits.isEmpty(), "BM25 leg must find vector-less study chunks");
        assertTrue(hits.get(0).content().contains("greenhouse"),
            "keyword match should surface the greenhouse passage");
    }

    /**
     * No query embedding -> TEXT_ONLY, never an exception and never empty-by-error.
     * This is the path every existing caller takes.
     */
    @Test
    void null_embedding_falls_back_to_keyword_order() {
        store.insertStudyItem("s1", "did:key:zTest", "book", "Seeds",
            "Seed packets kept in a tin marked 1987.", "books", 1L, 1, null);
        store.commitAll();

        var hits = store.searchStudy("did:key:zTest", "seed packets", null, 5);
        assertEquals(1, hits.size());
    }

    /**
     * Query expansion: BM25 gets discriminating TERMS, the reranker gets the full
     * INTENT. A companion's queries are conversational, and lexical retrieval is
     * exactly where that hurts most — so the first stage must not be handed
     * stopwords.
     */
    @Test
    void conversational_query_is_reduced_to_content_words_for_bm25() {
        assertEquals("greenhouses keep warm night",
            WyrdLuceneStore.keywordsOf("I wonder how the greenhouses keep warm at night"));
        assertEquals("postman parcels blue bench",
            WyrdLuceneStore.keywordsOf("What about the postman and those parcels by the blue bench?"));
    }

    /**
     * LATENCY IS THE CONTRACT (regression guard, 2026-08-05).
     *
     * <p>The first version of this mode reranked up to 300 candidates with one
     * sequential bge-m3 embed each and a study search over 13.7M chunks never
     * returned — curl gave up at 90 seconds, on a live household node. The pass
     * is now bounded by a wall-clock budget. This test fails if a search over a
     * populated index ever again takes a pathological amount of time.
     */
    @Test
    void rerank_search_returns_within_a_bounded_time() {
        for (int i = 0; i < 400; i++) {
            store.insertStudyItem("b" + i, "did:key:zTest", "book", "Book " + i,
                "The greenhouse door sticks and the panes trap warmth overnight, entry " + i,
                "books", 1L, 1, null);
        }
        store.commitAll();

        long t0 = System.nanoTime();
        var hits = store.searchStudyByCollection("did:key:zTest", "books",
            "how does the greenhouse retain heat at night", null, 5);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertFalse(hits.isEmpty(), "must return results");
        assertTrue(ms < 15_000,
            "search took " + ms + "ms — the rerank budget is not bounding latency");
    }

    /** An all-stopword query keeps its own terms — silence would be worse. */
    @Test
    void all_stopword_query_falls_back_to_original_text() {
        assertEquals("what about that", WyrdLuceneStore.keywordsOf("what about that"));
    }

    /** A vague conversational query must still retrieve the right passage. */
    @Test
    void vague_query_still_finds_the_passage() {
        store.insertStudyItem("s1", "did:key:zTest", "book", "Greenhouse",
            "The greenhouse retains warmth overnight because the panes trap infrared.",
            "books", 1L, 1, null);
        store.insertStudyItem("s2", "did:key:zTest", "book", "Trains",
            "Mechanical interlocking prevented conflicting signal routes.",
            "books", 1L, 1, null);
        store.commitAll();

        var hits = store.searchStudyByCollection("did:key:zTest", "books",
            "I wonder how the greenhouse keeps warm at night", null, 5);
        assertFalse(hits.isEmpty(), "stopword-heavy query must still retrieve");
        assertTrue(hits.get(0).content().contains("greenhouse"));
    }

    /**
     * A query vector whose dimension does not match the embedding service must not
     * blow up the search — the whole point of the mode is that the BM25 leg still
     * carries the result. (Dim drift has previously killed an entire search path.)
     */
    @Test
    void mismatched_query_vector_degrades_to_bm25_not_failure() {
        store.insertStudyItem("s1", "did:key:zTest", "book", "Postman",
            "The postman left parcels under the blue bench.", "books", 1L, 1, null);
        store.commitAll();

        var bogus = List.of(0.1f, 0.2f, 0.3f); // deliberately wrong dimension
        var hits = store.searchStudyByCollection("did:key:zTest", "books",
            "postman parcels", bogus, 5);
        assertFalse(hits.isEmpty(),
            "rerank must degrade to keyword results rather than returning nothing");
    }
}
