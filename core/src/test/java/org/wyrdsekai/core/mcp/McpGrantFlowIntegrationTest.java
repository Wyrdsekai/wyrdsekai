package org.wyrdsekai.core.mcp;

import com.sun.net.httpserver.HttpServer;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.mcp.transport.HttpTransportHandler;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomAuthority;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end MCP flow: real HTTP transport → a stub MCP server, gated by
 * household-owned (steward-issued) grants, driven through the Study Tool Warden's
 * admin. Exercises the whole chain the way Main wires it — config (registry) +
 * tool (grant admin) + transport (real HTTP round-trip) + gating — and runs the
 * full grant → allow → revoke → deny cycle plus the steward-only guard.
 */
class McpGrantFlowIntegrationTest {

    private static final String STEWARD = "did:key:steward";
    private static final String SERVICE = "searxng";

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private HttpServer stub;
    private String endpoint;
    private McpServiceRegistry registry;
    private McpGatewayService gateway;
    private McpGrantAdmin grantAdmin;

    @TempDir Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        // 1. A stub MCP server: JSON-RPC initialize + tools/call (echoes the query).
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/mcp", exchange -> {
            var reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var mapper = Json.mapper();
            var req = mapper.readTree(reqBody);
            var id = req.has("id") ? req.get("id").asLong() : 1;
            var method = req.path("method").asText("");
            String result;
            if (method.equals("initialize")) {
                result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"stub-searxng\",\"version\":\"1.0\"}}";
            } else if (method.equals("tools/call")) {
                var query = req.path("params").path("arguments").path("query").asText("");
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"results for: "
                    + query + "\"}],\"isError\":false}";
            } else {
                result = "{}";
            }
            var body = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        stub.start();
        endpoint = "http://127.0.0.1:" + stub.getAddress().getPort() + "/mcp";

        // 2. Home registry + client (real grant storage).
        testKit = ActorTestKit.create("McpGrantFlowIntegrationTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("mcp.db"));
        var registryActor = testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc)));
        homeClient = new HomeClient(registryActor, testKit.system());

        // 3. The steward is a household administrator (granted at boot in prod).
        RoomAuthority.grantAdmin(STEWARD);

        // 4. Service registry with the stub configured (config = household opt-in).
        registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig(
            SERVICE, "SearXNG", "http", endpoint, "local", null, null, true));

        // 5. Gateway with the REAL transport (identical to Main) + strict steward grant.
        gateway = new McpGatewayService(registry,
            (ep, toolName, params, authHeader) -> {
                var cfg = registry.enabledServices().stream()
                    .filter(s -> ep != null && ep.equals(s.endpoint()))
                    .findFirst().orElse(null);
                McpTransportHandler handler = (cfg != null)
                    ? McpTransportFactory.create(cfg, authHeader)
                    : new HttpTransportHandler(ep, Map.of(), authHeader);
                try {
                    handler.initialize();
                    var r = handler.callTool(toolName, params != null ? params : Map.of());
                    return r.textContent();
                } finally {
                    try { handler.close(); } catch (Exception ignore) { /* best-effort */ }
                }
            });
        gateway.setGrantCheck(McpGrantCheck.stewardOwned(homeClient, STEWARD, true));

        // 6. The steward-facing grant admin (behind the Study Tool Warden).
        grantAdmin = new McpGrantAdmin(homeClient, STEWARD, registry);
    }

    @AfterEach
    void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
        if (stub != null) stub.stop(0);
    }

    private boolean callSucceeds(String agent) {
        var result = gateway.execute(agent, "zone-1", SERVICE, "search",
            Map.of("query", "wyrdsekai"));
        return result.success();
    }

    @Test
    void full_grant_allow_revoke_deny_cycle() {
        // Strict + no grant → denied (secure default; the seeded §88 rooms stay dark).
        assertThat(callSucceeds("ma")).isFalse();

        // A non-steward cannot grant.
        var denied = grantAdmin.grant("ma", "ma", SERVICE);
        assertThat(denied.get("ok")).isEqualTo(false);
        assertThat(callSucceeds("ma")).isFalse();

        // Steward grants everyone → the real HTTP round-trip now succeeds.
        var granted = grantAdmin.grant(STEWARD, "everyone", SERVICE);
        assertThat(granted.get("ok")).isEqualTo(true);
        var result = gateway.execute("ma", "zone-1", SERVICE, "search",
            Map.of("query", "wyrdsekai"));
        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data())).contains("results for: wyrdsekai");

        // Steward revokes → denied again.
        var revoked = grantAdmin.revoke(STEWARD, "everyone", SERVICE);
        assertThat(revoked.get("ok")).isEqualTo(true);
        assertThat(callSucceeds("ma")).isFalse();
    }

    @Test
    void per_agent_grant_scopes_to_that_agent() {
        assertThat(grantAdmin.grant(STEWARD, "ma", SERVICE).get("ok")).isEqualTo(true);
        assertThat(callSucceeds("ma")).isTrue();     // granted agent
        assertThat(callSucceeds("bob")).isFalse();   // ungranted agent
    }

    @Test
    void tool_warden_view_lists_services_and_grantees() {
        grantAdmin.grant(STEWARD, "ma", SERVICE);
        var services = grantAdmin.services(STEWARD);
        assertThat(services).hasSize(1);
        assertThat(services.get(0).get("id")).isEqualTo(SERVICE);
        assertThat(services.get(0).get("grantedText")).isEqualTo("ma");

        // A non-steward sees nothing.
        assertThat(grantAdmin.services("ma")).isEmpty();
    }
}
