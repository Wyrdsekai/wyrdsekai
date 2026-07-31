package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;
import org.wyrdsekai.core.mcp.transport.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MCP transport layer: JSON-RPC protocol, transport factory,
 * tool index, server manager.
 */
class McpTransportTest {

    // ── JSON-RPC Protocol ────────────────────────────────────────────

    @Test
    void json_rpc_request_creates_valid_message() {
        var req = JsonRpcMessage.Request.create(1, "tools/list", Map.of("cursor", "abc"));
        assertEquals("2.0", req.jsonrpc());
        assertEquals(1, req.id());
        assertEquals("tools/list", req.method());
        assertEquals("abc", req.params().get("cursor"));
    }

    @Test
    void json_rpc_response_detects_error() {
        var error = new JsonRpcMessage.Response("2.0", 1, null,
            new JsonRpcMessage.RpcError(-32600, "Invalid", null));
        assertTrue(error.isError());

        var ok = new JsonRpcMessage.Response("2.0", 1, Map.of("tools", List.of()), null);
        assertFalse(ok.isError());
    }

    @Test
    void tool_call_result_extracts_text_content() {
        var result = new JsonRpcMessage.ToolCallResult(
            List.of(
                new JsonRpcMessage.ContentBlock("text", "Hello "),
                new JsonRpcMessage.ContentBlock("text", "World"),
                new JsonRpcMessage.ContentBlock("image", "base64data")
            ), false);
        assertEquals("Hello World", result.textContent());
    }

    @Test
    void tool_call_result_empty_content() {
        var result = new JsonRpcMessage.ToolCallResult(List.of(), false);
        assertEquals("", result.textContent());

        var nullResult = new JsonRpcMessage.ToolCallResult(null, false);
        assertEquals("", nullResult.textContent());
    }

    // ── Transport Factory ────────────────────────────────────────────

    @Test
    void factory_creates_stdio_transport() {
        var config = new McpServiceConfig("test", "Test", "stdio",
            "python -m mcp_server", "local", null, null, true);
        var handler = McpTransportFactory.create(config, null);
        assertInstanceOf(StdioTransportHandler.class, handler);
    }

    @Test
    void factory_creates_http_transport() {
        var config = new McpServiceConfig("test", "Test", "http",
            "http://localhost:8080", "local", null, null, true);
        var handler = McpTransportFactory.create(config, null);
        assertInstanceOf(HttpTransportHandler.class, handler);
    }

    @Test
    void factory_creates_websocket_transport() {
        var config = new McpServiceConfig("test", "Test", "websocket",
            "ws://localhost:8080", "local", null, null, true);
        var handler = McpTransportFactory.create(config, null);
        assertInstanceOf(WebSocketTransportHandler.class, handler);
    }

    @Test
    void factory_creates_sse_transport() {
        var config = new McpServiceConfig("test", "Test", "sse",
            "http://localhost:8080/sse", "local", null, null, true);
        var handler = McpTransportFactory.create(config, null);
        assertInstanceOf(SseTransportHandler.class, handler);
    }

    @Test
    void factory_defaults_to_http_for_unknown_transport() {
        var config = new McpServiceConfig("test", "Test", "unknown",
            "http://localhost:8080", "local", null, null, true);
        var handler = McpTransportFactory.create(config, null);
        assertInstanceOf(HttpTransportHandler.class, handler);
    }

    // ── Tool Index ───────────────────────────────────────────────────

    @Test
    void tool_index_registers_and_looks_up() {
        var index = new McpToolIndex();
        var tool = new JsonRpcMessage.McpTool("search", "Search the web", Map.of());

        var qualified = index.register("searxng", tool);
        assertThat(qualified).isEqualTo("mcp__searxng__search");

        var route = index.lookup(qualified);
        assertThat(route).isPresent();
        assertThat(route.get().serverId()).isEqualTo("searxng");
        assertThat(route.get().rawToolName()).isEqualTo("search");
    }

    @Test
    void tool_index_prevents_collisions() {
        var index = new McpToolIndex();
        index.register("server-a", new JsonRpcMessage.McpTool("search", "A search", Map.of()));
        index.register("server-b", new JsonRpcMessage.McpTool("search", "B search", Map.of()));

        assertThat(index.size()).isEqualTo(2);
        assertThat(index.lookup("mcp__server_a__search")).isPresent();
        assertThat(index.lookup("mcp__server_b__search")).isPresent();
    }

    @Test
    void tool_index_removes_server() {
        var index = new McpToolIndex();
        index.register("s1", new JsonRpcMessage.McpTool("t1", "d1", Map.of()));
        index.register("s1", new JsonRpcMessage.McpTool("t2", "d2", Map.of()));
        index.register("s2", new JsonRpcMessage.McpTool("t1", "d3", Map.of()));

        assertThat(index.size()).isEqualTo(3);
        index.removeServer("s1");
        assertThat(index.size()).isEqualTo(1);
        assertThat(index.lookup("mcp__s2__t1")).isPresent();
    }

    @Test
    void tool_index_lists_tools_for_server() {
        var index = new McpToolIndex();
        index.register("github", new JsonRpcMessage.McpTool("list_repos", "List repos", Map.of()));
        index.register("github", new JsonRpcMessage.McpTool("create_issue", "Create issue", Map.of()));
        index.register("searxng", new JsonRpcMessage.McpTool("search", "Search", Map.of()));

        var githubTools = index.toolsForServer("github");
        assertThat(githubTools).hasSize(2);
    }

    @Test
    void qualify_name_normalizes_special_chars() {
        var name = McpToolIndex.qualifyName("my-server.v2", "list/all-items");
        assertThat(name).isEqualTo("mcp__my_server_v2__list_all_items");
    }

    // ── Server Manager ───────────────────────────────────────────────

    @Test
    void server_manager_starts_empty() {
        var manager = new McpServerManager(null);
        assertThat(manager.connectedServers()).isEmpty();
        assertThat(manager.toolIndex().size()).isEqualTo(0);
    }

    @Test
    void server_manager_disconnect_cleans_up() {
        var manager = new McpServerManager(null);
        // Disconnect non-existent is silent
        manager.disconnect("nonexistent");
        assertThat(manager.connectedServers()).isEmpty();
    }

    @Test
    void server_manager_shutdown_is_safe_when_empty() {
        var manager = new McpServerManager(null);
        manager.shutdown();
        assertThat(manager.connectedServers()).isEmpty();
    }
}
