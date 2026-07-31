package org.wyrdsekai.core.search;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying soul fragment retrieval via WyrdLuceneStore.
 * Validates hybrid retrieval (text + vector via RRF) as described in Experiment 17:
 * MEDIUM resident manifest + semantic retrieval of DEEP fragments.
 * <p>
 * Uses synthetic embeddings with known cosine similarities (dimension 8) so that
 * vector ranking is deterministic without requiring a real embedding model.
 */
@Tag("integration")
class SoulFragmentLuceneTest {

    private static final int DIM = 8;  // small dimension for test speed
    private static final String ALICE = "did:key:alice";
    private static final String BOB = "did:key:bob";

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
    //  Synthetic embedding helpers
    // -----------------------------------------------------------------------

    /**
     * Create a unit vector along axis {@code axis} in 8-dimensional space.
     * axis=0 -> [1,0,0,...], axis=1 -> [0,1,0,...], etc.
     * Known cosine similarity: same axis=1.0, different axis=0.0.
     */
    private static List<Float> basisVector(int axis) {
        var vec = new ArrayList<Float>(DIM);
        for (int i = 0; i < DIM; i++) {
            vec.add(i == axis ? 1.0f : 0.0f);
        }
        return vec;
    }

    /**
     * Create a vector that is a weighted mix of two basis vectors.
     * Useful for testing cosine similarity gradients.
     */
    private static List<Float> mixedVector(int axis1, float w1, int axis2, float w2) {
        var vec = new ArrayList<Float>(DIM);
        float norm = (float) Math.sqrt(w1 * w1 + w2 * w2);
        for (int i = 0; i < DIM; i++) {
            float val = 0;
            if (i == axis1) val += w1;
            if (i == axis2) val += w2;
            vec.add(norm > 0 ? val / norm : 0.0f);
        }
        return vec;
    }

    /**
     * Create a normalized vector with a specific pattern.
     * Used for fragments where we want predictable but non-trivial similarity.
     */
    private static List<Float> patternVector(float... values) {
        float norm = 0;
        for (float v : values) norm += v * v;
        norm = (float) Math.sqrt(norm);
        var vec = new ArrayList<Float>(values.length);
        for (float v : values) {
            vec.add(norm > 0 ? v / norm : 0.0f);
        }
        // Pad to DIM if needed
        while (vec.size() < DIM) vec.add(0.0f);
        return vec;
    }

    // -----------------------------------------------------------------------
    //  Tests
    // -----------------------------------------------------------------------

    @Test
    void insertAndRetrieveFragmentsByText() {
        // Insert fragments with text content, no embeddings
        store.insertFragment("f1", ALICE, "trait",
            "stubbornly loyal and protective of friends, will not back down from a fight",
            null, 1000L, 0.9f);
        store.insertFragment("f2", ALICE, "memory",
            "remembers the day the village burned, the smell of smoke lingers in nightmares",
            null, 1001L, 0.7f);
        store.insertFragment("f3", ALICE, "value",
            "believes justice comes before mercy, but mercy is not weakness",
            null, 1002L, 0.8f);
        store.commitAll();

        // Search by text — should match f1 (loyal, protective, fight)
        var results = store.searchFragments(ALICE, "loyal protective", null, 10,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertFalse(results.isEmpty(), "Should find fragments by text content");
        assertEquals("f1", results.getFirst().id(),
            "Fragment about loyalty should rank first for 'loyal protective'");

        // Search for smoke/nightmares — should match f2
        var smokeResults = store.searchFragments(ALICE, "smoke nightmares", null, 10,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertFalse(smokeResults.isEmpty());
        assertEquals("f2", smokeResults.getFirst().id());

        // Search for justice — should match f3
        var justiceResults = store.searchFragments(ALICE, "justice mercy", null, 10,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertFalse(justiceResults.isEmpty());
        assertEquals("f3", justiceResults.getFirst().id());
    }

    @Test
    void insertAndRetrieveFragmentsByVector() {
        // Insert fragments with orthogonal basis vectors — known cosine distances
        store.insertFragment("f1", ALICE, "trait",
            "courageous warrior", basisVector(0), 1000L, 0.9f);
        store.insertFragment("f2", ALICE, "trait",
            "gentle healer", basisVector(1), 1001L, 0.8f);
        store.insertFragment("f3", ALICE, "trait",
            "clever trickster", basisVector(2), 1002L, 0.7f);
        store.commitAll();

        // Query with basis(0) — should match f1 perfectly (cosine=1.0), others cosine=0.0
        var results = store.searchFragments(ALICE, null, basisVector(0), 3,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertFalse(results.isEmpty(), "Should find fragments by vector similarity");
        assertEquals("f1", results.getFirst().id(),
            "Exact vector match should rank first");

        // Query with basis(1) — should match f2
        var results2 = store.searchFragments(ALICE, null, basisVector(1), 3,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertEquals("f2", results2.getFirst().id());

        // Query with basis(2) — should match f3
        var results3 = store.searchFragments(ALICE, null, basisVector(2), 3,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertEquals("f3", results3.getFirst().id());
    }

    @Test
    void hybridRetrievalCombinesTextAndVector() {
        // f1: text says "warrior", vector along axis 0
        store.insertFragment("f1", ALICE, "trait",
            "fierce warrior who fights with honor",
            basisVector(0), 1000L, 0.9f);

        // f2: text says "scholar", vector along axis 1
        store.insertFragment("f2", ALICE, "trait",
            "quiet scholar who studies ancient texts",
            basisVector(1), 1001L, 0.8f);

        // f3: text says "warrior scholar" (matches both terms), vector along axis 2 (unrelated)
        store.insertFragment("f3", ALICE, "trait",
            "warrior scholar who bridges combat and knowledge",
            basisVector(2), 1002L, 0.85f);
        store.commitAll();

        // Hybrid query: text="warrior", vector=basis(0)
        // Text matches: f1 (warrior), f3 (warrior scholar) — f1 should rank higher (stronger text match)
        // Vector matches: f1 (cosine=1.0 with basis(0))
        // RRF should rank f1 highest (both signals agree)
        var hybridResults = store.searchFragments(ALICE, "warrior", basisVector(0), 3,
            WyrdLuceneStore.SearchMode.HYBRID);
        assertFalse(hybridResults.isEmpty());
        assertEquals("f1", hybridResults.getFirst().id(),
            "Hybrid should rank f1 first (both text and vector agree)");

        // Compare with text-only — f1 and f3 both match "warrior"
        var textOnly = store.searchFragments(ALICE, "warrior", null, 3,
            WyrdLuceneStore.SearchMode.TEXT_ONLY);
        assertTrue(textOnly.size() >= 2, "Text-only should find at least 2 warrior matches");

        // Compare with vector-only — only f1 matches basis(0)
        var vectorOnly = store.searchFragments(ALICE, null, basisVector(0), 3,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertEquals("f1", vectorOnly.getFirst().id());

        // Hybrid should return at least as many unique results as vector-only
        // (RRF union of both result sets)
        assertTrue(hybridResults.size() >= vectorOnly.size(),
            "Hybrid should return at least as many results as vector-only");
    }

    @Test
    void fragmentsFilteredByAgentDid() {
        // Insert fragments for two different agents with overlapping content
        store.insertFragment("alice-1", ALICE, "trait",
            "brave and adventurous explorer of unknown lands",
            basisVector(0), 1000L, 0.9f);
        store.insertFragment("alice-2", ALICE, "memory",
            "explored the crystal caverns beneath the mountain",
            basisVector(1), 1001L, 0.7f);

        store.insertFragment("bob-1", BOB, "trait",
            "brave and adventurous explorer of the high seas",
            basisVector(0), 1000L, 0.9f);
        store.insertFragment("bob-2", BOB, "memory",
            "explored the sunken ruins of an ancient city",
            basisVector(1), 1001L, 0.7f);
        store.commitAll();

        // Search for Alice — should only return Alice's fragments
        var aliceResults = store.searchFragments(ALICE, "brave explorer", null, 10);
        assertFalse(aliceResults.isEmpty());
        for (var r : aliceResults) {
            var agentDid = r.metadata().get("agent_did");
            assertEquals(ALICE, agentDid,
                "All results should belong to Alice, got: " + agentDid);
        }

        // Verify Alice doesn't see Bob's fragments
        var aliceIds = aliceResults.stream().map(WyrdLuceneStore.SearchResult::id).toList();
        assertFalse(aliceIds.contains("bob-1"), "Alice should not see Bob's fragments");
        assertFalse(aliceIds.contains("bob-2"), "Alice should not see Bob's fragments");

        // Search for Bob — should only return Bob's fragments
        var bobResults = store.searchFragments(BOB, "brave explorer", null, 10);
        assertFalse(bobResults.isEmpty());
        for (var r : bobResults) {
            assertEquals(BOB, r.metadata().get("agent_did"),
                "All results should belong to Bob");
        }

        // Vector search also respects agent filter
        var aliceVec = store.searchFragments(ALICE, null, basisVector(0), 10,
            WyrdLuceneStore.SearchMode.DENSE_ONLY);
        assertFalse(aliceVec.isEmpty(), "Vector search for Alice should return results");
        // All returned results should belong to Alice
        for (var r : aliceVec) {
            assertEquals(ALICE, r.metadata().get("agent_did"),
                "Vector results should be filtered to Alice");
        }
        // Alice's basis(0) fragment should be first
        assertEquals("alice-1", aliceVec.getFirst().id());
    }

    @Test
    void top3RetrievalMatchesExpectedOrder() {
        // Simulate Experiment 17: 9 fragments from SoulExtractor.fragmentDeep(),
        // retrieve top-3 for a given query. Use synthetic embeddings with known
        // similarity gradients so ordering is deterministic.
        //
        // Query: about courage and loyalty
        // Fragment embeddings designed so that courage-related fragments have highest
        // cosine similarity to the query vector.

        // Query vector: strong on axis 0 (courage), weak on axis 1 (loyalty)
        var queryVec = mixedVector(0, 0.9f, 1, 0.4f);

        // f1: courage (very close to query — strong axis 0)
        store.insertFragment("f1", ALICE, "trait",
            "boundless courage in the face of danger",
            mixedVector(0, 0.95f, 1, 0.3f), 1000L, 0.9f);

        // f2: loyalty (moderate match — strong axis 1)
        store.insertFragment("f2", ALICE, "trait",
            "unwavering loyalty to those who have earned trust",
            mixedVector(1, 0.9f, 0, 0.4f), 1001L, 0.85f);

        // f3: courage + loyalty (good match — balanced)
        store.insertFragment("f3", ALICE, "value",
            "courage and loyalty are the twin pillars of a true warrior",
            mixedVector(0, 0.7f, 1, 0.7f), 1002L, 0.88f);

        // f4: wisdom (low match — axis 2)
        store.insertFragment("f4", ALICE, "trait",
            "ancient wisdom gathered through centuries of observation",
            basisVector(2), 1003L, 0.7f);

        // f5: humor (low match — axis 3)
        store.insertFragment("f5", ALICE, "trait",
            "dry humor and wit that lightens the darkest moments",
            basisVector(3), 1004L, 0.6f);

        // f6: stubbornness (low match — axis 4)
        store.insertFragment("f6", ALICE, "trait",
            "stubborn determination that borders on obsession",
            basisVector(4), 1005L, 0.75f);

        // f7: fear (moderate match — weak axis 0 component)
        store.insertFragment("f7", ALICE, "memory",
            "the terror of the night assault, when courage nearly failed",
            mixedVector(0, 0.3f, 5, 0.9f), 1006L, 0.5f);

        // f8: compassion (low match — axis 6)
        store.insertFragment("f8", ALICE, "value",
            "compassion for the weak drives all meaningful action",
            basisVector(6), 1007L, 0.65f);

        // f9: anger (low match — axis 7)
        store.insertFragment("f9", ALICE, "memory",
            "burning anger when injustice goes unanswered",
            basisVector(7), 1008L, 0.55f);

        store.commitAll();

        // Retrieve top-3 by vector similarity (simulating hybrid retrieval with strong vector signal)
        var top3 = store.searchFragments(ALICE, "courage loyalty warrior", queryVec, 3,
            WyrdLuceneStore.SearchMode.HYBRID);

        assertEquals(3, top3.size(), "Should retrieve exactly 3 fragments");

        // Extract the top-3 IDs
        var topIds = top3.stream().map(WyrdLuceneStore.SearchResult::id).toList();

        // f1 should be in top-3 (best vector match + strong text match on "courage")
        assertTrue(topIds.contains("f1"),
            "f1 (courage) should be in top-3, got: " + topIds);

        // At least one of f2, f3 should be in top-3 (both have "loyalty" text match or
        // "courage" and "warrior" text match, plus non-zero vector overlap with query)
        boolean hasLoyaltyOrCourageLoyalty = topIds.contains("f2") || topIds.contains("f3");
        assertTrue(hasLoyaltyOrCourageLoyalty,
            "f2 (loyalty) or f3 (courage+loyalty+warrior) should be in top-3, got: " + topIds);

        // Now verify all 9 results via a wider retrieval to confirm ranking quality
        var all9 = store.searchFragments(ALICE, "courage loyalty warrior", queryVec, 9,
            WyrdLuceneStore.SearchMode.HYBRID);
        assertEquals(9, all9.size(), "Should retrieve all 9 fragments");
        var allIds = all9.stream().map(WyrdLuceneStore.SearchResult::id).toList();

        // f1 should rank in top-3 of the full retrieval (strong on both signals)
        int f1Rank = allIds.indexOf("f1");
        assertTrue(f1Rank < 3,
            "f1 should be ranked in top-3 of full retrieval, was at position " + f1Rank);

        // Low-relevance fragments (wisdom, humor) should rank lower than courage/loyalty
        int f4Rank = allIds.indexOf("f4"); // wisdom — no text match, orthogonal vector
        int f5Rank = allIds.indexOf("f5"); // humor — no text match, orthogonal vector
        assertTrue(f4Rank > f1Rank,
            "wisdom (f4) should rank lower than courage (f1)");
        assertTrue(f5Rank > f1Rank,
            "humor (f5) should rank lower than courage (f1)");
    }
}
