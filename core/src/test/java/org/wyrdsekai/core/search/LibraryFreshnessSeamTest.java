package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Library-freshness seam (#1139): the side-channel enumerate / prune-by-id path
 * the {@code research-pack-freshness} recipe drives, plus the tag-edit
 * vector-preservation fix that motivated it.
 *
 * <p>The bug: {@code updateKnowledgeTags} rebuilt the doc via
 * {@code newDocument(id, content, null)}, dropping the KnnFloatVectorField — so
 * editing a chunk's tags silently destroyed its dense vector and it fell out of
 * dense / hybrid search. These tests prove the vector survives an edit, and that
 * enumerate + prune-by-id behave.</p>
 */
class LibraryFreshnessSeamTest {

    private static final int DIM = 384;

    @TempDir
    Path tempDir;

    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tempDir, DIM);
        store.ensureAllCollections();
    }

    @AfterEach
    void tearDown() throws IOException {
        store.close();
    }

    /** A unit-ish vector: 1.0 at {@code hot}, 0 elsewhere (non-zero norm). */
    private static List<Float> vec(int hot) {
        var v = new ArrayList<Float>(DIM);
        for (int i = 0; i < DIM; i++) v.add(i == hot ? 1.0f : 0.0f);
        return v;
    }

    @Test
    void tagEditPreservesDenseVector() {
        store.insertKnowledge("k1", "pack-a", "Chunk One",
            "alpha content about gardens", "http://example.com/a", "old|tags", vec(0));

        // Before edit: dense search by the chunk's own vector finds it.
        var before = store.searchKnowledge("anything", vec(0), 5,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertTrue(before.stream().anyMatch(r -> r.id().equals("k1")),
            "chunk should be dense-searchable before tag edit");

        // Edit the tags.
        var res = store.updateKnowledgeTags(SearchCollections.KNOWLEDGE, "k1",
            List.of("new", "garden"));
        assertEquals(Boolean.TRUE, res.get("ok"));

        // After edit: the dense vector must SURVIVE (the bug dropped it here).
        var after = store.searchKnowledge("anything", vec(0), 5,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertTrue(after.stream().anyMatch(r -> r.id().equals("k1")),
            "tag edit must not destroy the chunk's dense vector");

        // And the new subject tags are stored.
        var chunk = store.getById(SearchCollections.KNOWLEDGE, "k1");
        assertNotNull(chunk);
        var subject = String.valueOf(chunk.metadata().getOrDefault("subject", ""));
        assertTrue(subject.contains("garden"), "edited tags should persist");
    }

    @Test
    void enumerateReturnsProvenanceAndPruneRemovesById() {
        store.insertKnowledge("a1", "pack-a", "A", "alpha",
            "http://example.com/a", "t", vec(1));
        store.insertKnowledge("b1", "pack-b", "B", "bravo",
            "http://example.com/b", "t", vec(2));
        store.insertKnowledge("c1", "pack-c", "C", "charlie",
            "http://example.com/c", "t", vec(3));

        var entries = store.enumerateKnowledgeProvenance(100);
        assertTrue(entries.size() >= 3, "all inserted chunks should enumerate");
        assertTrue(entries.stream().anyMatch(e ->
                "a1".equals(e.get("id")) && "http://example.com/a".equals(e.get("source"))),
            "enumerate should carry id + source for freshness checks");

        // Prune two by id; the third survives.
        long pruned = store.pruneKnowledgeByIds(List.of("a1", "b1"));
        assertEquals(2, pruned, "should remove exactly the requested ids");
        assertNull(store.getById(SearchCollections.KNOWLEDGE, "a1"));
        assertNull(store.getById(SearchCollections.KNOWLEDGE, "b1"));
        assertNotNull(store.getById(SearchCollections.KNOWLEDGE, "c1"),
            "unrequested chunk must remain");
    }

    @Test
    void pruneEmptyOrUnknownIsSafeNoOp() {
        store.insertKnowledge("z1", "pack-z", "Z", "zulu",
            "http://example.com/z", "t", vec(4));
        assertEquals(0, store.pruneKnowledgeByIds(List.of()),
            "empty id list prunes nothing");
        assertEquals(0, store.pruneKnowledgeByIds(List.of("does-not-exist")),
            "unknown id prunes nothing");
        assertNotNull(store.getById(SearchCollections.KNOWLEDGE, "z1"));
    }
}
