package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApi;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the inherit() mechanism in ItemScriptExecutor.
 * Validates that base scripts load correctly, override works,
 * and the item object is shared between base and creator scripts.
 */
class InheritMechanismTest {

    private static ItemScriptExecutor executor;
    private static ItemWorldApiProvider mockProvider;

    @BeforeAll
    static void setUp() {
        executor = new ItemScriptExecutor();

        // Resolver that provides fake base scripts for testing
        executor.setScriptResolver(path -> {
            if ("std/book".equals(path)) {
                return """
                    var item = this;
                    item._type = "book";
                    item._title = "default title";
                    item.set_title = function(t) { item._title = t; };
                    function invoke(params) {
                        return { type: item._type, title: item._title, action: params.action || "read" };
                    }
                    """;
            }
            if ("std/tool".equals(path)) {
                return """
                    var item = this;
                    item._type = "tool";
                    item._name = "default tool";
                    item.set_name = function(n) { item._name = n; };
                    function invoke(params) {
                        return { type: item._type, name: item._name };
                    }
                    """;
            }
            if ("std/crystal".equals(path)) {
                return """
                    var item = this;
                    item._type = "crystal";
                    item._source = "zone";
                    item.set_source = function(s) { item._source = s; };
                    function invoke(params) {
                        return { type: item._type, source: item._source, topic: params.topic };
                    }
                    """;
            }
            return null;
        });

        mockProvider = createMockProvider();
    }

    @AfterAll
    static void tearDown() {
        executor.close();
    }

    @Test
    void inheritLoadsBaseScript() {
        var script = """
            inherit("std/book");
            """;
        var result = executor.execute("test-inherit-base", script, Map.of("action", "read"), mockProvider);
        assertEquals("book", result.get("type"));
        assertEquals("default title", result.get("title"));
    }

    @Test
    void inheritWithSetterOverridesDefault() {
        var script = """
            inherit("std/book");
            item.set_title("My Custom Book");
            """;
        var result = executor.execute("test-inherit-setter", script, Map.of(), mockProvider);
        assertEquals("book", result.get("type"));
        assertEquals("My Custom Book", result.get("title"));
    }

    @Test
    void overrideInvokeFunctionReplacesBase() {
        var script = """
            inherit("std/book");
            item.set_title("Overridden Book");
            function invoke(params) {
                return { custom: true, title: item._title, greeting: "hello from override" };
            }
            """;
        var result = executor.execute("test-inherit-override", script, Map.of(), mockProvider);
        assertTrue((Boolean) result.get("custom"));
        assertEquals("Overridden Book", result.get("title"));
        assertEquals("hello from override", result.get("greeting"));
    }

    @Test
    void inheritDifferentTypes() {
        var bookScript = "inherit(\"std/book\"); item.set_title(\"A Book\");";
        var toolScript = "inherit(\"std/tool\"); item.set_name(\"A Tool\");";
        var crystalScript = "inherit(\"std/crystal\"); item.set_source(\"weather\");";

        var bookResult = executor.execute("test-book", bookScript, Map.of(), mockProvider);
        var toolResult = executor.execute("test-tool", toolScript, Map.of(), mockProvider);
        var crystalResult = executor.execute("test-crystal", crystalScript, Map.of("topic", "rain"), mockProvider);

        assertEquals("book", bookResult.get("type"));
        assertEquals("A Book", bookResult.get("title"));

        assertEquals("tool", toolResult.get("type"));
        assertEquals("A Tool", toolResult.get("name"));

        assertEquals("crystal", crystalResult.get("type"));
        assertEquals("weather", crystalResult.get("source"));
        assertEquals("rain", crystalResult.get("topic"));
    }

    @Test
    void inheritUnknownPathIsNoOp() {
        var script = """
            inherit("std/nonexistent");
            function invoke(params) {
                return { ok: true };
            }
            """;
        var result = executor.execute("test-unknown-inherit", script, Map.of(), mockProvider);
        assertTrue((Boolean) result.get("ok"));
    }

    @Test
    void noResolverInheritIsNoOp() {
        var noResolverExecutor = new ItemScriptExecutor();
        // No setScriptResolver called
        var script = """
            inherit("std/book");
            function invoke(params) {
                return { fallback: true };
            }
            """;
        var result = noResolverExecutor.execute("test-no-resolver", script, Map.of(), mockProvider);
        assertTrue((Boolean) result.get("fallback"));
        noResolverExecutor.close();
    }

    @Test
    void baseScriptAndCreatorScriptShareItemObject() {
        var script = """
            inherit("std/book");
            item.set_title("Shared State Test");
            item.customField = "added by creator";
            function invoke(params) {
                return {
                    type: item._type,
                    title: item._title,
                    custom: item.customField
                };
            }
            """;
        var result = executor.execute("test-shared-item", script, Map.of(), mockProvider);
        assertEquals("book", result.get("type"));
        assertEquals("Shared State Test", result.get("title"));
        assertEquals("added by creator", result.get("custom"));
    }

    @Test
    void multipleSettersApply() {
        var script = """
            inherit("std/book");
            item.set_title("Title 1");
            item.set_title("Title 2");
            item.set_title("Final Title");
            """;
        var result = executor.execute("test-multi-setter", script, Map.of(), mockProvider);
        assertEquals("Final Title", result.get("title"));
    }

    @Test
    void paramsPassThroughToInheritedInvoke() {
        var script = """
            inherit("std/book");
            item.set_title("Params Test");
            """;
        var result = executor.execute("test-params-passthrough", script,
            Map.of("action", "search"), mockProvider);
        assertEquals("search", result.get("action"));
    }

    // ─── Helper ────────────────────────────────────────

    private static ItemWorldApiProvider createMockProvider() {
        return new ItemWorldApiProvider() {
            @Override public List<Map<String, Object>> searchKnowledge(String query, int limit) { return List.of(); }
            @Override public Map<String, Object> readKnowledgeChunk(String chunkId) { return Map.of(); }
            @Override public List<Map<String, Object>> webSearch(String query, String type, int limit) { return List.of(); }
            @Override public String webFetch(String url, int maxChars) { return ""; }
            @Override public List<Map<String, Object>> queryOracle(String topic, String analysisType) { return List.of(); }
            @Override public String llmSummarize(String text, String instruction) { return "summary"; }
            @Override public String llmAnalyze(String text, String prompt) { return "analysis"; }
            @Override public void agentSpeak(String text) {}
            @Override public void agentRemember(String content) {}
            @Override public void agentTell(String target, String message) {}
            @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
            @Override public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) { return Map.of(); }
        };
    }
}
