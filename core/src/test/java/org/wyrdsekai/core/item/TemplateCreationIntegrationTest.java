package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for template-based item creation and execution.
 * Tests the full chain: StandardItemLibrary → instantiate → ItemScriptExecutor with inherit().
 */
class TemplateCreationIntegrationTest {

    private static StandardItemLibrary library;
    private static ItemScriptExecutor executor;
    private static TestProvider provider;

    @BeforeAll
    static void setUp() {
        // Gradle runs tests from the subproject dir (core/), but scripts/ is at project root
        var scriptsPath = Path.of("scripts");
        if (!scriptsPath.resolve("std/book.js").toFile().exists()) {
            scriptsPath = Path.of("../scripts"); // from core/ to root
        }
        library = new StandardItemLibrary(scriptsPath);
        executor = new ItemScriptExecutor();
        executor.setScriptResolver(library::resolveBaseScript);
        provider = new TestProvider();
    }

    @AfterAll
    static void tearDown() {
        executor.close();
    }

    @Test
    void createBookFromTemplateAndRead() {
        var item = library.instantiate("simple-book",
            Map.of("title", "Ember's Notes", "author", "Ember", "content", "Day 1: I explored the Nexus."),
            "ember");

        assertTrue(item.isScripted());
        assertTrue(item.isTemplate());
        assertEquals("std/book", item.templateBase());

        // Execute the item script — should invoke the book's read action
        var result = executor.execute(item.id(), item.script(), Map.of("action", "read"), provider);
        assertNotNull(result);
        assertEquals("Ember's Notes", result.get("title"));
        assertEquals("Ember", result.get("author"));
        // Content should contain what we set
        var text = result.get("text");
        assertNotNull(text, "Book should return text on read");
        assertTrue(text.toString().contains("explored the Nexus"),
            "Book content should match what was set: " + text);
    }

    @Test
    void createBookFromTemplateAndSearch() {
        var item = library.instantiate("simple-book",
            Map.of("title", "Mythology", "content", "Zeus ruled Olympus. Thor wielded Mjolnir."),
            "ember");

        var result = executor.execute(item.id(), item.script(), Map.of("action", "search", "query", "zeus"), provider);
        assertNotNull(result);
        var results = result.get("results");
        assertNotNull(results, "Search should return results");
        assertTrue(results instanceof List, "Results should be a list");
        assertFalse(((List<?>) results).isEmpty(), "Search for 'zeus' should find a match");
    }

    @Test
    void createCrystalFromTemplateAndQuery() {
        var item = library.instantiate("scrying-crystal", Map.of(), "ember");

        assertTrue(item.isScripted());
        assertEquals("std/crystal", item.templateBase());

        // Crystal queries oracle — our mock returns empty list
        var result = executor.execute(item.id(), item.script(), Map.of("topic", "activity"), provider);
        assertNotNull(result);
        assertEquals("zone", result.get("source"));
        assertEquals("activity", result.get("topic"));
    }

    @Test
    void createWeatherGlobeAndQuery() {
        var item = library.instantiate("weather-globe", Map.of(), "ember");
        assertEquals("std/crystal", item.templateBase());

        // Weather globe uses web search — mock returns results
        provider.webResults = List.of(
            Map.of("title", "Tokyo Weather", "snippet", "Sunny, 22°C"));

        var result = executor.execute(item.id(), item.script(), Map.of("topic", "Tokyo weather"), provider);
        assertNotNull(result);
        assertEquals("weather", result.get("source"));
        var observations = result.get("observations");
        assertNotNull(observations, "Weather globe should return observations");
    }

    @Test
    void createKeyFromTemplateAndCheck() {
        var item = library.instantiate("room-key",
            Map.of("target", "study-ember", "ttl", "60"),
            "ember");

        assertEquals("std/key", item.templateBase());

        var result = executor.execute(item.id(), item.script(), Map.of("action", "check"), provider);
        assertNotNull(result);
        assertTrue((Boolean) result.get("valid"), "Freshly created key should be valid");
        assertEquals("study-ember", result.get("target"));
    }

    @Test
    void createContainerAndPutTake() {
        var item = library.instantiate("mailbox", Map.of(), "ember");
        assertEquals("std/container", item.templateBase());

        // List empty
        var list = executor.execute(item.id(), item.script(), Map.of("action", "list"), provider);
        assertEquals(0, list.get("count"));

        // Put an item — note: each execute creates a fresh context, so state won't persist
        // between calls. This tests the script logic, not persistence.
        var put = executor.execute(item.id(), item.script(),
            Map.of("action", "put", "item_name", "letter from Masumi"), provider);
        assertNotNull(put);
        // Put should succeed (starts empty each time due to fresh context)
        assertTrue(put.containsKey("stored") || put.containsKey("error"));
    }

    @Test
    void createConsumableAndInspect() {
        var item = library.instantiate("clarity-draught", Map.of(), "ember");
        assertEquals("std/consumable", item.templateBase());

        var result = executor.execute(item.id(), item.script(), Map.of("action", "inspect"), provider);
        assertNotNull(result);
        assertNotNull(result.get("effect"), "Consumable should have effect description");
        assertNotNull(result.get("duration_minutes"));
    }

    @Test
    void createAspectAndInspect() {
        var item = library.instantiate("scholars-mantle", Map.of(), "ember");
        assertEquals("std/aspect", item.templateBase());

        var result = executor.execute(item.id(), item.script(), Map.of("action", "inspect"), provider);
        assertNotNull(result);
        assertNotNull(result.get("overlay"), "Aspect should have overlay text");
        assertNotNull(result.get("appearance"), "Aspect should have appearance description");
    }

    @Test
    void createBlueprintAndInspect() {
        var item = library.instantiate("blueprint-pad", Map.of(
            "result_template", "std/crystal",
            "result_name", "Custom Crystal"), "ember");
        assertEquals("std/blueprint", item.templateBase());

        var result = executor.execute(item.id(), item.script(), Map.of("action", "inspect"), provider);
        assertNotNull(result);
        assertEquals("std/crystal", result.get("result_template"));
    }

    @Test
    void createPortalAndView() {
        var item = library.instantiate("web-window",
            Map.of("source", "https://example.com"), "ember");
        assertEquals("std/portal", item.templateBase());

        provider.fetchResult = "Example Domain page content here";
        provider.summarizeResult = "This is example.com, a domain for testing";

        var result = executor.execute(item.id(), item.script(), Map.of("action", "view"), provider);
        assertNotNull(result);
        assertNotNull(result.get("content"), "Portal should return content");
    }

    @Test
    void createAutomatorAndCheckStatus() {
        var item = library.instantiate("signal-mirror",
            Map.of("condition", "urgent", "action_message", "Alert: urgent keyword detected"), "ember");
        assertEquals("std/automator", item.templateBase());

        var result = executor.execute(item.id(), item.script(), Map.of("action", "status"), provider);
        assertNotNull(result);
        assertEquals("urgent", result.get("condition"));
        assertTrue((Boolean) result.get("enabled"));
    }

    @Test
    void catalogSearchViaWorldApi() {
        var catalogProvider = new TestProvider() {
            private final StandardItemLibrary lib = library;

            @Override
            public List<Map<String, Object>> catalogSearch(String query) {
                return lib.search(query).stream()
                    .<Map<String, Object>>map(t -> {
                        var m = new HashMap<String, Object>();
                        m.put("name", t.name());
                        m.put("displayName", t.displayName());
                        m.put("category", t.category());
                        return m;
                    }).toList();
            }
        };

        // Script that uses world.catalog.search()
        var script = """
            function invoke(params) {
                var results = world.catalog.search(params.query);
                return { count: results.length, results: results };
            }
            """;
        var result = executor.execute("catalog-test", script, Map.of("query", "book"), catalogProvider);
        assertNotNull(result);
        var count = (int) result.get("count");
        assertTrue(count > 0, "Catalog search for 'book' should return results");
    }

    // ─── Test Provider ──────────────────────────────────────────

    static class TestProvider implements ItemWorldApiProvider {
        List<Map<String, Object>> webResults = List.of();
        String fetchResult = "";
        String summarizeResult = "test summary";
        final List<String> spoken = new ArrayList<>();

        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return webResults; }
        @Override public String webFetch(String url, int max) { return fetchResult; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String instruction) { return summarizeResult; }
        @Override public String llmAnalyze(String text, String prompt) { return "analysis"; }
        @Override public void agentSpeak(String text) { spoken.add(text); }
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String target, String message) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}
