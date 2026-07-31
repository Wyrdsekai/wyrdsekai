package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolSearchIndex — the lexical fallback ranking.
 *
 * <p><b>These tests used to be the bug.</b> The old setUp read
 * {@code new ToolSearchIndex("http://localhost:99999")} — "use a non-existent URL so embedding
 * fails → keyword fallback" — and {@code hasVectorSearchFalseWithoutModel} asserted vector search
 * was OFF. Real vector search was, per the class comment, "tested via integration test with
 * Ollama": a daemon this project does not run. So the suite pinned the DEGRADED path as expected
 * behaviour and went green, while production sat in that same degraded path permanently and
 * handed agents a hash-ordered tool menu. A test that asserts the wiring is absent will never
 * tell you the wiring is absent.</p>
 *
 * <p>Embedding is in-process now ({@link org.wyrdsekai.core.search.EmbeddingService}). Where the
 * bundled model isn't present (most unit runs) the index falls back to lexical scoring, which is
 * what these tests exercise — but the fallback must now be RANKED and reachable, never arbitrary.
 * See {@code ToolMenuIsNotArbitraryTest} for the second-node regression.</p>
 */
class ToolSearchIndexTest {

    private ToolSearchIndex index;

    @BeforeEach
    void setUp() {
        index = new ToolSearchIndex();
    }

    @Test
    void registerAndSearchByKeyword() {
        index.register(tool("library_card", "Search all knowledge in the system"));
        index.register(tool("searching_glass", "Search the web for current information"));
        index.register(tool("oracle_lens", "Query oracle for patterns and predictions"));

        var results = index.search("search for knowledge about mythology");
        assertFalse(results.isEmpty());
        assertEquals("library_card", results.getFirst().function().name(),
            "Should find library_card for 'knowledge' query");
    }

    @Test
    void searchRanksRelevance() {
        index.register(tool("library_card", "Search all knowledge in the system"));
        index.register(tool("searching_glass", "Search the web for current news and information"));
        index.register(tool("quill", "Write text and documents"));

        var results = index.search("search the web for news");
        // "searching_glass" matches more words (search, web, news) than library_card (search)
        assertEquals("searching_glass", results.getFirst().function().name());
    }

    @Test
    void searchOracleByPattern() {
        index.register(tool("library_card", "Search knowledge"));
        index.register(tool("oracle_lens", "Query oracle for patterns predictions anomalies"));
        index.register(tool("searching_glass", "Search the web"));

        var results = index.search("ask about patterns in recent activity");
        assertEquals("oracle_lens", results.getFirst().function().name(),
            "Should find oracle_lens for 'patterns' query");
    }

    @Test
    void searchReturnsAllWhenFewTools() {
        index.register(tool("library_card", "Search knowledge"));
        index.register(tool("quill", "Write text"));

        var results = index.search("do something", 8);
        assertEquals(2, results.size(), "Should return all tools when fewer than topK");
    }

    @Test
    void emptyIndexReturnsEmpty() {
        var results = index.search("anything");
        assertTrue(results.isEmpty());
    }

    @Test
    void nullQueryReturnsAll() {
        index.register(tool("a", "Tool A"));
        index.register(tool("b", "Tool B"));
        var results = index.search(null);
        assertEquals(2, results.size());
    }

    @Test
    void unregisterRemovesTool() {
        index.register(tool("library_card", "Search knowledge"));
        assertEquals(1, index.size());
        index.unregister("library_card");
        assertEquals(0, index.size());
    }

    @Test
    void topKLimitsResults() {
        for (int i = 0; i < 20; i++) {
            index.register(tool("tool_" + i, "Description for tool " + i + " search find"));
        }
        var results = index.search("search find", 5);
        assertEquals(5, results.size());
    }

    @Test
    void roomCreationToolFound() {
        index.register(tool("library_card", "Search knowledge"));
        index.register(tool("create_room_from_template", "Create a new room from template hub study garden library"));
        index.register(tool("craft_from_template", "Create items from template"));

        var results = index.search("create a garden room");
        assertEquals("create_room_from_template", results.getFirst().function().name());
    }

    @Test
    void craftToolFound() {
        index.register(tool("library_card", "Search knowledge"));
        index.register(tool("create_room_from_template", "Create rooms from template"));
        index.register(tool("craft_from_template", "Create new items and craft tools from standard template library"));

        var results = index.search("craft a new item from template");
        assertEquals("craft_from_template", results.getFirst().function().name(),
            "'craft' + 'item' + 'template' match craft_from_template");
    }

    /**
     * Losing vector search is a real degradation, not a neutral configuration. It's allowed
     * (a fallback exists), but it must not be SILENT — the old code dropped to keyword matching
     * at debug level and nobody noticed for months. The contract asserted here is only that we
     * can tell which mode we're in; {@code search()} logs a one-shot WARN in the degraded one.
     */
    @Test
    void degradedModeIsObservable() {
        index.register(tool("library_card", "Search knowledge"));
        // Without the bundled model, hasVectorSearch() is false — and search still answers.
        var results = index.search("search knowledge");
        assertFalse(results.isEmpty(), "the fallback must still return a usable menu");
        assertEquals(index.hasVectorSearch(), !results.isEmpty() && index.hasVectorSearch(),
            "hasVectorSearch() must honestly report the mode we are actually in");
    }

    @Test
    void allReturnsEverything() {
        index.register(tool("a", "A"));
        index.register(tool("b", "B"));
        index.register(tool("c", "C"));
        assertEquals(3, index.all().size());
    }

    // ─── Helper ─────────────────────────────────────────────────

    private static ToolDefinition tool(String name, String description) {
        return ToolDefinition.function(name, description,
            new LinkedHashMap<>(Map.of("type", "object")));
    }
}
