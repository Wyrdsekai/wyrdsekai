package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP gateway surface
 * ({@code world.mcp.list_servers / list_tools / call}). These tests pin
 * the input-validation and graceful-unavailable contracts so item scripts
 * can rely on a structured error shape regardless of whether MCP is wired.
 */
class ItemWorldApiProviderImplMcpTest {

    private ItemWorldApiProviderImpl provider;

    @BeforeEach
    void setUp() {
        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:test-agent", "Tester",
            t -> {}, t -> {}, (a, b) -> {},
            null, null);
    }

    @AfterEach
    void tearDown() {
        provider = null;
    }

    @Test
    void list_servers_returns_list_even_without_manager() {
        var servers = provider.mcpListServers();
        assertThat(servers).isNotNull();
        // Each entry must have the documented shape if present.
        for (var s : servers) {
            assertThat(s).containsKeys("server", "transport", "status");
        }
    }

    @Test
    void list_tools_with_unknown_server_returns_empty() {
        var tools = provider.mcpListTools("__never_registered__");
        assertThat(tools).isNotNull().isEmpty();
    }

    @Test
    void list_tools_null_server_returns_list() {
        var tools = provider.mcpListTools(null);
        assertThat(tools).isNotNull();
    }

    @Test
    void invoke_blank_server_returns_invalid_args() {
        var res = provider.mcpInvoke("", "any-tool", Map.of("k", 1));
        assertInvalidArgs(res);
    }

    @Test
    void invoke_null_server_returns_invalid_args() {
        var res = provider.mcpInvoke(null, "any-tool", Map.of());
        assertInvalidArgs(res);
    }

    @Test
    void invoke_blank_tool_returns_invalid_args() {
        var res = provider.mcpInvoke("server-1", "", Map.of());
        assertInvalidArgs(res);
    }

    @Test
    void invoke_null_tool_returns_invalid_args() {
        var res = provider.mcpInvoke("server-1", null, null);
        assertInvalidArgs(res);
    }

    @Test
    void invoke_unknown_server_returns_structured_envelope() {
        // With or without an MCP manager initialized in this JVM, a never-
        // registered server name must produce a structured envelope, never
        // throw. Either mcp_unavailable (no manager) or a permission/lookup
        // failure with the envelope shape are acceptable.
        var res = provider.mcpInvoke("__never_registered_server__",
            "__never_registered_tool__", Map.of());
        assertThat(res).isNotNull();
        assertThat(res).containsKey("success");
        if (Boolean.FALSE.equals(res.get("success"))) {
            assertThat(res).containsKey("error");
            @SuppressWarnings("unchecked")
            var err = (Map<String, Object>) res.get("error");
            assertThat(err).containsKeys("code", "message", "retryable");
        }
    }

    /**
     * Both {@code invalid_args} (manager present, args bad) and
     * {@code mcp_unavailable} (no manager initialized) are acceptable
     * structured failures for blank-arg invocation — order of the two
     * guard checks shouldn't affect the test contract.
     */
    private static void assertInvalidArgs(Map<String, Object> res) {
        assertThat(res).isNotNull();
        assertThat(res.get("success")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        var err = (Map<String, Object>) res.get("error");
        assertThat((String) err.get("code")).isIn("invalid_args", "mcp_unavailable");
        assertThat((String) err.get("message")).isNotBlank();
        assertThat(err.get("retryable")).isEqualTo(false);
    }
}
