package org.wyrdsekai.core.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One door (MCP.md "What a call passes through"). Item {@code mcp.invoke}, the librarian's
 * desk and patron, and code-mode {@code mcp.execute} all call
 * {@link McpServerManager#invokeTool}; before 2026-09-28 that went straight to the server's
 * connection with no rate limit, spend cap, circuit breaker or quarantine. Here the manager is
 * connected to a real (stub) MCP server over HTTP, the way Main connects at boot, and every
 * call must meet the gateway's checks on its way to that live connection.
 */
class McpSingleDoorTest {

    private HttpServer stub;
    private String endpoint;
    private McpServiceRegistry registry;
    private McpServerManager manager;
    private McpGatewayService gateway;

    @BeforeEach
    void setUp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/mcp", exchange -> {
            var req = Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
            var id = req.path("id").asLong(1);
            var method = req.path("method").asText("");
            var result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":["
                    + "{\"name\":\"search\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
                    + "{\"name\":\"priced\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
                    + "{\"name\":\"package\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> switch (req.path("params").path("name").asText("")) {
                    case "priced" -> "{\"content\":[{\"type\":\"text\",\"text\":\"paid answer\"}],"
                        + "\"isError\":false,\"_meta\":{\"cost\":0.25}}";
                    case "package" -> "{\"content\":[{\"type\":\"text\",\"text\":"
                        + Json.mapper().writeValueAsString(
                            "{\"library_id\":\"lib\",\"entries\":[{\"id\":\"e1\",\"kind\":\"raw\","
                                + "\"body\":\"<script>x()</script>page words​ here\"}]}")
                        + "}],\"isError\":false}";
                    default -> "{\"content\":[{\"type\":\"text\",\"text\":"
                        + Json.mapper().writeValueAsString("<b>Result</b> one​. "
                            + req.path("params").path("arguments").path("q").asText(""))
                        + "}],\"isError\":false}";
                };
                default -> "{}";
            };
            var body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        stub.start();
        endpoint = "http://127.0.0.1:" + stub.getAddress().getPort() + "/mcp";

        registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig("svc", "Stub", "http", endpoint, "local", null, null, true));
        // Three calls a minute per caller: the fourth is the rate limit speaking.
        gateway = new McpGatewayService(registry, new McpRateLimiter(3, 100, 100), new McpCircuitBreaker(),
            (ep, tool, params, auth) -> { throw new AssertionError("a connected service is called on its connection"); });
        manager = new McpServerManager(null);
        manager.connect(registry.get("svc").orElseThrow());
        manager.setGateway(gateway);
        gateway.useConnections(manager);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.shutdown();
        if (stub != null) stub.stop(0);
    }

    @Test
    void invoke_tool_meets_the_gateway_rate_limit() throws Exception {
        manager.invokeTool("mcp__svc__search", Map.of("q", "a"), "did:mia");
        manager.invokeTool("mcp__svc__search", Map.of("q", "b"), "did:mia");
        manager.invokeTool("mcp__svc__search", Map.of("q", "c"), "did:mia");
        assertThatThrownBy(() -> manager.invokeTool("mcp__svc__search", Map.of("q", "e"), "did:mia"))
            .isInstanceOf(McpGatewayService.Refused.class)
            .hasMessageContaining("harbor master");
        // The limit is the caller's: another being is not held up by her.
        assertThat(manager.invokeTool("mcp__svc__search", Map.of("q", "d"), "did:rose")).contains("d");
    }

    @Test
    void invoke_tool_output_is_quarantined() throws Exception {
        var text = manager.invokeTool("mcp__svc__search", Map.of("q", "x"), "did:mia");
        assertThat(text).doesNotContain("<b>").doesNotContain("​").contains("Result one. x");
    }

    @Test
    void a_json_answer_is_quarantined_value_by_value_and_still_parses() throws Exception {
        var text = manager.invokeTool("mcp__svc__package", Map.of(), "did:mia");
        var tree = Json.mapper().readTree(text);
        assertThat(tree.path("library_id").asText()).isEqualTo("lib");
        var body = tree.path("entries").get(0).path("body").asText();
        assertThat(body).doesNotContain("<script>").doesNotContain("​").contains("page words here");
    }

    @Test
    void invoke_tool_meets_the_grant_check_and_the_librarian_is_exempt() throws Exception {
        gateway.setGrantCheck((caller, service, tool) -> false);
        assertThatThrownBy(() -> manager.invokeTool("mcp__svc__search", Map.of("q", "a"), "did:mia"))
            .isInstanceOf(SecurityException.class)
            .hasMessageContaining("no MCP-tool grant");

        gateway.setHouseholdServices(() -> Set.of("svc"));
        assertThat(manager.invokeTool("mcp__svc__search", Map.of("q", "a"), "did:mia")).contains("a");
    }

    @Test
    void the_reported_price_is_charged_and_meets_the_spend_cap() throws Exception {
        gateway.setBudgetTracker(new McpBudgetTracker(0.3));
        manager.invokeTool("mcp__svc__priced", Map.of("n", 1), "did:mia");
        assertThat(gateway.budgetTracker().getSpend("did:mia", "svc")).isEqualTo(0.25);
        manager.invokeTool("mcp__svc__priced", Map.of("n", 2), "did:mia");
        assertThatThrownBy(() -> manager.invokeTool("mcp__svc__priced", Map.of("n", 3), "did:mia"))
            .isInstanceOf(McpGatewayService.Refused.class)
            .hasMessageContaining("allocation");
    }

    @Test
    void price_is_reported_else_configured_else_estimated_on_metered_only() {
        var metered = new McpServiceConfig("m", "M", "http", "x", "metered", null, null, true);
        var configured = new McpServiceConfig("c", "C", "http", "x", "keyed", null, null, true, 0.05, null);
        var free = new McpServiceConfig("f", "F", "http", "x", "local", null, null, true);
        assertThat(McpGatewayService.priceOf(configured, 0.4)).isEqualTo(0.4);
        assertThat(McpGatewayService.priceOf(configured, null)).isEqualTo(0.05);
        assertThat(McpGatewayService.priceOf(metered, null)).isEqualTo(McpGatewayService.ESTIMATED_PRICE);
        assertThat(McpGatewayService.priceOf(free, null)).isNull();
        assertThat(McpGatewayService.priceOf(free, 0.01)).isEqualTo(0.01);
    }
}
