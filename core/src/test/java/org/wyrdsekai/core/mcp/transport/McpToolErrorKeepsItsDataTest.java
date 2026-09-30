package org.wyrdsekai.core.mcp.transport;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ResearchZosho 0.5.1 puts the helplines of a {@code confirm} in the error's data
 * ({@code language}, {@code helplines}); over its REST door they sit beside the code and the
 * message. The whole data object is kept, not only its code.
 */
class McpToolErrorKeepsItsDataTest {

    static final String HELPLINES = "\"language\":\"es\",\"helplines\":[{\"countries\":[\"ES\"],\"name\":\"Línea 024\","
        + "\"contact\":\"call 024\",\"hours\":\"any hour\",\"free\":true,\"url\":\"https://www.sanidad.gob.es/linea024/home.htm\","
        + "\"text\":\"En España: llama al 024, gratis y confidencial, a cualquier hora.\"}]";

    private HttpServer stub;
    private String endpoint;
    private volatile int callStatus = 200;
    private volatile String callBody;

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
                    + "\"capabilities\":{},\"serverInfo\":{\"name\":\"researchzosho\",\"version\":\"0.5.1\"}}}";
                case "tools/list" -> body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[]}}";
                default -> {
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

    @Test
    void a_json_rpc_confirm_keeps_its_helplines() {
        callBody = "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32007,\"message\":\"Llama al 024.\","
            + "\"data\":{\"code\":\"confirm\"," + HELPLINES + "}}}";
        var e = catchThrowableOfType(McpToolException.class,
            () -> new HttpTransportHandler(endpoint, Map.of(), null).callTool("library_research", Map.of("question", "q")));

        assertThat(e.isConfirm()).isTrue();
        var data = e.data();
        assertThat(data.path("language").asText()).isEqualTo("es");
        assertThat(data.path("helplines").get(0).path("text").asText()).startsWith("En España: llama al 024");
        assertThat(data.path("helplines").get(0).path("countries").get(0).asText()).isEqualTo("ES");
        // A copy: what a caller does with it does not change the exception.
        ((ObjectNode) data).remove("helplines");
        assertThat(e.data().has("helplines")).isTrue();
    }

    @Test
    void a_rest_confirm_keeps_what_sits_beside_its_code() {
        callStatus = 422;
        callBody = "{\"error\":{\"code\":\"confirm\",\"message\":\"Llama al 024.\"," + HELPLINES + "}}";
        var e = catchThrowableOfType(McpToolException.class,
            () -> new HttpTransportHandler(endpoint, Map.of(), null).callTool("library_research", Map.of("question", "q")));

        assertThat(e.isConfirm()).isTrue();
        assertThat(e.data().path("code").asText()).isEqualTo("confirm");
        assertThat(e.data().path("language").asText()).isEqualTo("es");
        assertThat(e.data().path("helplines")).hasSize(1);
        assertThat(e.data().has("message")).isFalse();
    }

    @Test
    void an_error_with_no_data_has_none() {
        callBody = "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32602,\"message\":\"bad args\"}}";
        var e = catchThrowableOfType(McpToolException.class,
            () -> new HttpTransportHandler(endpoint, Map.of(), null).callTool("library_research", Map.of("question", "q")));
        assertThat(e.data()).isNull();
        assertThat(new McpToolException("t", 0, null, "m", 0).data()).isNull();
    }
}
