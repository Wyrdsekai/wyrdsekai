package org.wyrdsekai.core.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

// MCP transport over Server-Sent Events.
// Uses HTTP POST for requests and SSE for streaming responses.
// Simplified implementation: POST JSON-RPC, parse SSE data lines.
public class SseTransportHandler implements McpTransportHandler {

    private static final Logger log = LoggerFactory.getLogger(SseTransportHandler.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String url;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final ObjectMapper mapper = Json.mapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private boolean initialized = false;

    public SseTransportHandler(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers != null ? headers : Map.of();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public JsonRpcMessage.InitializeResult initialize() throws Exception {
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "initialize",
            Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "wyrdsekai", "version", "1.0")));
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("init failed: " + response.error().message());
        initialized = true;
        return mapper.convertValue(response.result(), JsonRpcMessage.InitializeResult.class);
    }

    @Override
    public JsonRpcMessage.ToolListResult listTools(String cursor) throws Exception {
        var params = new HashMap<String, Object>();
        if (cursor != null) params.put("cursor", cursor);
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/list", params);
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("tools/list failed: " + response.error().message());
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolListResult.class);
    }

    @Override
    public JsonRpcMessage.ToolCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/call",
            Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of()));
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("tools/call failed: " + response.error().message());
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolCallResult.class);
    }

    @Override
    public JsonRpcMessage.Response sendRequest(JsonRpcMessage.Request request) throws Exception {
        var body = mapper.writeValueAsString(request);

        var reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream");
        headers.forEach(reqBuilder::header);

        var httpReq = reqBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var httpResp = client.send(httpReq, HttpResponse.BodyHandlers.ofString());

        // Parse SSE response -- extract data lines
        var responseBody = httpResp.body();
        var dataBuilder = new StringBuilder();
        for (var line : responseBody.split("\n")) {
            if (line.startsWith("data: ")) {
                dataBuilder.append(line.substring(6));
            }
        }

        var data = dataBuilder.toString().trim();
        if (data.isEmpty()) {
            // Fallback: treat entire body as JSON-RPC response (non-SSE server)
            data = responseBody;
        }
        return mapper.readValue(data, JsonRpcMessage.Response.class);
    }

    @Override
    public boolean isAlive() { return initialized; }

    @Override
    public void close() { initialized = false; }
}
