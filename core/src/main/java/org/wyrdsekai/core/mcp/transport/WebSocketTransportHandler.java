package org.wyrdsekai.core.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// MCP transport over WebSocket with JSON-RPC.
public class WebSocketTransportHandler implements McpTransportHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTransportHandler.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final String url;
    private final Map<String, String> headers;
    private final ObjectMapper mapper = Json.mapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonRpcMessage.Response>> pending = new ConcurrentHashMap<>();

    private WebSocket webSocket;
    private final StringBuilder messageBuffer = new StringBuilder();

    public WebSocketTransportHandler(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers != null ? headers : Map.of();
    }

    private void ensureConnected() throws Exception {
        if (webSocket != null) return;

        var builder = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build()
            .newWebSocketBuilder();

        headers.forEach(builder::header);

        webSocket = builder.buildAsync(URI.create(url), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                messageBuffer.append(data);
                if (last) {
                    handleMessage(messageBuffer.toString());
                    messageBuffer.setLength(0);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                log.info("MCP WebSocket closed: {} {}", statusCode, reason);
                pending.values().forEach(f -> f.completeExceptionally(
                    new IOException("WebSocket closed: " + reason)));
                pending.clear();
                return null;
            }
        }).join();

        log.info("MCP WebSocket connected to {}", url);
    }

    private void handleMessage(String text) {
        try {
            var response = mapper.readValue(text, JsonRpcMessage.Response.class);
            var future = pending.remove(response.id());
            if (future != null) {
                future.complete(response);
            }
        } catch (Exception e) {
            log.warn("Failed to parse WebSocket message: {}", e.getMessage());
        }
    }

    @Override
    public JsonRpcMessage.InitializeResult initialize() throws Exception {
        ensureConnected();
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "initialize",
            Map.of("protocolVersion", PROTOCOL_VERSION, "capabilities", Map.of(),
                "clientInfo", Map.of("name", "wyrdsekai", "version", "1.0")));
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("init failed: " + response.error().message());
        return mapper.convertValue(response.result(), JsonRpcMessage.InitializeResult.class);
    }

    @Override
    public JsonRpcMessage.ToolListResult listTools(String cursor) throws Exception {
        ensureConnected();
        var params = new HashMap<String, Object>();
        if (cursor != null) params.put("cursor", cursor);
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/list", params);
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("tools/list failed: " + response.error().message());
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolListResult.class);
    }

    @Override
    public JsonRpcMessage.ToolCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ensureConnected();
        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/call",
            Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of()));
        var response = sendRequest(request);
        if (response.isError()) throw new IOException("tools/call failed: " + response.error().message());
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolCallResult.class);
    }

    @Override
    public JsonRpcMessage.Response sendRequest(JsonRpcMessage.Request request) throws Exception {
        var future = new CompletableFuture<JsonRpcMessage.Response>();
        pending.put(request.id(), future);

        var json = mapper.writeValueAsString(request);
        webSocket.sendText(json, true).join();

        try {
            return future.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(request.id());
            throw new IOException("WebSocket response timeout for " + request.method());
        }
    }

    @Override
    public boolean isAlive() {
        return webSocket != null && !webSocket.isOutputClosed();
    }

    @Override
    public void close() throws Exception {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").join();
            webSocket = null;
        }
        pending.values().forEach(f -> f.cancel(true));
        pending.clear();
    }
}
