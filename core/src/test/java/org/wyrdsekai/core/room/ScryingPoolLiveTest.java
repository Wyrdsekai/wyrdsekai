package org.wyrdsekai.core.room;

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
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.item.VisitorItemProvider;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpGrantAdmin;
import org.wyrdsekai.core.mcp.McpGrantCheck;
import org.wyrdsekai.core.mcp.McpServiceConfig;
import org.wyrdsekai.core.mcp.McpServiceRegistry;
import org.wyrdsekai.core.mcp.transport.HttpTransportHandler;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.loader.ScriptLoader;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The full "walk into the Scrying Pool" narrative, automated end to end through
 * the REAL scripts:
 *
 * <ol>
 *   <li>An agent says "search …" in the real {@code scrying-pool.js} → world.mcp
 *       hits the real HTTP transport → a mock searxng — but strict grants are on,
 *       so it is DENIED (the pool "shows nothing").</li>
 *   <li>The steward hands out the key through the real Study "Tool Warden" item
 *       ({@code world.mcp.grant}).</li>
 *   <li>The agent searches again → the pool now reveals live results from the
 *       mock server.</li>
 * </ol>
 *
 * Nothing is stubbed but the external MCP server itself: the room script, the
 * transport, the grant model, and the Study item are all the production code.
 */
class ScryingPoolLiveTest {

    private static final Path ROOM_SCRIPTS = Files.exists(Path.of("scripts/rooms"))
        ? Path.of("scripts/rooms") : Path.of("../scripts/rooms");
    private static final String STEWARD = "did:key:steward";
    private static final String AGENT = "ma";

    private ActorTestKit testKit;
    private HttpServer stub;
    private String endpoint;
    private McpServiceRegistry registry;
    private McpGrantAdmin admin;
    private RoomScriptEngine engine;

    @TempDir Path workspace;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Files.exists(ROOM_SCRIPTS.resolve("scrying-pool.js")),
            "scrying-pool.js must be on disk");

        // Mock searxng MCP server (JSON-RPC initialize + tools/call).
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/mcp", exchange -> {
            var req = Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
            var id = req.has("id") ? req.get("id").asLong() : 1;
            var method = req.path("method").asText("");
            String result;
            if (method.equals("initialize")) {
                result = "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"mock-searxng\",\"version\":\"1.0\"}}";
            } else if (method.equals("tools/call")) {
                var q = req.path("params").path("arguments").path("query").asText("");
                result = "{\"content\":[{\"type\":\"text\",\"text\":\"1. " + q
                    + " — the encyclopedia entry\\n2. " + q + " — the news roundup\"}],"
                    + "\"isError\":false}";
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

        // Home registry + client (real grant storage).
        testKit = ActorTestKit.create("ScryingPoolLiveTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("home.db"));
        var registryActor = testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc)));
        var homeClient = new HomeClient(registryActor, testKit.system());
        RoomAuthority.grantAdmin(STEWARD);

        // searxng configured (household opt-in).
        registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig(
            "searxng", "SearXNG", "http", endpoint, "local", null, null, true));

        // Real gateway: real transport + strict steward-owned grant check.
        var gateway = new McpGatewayService(registry,
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
        RoomMcpBridge.install(gateway);

        admin = new McpGrantAdmin(homeClient, STEWARD, registry);
        McpGrantAdmin.install(admin);

        // The REAL Scrying Pool room script, wired to the gateway.
        var loader = new ScriptLoader(ROOM_SCRIPTS, null);
        engine = new RoomScriptEngine("scrying-pool", loader, null, RoomMcpBridge.get());
        engine.setCurrentEntityId(AGENT);
    }

    @AfterEach
    void tearDown() {
        if (engine != null) engine.close();
        RoomMcpBridge.install(null);
        McpGrantAdmin.install(null);
        if (testKit != null) testKit.shutdownTestKit();
        if (stub != null) stub.stop(0);
    }

    private String search(String query) {
        var emissions = engine.invokeHook("onSay", AGENT, "Ma", "search " + query);
        return emissions.stream()
            .filter(e -> "narrate".equals(e.eventType()))
            .map(e -> String.valueOf(e.data().get("text")))
            .reduce("", (a, b) -> a + "\n" + b);
    }

    private void grantViaToolWarden(String subject) {
        var executor = new ItemScriptExecutor();
        ItemWorldApiProvider stewardProvider = new VisitorItemProvider("alpha", "alpha") {
            @Override public String callerDid() { return STEWARD; }
            @Override public Map<String, Object> mcpGrantIssue(String s, String svc) {
                return admin.grant(STEWARD, s, svc);
            }
        };
        var item = StudyFurnishingKit.toolWarden();
        var out = executor.execute(item.id(), item.script(),
            Map.of("op", "grant", "agent", subject, "service", "searxng"), stewardProvider);
        assertThat(String.valueOf(out.get("text"))).contains("Key handed over");
    }

    @Test
    void denied_until_steward_grants_then_reveals_live_results() {
        // 1. Strict grants on, no key yet → the pool shows nothing.
        var before = search("wyrdsekai");
        assertThat(before).contains("shows nothing");
        assertThat(before).doesNotContain("encyclopedia entry");

        // 2. Steward hands out the key via the Study Tool Warden.
        grantViaToolWarden("everyone");

        // 3. The same search now reveals live results from the mock searxng.
        var after = search("wyrdsekai");
        assertThat(after).contains("The pool reveals");
        assertThat(after).contains("wyrdsekai — the encyclopedia entry");
    }
}
