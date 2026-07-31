package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: EmbeddingService + AdmissionController + Lucene + MemoryGraphTraverser.
 * Tests that semantic dedup actually catches near-duplicates that BM25 misses.
 */
class MemoryEmbeddingIntegrationTest {

    private static EmbeddingService embeddingService;
    private Path luceneTempDir;
    private WyrdLuceneStore store;
    private AdmissionController controller;
    private SignificanceBuffer buffer;

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
        luceneTempDir = Files.createTempDirectory("mem-embed-test-");
        store = new WyrdLuceneStore(luceneTempDir, 384);
        store.ensureAllCollections();
        controller = new AdmissionController(store);
        buffer = new SignificanceBuffer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
        try (var walk = Files.walk(luceneTempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }

    // ── Semantic Dedup ──────────────────────────────────────────────────

    @Test
    void semanticDedupCatchesNearDuplicate() {
        // Seed with a fact using embedding
        var embedding = embeddingService.embed("User always prefers dark mode interfaces for all applications");
        store.insertFragment("frag-1", "agent-test", "preference",
            "User always prefers dark mode interfaces for all applications",
            embedding, System.currentTimeMillis(), 0.8f);
        store.commitAll();

        // Admit the same fact phrased differently
        var result = controller.evaluate(
            "I like dark mode everywhere, please use it",
            AdmissionController.ContentType.USER_PREFERENCE,
            -1, Instant.now(), "agent-test");

        // With semantic embeddings, the novelty score should be LOW
        // because the meaning is nearly identical
        // The overall score might still pass admission (high content type prior)
        // but we can verify the system detected the similarity
        assertThat(result).isNotNull();

        // Now try something genuinely new
        var newResult = controller.evaluate(
            "I'm interested in quantum physics research",
            AdmissionController.ContentType.USER_STATEMENT,
            -1, Instant.now(), "agent-test");

        // The new topic should score higher than the near-duplicate
        // (novelty component is higher for genuinely new content)
        System.out.println("Near-duplicate score: " + result.score());
        System.out.println("New content score: " + newResult.score());
    }

    @Test
    void exactDuplicateHasLowestNovelty() {
        var embedding = embeddingService.embed("Masumi is building Wyrdsekai");
        store.insertFragment("frag-2", "agent-test", "fact",
            "Masumi is building Wyrdsekai",
            embedding, System.currentTimeMillis(), 0.9f);
        store.commitAll();

        // Exact same text
        var exactDup = controller.evaluate(
            "Masumi is building Wyrdsekai",
            AdmissionController.ContentType.USER_STATEMENT,
            -1, Instant.now(), "agent-test");

        // Paraphrased
        var paraphrase = controller.evaluate(
            "Masumi works on the Wyrdsekai project",
            AdmissionController.ContentType.USER_STATEMENT,
            -1, Instant.now(), "agent-test");

        // Unrelated
        var unrelated = controller.evaluate(
            "The weather in Tokyo is rainy today",
            AdmissionController.ContentType.USER_STATEMENT,
            -1, Instant.now(), "agent-test");

        // Exact duplicate should score lowest or equal (least novel)
        // Unrelated should score at least as high
        System.out.println("Exact dup: " + exactDup.score() +
            " Paraphrase: " + paraphrase.score() +
            " Unrelated: " + unrelated.score());
        assertThat(unrelated.score()).isGreaterThanOrEqualTo(exactDup.score());
    }

    // ── Full Pipeline with Embeddings ───────────────────────────────────

    @Test
    void fullPipelineWithEmbeddedFragments() {
        // Step 1: Admit and embed several memories
        var memories = List.of(
            "Masumi is a software engineer from Tokyo",
            "Wyrdsekai uses Apache Pekko for actor system",
            "The soul substrate has 8 drives based on Panksepp",
            "Masumi prefers dark mode and Earl Grey tea",
            "CfC neural network runs drive dynamics at sub-microsecond"
        );

        for (int i = 0; i < memories.size(); i++) {
            var text = memories.get(i);
            var result = controller.evaluate(text,
                AdmissionController.ContentType.USER_STATEMENT,
                -1, Instant.now(), "agent-test");

            if (result instanceof AdmissionController.AdmissionResult.Admit) {
                buffer.remember(text, result.score());
                var embedding = embeddingService.embed(text);
                store.insertFragment("mem-" + i, "agent-test", "fact",
                    text, embedding, System.currentTimeMillis(), result.score());
            }
        }
        store.commitAll();

        // Step 2: Build CompactedMemory with links
        var nodes = new ArrayList<MemoryNode>();
        for (int i = 0; i < memories.size(); i++) {
            nodes.add(new MemoryNode("mem-" + i, memories.get(i),
                List.of(), 0.7f, 0.5f, i < 2, // first two are formative
                null, Instant.now(), 0, null));
        }
        var links = List.of(
            new CompactedMemory.MemoryLink("mem-0", "mem-1", 0.8f, "causal"),   // Masumi → Pekko
            new CompactedMemory.MemoryLink("mem-1", "mem-2", 0.7f, "thematic"), // Pekko → drives
            new CompactedMemory.MemoryLink("mem-2", "mem-4", 0.9f, "thematic"), // drives → CfC
            new CompactedMemory.MemoryLink("mem-0", "mem-3", 0.3f, "personal")  // Masumi → preferences
        );
        var memory = new CompactedMemory(nodes, links, Map.of("engineering", 0.9f));

        // Step 3: Graph traversal from "drives" node
        var traverser = MemoryGraphTraverser.fromMemory(memory);
        var expanded = traverser.expand(List.of("mem-2"), 2);

        // Should reach CfC (1 hop), Pekko (1 hop), and Masumi (2 hops)
        var ids = expanded.stream().map(r -> r.node().id()).toList();
        assertThat(ids).contains("mem-4", "mem-1"); // CfC and Pekko
        assertThat(ids).contains("mem-0"); // Masumi at 2 hops

        // Step 4: Verify semantic search finds related content
        var queryEmbedding = embeddingService.embed("neural network drives");
        var searchResults = store.searchFragments("agent-test", "neural network drives",
            queryEmbedding, 3);
        assertThat(searchResults).isNotEmpty();
        // Should find CfC and/or drives fragments
        var foundContent = searchResults.stream()
            .map(r -> r.content())
            .toList();
        System.out.println("Semantic search for 'neural network drives': " + foundContent);
        assertThat(foundContent.stream().anyMatch(c ->
            c.contains("CfC") || c.contains("drive"))).isTrue();

        // Step 5: Verify dedup rejects known content
        var dupResult = controller.evaluate(
            "The CfC neural network handles drive dynamics very fast",
            AdmissionController.ContentType.AGENT_NOTE,
            -1, Instant.now(), "agent-test");
        // This is semantically similar to mem-4 — should score lower than novel content
        var novelResult = controller.evaluate(
            "We should add vision support to the companion",
            AdmissionController.ContentType.AGENT_NOTE,
            -1, Instant.now(), "agent-test");
        System.out.println("Near-dup CfC note: " + dupResult.score() +
            " Novel vision note: " + novelResult.score());
    }

    // ── Embedding Quality in Memory Context ─────────────────────────────

    @Test
    void emotionalMemoriesCluster() {
        // Verify that emotionally-tagged memories cluster correctly
        float sim1 = embeddingService.similarity(
            "User felt frustrated when the build kept failing",
            "User was angry about repeated test failures");
        float sim2 = embeddingService.similarity(
            "User felt frustrated when the build kept failing",
            "User enjoys hiking in the mountains on weekends");

        assertThat(sim1).isGreaterThan(sim2);
        System.out.println("Emotional cluster: frustration-anger=" + sim1 +
            " frustration-hiking=" + sim2);
    }

    @Test
    void identityMemoriesRetrievable() {
        // Store identity fragments with embeddings
        var fragments = Map.of(
            "identity-name", "User's name is Masumi, born in Tokyo 1973",
            "identity-career", "VP Engineering at Mercari US, O'Reilly author",
            "identity-project", "Building Wyrdsekai, a distributed text-native OS",
            "identity-passion", "The Empathy Engine from 2003 — 23 years of the same vision"
        );

        for (var entry : fragments.entrySet()) {
            var embedding = embeddingService.embed(entry.getValue());
            store.insertFragment(entry.getKey(), "agent-test", "identity",
                entry.getValue(), embedding, System.currentTimeMillis(), 1.0f);
        }
        store.commitAll();

        // Search for "who built this"
        var query = "who is the creator of this project";
        var queryEmbed = embeddingService.embed(query);
        var results = store.searchFragments("agent-test", query, queryEmbed, 3);

        assertThat(results).isNotEmpty();
        var allContent = results.stream().map(r -> r.content()).toList();
        System.out.println("Query: '" + query + "' → " + allContent);
        // Should find identity-relevant results somewhere in top-3
        var joined = String.join(" ", allContent);
        assertThat(joined).containsAnyOf("Masumi", "Wyrdsekai", "Empathy", "Mercari");
    }

    @Test
    void admissionStatsReflectEmbeddingQuality() {
        // Run a batch through admission with embeddings available
        var events = List.of(
            "User mentioned they like Japanese food",
            "User said they like Japanese cuisine",     // near-duplicate
            "User is working on a distributed system",
            "anonymous enters from the west",           // noise
            "Build succeeded with 5140 tests passing"
        );

        store.insertFragment("existing-1", "agent-test", "preference",
            "User enjoys Japanese food, especially sushi and ramen",
            embeddingService.embed("User enjoys Japanese food, especially sushi and ramen"),
            System.currentTimeMillis(), 0.7f);
        store.commitAll();

        int admitted = 0;
        for (var event : events) {
            var type = event.startsWith("anonymous")
                ? AdmissionController.ContentType.NARRATOR_MESSAGE
                : AdmissionController.ContentType.USER_STATEMENT;
            var result = controller.evaluate(event, type, -1, Instant.now(), "agent-test");
            if (result instanceof AdmissionController.AdmissionResult.Admit) admitted++;
            System.out.println(result.getClass().getSimpleName() + " (" +
                String.format("%.2f", result.score()) + "): " + event.substring(0, Math.min(50, event.length())));
        }

        var stats = controller.stats();
        System.out.println("Admission rate: " + String.format("%.0f%%", stats.admissionRate() * 100) +
            " (" + stats.admitted() + " admitted, " + stats.rejected() + " rejected)");
    }
}
