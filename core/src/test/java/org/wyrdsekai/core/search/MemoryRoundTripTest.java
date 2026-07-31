package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repro for the second-node 2026-07-09 memory round-trip failure: handleRemember indexes the fact
 * (term visible in the .tim file) but handleRecall's searchMemory returns empty. Exercises
 * insertMemoryItem → searchMemory across the exact shapes production uses: BM25-only (null
 * embedding), hybrid SET_UNION, and a query-vs-index embedding dimension mismatch (the node
 * switched to bge-m3/1024d while older entries may carry other dims).
 */
class MemoryRoundTripTest {

    private static final String DID = "did:key:z6MkTestAgent";

    private static List<Float> vec(int dim, float seed) {
        var v = new ArrayList<Float>(dim);
        for (int i = 0; i < dim; i++) v.add((float) Math.sin(seed + i));
        return v;
    }

    @Test
    void bm25_only_round_trip(@TempDir Path tmp) {
        var store = new WyrdLuceneStore(tmp, 384);
        store.insertMemoryItem("fact-1", DID, "user_fact",
            "Wyrd's favorite tea is genmaicha, and he takes it in the evening.",
            null, System.currentTimeMillis(), "study");
        var hits = store.searchMemory(DID, "favorite tea and when they drink it", null, 8,
            WyrdLuceneStore.SearchMode.SET_UNION);
        assertThat(hits).as("BM25-only recall must find the just-inserted fact").isNotEmpty();
        assertThat(hits.getFirst().content()).contains("genmaicha");
    }

    @Test
    void hybrid_round_trip_same_dim(@TempDir Path tmp) {
        var store = new WyrdLuceneStore(tmp, 384);
        store.insertMemoryItem("fact-2", DID, "user_fact",
            "Wyrd's favorite tea is genmaicha.", vec(384, 1f),
            System.currentTimeMillis(), "study");
        var hits = store.searchMemory(DID, "favorite tea", vec(384, 2f), 8,
            WyrdLuceneStore.SearchMode.SET_UNION);
        assertThat(hits).as("hybrid recall must find the fact").isNotEmpty();
    }

    @Test
    void dim_mismatch_must_not_kill_bm25(@TempDir Path tmp) {
        // Index carries 384-d vectors; the query arrives with 1024-d (bge-m3). The dense leg
        // may fail, but the BM25 leg must still return the fact — a dimension change must not
        // silently break recall.
        var store = new WyrdLuceneStore(tmp, 384);
        store.insertMemoryItem("fact-3", DID, "user_fact",
            "Wyrd's favorite tea is genmaicha.", vec(384, 1f),
            System.currentTimeMillis(), "study");
        var hits = store.searchMemory(DID, "favorite tea genmaicha", vec(1024, 2f), 8,
            WyrdLuceneStore.SearchMode.SET_UNION);
        assertThat(hits).as("BM25 leg must survive a dense-leg dimension mismatch").isNotEmpty();
    }

    @Test
    void mixed_dim_docs_must_not_block_insert_or_search(@TempDir Path tmp) {
        // Older working_memory entries may exist with a different embedding dim than a new
        // user_fact after an embedding-model switch. Neither insert nor search should fail.
        var store = new WyrdLuceneStore(tmp, 384);
        store.insertMemoryItem("old-1", DID, "working_memory",
            "10:00 [Task completed] something old", vec(384, 3f),
            System.currentTimeMillis(), "study");
        store.insertMemoryItem("fact-4", DID, "user_fact",
            "Wyrd's favorite tea is genmaicha.", vec(1024, 1f),
            System.currentTimeMillis(), "study");
        var hits = store.searchMemory(DID, "favorite tea genmaicha", null, 8,
            WyrdLuceneStore.SearchMode.SET_UNION);
        assertThat(hits).as("fact must be findable via BM25 even after a dim-mixed insert")
            .anySatisfy(h -> assertThat(h.content()).contains("genmaicha"));
    }
}
