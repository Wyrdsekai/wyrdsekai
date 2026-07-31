package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemScriptExecutorTest {

    private ItemScriptExecutor executor;
    private MockItemWorldApiProvider provider;

    @BeforeEach
    void setUp() {
        executor = new ItemScriptExecutor();
        provider = new MockItemWorldApiProvider();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void echo_script_returns_params() {
        var result = executor.execute("echo", """
            function invoke(params) {
                return { echo: params.query, ok: true };
            }
            """, Map.of("query", "hello"), provider);

        assertEquals("hello", result.get("echo"));
        assertEquals(true, result.get("ok"));
    }

    @Test
    void script_can_access_world_library() {
        provider.knowledgeResults = List.of(
            Map.of("id", "chunk1", "title", "Norse Myths", "text", "Odin rode Sleipnir", "score", 0.9)
        );

        var result = executor.execute("lib-test", """
            function invoke(params) {
                var results = world.library.search(params.query);
                return { count: results.length, firstTitle: results[0].title };
            }
            """, Map.of("query", "mythology"), provider);

        assertEquals(1, result.get("count"));
        assertEquals("Norse Myths", result.get("firstTitle"));
    }

    @Test
    void script_can_access_world_web() {
        provider.webResults = List.of(
            Map.of("title", "AI News", "url", "https://example.com", "snippet", "Latest research")
        );

        var result = executor.execute("web-test", """
            function invoke(params) {
                var results = world.web.search(params.query);
                return { count: results.length, firstUrl: results[0].url };
            }
            """, Map.of("query", "AI research"), provider);

        assertEquals(1, result.get("count"));
        assertEquals("https://example.com", result.get("firstUrl"));
    }

    @Test
    void script_can_call_world_llm() {
        provider.summarizeResult = "Key findings: Norse mythology is rich.";

        var result = executor.execute("llm-test", """
            function invoke(params) {
                var summary = world.llm.summarize("Long text here", "Summarize");
                return { summary: summary };
            }
            """, Map.of(), provider);

        assertEquals("Key findings: Norse mythology is rich.", result.get("summary"));
    }

    @Test
    void script_can_call_agent_speak() {
        var result = executor.execute("speak-test", """
            function invoke(params) {
                world.agent.speak("Hello from script!");
                return { spoke: true };
            }
            """, Map.of(), provider);

        assertEquals(true, result.get("spoke"));
        assertEquals("Hello from script!", provider.lastSpokenText);
    }

    @Test
    void script_can_call_agent_tell() {
        var result = executor.execute("tell-test", """
            function invoke(params) {
                world.agent.tell(params.target, params.message);
                return { sent: true };
            }
            """, Map.of("target", "Ember", "message", "Hello!"), provider);

        assertEquals(true, result.get("sent"));
        assertEquals("Ember", provider.lastTellTarget);
        assertEquals("Hello!", provider.lastTellMessage);
    }

    @Test
    void script_error_returns_error_map() {
        var result = executor.execute("error-test", """
            function invoke(params) {
                throw new Error("Boom!");
            }
            """, Map.of(), provider);

        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("Boom"));
    }

    @Test
    void missing_invoke_returns_error() {
        var result = executor.execute("no-invoke", """
            function notInvoke(params) {
                return { ok: true };
            }
            """, Map.of(), provider);

        assertNotNull(result.get("error"));
        assertTrue(result.get("error").toString().contains("no invoke()"));
    }

    @Test
    void source_caching_works() {
        var script = """
            function invoke(params) {
                return { n: params.n };
            }
            """;

        executor.precompile("cache-test", script);
        var result1 = executor.execute("cache-test", script, Map.of("n", "1"), provider);
        var result2 = executor.execute("cache-test", script, Map.of("n", "2"), provider);

        assertEquals("1", result1.get("n"));
        assertEquals("2", result2.get("n"));
    }

    @Test
    void null_params_handled() {
        var result = executor.execute("null-params", """
            function invoke(params) {
                return { ok: true };
            }
            """, null, provider);

        assertEquals(true, result.get("ok"));
    }

    @Test
    void nested_object_result_converted() {
        var result = executor.execute("nested", """
            function invoke(params) {
                return {
                    findings: "Found stuff",
                    sources: ["book1", "book2"],
                    meta: { count: 2, quality: "high" }
                };
            }
            """, Map.of(), provider);

        assertEquals("Found stuff", result.get("findings"));
        assertInstanceOf(List.class, result.get("sources"));
        @SuppressWarnings("unchecked")
        var sources = (List<Object>) result.get("sources");
        assertEquals(2, sources.size());
        assertEquals("book1", sources.get(0));
        assertInstanceOf(Map.class, result.get("meta"));
    }

    @Test
    void library_card_script_integration() {
        // Simulate what the real Library Card script does
        provider.knowledgeResults = List.of(
            Map.of("id", "c1", "title", "Greek Myths", "text", "Zeus ruled Olympus", "score", 0.95),
            Map.of("id", "c2", "title", "Norse Myths", "text", "Thor wielded Mjolnir", "score", 0.85)
        );
        provider.chunkResults = Map.of(
            "c1", Map.of("id", "c1", "title", "Greek Myths", "text", "Zeus, king of the gods, ruled from Mount Olympus."),
            "c2", Map.of("id", "c2", "title", "Norse Myths", "text", "Thor, the thunder god, wielded the hammer Mjolnir.")
        );
        provider.summarizeResult = "Greek and Norse mythologies feature powerful gods. Zeus rules Olympus; Thor wields Mjolnir.";

        var libraryCardScript = """
            function invoke(params) {
                var results = world.library.search(params.query);
                if (!results || results.length === 0) {
                    return { findings: "No results found for: " + params.query, sources: [] };
                }
                var texts = [];
                var sources = [];
                var count = Math.min(results.length, 3);
                for (var i = 0; i < count; i++) {
                    var chunk = world.library.read(results[i].id);
                    if (chunk && chunk.text) {
                        texts.push(chunk.text);
                        sources.push(chunk.title || results[i].id);
                    }
                }
                var combined = texts.join("\\n\\n---\\n\\n");
                var summary = world.llm.summarize(combined, "Extract key findings about: " + params.query);
                return { findings: summary, sources: sources };
            }
            """;

        var result = executor.execute("library_card", libraryCardScript,
            Map.of("query", "mythology"), provider);

        assertNotNull(result.get("findings"));
        assertTrue(result.get("findings").toString().contains("Zeus"));
        assertInstanceOf(List.class, result.get("sources"));
    }

    // ─── Mock Provider ───────────────────────────────────────────

    static class MockItemWorldApiProvider implements ItemWorldApiProvider {
        List<Map<String, Object>> knowledgeResults = List.of();
        Map<String, Map<String, Object>> chunkResults = Map.of();
        List<Map<String, Object>> webResults = List.of();
        String summarizeResult = "Summary placeholder";
        String analyzeResult = "Analysis placeholder";
        String lastSpokenText;
        String lastRememberedText;
        String lastTellTarget;
        String lastTellMessage;

        @Override
        public List<Map<String, Object>> searchKnowledge(String query, int limit) {
            return knowledgeResults;
        }

        @Override
        public Map<String, Object> readKnowledgeChunk(String chunkId) {
            return chunkResults.get(chunkId);
        }

        @Override
        public List<Map<String, Object>> webSearch(String query, String type, int limit) {
            return webResults;
        }

        @Override
        public String webFetch(String url, int maxChars) {
            return "Fetched content from " + url;
        }

        @Override
        public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
            return List.of();
        }

        @Override
        public String llmSummarize(String text, String instruction) {
            return summarizeResult;
        }

        @Override
        public String llmAnalyze(String text, String prompt) {
            return analyzeResult;
        }

        @Override
        public void agentSpeak(String text) {
            lastSpokenText = text;
        }

        @Override
        public void agentRemember(String content) {
            lastRememberedText = content;
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
            return Map.of("error", "Not implemented in mock");
        }
    }
}
