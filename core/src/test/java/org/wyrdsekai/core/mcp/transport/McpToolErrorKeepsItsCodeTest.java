package org.wyrdsekai.core.mcp.transport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.McpCircuitBreaker;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpRateLimiter;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpServiceConfig;
import org.wyrdsekai.core.mcp.McpServiceRegistry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A tool's error answer keeps its identity through the transports (ResearchZosho 0.5.0).
 *
 * <p>The library answers a flagged question with JSON-RPC {@code -32007} / {@code data.code
 * "confirm"} and a model's refusal with {@code -32006} / {@code "declined"}; its {@code /rpc}
 * door sends both with HTTP 200, and its REST door with HTTP 422. Every transport used to turn
 * them into {@code IOException("tools/call failed for '…': <message>")}, dropping the code and
 * the data, so nothing downstream could tell a person's-yes question from a decline.
 */
class McpToolErrorKeepsItsCodeTest {

    static final String CONFIRM_MESSAGE = "If you are in the US or Canada, call or text 988. "
        + "Show this to the person and ask them whether the library should research the question; "
        + "send the question again with allow [\"self-harm\"] only if the person says yes.";

    private HttpServer stub;
    private String endpoint;
    /** What /rpc answers a tools/call with: status and body. */
    private volatile int callStatus = 200;
    private volatile String callBody;
    private final AtomicInteger toolCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/rpc", exchange -> {
            var req = Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
            var id = req.path("id").asLong(1);
            int status = 200;
            String body;
            switch (req.path("method").asText("")) {
                case "initialize" -> body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2024-11-05\","
                    + "\"capabilities\":{},\"serverInfo\":{\"name\":\"researchzosho\",\"version\":\"0.5.0\"}}}";
                case "tools/list" -> body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":["
                    + "{\"name\":\"library_research\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}";
                default -> {
                    toolCalls.incrementAndGet();
                    status = callStatus;
                    body = callBody.replace("\"id\":0", "\"id\":" + id);
                }
            }
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        stub.start();
        endpoint = "http://127.0.0.1:" + stub.getAddress().getPort() + "/rpc";
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
    }

    private static String rpcError(int code, String message, String dataCode) throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":" + code + ",\"message\":"
            + Json.mapper().writeValueAsString(message) + ",\"data\":{\"code\":\"" + dataCode + "\"}}}";
    }

    @Test
    void a_json_rpc_error_sent_with_http_200_keeps_its_code_and_data() throws Exception {
        callBody = rpcError(-32007, CONFIRM_MESSAGE, "confirm");
        var http = new HttpTransportHandler(endpoint, Map.of(), null);

        var e = catchThrowableOfType(McpToolException.class,
            () -> http.callTool("library_research", Map.of("question", "q")));

        assertThat(e).isInstanceOf(IOException.class);   // callers that catch IOException keep working
        assertThat(e.rpcCode()).isEqualTo(-32007);
        assertThat(e.dataCode()).isEqualTo("confirm");
        assertThat(e.isConfirm()).isTrue();
        assertThat(e.isDeclined()).isFalse();
        assertThat(e.tool()).isEqualTo("library_research");
        assertThat(e.toolMessage()).isEqualTo(CONFIRM_MESSAGE);
        assertThat(e.getMessage()).startsWith("tools/call failed for 'library_research': ");
    }

    @Test
    void a_rest_422_error_body_is_read_the_same_way() throws Exception {
        callStatus = 422;
        callBody = "{\"error\":{\"code\":\"declined\",\"message\":\"model m1 declined the explain step: I can't help with that.\"}}";
        var http = new HttpTransportHandler(endpoint, Map.of(), null);

        var e = catchThrowableOfType(McpToolException.class,
            () -> http.callTool("library_explain", Map.of("id", "F-1")));

        assertThat(e.isDeclined()).isTrue();
        assertThat(e.dataCode()).isEqualTo("declined");
        assertThat(e.rpcCode()).isEqualTo(McpToolException.DECLINED);
        assertThat(e.httpStatus()).isEqualTo(422);
        assertThat(e.toolMessage()).contains("model m1 declined the explain step");
    }

    @Test
    void an_older_librarys_budget_exceeded_is_read_over_json_rpc_and_over_http_429() throws Exception {
        callBody = rpcError(-32005, "This patron's research budget for today is spent.", "budget_exceeded");
        var http = new HttpTransportHandler(endpoint, Map.of(), null);
        var rpc = catchThrowableOfType(McpToolException.class,
            () -> http.callTool("library_research", Map.of("question", "q")));
        assertThat(rpc.isBudgetExceeded()).isTrue();
        assertThat(rpc.isConfirm()).isFalse();
        assertThat(rpc.toolMessage()).isEqualTo("This patron's research budget for today is spent.");

        callStatus = 429;
        callBody = "{\"error\":{\"code\":\"budget_exceeded\",\"message\":\"This patron's research budget for today is spent.\"}}";
        var rest = catchThrowableOfType(McpToolException.class,
            () -> http.callTool("library_research", Map.of("question", "q")));
        assertThat(rest.isBudgetExceeded()).isTrue();
        assertThat(rest.rpcCode()).isEqualTo(McpToolException.BUDGET_EXCEEDED);
        assertThat(rest.httpStatus()).isEqualTo(429);

        // A 429 that is not a tool's answer (a proxy's, say) stays an outage.
        callBody = "Too Many Requests";
        assertThatThrownBy(() -> http.callTool("library_research", Map.of()))
            .isInstanceOf(IOException.class)
            .isNotInstanceOf(McpToolException.class)
            .hasMessageStartingWith("HTTP 429");
    }

    @Test
    void any_other_http_failure_stays_a_plain_io_exception() {
        callStatus = 500;
        callBody = "{\"error\":{\"code\":\"declined\",\"message\":\"not a tool answer on a 500\"}}";
        var http = new HttpTransportHandler(endpoint, Map.of(), null);

        assertThatThrownBy(() -> http.callTool("library_research", Map.of()))
            .isInstanceOf(IOException.class)
            .isNotInstanceOf(McpToolException.class)
            .hasMessageStartingWith("HTTP 500");
    }

    @Test
    void stdio_keeps_the_code_and_data_too() throws Exception {
        var answer = rpcError(-32006, "model m2 declined the describe step", "declined").replace("\"id\":0", "\"id\":1");
        var script = Files.createTempFile("mcp-declines", ".sh");
        try {
            Files.writeString(script, "read line\nprintf '%s\\n' '" + answer + "'\n");
            var stdio = new StdioTransportHandler("/bin/sh", List.of(script.toString()), Map.of());
            try {
                var e = catchThrowableOfType(McpToolException.class,
                    () -> stdio.callTool("library_survey", Map.of()));
                assertThat(e.rpcCode()).isEqualTo(-32006);
                assertThat(e.dataCode()).isEqualTo("declined");
                assertThat(e.isDeclined()).isTrue();
                assertThat(e.toolMessage()).isEqualTo("model m2 declined the describe step");
            } finally {
                stdio.close();
            }
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void the_answer_survives_the_gateway_and_is_not_counted_as_an_outage() throws Exception {
        callBody = rpcError(-32007, CONFIRM_MESSAGE, "confirm");
        var registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig("researchzosho", "ResearchZosho", "http", endpoint, "local", null, null, true));
        var breaker = new McpCircuitBreaker();
        var gateway = new McpGatewayService(registry, new McpRateLimiter(100, 1000, 1000), breaker,
            (ep, tool, params, auth) -> { throw new AssertionError("a connected service is called on its connection"); });
        var manager = new McpServerManager(null);
        manager.connect(registry.get("researchzosho").orElseThrow());
        manager.setGateway(gateway);
        gateway.useConnections(manager);
        try {
            for (int i = 0; i < 8; i++) {
                int n = i;
                var e = catchThrowableOfType(McpToolException.class, () -> manager.invokeTool(
                    "mcp__researchzosho__library_research", Map.of("question", "q" + n), "did:companion"));
                assertThat(e.isConfirm()).isTrue();
            }
            // Eight answers in a row: the librarian is up, and its circuit stays closed.
            assertThat(breaker.check("researchzosho")).isNull();
            assertThat(gateway.isAvailable("researchzosho")).isTrue();
            assertThat(toolCalls).hasValue(8);
        } finally {
            manager.shutdown();
        }
    }
}
