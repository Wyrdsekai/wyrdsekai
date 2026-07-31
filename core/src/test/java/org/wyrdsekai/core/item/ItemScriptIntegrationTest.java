package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: Library Card and Searching Glass scripts with real WyrdLuceneStore.
 * No Ollama needed — LLM calls are mocked via a provider wrapper.
 */
class ItemScriptIntegrationTest {

    private static final int DIM = 384;

    @TempDir
    Path tempDir;

    private WyrdLuceneStore luceneStore;
    private ItemScriptExecutor executor;

    @BeforeEach
    void setUp() {
        luceneStore = new WyrdLuceneStore(tempDir, DIM);
        luceneStore.ensureAllCollections();
        executor = new ItemScriptExecutor();

        // Seed knowledge chunks
        luceneStore.insertKnowledge("myth-greek-1", "mythology-pack",
            "Greek Mythology: Zeus and Olympus",
            "Zeus, king of the gods, ruled from Mount Olympus. He wielded thunderbolts and was the father of many heroes.",
            "mythology-pack", "mythology", null);

        luceneStore.insertKnowledge("myth-norse-1", "mythology-pack",
            "Norse Mythology: Thor and Mjolnir",
            "Thor, the thunder god of Norse mythology, wielded the hammer Mjolnir. He was the son of Odin.",
            "mythology-pack", "mythology", null);

        luceneStore.insertKnowledge("myth-egypt-1", "mythology-pack",
            "Egyptian Mythology: Ra and the Sun",
            "Ra, the sun god of ancient Egypt, sailed across the sky in his solar barque each day.",
            "mythology-pack", "mythology", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
        luceneStore.close();
    }

    @Test
    void library_card_searches_and_summarizes() {
        var provider = new TestProvider(luceneStore);
        var card = ToolItemStarterKit.libraryCard();

        var result = executor.execute(card.id(), card.script(),
            Map.of("query", "mythology"), provider);

        assertNotNull(result.get("findings"), "Should have findings");
        assertFalse(result.get("findings").toString().isEmpty(), "Findings should not be empty");
        // The mock LLM returns canned summary
        assertTrue(result.get("findings").toString().contains("mythology"),
            "Summary should mention the query topic");

        assertNotNull(result.get("sources"), "Should have sources");
        assertInstanceOf(List.class, result.get("sources"));
        @SuppressWarnings("unchecked")
        var sources = (List<Object>) result.get("sources");
        assertTrue(sources.size() > 0, "Should have at least one source");
    }

    @Test
    void library_card_no_results() {
        var provider = new TestProvider(luceneStore);
        var card = ToolItemStarterKit.libraryCard();

        var result = executor.execute(card.id(), card.script(),
            Map.of("query", "quantum_chromodynamics_xyzzy"), provider);

        assertNotNull(result.get("findings"));
        assertTrue(result.get("findings").toString().contains("No results"),
            "Should report no results found");
    }

    @Test
    void searching_glass_with_web_results() {
        var provider = new TestProvider(luceneStore);
        provider.webResults = List.of(
            Map.of("title", "AI Research 2026", "url", "https://example.com/ai",
                "snippet", "Latest advances in artificial intelligence"),
            Map.of("title", "ML Trends", "url", "https://example.com/ml",
                "snippet", "Machine learning trends and forecasts")
        );
        provider.fetchResults = Map.of(
            "https://example.com/ai", "Artificial intelligence has made tremendous progress in 2026...",
            "https://example.com/ml", "Machine learning continues to evolve with new architectures..."
        );

        var glass = ToolItemStarterKit.searchingGlass();
        var result = executor.execute(glass.id(), glass.script(),
            Map.of("query", "AI research"), provider);

        assertNotNull(result.get("findings"));
        assertFalse(result.get("findings").toString().isEmpty());
        assertNotNull(result.get("sources"));
    }

    @Test
    void searching_glass_no_web_results() {
        var provider = new TestProvider(luceneStore);
        // Default: empty web results

        var glass = ToolItemStarterKit.searchingGlass();
        var result = executor.execute(glass.id(), glass.script(),
            Map.of("query", "nonexistent_topic_xyzzy"), provider);

        assertNotNull(result.get("findings"));
        assertTrue(result.get("findings").toString().contains("No web results"));
    }

    @Test
    void quill_writes_and_speaks() {
        var provider = new TestProvider(luceneStore);
        var quill = ToolItemStarterKit.quill();

        var result = executor.execute(quill.id(), quill.script(),
            Map.of("title", "My Note", "content", "This is important.", "format", "note"),
            provider);

        assertNotNull(result.get("title"));
        assertEquals("My Note", result.get("title"));
        assertEquals("This is important.", result.get("content"));
        assertEquals("note", result.get("format"));
        assertNotNull(provider.lastSpoken, "Quill should speak narration");
        assertTrue(provider.lastSpoken.contains("My Note"));
    }

    @Test
    void quill_polishes_reports() {
        var provider = new TestProvider(luceneStore);
        var quill = ToolItemStarterKit.quill();

        var result = executor.execute(quill.id(), quill.script(),
            Map.of("title", "Q1 Report", "content", "Revenue was good.", "format", "report"),
            provider);

        // Report format triggers LLM polish
        assertTrue(provider.analyzeCalled, "Report should trigger LLM analyze for polish");
        assertNotNull(result.get("content"));
    }

    @Test
    void sending_stone_tells_target() {
        var provider = new TestProvider(luceneStore);
        var stone = ToolItemStarterKit.sendingStone();

        var result = executor.execute(stone.id(), stone.script(),
            Map.of("target", "Ember", "message", "Hello there!"), provider);

        assertEquals(true, result.get("sent"));
        assertEquals("Ember", result.get("target"));
        assertEquals("Ember", provider.lastTellTarget);
        assertEquals("Hello there!", provider.lastTellMessage);
    }

    // ─── Test Provider ───────────────────────────────────────────

    static class TestProvider implements ItemWorldApiProvider {
        private final WyrdLuceneStore luceneStore;
        List<Map<String, Object>> webResults = List.of();
        Map<String, String> fetchResults = Map.of();
        String lastSpoken;
        String lastRemembered;
        String lastTellTarget;
        String lastTellMessage;
        boolean analyzeCalled;

        TestProvider(WyrdLuceneStore luceneStore) {
            this.luceneStore = luceneStore;
        }

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            var results = luceneStore.searchKnowledge(query, null, limit);
            var mapped = new ArrayList<Map<String, Object>>();
            for (var r : results) {
                var m = new HashMap<String, Object>();
                m.put("id", r.id());
                m.put("title", r.metadata() != null
                    ? r.metadata().getOrDefault("title", r.id()) : r.id());
                m.put("text", r.content());
                m.put("score", r.score());
                mapped.add(m);
            }
            return mapped;
        }

        @Override
        public Map<String, Object> readKnowledgeChunk(String chunkId) {
            var r = luceneStore.getById("knowledge", chunkId);
            if (r == null) return null;
            var m = new HashMap<String, Object>();
            m.put("id", r.id());
            m.put("title", r.metadata() != null
                ? r.metadata().getOrDefault("title", r.id()) : r.id());
            m.put("text", r.content());
            return m;
        }

        @Override
        public List<Map<String, Object>> webSearch(String query, String type, int limit) {
            return webResults;
        }

        @Override
        public String webFetch(String url, int maxChars) {
            return fetchResults.getOrDefault(url, "Page content for " + url);
        }

        @Override
        public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
            return List.of();
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            // Return a realistic-looking summary that incorporates the query topic
            if (instruction != null && instruction.contains("mythology")) {
                return "Key findings about mythology: Greek, Norse, and Egyptian traditions feature powerful gods.";
            }
            return "Summary of " + text.substring(0, Math.min(50, text.length())) + "...";
        }

        @Override
        public String llmAnalyze(String text, String prompt) {
            analyzeCalled = true;
            return "Polished: " + text;
        }

        @Override
        public void agentSpeak(String text) {
            lastSpoken = text;
        }

        @Override
        public void agentRemember(String content) {
            lastRemembered = content;
        }

        @Override
        public void agentTell(String target, String message) {
            lastTellTarget = target;
            lastTellMessage = message;
        }

        @Override
        public List<Map<String, Object>> inventoryList() {
            return List.of();
        }

        @Override
        public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
            return Map.of("error", "Not implemented in test");
        }
    }
}
