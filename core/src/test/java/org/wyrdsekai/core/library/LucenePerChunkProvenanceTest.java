package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #474 — per-chunk provenance schema in {@link WyrdLuceneStore}. Verifies that
 * {@link WyrdLuceneStore#insertKnowledge(String, String, String, String, String, String,
 * java.util.List, Provenance)} persists trust tier as a filterable
 * {@code StringField} and round-trips the full {@link Provenance} record via
 * {@link WyrdLuceneStore#readKnowledgeChunk(String)}.
 */
class LucenePerChunkProvenanceTest {

    private WyrdLuceneStore store;

    @AfterEach
    void close() {
        if (store != null) {
            try { store.close(); } catch (Exception ignore) {}
            store = null;
        }
    }

    @Test
    void insert_with_provenance_round_trips_via_read_chunk(@TempDir Path tmp) {
        store = new WyrdLuceneStore(tmp, 384);
        var prov = new Provenance(
            new Provenance.Source("arxiv", "arXiv:1234.5678", "https://arxiv.org/abs/1234.5678",
                "Some Paper", List.of("Author"), 2024),
            Provenance.TrustTier.PAPER, "cc-by",
            Instant.parse("2026-04-01T00:00:00Z"),
            "did:key:wyrd", "did:key:operator",
            Instant.parse("2026-04-01T01:00:00Z"),
            "bunshin-scout", null);

        store.insertKnowledge("paper:0", "papers", "Some Paper",
            "Body of a paper about something interesting.",
            "https://arxiv.org/abs/1234.5678", null, null, prov);
        store.commitAll();

        var read = store.readKnowledgeChunk("paper:0");
        assertThat(read).isNotNull();
        assertThat(read.get("id")).isEqualTo("paper:0");
        assertThat(read.get("text")).asString().contains("interesting");
        assertThat(read.get("trustTier")).isEqualTo("PAPER");
        // Full provenance comes back as a Map (Jackson convertValue).
        assertThat(read.get("provenance")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var provMap = (Map<String, Object>) read.get("provenance");
        assertThat(provMap.get("trustTier")).isEqualTo("PAPER");
        assertThat(provMap.get("via")).isEqualTo("bunshin-scout");
    }

    @Test
    void insert_without_provenance_defaults_to_unknown_tier(@TempDir Path tmp) {
        store = new WyrdLuceneStore(tmp, 384);
        store.insertKnowledge("legacy:0", "legacy-pack", "Old chunk",
            "Body without provenance.", "legacy", null, null);
        store.commitAll();

        var read = store.readKnowledgeChunk("legacy:0");
        assertThat(read).isNotNull();
        assertThat(read.get("trustTier")).isEqualTo("UNKNOWN");
        assertThat(read.get("provenance")).isNull();
    }

    @Test
    void search_by_tier_filter_keeps_only_high_trust(@TempDir Path tmp) {
        store = new WyrdLuceneStore(tmp, 384);
        var paperProv = new Provenance(null, Provenance.TrustTier.PAPER,
            null, null, null, null, null, null, null);
        var blogProv = new Provenance(null, Provenance.TrustTier.BLOG,
            null, null, null, null, null, null, null);

        store.insertKnowledge("p:1", "papers", "FHE intro",
            "homomorphic encryption is fully homomorphic.",
            "https://arxiv.org/abs/p1", null, null, paperProv);
        store.insertKnowledge("b:1", "blogs", "FHE blog post",
            "homomorphic encryption explained casually.",
            "https://blog.example/fhe", null, null, blogProv);
        store.commitAll();

        // No filter — both come back.
        var unfiltered = store.searchKnowledge("homomorphic", null, 10);
        assertThat(unfiltered).hasSizeGreaterThanOrEqualTo(2);

        // Filter PAPER — only the paper passes.
        var filtered = store.searchKnowledgeByTier("homomorphic", null,
            Provenance.TrustTier.PAPER, 10);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().id()).isEqualTo("p:1");

        // Filter BLOG — both PAPER and BLOG pass (PAPER is more trusted than BLOG).
        var blogOrAbove = store.searchKnowledgeByTier("homomorphic", null,
            Provenance.TrustTier.BLOG, 10);
        assertThat(blogOrAbove).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void pack_indexer_backfills_legacy_chunks_with_default_tier(@TempDir Path tmp) {
        store = new WyrdLuceneStore(tmp, 384);
        var indexer = new KnowledgePackIndexer(store);
        var legacyChunks = List.of(
            // No provenance → indexer should backfill to wiki tier for "simple-wikipedia".
            KnowledgeChunk.text("simple-wikipedia:0", "simple-wikipedia",
                "Tokyo", "Tokyo is the capital of Japan.", "Wikipedia"));
        var result = indexer.indexChunks("simple-wikipedia", legacyChunks, null);
        assertThat(result.chunksIndexed()).isEqualTo(1);

        var read = store.readKnowledgeChunk("simple-wikipedia:0");
        assertThat(read).isNotNull();
        // Backfill writes the WIKI tier into the chunk's provenance.
        assertThat(read.get("trustTier")).isEqualTo("WIKI");
    }
}
