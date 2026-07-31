package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase C MCP gateway extensions
 * (list_servers, list_tools, invoke, resources, read_resource, prompts,
 * subscribe). Pins gating + per-item mcp_servers allowlist enforcement.
 */
class McpApiPhaseCTest {

    @Test
    void invoke_requires_capability() {
        var caps = ItemCapabilitySet.of(List.of()); // no caps
        var api = new ItemWorldApi(new StubProvider(), caps);
        assertThatThrownBy(() -> api.mcp.invoke("filesystem", "read_file", Map.of()))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void invoke_blocked_when_server_not_allowlisted() {
        var p = new StubProvider();
        var caps = capsWith(List.of("mcp.invoke"), List.of("filesystem"));
        var api = new ItemWorldApi(p, caps);
        var res = api.mcp.invoke("brave-search", "search", Map.of());
        assertThat(res.get("success")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var err = (Map<String, Object>) res.get("error");
        assertThat(err.get("code")).isEqualTo("mcp_server_not_allowed");
        assertThat(p.invokedServer).isNull();
    }

    @Test
    void invoke_succeeds_when_server_allowlisted() {
        var p = new StubProvider();
        var caps = capsWith(List.of("mcp.invoke"), List.of("filesystem"));
        var api = new ItemWorldApi(p, caps);
        var res = api.mcp.invoke("filesystem", "read_file", Map.of("path", "x"));
        assertThat(res.get("success")).isEqualTo(true);
        assertThat(p.invokedServer).isEqualTo("filesystem");
        assertThat(p.invokedTool).isEqualTo("read_file");
    }

    @Test
    void list_servers_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mcp.list_servers())
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void list_tools_filters_by_server() {
        var p = new StubProvider();
        var caps = capsWith(List.of("mcp.list_tools"), List.of("filesystem"));
        var api = new ItemWorldApi(p, caps);
        var res = api.mcp.list_tools("filesystem");
        assertThat(p.lastListServer).isEqualTo("filesystem");
        assertThat(res).isNotNull();
    }

    @Test
    void resources_read_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mcp.resources("filesystem"))
            .isInstanceOf(CapabilityDeniedError.class);
        assertThatThrownBy(() -> api.mcp.read_resource("filesystem", "file://foo"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void prompts_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mcp.prompts("filesystem"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void subscribe_requires_capability() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        assertThatThrownBy(() -> api.mcp.subscribe("filesystem", "file://foo", "onChange"))
            .isInstanceOf(CapabilityDeniedError.class);
    }

    @Test
    void available_is_implicit_tier1() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        // No throw — implicit tier 1.
        assertThat(api.mcp.available("filesystem")).isFalse();
    }

    @Test
    void budget_remaining_is_implicit_tier1() {
        var api = new ItemWorldApi(new StubProvider(), ItemCapabilitySet.of(List.of()));
        var res = api.mcp.budget_remaining("filesystem");
        assertThat(res).containsKey("remaining");
    }

    @Test
    void unrestricted_caps_bypass_server_allowlist() {
        var p = new StubProvider();
        var api = new ItemWorldApi(p, ItemCapabilitySet.UNRESTRICTED);
        var res = api.mcp.invoke("anything", "tool", Map.of());
        assertThat(res.get("success")).isEqualTo(true);
        assertThat(p.invokedServer).isEqualTo("anything");
    }

    private static ItemCapabilitySet capsWith(List<String> declared, List<String> servers) {
        var m = new ItemManifest("test", "1.0.0", "T.", "did:wyrd:x",
            declared, Map.of(), "low", List.of(),
            List.of(), servers, List.of(),
            null, null, null, null, null);
        return ItemCapabilitySet.from(m);
    }

    private static final class StubProvider implements ItemWorldApiProvider {
        String invokedServer;
        String invokedTool;
        String lastListServer;

        @Override public List<Map<String, Object>> searchKnowledge(String q, int n) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int n) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }

        @Override public Map<String, Object> mcpInvoke(String server, String tool, Map<String, Object> args) {
            invokedServer = server; invokedTool = tool;
            return Map.of("success", true, "data", "ok", "cost", 0.0, "latencyMs", 5L);
        }

        @Override public List<Map<String, Object>> mcpListTools(String server) {
            lastListServer = server; return List.of();
        }

        @Override public List<Map<String, Object>> mcpListServers() {
            return List.of(Map.of("server", "filesystem", "transport", "stdio", "status", "connected"));
        }
    }
}
