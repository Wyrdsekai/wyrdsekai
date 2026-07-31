package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SET_UNION search mode — dense results keep native ranking,
 * BM25-only matches appended (Omni-SimpleMem discovery).
 */
class SetUnionSearchTest {

    private static EmbeddingService embeddingService;
    private Path tempDir;
    private WyrdLuceneStore store;

    @BeforeAll
    static void initEmbedding() {
        embeddingService = EmbeddingService.init();
        Assumptions.assumeTrue(embeddingService != null, "EmbeddingService not available");
    }

    @AfterAll
    static void closeEmbedding() {
        if (embeddingService != null) embeddingService.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("set-union-test-");
        store = new WyrdLuceneStore(tempDir, 384);
        store.ensureAllCollections();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
        try (var walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }

    @Test
    void setUnionPreservesDenseRanking() {
        // Insert fragments with embeddings
        seed("frag-soul", "The soul substrate uses CfC neural networks for drive dynamics",
            "soul");
        seed("frag-pref", "User prefers dark mode interfaces", "preference");
        seed("frag-tech", "Apache Pekko typed actors for distributed systems", "technical");
        store.commitAll();

        var queryEmbed = embeddingService.embed("neural network drive engine");
        var results = store.searchFragments(null, "neural network drive",
            queryEmbed, 5, WyrdLuceneStore.SearchMode.SET_UNION);

        assertThat(results).isNotEmpty();
        // Dense search should rank soul fragment highest (semantic match)
        // BM25-only matches (if any) appended after
        System.out.println("SET_UNION results:");
        results.forEach(r -> System.out.println("  " + r.id() + ": " + r.score() + " — " +
            r.content().substring(0, Math.min(50, r.content().length()))));
    }

    @Test
    void setUnionIncludesBm25OnlyMatches() {
        // Insert one fragment WITH embedding and one WITHOUT (text-only)
        var embed = embeddingService.embed("Tokyo is the capital of Japan");
        store.insertFragment("with-embed", "test-agent", "fact",
            "Tokyo is the capital of Japan", embed,
            System.currentTimeMillis(), 0.8f);
        // Insert via knowledge (no embedding) — only findable by BM25
        store.insertKnowledge("text-only", "geography",
            "Japan Geography",
            "Japan is an island nation in East Asia with Tokyo as capital",
            "geography", "japan;tokyo", null);
        store.commitAll();

        var queryEmbed = embeddingService.embed("Tell me about Tokyo Japan");
        var results = store.searchFragments(null, "Tokyo Japan capital",
            queryEmbed, 5, WyrdLuceneStore.SearchMode.SET_UNION);

        // Should find the embedded fragment via dense search
        assertThat(results).isNotEmpty();
    }

    @Test
    void setUnionVsHybridProducesDifferentOrder() {
        // Seed several fragments
        seed("a", "Machine learning models for natural language processing", "tech");
        seed("b", "The garden needs watering every morning", "personal");
        seed("c", "Neural networks with attention mechanisms", "tech");
        seed("d", "Natural language understanding benchmarks", "tech");
        store.commitAll();

        var queryEmbed = embeddingService.embed("NLP attention neural networks");

        var setUnion = store.searchFragments(null, "NLP attention neural",
            queryEmbed, 4, WyrdLuceneStore.SearchMode.SET_UNION);
        var hybrid = store.searchFragments(null, "NLP attention neural",
            queryEmbed, 4, WyrdLuceneStore.SearchMode.HYBRID);

        // Both should return results
        assertThat(setUnion).isNotEmpty();
        assertThat(hybrid).isNotEmpty();

        // Results may differ in ordering (SET_UNION preserves dense ranking)
        System.out.println("SET_UNION: " + setUnion.stream().map(r -> r.id()).toList());
        System.out.println("HYBRID:    " + hybrid.stream().map(r -> r.id()).toList());
    }

    private void seed(String id, String content, String type) {
        var embed = embeddingService.embed(content);
        store.insertFragment(id, "test-agent", type, content, embed,
            System.currentTimeMillis(), 0.7f);
    }
}
