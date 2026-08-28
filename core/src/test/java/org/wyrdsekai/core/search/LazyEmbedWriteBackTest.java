package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Task #153 — a chunk should be embedded at most once, ever.
 *
 * <p>The live Study index holds <b>13,746,741 documents and zero vectors</b>: it
 * was built BM25-only. Every semantic rerank therefore embeds its candidates from
 * scratch on every query — 64 chunks, ~48s — and the rerank cache reports
 * {@code 0 cached} because each query draws a different BM25 top-N. Capping the
 * work traded recall for latency; persisting the vectors removes the trade.</p>
 *
 * <p>The dangerous part is the rebuild. Lucene cannot update one field, and
 * {@code storedFields()} returns only STORED fields — a naive copy produces a
 * document that is <b>present but unsearchable</b>, and one that silently drops
 * {@code user_did} would take a person's private content out of their own
 * ownership. These tests exist for that, not for the happy path.</p>
 */
class LazyEmbedWriteBackTest {

    private static final int DIM = 384;
    @TempDir Path tmp;
    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, DIM);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    private static List<Float> vec(float lead) {
        var v = new ArrayList<Float>(DIM);
        v.add(lead);
        for (int i = 1; i < DIM; i++) v.add(0.0f);
        return v;
    }

    private void seedStudy(String id, String owner, String text) {
        store.insertStudyItem(id, owner, "note", "A Title", text, "books",
            System.currentTimeMillis(), 1, null, null, null, false);
    }

    /**
     * THE case: a written-back document carries its vector afterwards.
     *
     * <p>Asserted directly rather than through a dense search, because
     * {@code searchStudy} is BM25-first even when given a vector — persisting
     * makes the RERANK free, it does not turn Study into a dense index.</p>
     */
    @Test
    void a_chunk_gains_a_vector() {
        seedStudy("c1", "did:key:zOwner", "the vel-shara of Adrun");
        var vectors = new LinkedHashMap<String, List<Float>>();
        vectors.put("c1", vec(1.0f));

        int written = store.writeBackVectors(SearchCollections.STUDY, vectors);

        assertThat(written).isEqualTo(1);
        assertThat(store.hasStoredVector(SearchCollections.STUDY, "c1"))
            .as("the chunk must carry its embedding afterwards — that is the whole point")
            .isTrue();
    }

    /** The rebuilt document must remain findable by TEXT — the round-trip trap. */
    @Test
    void the_rebuilt_document_is_still_searchable() {
        seedStudy("c1", "did:key:zOwner", "the vel-shara of Adrun");
        store.writeBackVectors(SearchCollections.STUDY,
            new LinkedHashMap<>(Map.of("c1", vec(1.0f))));

        assertThat(store.searchStudy("did:key:zOwner", "vel-shara", null, 5))
            .as("a copied document is present but unsearchable — it must be rebuilt")
            .isNotEmpty();
    }

    /** Ownership must survive. Losing user_did takes content out of a person's hands. */
    @Test
    void ownership_and_metadata_survive() {
        seedStudy("c1", "did:key:zOwner", "private thinking");
        store.writeBackVectors(SearchCollections.STUDY,
            new LinkedHashMap<>(Map.of("c1", vec(1.0f))));

        var hits = store.searchStudy("did:key:zOwner", "private", null, 5);
        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().metadata()).containsEntry("collection", "books");
        assertThat(hits.getFirst().metadata()).containsEntry("item_type", "note");
        assertThat(store.searchStudy("did:key:zSomeoneElse", "private", null, 5))
            .as("write-back must not hand one person's content to another")
            .isEmpty();
    }

    /** Content must survive intact, not be truncated to whatever was embedded. */
    @Test
    void the_full_text_survives() {
        var text = "The Librarian explained that a vel-shara is a speech with power — "
            + "an incantation that reprograms the listener.";
        seedStudy("c1", "did:key:zOwner", text);
        store.writeBackVectors(SearchCollections.STUDY,
            new LinkedHashMap<>(Map.of("c1", vec(1.0f))));

        assertThat(store.searchStudy("did:key:zOwner", "incantation", null, 5).getFirst().content())
            .isEqualTo(text);
    }

    /** A wrong-dimension vector must be refused, not indexed. */
    @Test
    void a_mismatched_dimension_is_skipped() {
        seedStudy("c1", "did:key:zOwner", "text");
        var bad = new LinkedHashMap<String, List<Float>>();
        bad.put("c1", List.of(1.0f, 2.0f));      // not DIM

        assertThat(store.writeBackVectors(SearchCollections.STUDY, bad)).isZero();
        assertThat(store.searchStudy("did:key:zOwner", "text", null, 5))
            .as("the original must be untouched")
            .isNotEmpty();
    }

    /** An unknown id must not create a hollow document. */
    @Test
    void an_unknown_id_is_skipped() {
        var v = new LinkedHashMap<String, List<Float>>();
        v.put("does-not-exist", vec(1.0f));

        assertThat(store.writeBackVectors(SearchCollections.STUDY, v)).isZero();
    }

    /** Degenerate input must not throw. */
    @Test
    void handles_empty_and_null() {
        assertThat(store.writeBackVectors(SearchCollections.STUDY, null)).isZero();
        assertThat(store.writeBackVectors(SearchCollections.STUDY, new LinkedHashMap<>())).isZero();
        assertThat(store.writeBackVectors(null, new LinkedHashMap<>())).isZero();
    }

    /** Writing back twice must not duplicate the document. */
    @Test
    void is_idempotent() {
        seedStudy("c1", "did:key:zOwner", "once only");
        var v = new LinkedHashMap<String, List<Float>>(Map.of("c1", vec(1.0f)));

        store.writeBackVectors(SearchCollections.STUDY, v);
        store.writeBackVectors(SearchCollections.STUDY, v);

        assertThat(store.searchStudy("did:key:zOwner", "once", null, 10))
            .as("updateDocument by id, never append")
            .hasSize(1);
    }

    /**
     * Persisting a vector must not cost the chunk its place in its book.
     *
     * <p>Live 2026-08-09, the two features were shipped hours apart and the
     * older one silently ate the newer: the adjacency backfill placed all 13.7M
     * chunks at 11:50, a rerank warmed its cache at 13:04, and this rebuild —
     * whose hand-copied field list predates {@code doc_group}/{@code part} —
     * stripped the placement from exactly the chunks people query most. The
     * neighbour read then saw {@code doc_group='null'} on a document the raw
     * index had carried correctly an hour earlier.</p>
     */
    @Test
    void a_chunk_keeps_its_place_in_the_book() {
        for (int p = 78; p <= 80; p++) {
            store.insertStudyItem("p" + p, "did:key:zOwner", "document",
                "The Diamond Age (part " + p + "/471)", "page " + p, "books",
                System.currentTimeMillis(), 1, null, null, null, false);
        }

        store.writeBackVectors(SearchCollections.STUDY,
            new LinkedHashMap<>(Map.of("p79", vec(1.0f))));

        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "p79", 1))
            .extracting(WyrdLuceneStore.SearchResult::id)
            .as("the write-back must not un-place the chunk it touches")
            .containsExactly("p78", "p79", "p80");
        assertThat(store.hasStoredVector(SearchCollections.STUDY, "p79")).isTrue();
    }

    /** The rerank must consult the index before paying to embed. */
    @Test
    void the_rerank_reads_the_index_before_embedding() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/search/WyrdLuceneStore.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(src).contains("var stored = storedVector(collection, c.id());");
        assertThat(src)
            .as("and must persist what it computes, off the query's critical path")
            .contains("vector-writeback-");
        assertThat(src).contains("setDaemon(true)");
    }
}
