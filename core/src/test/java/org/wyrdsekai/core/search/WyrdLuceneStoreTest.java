package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.library.CapabilityRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WyrdLuceneStore: all five collections, three search modes,
 * upsert semantics, metadata filtering, graceful degradation, and deletion.
 */
class WyrdLuceneStoreTest {

    private static final int DIM = 384; // all-minilm embedding dimension

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

    // -----------------------------------------------------------------------
    //  Soul Fragments
    // -----------------------------------------------------------------------

    @Test
    void fragmentInsertAndTextSearch() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "stubborn but kind hearted warrior", null, 1000L, 0.8f);
        store.insertFragment("f2", "did:key:abc", "memory",
            "remembers the battle of the northern gate", null, 1001L, 0.5f);
        store.commitAll();

        var results = store.searchFragments("did:key:abc", "warrior", null, 10);
        assertFalse(results.isEmpty(), "Should find fragment by text");
        assertEquals("f1", results.getFirst().id());
    }

    @Test
    void fragmentDenseVectorSearch() {
        var emb1 = dummyEmbedding(DIM, 1.0f);
        var emb2 = dummyEmbedding(DIM, -1.0f);

        store.insertFragment("f1", "did:key:abc", "trait",
            "brave warrior", emb1, 1000L, 0.9f);
        store.insertFragment("f2", "did:key:abc", "trait",
            "timid scholar", emb2, 1001L, 0.3f);
        store.commitAll();

        // Search with embedding close to emb1
        var queryEmb = dummyEmbedding(DIM, 0.9f);
        var results = store.searchFragments("did:key:abc", null, queryEmb, 10,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertFalse(results.isEmpty(), "Should find by vector similarity");
        assertEquals("f1", results.getFirst().id(), "Closest vector should rank first");
    }

    @Test
    void fragmentHybridSearch() {
        var emb = dummyEmbedding(DIM, 1.0f);
        store.insertFragment("f1", "did:key:abc", "trait",
            "courageous fighter with a heart of gold", emb, 1000L, 0.9f);
        store.insertFragment("f2", "did:key:abc", "memory",
            "once fought a dragon in the marketplace", dummyEmbedding(DIM, -0.5f), 1001L, 0.4f);
        store.commitAll();

        // Hybrid: text matches f2 (dragon), vector matches f1 — RRF should combine
        var queryEmb = dummyEmbedding(DIM, 0.95f);
        var results = store.searchFragments("did:key:abc", "fighter gold", queryEmb, 10);
        assertFalse(results.isEmpty());
        // Both text and vector favor f1
        assertEquals("f1", results.getFirst().id());
    }

    @Test
    void fragmentFilterByAgent() {
        store.insertFragment("f1", "did:key:alice", "trait",
            "a gentle healer from the forest", null, 1000L, 0.7f);
        store.insertFragment("f2", "did:key:bob", "trait",
            "a fierce warrior from the mountains", null, 1001L, 0.8f);
        store.commitAll();

        var aliceResults = store.searchFragments("did:key:alice", "healer", null, 10);
        assertEquals(1, aliceResults.size());
        assertEquals("f1", aliceResults.getFirst().id());

        // Bob's fragments should not appear when filtering by Alice
        var aliceSearch = store.searchFragments("did:key:alice", "warrior", null, 10);
        assertTrue(aliceSearch.isEmpty(), "Should not find Bob's fragments when filtering by Alice");
    }

    @Test
    void fragmentDeleteByAgent() {
        store.insertFragment("f1", "did:key:alice", "trait", "kind", null, 1000L, 0.5f);
        store.insertFragment("f2", "did:key:alice", "memory", "old memory", null, 1001L, 0.3f);
        store.insertFragment("f3", "did:key:bob", "trait", "brave", null, 1002L, 0.6f);
        store.commitAll();

        assertEquals(3, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
        long deleted = store.deleteFragmentsByAgent("did:key:alice");
        assertEquals(2, deleted);
        assertEquals(1, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
    }

    // -----------------------------------------------------------------------
    //  Library (FTS5 replacement)
    // -----------------------------------------------------------------------

    @Test
    void libraryInsertAndSearch() {
        store.insertCapability("cap1", "file-reader", "MCP",
            "Read files from the local filesystem with path expansion", "io,filesystem", 0.9f);
        store.insertCapability("cap2", "web-search", "MCP",
            "Search the web using a search engine API", "web,search", 0.7f);
        store.commitAll();

        var results = store.searchCapabilities("filesystem", 10);
        assertFalse(results.isEmpty());
        assertEquals("cap1", results.getFirst().id());
    }

    @Test
    void librarySearchReturnsMetadata() {
        store.insertCapability("cap1", "email-sender", "ROOM_SCRIPT",
            "Send email messages via SMTP", "email,communication", 0.85f);
        store.commitAll();

        var results = store.searchCapabilities("email", 10);
        assertEquals(1, results.size());
        var meta = results.getFirst().metadata();
        assertEquals("email-sender", meta.get("name"));
        assertEquals("ROOM_SCRIPT", meta.get("protocol"));
    }

    // -----------------------------------------------------------------------
    //  Memory Items
    // -----------------------------------------------------------------------

    @Test
    void memoryItemInsertAndSearch() {
        store.insertMemoryItem("m1", "did:key:alice", "journal",
            "Today I met a mysterious traveler at the crossroads",
            null, System.currentTimeMillis(), "room:crossroads");
        store.insertMemoryItem("m2", "did:key:alice", "item",
            "A rusty key found near the old well",
            null, System.currentTimeMillis(), "room:village");
        store.commitAll();

        var results = store.searchMemory("did:key:alice", "traveler crossroads", null, 10);
        assertFalse(results.isEmpty());
        assertEquals("m1", results.getFirst().id());
    }

    @Test
    void memoryItemVectorSearch() {
        var emb1 = dummyEmbedding(DIM, 0.5f);
        var emb2 = dummyEmbedding(DIM, -0.5f);

        store.insertMemoryItem("m1", "did:key:alice", "journal",
            "joyful morning in the garden", emb1, 1000L, "room:garden");
        store.insertMemoryItem("m2", "did:key:alice", "journal",
            "sorrowful evening by the river", emb2, 1001L, "room:river");
        store.commitAll();

        var queryEmb = dummyEmbedding(DIM, 0.4f);
        var results = store.searchMemory("did:key:alice", null, queryEmb, 10,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertFalse(results.isEmpty());
        assertEquals("m1", results.getFirst().id());
    }

    @Test
    void memoryDeleteByRoom() {
        store.insertMemoryItem("m1", "did:key:alice", "item", "sword", null, 1000L, "room:armory");
        store.insertMemoryItem("m2", "did:key:alice", "item", "shield", null, 1001L, "room:armory");
        store.insertMemoryItem("m3", "did:key:alice", "item", "potion", null, 1002L, "room:shop");
        store.commitAll();

        long deleted = store.deleteMemoryByRoom("did:key:alice", "room:armory");
        assertEquals(2, deleted);
        assertEquals(1, store.totalCount(SearchCollections.MEMORY_ITEMS));
    }

    // -----------------------------------------------------------------------
    //  Room Content
    // -----------------------------------------------------------------------

    @Test
    void roomContentInsertAndSearch() {
        store.insertRoomContent("r1", "room:tavern", "village", "The Rusty Mug",
            "A warm tavern with a crackling fireplace. The barkeeper polishes mugs behind the counter.",
            "barkeeper fireplace mug");
        store.insertRoomContent("r2", "room:market", "village", "Village Market",
            "An open-air market bustling with merchants selling fresh produce and trinkets.",
            "merchant produce trinkets");
        store.commitAll();

        var results = store.searchRooms("tavern fireplace", 10);
        assertFalse(results.isEmpty());
        assertEquals("r1", results.getFirst().id());
    }

    @Test
    void roomContentFilterByZone() {
        store.insertRoomContent("r1", "room:tavern", "village", "Tavern",
            "A cozy village tavern", "beer");
        store.insertRoomContent("r2", "room:throne", "castle", "Throne Room",
            "A grand throne room", "throne");
        store.commitAll();

        var villageResults = store.searchRoomsByZone("tavern", "village", 10);
        assertEquals(1, villageResults.size());

        var castleResults = store.searchRoomsByZone("tavern", "castle", 10);
        assertTrue(castleResults.isEmpty(), "Should not find village rooms when filtering by castle");
    }

    @Test
    void roomContentDeleteByRoom() {
        store.insertRoomContent("r1", "room:tavern", "village", "Tavern", "tavern desc", "beer");
        store.insertRoomContent("r2", "room:market", "village", "Market", "market desc", "apples");
        store.commitAll();

        assertEquals(2, store.totalCount(SearchCollections.ROOM_CONTENT));
        store.deleteRoomContent("room:tavern");
        assertEquals(1, store.totalCount(SearchCollections.ROOM_CONTENT));
    }

    // -----------------------------------------------------------------------
    //  World DNA
    // -----------------------------------------------------------------------

    @Test
    void worldDnaInsertAndTextSearch() {
        store.insertWorldDna("d1", "room:tavern", "interaction_style",
            "Agents in this room tend to be informal and use humor", null, 0.85f);
        store.insertWorldDna("d2", "room:temple", "interaction_style",
            "Reverent and formal speech patterns dominate", null, 0.9f);
        store.commitAll();

        var results = store.searchWorldDna("informal humor", null, 10,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertFalse(results.isEmpty());
        assertEquals("d1", results.getFirst().id());
    }

    @Test
    void worldDnaHybridSearch() {
        var emb1 = dummyEmbedding(DIM, 0.7f);
        var emb2 = dummyEmbedding(DIM, -0.7f);

        store.insertWorldDna("d1", "room:library", "topic_focus",
            "scholarly discussion about ancient history", emb1, 0.8f);
        store.insertWorldDna("d2", "room:arena", "topic_focus",
            "combat tactics and weapon selection", emb2, 0.75f);
        store.commitAll();

        var results = store.searchWorldDna("history", dummyEmbedding(DIM, 0.65f), 10);
        assertFalse(results.isEmpty());
        assertEquals("d1", results.getFirst().id());
    }

    // -----------------------------------------------------------------------
    //  Upsert semantics
    // -----------------------------------------------------------------------

    @Test
    void upsertReplacesExistingDocument() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "original description", null, 1000L, 0.5f);
        store.commitAll();

        var results1 = store.searchFragments("did:key:abc", "original", null, 10);
        assertEquals(1, results1.size());

        // Upsert with same ID but different content
        store.insertFragment("f1", "did:key:abc", "trait",
            "updated completely different description", null, 2000L, 0.9f);
        store.commitAll();

        // Old content should not be findable
        var resultsOld = store.searchFragments("did:key:abc", "original", null, 10);
        assertTrue(resultsOld.isEmpty(), "Old content should be gone after upsert");

        // New content should be findable
        var resultsNew = store.searchFragments("did:key:abc", "updated", null, 10);
        assertEquals(1, resultsNew.size());
        assertEquals("f1", resultsNew.getFirst().id());

        // Should still be exactly 1 document
        assertEquals(1, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
    }

    // -----------------------------------------------------------------------
    //  Cross-collection isolation
    // -----------------------------------------------------------------------

    @Test
    void collectionsDoNotInterfere() {
        store.insertFragment("id1", "did:key:abc", "trait",
            "a brave warrior", null, 1000L, 0.8f);
        store.insertCapability("id1", "brave-tool", "MCP",
            "a brave tool for warriors", "brave", 0.5f);
        store.insertMemoryItem("id1", "did:key:abc", "item",
            "a brave sword", null, 1000L, "room:armory");
        store.commitAll();

        // Each collection should have exactly 1 doc
        assertEquals(1, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
        assertEquals(1, store.totalCount(SearchCollections.LIBRARY));
        assertEquals(1, store.totalCount(SearchCollections.MEMORY_ITEMS));
        assertEquals(0, store.totalCount(SearchCollections.ROOM_CONTENT));
        assertEquals(0, store.totalCount(SearchCollections.WORLD_DNA));

        // Fragment search should not return library results
        var fragmentResults = store.searchFragments("did:key:abc", "brave", null, 10);
        assertEquals(1, fragmentResults.size());
    }

    // -----------------------------------------------------------------------
    //  Graceful degradation
    // -----------------------------------------------------------------------

    @Test
    void nullEmbeddingGracefullyDegradesToTextOnly() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "a thoughtful philosopher", null, 1000L, 0.7f);
        store.commitAll();

        // Request hybrid search with null embedding — should fall back to text-only
        var results = store.searchFragments("did:key:abc", "philosopher", null, 10,
            WyrdLuceneStore.SearchMode.HYBRID);
        assertFalse(results.isEmpty());
        assertEquals("f1", results.getFirst().id());
    }

    @Test
    void emptyEmbeddingGracefullyDegradesToTextOnly() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "a wise sage from the mountains", null, 1000L, 0.6f);
        store.commitAll();

        var results = store.searchFragments("did:key:abc", "sage mountains", List.of(), 10);
        assertFalse(results.isEmpty());
    }

    @Test
    void denseOnlyWithNoEmbeddingReturnsEmpty() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "a fierce dragon slayer", null, 1000L, 0.9f);
        store.commitAll();

        var results = store.searchFragments("did:key:abc", null, null, 10,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertTrue(results.isEmpty());
    }

    @Test
    void textOnlyWithBlankQueryReturnsEmpty() {
        store.insertFragment("f1", "did:key:abc", "trait",
            "something searchable", dummyEmbedding(DIM, 1.0f), 1000L, 0.5f);
        store.commitAll();

        var results = store.searchFragments("did:key:abc", "", null, 10,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertTrue(results.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  LuceneLibraryAdapter
    // -----------------------------------------------------------------------

    @Test
    void luceneLibraryAdapterSearch() {
        var adapter = new LuceneLibraryAdapter(store);

        // Index a capability record
        var record = new CapabilityRecord(
            "cap1", "json-parser", "1.0.0",
            "Parse JSON documents from room script output",
            null, List.of("json", "parsing"),
            null, CapabilityRecord.CapabilityProtocol.ROOM_SCRIPT,
            0.8f, null, null, null, null,
            "system", null, 0, false, null, null, null
        );
        adapter.index(record);
        store.commitAll();

        var results = adapter.search("json parse", 10);
        assertFalse(results.isEmpty());
        assertEquals("cap1", results.getFirst().id());
        assertEquals("json-parser", results.getFirst().name());
    }

    @Test
    void luceneLibraryAdapterBulkIndex() {
        var adapter = new LuceneLibraryAdapter(store);
        var records = new ArrayList<CapabilityRecord>();
        for (int i = 0; i < 5; i++) {
            records.add(new CapabilityRecord(
                "cap" + i, "tool-" + i, "1.0.0", "Description for tool " + i,
                null, List.of("test"), null, null,
                0.5f, null, null, null, null,
                "system", null, 0, false, null, null, null
            ));
        }
        int indexed = adapter.bulkIndex(records);
        assertEquals(5, indexed);
        assertEquals(5, store.totalCount(SearchCollections.LIBRARY));
    }

    // -----------------------------------------------------------------------
    //  Multiple results ranking
    // -----------------------------------------------------------------------

    @Test
    void searchReturnsResultsRankedByRelevance() {
        store.insertCapability("c1", "file-reader", "MCP",
            "Read files from disk using file path expansion", "io", 0.9f);
        store.insertCapability("c2", "file-writer", "MCP",
            "Write files to disk using file output streams", "io", 0.8f);
        store.insertCapability("c3", "database-query", "MCP",
            "Query databases with SQL", "database", 0.7f);
        store.commitAll();

        // StandardAnalyzer tokenizes "files" and "file" separately — search for "files"
        var results = store.searchCapabilities("files disk", 10);
        assertTrue(results.size() >= 2, "Should find at least 2 file-related capabilities");
        // Both file-reader and file-writer should appear before database-query
        var ids = results.stream().map(WyrdLuceneStore.SearchResult::id).toList();
        assertTrue(ids.contains("c1"));
        assertTrue(ids.contains("c2"));
    }

    // -----------------------------------------------------------------------
    //  Store lifecycle
    // -----------------------------------------------------------------------

    @Test
    void totalCountAccurateAcrossOperations() {
        assertEquals(0, store.totalCount(SearchCollections.SOUL_FRAGMENTS));

        store.insertFragment("f1", "did:key:abc", "trait", "one", null, 1000L, 0.5f);
        store.insertFragment("f2", "did:key:abc", "trait", "two", null, 1001L, 0.5f);
        store.insertFragment("f3", "did:key:abc", "trait", "three", null, 1002L, 0.5f);
        store.commitAll();

        assertEquals(3, store.totalCount(SearchCollections.SOUL_FRAGMENTS));

        store.deleteFragmentsByAgent("did:key:abc");
        assertEquals(0, store.totalCount(SearchCollections.SOUL_FRAGMENTS));
    }

    @Test
    void storeCanBeReopenedAfterClose() throws Exception {
        store.insertCapability("cap1", "test-tool", "MCP",
            "A test tool for verification", "test", 0.5f);
        store.commitAll();
        store.close();

        // Reopen on same directory
        var store2 = new WyrdLuceneStore(tempDir, DIM);
        store2.ensureAllCollections();

        var results = store2.searchCapabilities("test tool", 10);
        assertFalse(results.isEmpty(), "Data should persist across close/reopen");
        assertEquals("cap1", results.getFirst().id());

        store2.close();
        // Reassign so tearDown doesn't double-close
        store = new WyrdLuceneStore(tempDir, DIM);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /** Create a dummy embedding vector of the given dimension and fill value. */
    private static List<Float> dummyEmbedding(int dim, float fillValue) {
        var list = new ArrayList<Float>(dim);
        // Use a deterministic but varied pattern so cosine similarity works
        float norm = 0;
        float[] raw = new float[dim];
        for (int i = 0; i < dim; i++) {
            raw[i] = fillValue * (float) Math.sin(i * 0.1) + fillValue;
            norm += raw[i] * raw[i];
        }
        // Normalize for cosine similarity
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dim; i++) {
            list.add(norm > 0 ? raw[i] / norm : 0);
        }
        return list;
    }

    // -----------------------------------------------------------------------
    //  Knowledge pack roster counts
    // -----------------------------------------------------------------------

    /**
     * Regression: listKnowledgePacks() must report each pack's true chunk count,
     * NOT count×(number of segments it spans). The old code ran a whole-index
     * count once per segment containing the pack term and summed them, so a pack
     * spread over N segments was reported at N× its size (e.g. a freshly
     * re-installed pack with many unmerged segments). Here packA's docs are
     * written across three separate commits → multiple segments → the old code
     * would have reported 9 instead of 3.
     */
    @Test
    void listKnowledgePacks_counts_each_pack_once_across_segments() {
        store.insertKnowledge("packA:0", "packA", "t", "alpha content one", "src", null, null);
        store.commitAll();
        store.insertKnowledge("packA:1", "packA", "t", "alpha content two", "src", null, null);
        store.commitAll();
        store.insertKnowledge("packA:2", "packA", "t", "alpha content three", "src", null, null);
        store.insertKnowledge("packB:0", "packB", "t", "beta content one", "src", null, null);
        store.insertKnowledge("packB:1", "packB", "t", "beta content two", "src", null, null);
        store.commitAll();

        var packs = store.listKnowledgePacks();
        assertEquals(3L, packs.get("packA"), "packA counted once, not per-segment");
        assertEquals(2L, packs.get("packB"));
    }
}
