package org.wyrdsekai.rendezvous;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class SemanticIndexTest {

    private static final String DID_A =
        "did:wyrd:z6MkhaXgBZDvotDkL5257faiztiGiC2QtKLGpbnnEGta2doK";
    private static final String DID_B =
        "did:wyrd:z6MkszZtxCmA2Ce4vUV132PCuLQmwnaDD5mUcs8LU6CJr8ad";

    /** Stub embedder: returns a vector based on character presence so we can
     *  construct deterministic similarity relationships in tests. */
    private static Function<String, Optional<float[]>> charVecEmbedder() {
        return text -> {
            if (text == null || text.isBlank()) return Optional.empty();
            // 4-dim vector: how much of [a, e, i, o] is in the text.
            float[] v = new float[4];
            var lower = text.toLowerCase();
            for (int i = 0; i < lower.length(); i++) {
                var c = lower.charAt(i);
                if (c == 'a') v[0] += 1;
                else if (c == 'e') v[1] += 1;
                else if (c == 'i') v[2] += 1;
                else if (c == 'o') v[3] += 1;
            }
            return Optional.of(v);
        };
    }

    private static ZoneManifestV1 manifest(String did, String label, String description) {
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION, did, label, "Display", null,
            "tag", description, List.of(), null, null, null, 0,
            "2026-01-15T00:00:00Z", "2026-04-20T00:00:00Z", null);
    }

    @Test void indexAndSearch_basic() {
        var store = new DirectoryStore(100, 3600);
        var idx = new SemanticIndex(charVecEmbedder(), store);
        var m = manifest(DID_A, "kitchen", "aaa");
        store.publish(m);
        idx.indexManifest(m);

        assertEquals(1, idx.indexSize());
        var hits = idx.search("aaa", 10);
        assertEquals(1, hits.size());
        assertEquals(DID_A, hits.get(0).manifest().did());
    }

    @Test void search_ranksBySimilarity() {
        var store = new DirectoryStore(100, 3600);
        var idx = new SemanticIndex(charVecEmbedder(), store);
        // "aaa" vector = [3,0,0,0]; "ooo" = [0,0,0,3]; "eee" = [0,3,0,0]
        var ma = manifest(DID_A, "kitchen", "aaa");
        var mo = manifest(DID_B, "garage", "ooo");
        store.publish(ma); store.publish(mo);
        idx.indexManifest(ma); idx.indexManifest(mo);

        // Query "aaa" — should match DID_A more than DID_B.
        var hits = idx.search("aaa", 10);
        assertTrue(hits.size() >= 1);
        assertEquals(DID_A, hits.get(0).manifest().did(),
            "nearest-neighbour match ranks first");
    }

    @Test void search_emptyIndex_fallsBackToSubstring() {
        var store = new DirectoryStore(100, 3600);
        var m = manifest(DID_A, "kitchen", "cozy craft space");
        store.publish(m);

        // SemanticIndex has no entries — should fall back to store.searchText
        var idx = new SemanticIndex(charVecEmbedder(), store);
        var hits = idx.search("craft", 10);
        assertEquals(1, hits.size(), "falls back to substring when index empty");
    }

    @Test void search_queryNotEmbeddable_fallsBack() {
        var store = new DirectoryStore(100, 3600);
        var m = manifest(DID_A, "kitchen", "cozy craft space");
        store.publish(m);

        var idx = new SemanticIndex(
            (Function<String, Optional<float[]>>) t -> Optional.empty(),
            store);
        idx.indexManifest(m);
        // Query embedding fails → fallback to substring search.
        var hits = idx.search("craft", 10);
        assertEquals(1, hits.size());
    }

    @Test void search_nullClient_fallsBack() {
        var store = new DirectoryStore(100, 3600);
        store.publish(manifest(DID_A, "kitchen", "cozy craft space"));

        // Null embedding client — entire class degrades to substring search.
        var idx = new SemanticIndex((EmbeddingClient) null, store);
        var hits = idx.search("craft", 10);
        assertEquals(1, hits.size());
    }

    @Test void removeManifest_dropsFromIndex() {
        var store = new DirectoryStore(100, 3600);
        var idx = new SemanticIndex(charVecEmbedder(), store);
        var m = manifest(DID_A, "kitchen", "aaa");
        store.publish(m);
        idx.indexManifest(m);
        assertEquals(1, idx.indexSize());

        idx.removeManifest(DID_A);
        assertEquals(0, idx.indexSize());
    }

    @Test void indexManifest_embedderFailureSkipsSilently() {
        var store = new DirectoryStore(100, 3600);
        var failing = new SemanticIndex(
            (Function<String, Optional<float[]>>) t -> Optional.empty(),
            store);
        assertDoesNotThrow(() ->
            failing.indexManifest(manifest(DID_A, "kitchen", "text")));
        // Indexed manifest skipped → index remains empty → search falls back.
        assertEquals(0, failing.indexSize());
    }

    @Test void search_respectsLimit() {
        var store = new DirectoryStore(100, 3600);
        var idx = new SemanticIndex(charVecEmbedder(), store);
        for (char c = 'a'; c <= 'e'; c++) {
            var did = "did:wyrd:z6MkNneedToBe50CharsPadPadPadPadPadPadPadPadPad" + c;
            var m = manifest(did, "zone-" + c, "aaa eee iii");
            store.publish(m);
            idx.indexManifest(m);
        }
        var hits = idx.search("aaa eee iii", 2);
        assertEquals(2, hits.size());
    }

    @Test void search_emptyQuery_returnsEmpty() {
        var store = new DirectoryStore(100, 3600);
        var idx = new SemanticIndex(charVecEmbedder(), store);
        assertTrue(idx.search("", 10).isEmpty());
        assertTrue(idx.search(null, 10).isEmpty());
    }
}
