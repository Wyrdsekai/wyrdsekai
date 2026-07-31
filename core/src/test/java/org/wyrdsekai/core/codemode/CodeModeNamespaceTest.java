package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ToolItem;
import org.wyrdsekai.core.item.ToolItem.ToolParam;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Track A Phase 1 — namespace builder smoke.
 *
 * <p>Asserts: equipped scripted items become typed namespaces; calling the
 * wrapper invokes the underlying item script via {@link ItemScriptExecutor};
 * deferred Phase 2 surfaces (world.peek, world.listInventory, mcp.search,
 * mcp.execute) throw clear "not yet wired" errors.</p>
 */
class CodeModeNamespaceTest {

    private ItemScriptExecutor exec;
    private TestProvider provider;

    @BeforeEach
    void setUp() {
        exec = new ItemScriptExecutor();
        provider = new TestProvider();
    }

    @AfterEach
    void tearDown() {
        exec.close();
    }

    @Test
    void scripted_item_becomes_invokable_namespace() {
        var item = ToolItem.scripted(
            "library_card", "Library Card", "search",
            """
            function invoke(params) {
                return { findings: 'about ' + params.query, sources: ['s1', 's2'] };
            }
            """,
            List.of(new ToolParam("query", "string", "q", true, null)),
            "test");

        var ns = CodeModeNamespace.forActor(List.of(item), exec, provider);

        assertThat(ns).containsKey("library_card");
        assertThat(ns.get("library_card")).containsKey("invoke");

        // Direct executor call vs namespace call must agree.
        var direct = exec.execute(item.id(), item.script(),
            Map.of("query", "mythology"), provider);

        var fn = ns.get("library_card").get("invoke");
        @SuppressWarnings("unchecked")
        var via = (Map<String, Object>) fn.apply(new Object[]{
            Map.of("query", "mythology")
        });

        assertThat(via.get("findings")).isEqualTo(direct.get("findings"));
        assertThat(via.get("sources")).isEqualTo(direct.get("sources"));
    }

    @Test
    void aliases_route_to_invoke() {
        var item = ToolItem.scripted(
            "searching_glass", "Searching Glass", "web",
            """
            function invoke(params) {
                return { result: 'searched: ' + params.query };
            }
            """,
            List.of(new ToolParam("query", "string", "q", true, null)),
            "test");

        var ns = CodeModeNamespace.forActor(List.of(item), exec, provider);

        var search = ns.get("searching_glass").get("search");
        @SuppressWarnings("unchecked")
        var via = (Map<String, Object>) search.apply(new Object[]{
            Map.of("query", "ai")
        });
        assertThat(via.get("result")).isEqualTo("searched: ai");
    }

    @Test
    void positional_string_arg_maps_to_query_param() {
        var item = ToolItem.scripted(
            "library_card", "Library Card", "search",
            """
            function invoke(params) {
                return { q: params.query };
            }
            """,
            List.of(new ToolParam("query", "string", "q", true, null)),
            "test");

        var ns = CodeModeNamespace.forActor(List.of(item), exec, provider);
        @SuppressWarnings("unchecked")
        var via = (Map<String, Object>) ns.get("library_card").get("invoke")
            .apply(new Object[]{"mythology"});
        assertThat(via.get("q")).isEqualTo("mythology");
    }

    @Test
    void phase2a_world_peek_returns_null_when_no_provider() {
        // Phase 2a: with no peekProvider wired (3-arg form), peek
        // returns null gracefully (script-friendly).
        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);
        assertThat(ns).containsKey("world");

        var peek = ns.get("world").get("peek");
        assertThat(peek).isNotNull();
        assertThat(peek.apply(new Object[]{"foyer"})).isNull();
    }

    @Test
    void phase2a_world_list_inventory_returns_empty_when_provider_empty() {
        // TestProvider.inventoryList() returns empty list — namespace returns empty.
        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);
        var fn = ns.get("world").get("listInventory");
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) fn.apply(new Object[]{});
        assertThat(result).isEmpty();
    }

    @Test
    void phase2a_mcp_search_throws_unauthorized_when_no_provider() {
        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);
        var fn = ns.get("mcp").get("search");
        assertThatThrownBy(() -> fn.apply(new Object[]{"x"}))
            .hasMessageContaining("not authorized");
    }

    @Test
    void phase2a_mcp_execute_throws_unauthorized_when_no_provider() {
        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);
        var fn = ns.get("mcp").get("execute");
        assertThatThrownBy(() -> fn.apply(new Object[]{"server", "tool"}))
            .hasMessageContaining("not authorized");
    }

    @Test
    void non_scripted_items_skipped() {
        var builtin = ToolItem.builtin("task_ledger", "Task Ledger", "tasks",
            "task_ledger", List.of());
        var scripted = ToolItem.scripted("library_card", "Library Card", "search",
            "function invoke(p) { return { ok: true }; }",
            List.of(), "test");

        var ns = CodeModeNamespace.forActor(List.of(builtin, scripted), exec, provider);
        assertThat(ns).doesNotContainKey("task_ledger");
        assertThat(ns).containsKey("library_card");
    }

    @Test
    void executor_can_run_namespace_e2e() {
        var item = ToolItem.scripted(
            "library_card", "Library Card", "search",
            """
            function invoke(params) {
                return { findings: 'rich data about ' + params.query };
            }
            """,
            List.of(new ToolParam("query", "string", "q", true, null)),
            "test");

        var ns = CodeModeNamespace.forActor(List.of(item), exec, provider);
        var result = CodeModeExecutor.run("""
            const r = library_card.invoke({query: 'x'});
            console.log(r.findings);
            """, ns);

        assertThat(result.success()).isTrue();
        assertThat(result.log()).contains("rich data about x");
    }

    /** Minimal provider that returns nothing — equipped items in tests use closed-form scripts. */
    static class TestProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String inst) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String text) {}
        @Override public void agentTell(String tgt, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}
