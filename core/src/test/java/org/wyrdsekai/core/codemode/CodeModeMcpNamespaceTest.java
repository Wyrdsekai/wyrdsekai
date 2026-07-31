package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §Appendix A / Phase 2a — {@code mcp.search} and
 * {@code mcp.execute} wiring.
 *
 * <p>Asserts:
 * <ul>
 *   <li>{@code mcp.search("calendar")} returns matching tools with
 *       {@code server, tool, description, schema} fields.</li>
 *   <li>{@code mcp.search} respects the {@code k} option (top-K).</li>
 *   <li>{@code mcp.execute(server, tool, args)} invokes correctly.</li>
 *   <li>SecurityException from the provider surfaces as
 *       {@code [error] mcp.execute: not authorized} (clean error).</li>
 *   <li>Missing server / tool args throws clean error.</li>
 * </ul>
 */
class CodeModeMcpNamespaceTest {

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
    void mcp_search_returns_matching_tools() {
        CodeModeNamespace.McpSearchProvider search = (q, k) -> {
            var match = new LinkedHashMap<String, Object>();
            match.put("server", "calendar-mcp");
            match.put("tool", "list_events");
            match.put("description", "List upcoming calendar events");
            match.put("schema", Map.of("type", "object"));
            return List.of(match);
        };

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, search, null);

        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) ns.get("mcp").get("search")
            .apply(new Object[]{"calendar"});
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("server")).isEqualTo("calendar-mcp");
        assertThat(result.get(0).get("tool")).isEqualTo("list_events");
        assertThat(result.get(0).get("description")).isEqualTo("List upcoming calendar events");
        assertThat(result.get(0).get("schema")).isInstanceOf(Map.class);
    }

    @Test
    void mcp_search_respects_k_opt_override() {
        var calls = new int[]{-1};
        CodeModeNamespace.McpSearchProvider search = (q, k) -> {
            calls[0] = k;
            return List.of();
        };

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, search, null);

        // No opts → default k=5.
        ns.get("mcp").get("search").apply(new Object[]{"x"});
        assertThat(calls[0]).isEqualTo(5);

        // With k=10 → forwarded.
        ns.get("mcp").get("search").apply(new Object[]{"x", Map.of("k", 10)});
        assertThat(calls[0]).isEqualTo(10);

        // k > 50 → clamped to default (sane upper bound).
        ns.get("mcp").get("search").apply(new Object[]{"x", Map.of("k", 1_000_000)});
        assertThat(calls[0]).isEqualTo(5);
    }

    @Test
    void mcp_search_unauthorized_when_no_provider() {
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, null);

        assertThatThrownBy(() -> ns.get("mcp").get("search").apply(new Object[]{"x"}))
            .hasMessageContaining("not authorized");
    }

    @Test
    void mcp_execute_invokes_correctly() {
        var captured = new String[]{null, null};
        var capturedArgs = new Object[]{null};
        CodeModeNamespace.McpExecuteProvider execute = (server, tool, args) -> {
            captured[0] = server;
            captured[1] = tool;
            capturedArgs[0] = args;
            return Map.of("result", "ok", "value", 42);
        };

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, execute);

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) ns.get("mcp").get("execute")
            .apply(new Object[]{"calendar-mcp", "list_events", Map.of("date", "2026-04-30")});

        assertThat(captured[0]).isEqualTo("calendar-mcp");
        assertThat(captured[1]).isEqualTo("list_events");
        assertThat(capturedArgs[0]).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var args = (Map<String, Object>) capturedArgs[0];
        assertThat(args.get("date")).isEqualTo("2026-04-30");
        assertThat(result.get("result")).isEqualTo("ok");
    }

    @Test
    void mcp_execute_propagates_security_exception_as_unauthorized() {
        CodeModeNamespace.McpExecuteProvider execute = (server, tool, args) -> {
            throw new SecurityException("MCP tool 'mcp__x__y' denied: no grant");
        };

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, execute);

        assertThatThrownBy(() -> ns.get("mcp").get("execute")
            .apply(new Object[]{"x", "y", Map.of()}))
            .hasMessageContaining("not authorized");
    }

    @Test
    void mcp_execute_missing_args_throws_clean_error() {
        CodeModeNamespace.McpExecuteProvider execute = (s, t, a) -> "ok";
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, execute);

        // Missing tool.
        assertThatThrownBy(() -> ns.get("mcp").get("execute")
            .apply(new Object[]{"server"}))
            .hasMessageContaining("server and tool are required");

        // Empty server.
        assertThatThrownBy(() -> ns.get("mcp").get("execute")
            .apply(new Object[]{"", "tool", Map.of()}))
            .hasMessageContaining("server and tool are required");
    }

    @Test
    void mcp_execute_unauthorized_when_no_provider() {
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, null);

        assertThatThrownBy(() -> ns.get("mcp").get("execute")
            .apply(new Object[]{"server", "tool", Map.of()}))
            .hasMessageContaining("not authorized");
    }

    @Test
    void e2e_script_uses_mcp_search_and_execute() {
        CodeModeNamespace.McpSearchProvider search = (q, k) -> List.of(
            Map.of("server", "fs-mcp", "tool", "read_file",
                "description", "Read a file", "schema", Map.of("type", "object")));
        CodeModeNamespace.McpExecuteProvider execute = (server, tool, args) ->
            Map.of("content", "hello world");

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, search, execute);

        var result = CodeModeExecutor.run("""
            const matches = mcp.search('file');
            console.log('found=' + matches.length);
            console.log('first=' + matches[0].tool);
            const r = mcp.execute('fs-mcp', 'read_file', { path: '/tmp/x' });
            console.log('content=' + r.content);
            """, ns);

        assertThat(result.success()).isTrue();
        assertThat(result.log())
            .contains("found=1", "first=read_file", "content=hello world");
    }

    @Test
    void e2e_script_observes_unauthorized_mcp_as_log_error() {
        CodeModeNamespace.McpExecuteProvider execute = (s, t, a) -> {
            throw new SecurityException("denied");
        };
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, null, null, execute);

        // The script catches the thrown error and logs it — verifies the
        // [error] prefix flows through to the script's observation.
        var result = CodeModeExecutor.run("""
            try {
                mcp.execute('x', 'y', {});
                console.log('UNEXPECTED-OK');
            } catch (e) {
                console.error(String(e));
            }
            """, ns);

        assertThat(result.success()).isTrue();
        // ConsoleBridge.err prefixes with "[error]"; the message we throw
        // contains "not authorized" — both should appear in the log.
        var joined = String.join(" | ", result.log());
        assertThat(joined).contains("[error]");
        assertThat(joined).contains("not authorized");
    }

    /** Minimal provider that returns nothing. */
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
