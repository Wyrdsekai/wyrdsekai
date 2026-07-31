package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the knowledge pack system:
 * - Pack format parsing
 * - Chunk indexing into Lucene
 * - Knowledge search (text + hybrid)
 * - Pack management (install, remove, stats)
 */
class KnowledgePackTest {

    private static Path tempDir;
    private static WyrdLuceneStore store;
    private static KnowledgePackIndexer indexer;

    @BeforeAll
    static void setUp() throws Exception {
        tempDir = Files.createTempDirectory("knowledge-test-");
        store = new WyrdLuceneStore(tempDir.resolve("search"), 384);
        store.ensureAllCollections();
        indexer = new KnowledgePackIndexer(store);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (store != null) store.close();
    }

    @Test
    void pack_metadata_parses_correctly() throws Exception {
        var packDir = createTestPack("test-pack", "Test Pack", 3);
        var mapper = new ObjectMapper();
        var pack = mapper.readValue(packDir.resolve("pack.json").toFile(), KnowledgePack.class);

        assertEquals("test-pack", pack.name());
        assertEquals("Test Pack", pack.title());
        assertEquals("CC-BY-SA-4.0", pack.rights());
        assertEquals("general", pack.contentRating());
        assertEquals(KnowledgePack.CopyrightLevel.GREEN, pack.copyrightLevel());
        assertEquals(KnowledgePack.ContentRating.GENERAL, pack.rating());
    }

    @Test
    void index_pack_from_directory() throws Exception {
        var packDir = createTestPack("wiki-test", "Wiki Test", 5);
        var result = indexer.indexPack(packDir);

        assertEquals("wiki-test", result.packName());
        assertEquals(5, result.chunksIndexed());
        assertEquals(0, result.errors());
        assertTrue(result.success());
        assertTrue(result.elapsedMs() >= 0);
    }

    @Test
    void search_knowledge_text() throws Exception {
        var packDir = createTestPack("search-test", "Search Test", 0);
        // Add specific content for searching
        var chunksDir = packDir.resolve("chunks");
        Files.writeString(chunksDir.resolve("data.jsonl"),
            """
            {"id":"s1","title":"Sourdough Bread","content":"Sourdough is bread made by fermenting dough using wild lactobacillaceae and yeast. It has a distinctive tangy flavor.","source":"Wikipedia"}
            {"id":"s2","title":"French Baguette","content":"A baguette is a long thin loaf of French bread with a crispy crust. It is made from basic lean dough.","source":"Wikipedia"}
            {"id":"s3","title":"Quantum Computing","content":"Quantum computing uses quantum mechanical phenomena such as superposition and entanglement to perform computation.","source":"Wikipedia"}
            """);

        indexer.indexPack(packDir);

        // Text search for bread
        var breadResults = store.searchKnowledgeText("sourdough bread fermentation", 5);
        assertFalse(breadResults.isEmpty(), "Should find bread-related results");
        assertTrue(breadResults.getFirst().content().contains("Sourdough")
            || breadResults.getFirst().content().contains("sourdough"),
            "Top result should be about sourdough");

        // Text search for quantum
        var quantumResults = store.searchKnowledgeText("quantum superposition", 5);
        assertFalse(quantumResults.isEmpty());
        assertTrue(quantumResults.getFirst().content().contains("quantum"));
    }

    @Test
    void search_knowledge_by_pack() throws Exception {
        // Index two packs
        var pack1 = createTestPackWithContent("pack-a", "Pack A",
            List.of(KnowledgeChunk.text("a:1", "pack-a", "Cats", "Cats are domesticated felines.", "Wikipedia")));
        var pack2 = createTestPackWithContent("pack-b", "Pack B",
            List.of(KnowledgeChunk.text("b:1", "pack-b", "Dogs", "Dogs are domesticated canines.", "Wikipedia")));

        indexer.indexPack(pack1);
        indexer.indexPack(pack2);

        // Search only pack-a
        var results = store.searchKnowledgeByPack("domesticated", null, "pack-a", 5);
        assertTrue(results.stream().allMatch(r -> {
            var meta = r.metadata();
            return meta != null && "pack-a".equals(meta.get("pack"));
        }), "All results should be from pack-a");
    }

    @Test
    void remove_pack_deletes_chunks() throws Exception {
        var packDir = createTestPack("removable", "Removable Pack", 10);
        indexer.indexPack(packDir);

        assertEquals(10, indexer.packSize("removable"));

        long deleted = indexer.removePack("removable");
        assertEquals(10, deleted);
        assertEquals(0, indexer.packSize("removable"));
    }

    @Test
    void total_size_across_packs() throws Exception {
        long before = indexer.totalSize();

        var pack = createTestPack("size-test", "Size Test", 7);
        indexer.indexPack(pack);

        assertTrue(indexer.totalSize() >= before + 7);
    }

    @Test
    void index_chunks_programmatically() {
        var chunks = List.of(
            KnowledgeChunk.text("prog:1", "prog-test", "Alpha", "Alpha content here", "test"),
            KnowledgeChunk.text("prog:2", "prog-test", "Beta", "Beta content here", "test"),
            KnowledgeChunk.text("prog:3", "prog-test", "Gamma", "Gamma content here", "test")
        );

        var result = indexer.indexChunks("prog-test", chunks, null);

        assertEquals(3, result.chunksIndexed());
        assertEquals(0, result.errors());
        assertEquals(3, indexer.packSize("prog-test"));
    }

    @Test
    void copyright_levels() {
        assertEquals(KnowledgePack.CopyrightLevel.GREEN,
            KnowledgePack.CopyrightLevel.fromString("public-domain"));
        assertEquals(KnowledgePack.CopyrightLevel.GREEN,
            KnowledgePack.CopyrightLevel.fromString("cc-by-sa"));
        assertEquals(KnowledgePack.CopyrightLevel.YELLOW,
            KnowledgePack.CopyrightLevel.fromString("fair-use"));
        assertEquals(KnowledgePack.CopyrightLevel.YELLOW,
            KnowledgePack.CopyrightLevel.fromString("non-commercial"));
        assertEquals(KnowledgePack.CopyrightLevel.RED,
            KnowledgePack.CopyrightLevel.fromString("copyrighted"));
        assertEquals(KnowledgePack.CopyrightLevel.RED,
            KnowledgePack.CopyrightLevel.fromString(null));
    }

    @Test
    void content_rating_enforcement() {
        var general = KnowledgePack.ContentRating.GENERAL;
        var mature = KnowledgePack.ContentRating.MATURE;
        var explicit = KnowledgePack.ContentRating.EXPLICIT;

        assertTrue(general.allowedBy(mature));
        assertTrue(mature.allowedBy(mature));
        assertFalse(explicit.allowedBy(mature));
        assertTrue(explicit.allowedBy(explicit));
    }

    // --- Helpers ---

    private Path createTestPack(String name, String title, int chunkCount) throws IOException {
        var packDir = tempDir.resolve("packs/" + name);
        Files.createDirectories(packDir.resolve("chunks"));

        // Write pack.json
        Files.writeString(packDir.resolve("pack.json"), """
            {
              "name": "%s",
              "title": "%s",
              "creator": "Test",
              "subject": ["Testing"],
              "description": "Test pack with %d chunks",
              "publisher": "did:key:test",
              "date": "2026-03-25",
              "language": "en",
              "rights": "CC-BY-SA-4.0",
              "copyright": "cc-by-sa",
              "contentRating": "general",
              "version": "1.0",
              "size": {"download": "1 MB", "indexed": "2 MB"},
              "chunks": {"count": %d, "avgTokens": 100},
              "collections": ["knowledge"],
              "source": "test"
            }
            """.formatted(name, title, chunkCount, chunkCount));

        // Write chunk data
        if (chunkCount > 0) {
            var sb = new StringBuilder();
            for (int i = 0; i < chunkCount; i++) {
                sb.append("{\"id\":\"%s:%d\",\"title\":\"Article %d\",\"content\":\"Content for article %d about topic %d. This is test knowledge.\",\"source\":\"Test Source\"}\n"
                    .formatted(name, i, i, i, i));
            }
            Files.writeString(packDir.resolve("chunks/data.jsonl"), sb.toString());
        }

        return packDir;
    }

    private Path createTestPackWithContent(String name, String title, List<KnowledgeChunk> chunks) throws IOException {
        var packDir = createTestPack(name, title, 0);
        var mapper = new ObjectMapper();

        var sb = new StringBuilder();
        for (var chunk : chunks) {
            sb.append(mapper.writeValueAsString(chunk)).append("\n");
        }
        Files.writeString(packDir.resolve("chunks/data.jsonl"), sb.toString());

        return packDir;
    }
}
