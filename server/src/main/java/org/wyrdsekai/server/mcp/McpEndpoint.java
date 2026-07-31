package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.router.JavalinDefaultRoutingApi;
import io.javalin.http.Context;
import org.apache.pekko.actor.typed.ActorSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * MCP (Model Context Protocol) endpoint (§79).
 * Streamable HTTP transport: POST /mcp (JSON-RPC 2.0).
 * Also serves the server card at /.well-known/mcp/server-card.json.
 *
 * Basic tools:
 *   - room.look — describe current room
 *   - room.say — speak in current room
 *   - room.move — move to adjacent room
 *   - agent.status — get agent vitality status
 */
public class McpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(McpEndpoint.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final ActorSystem<?> system;
    private final McpToolRegistry toolRegistry;

    public McpEndpoint(ActorSystem<?> system) {
        this.system = system;
        this.toolRegistry = new McpToolRegistry();
    }

    /** Register MCP routes on the Javalin app. */
    public void register(JavalinDefaultRoutingApi app) {
        app.post("/mcp", this::handleMcp);
        app.get("/.well-known/mcp/server-card.json", this::handleServerCard);
        log.info("MCP endpoint registered at POST /mcp");
    }

    private void handleMcp(Context ctx) {
        try {
            var body = mapper.readTree(ctx.body());
            var jsonrpc = body.path("jsonrpc").asText("");
            if (!"2.0".equals(jsonrpc)) {
                ctx.status(400).json(errorResponse(null, -32600, "Invalid Request: jsonrpc must be 2.0"));
                return;
            }

            var method = body.path("method").asText("");
            var id = body.has("id") ? body.get("id") : null;
            var params = body.has("params") ? body.get("params") : mapper.createObjectNode();

            var result = dispatch(method, params);
            if (id != null) {
                ctx.json(successResponse(id, result));
            } else {
                // Notification — no response body
                ctx.status(202);
            }
        } catch (Exception e) {
            log.warn("MCP request failed: {}", e.getMessage());
            ctx.status(400).json(errorResponse(null, -32700, "Parse error"));
        }
    }

    private JsonNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> handleInitialize(params);
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params);
            case "ping" -> mapper.createObjectNode();
            default -> errorData(-32601, "Method not found: " + method);
        };
    }

    private JsonNode handleInitialize(JsonNode params) {
        var result = mapper.createObjectNode();
        result.put("protocolVersion", "2025-03-26");
        var capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        var serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "wyrdsekai");
        serverInfo.put("version", "0.1.0");
        return result;
    }

    private JsonNode handleToolsList() {
        var result = mapper.createObjectNode();
        var tools = result.putArray("tools");
        for (var tool : toolRegistry.listTools()) {
            var t = tools.addObject();
            t.put("name", tool.name());
            t.put("description", tool.description());
            t.set("inputSchema", tool.inputSchema());
        }
        return result;
    }

    private JsonNode handleToolsCall(JsonNode params) {
        var toolName = params.path("name").asText("");
        var toolArgs = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();
        return toolRegistry.call(toolName, toolArgs);
    }

    private void handleServerCard(Context ctx) {
        ctx.contentType("application/json").result("""
            {
              "name": "wyrdsekai",
              "description": "Wyrdsekai — a distributed text-native OS built on the MUD paradigm",
              "version": "0.1.0",
              "url": "/mcp",
              "transport": {
                "type": "streamable-http"
              },
              "capabilities": {
                "tools": true
              }
            }
            """);
    }

    // --- JSON-RPC helpers ---

    private Map<String, Object> successResponse(JsonNode id, JsonNode result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private Map<String, Object> errorResponse(JsonNode id, int code, String message) {
        var error = Map.of("code", code, "message", message);
        if (id != null) {
            return Map.of("jsonrpc", "2.0", "id", id, "error", error);
        }
        return Map.of("jsonrpc", "2.0", "error", error);
    }

    private JsonNode errorData(int code, String message) {
        var node = mapper.createObjectNode();
        node.put("error_code", code);
        node.put("error_message", message);
        return node;
    }
}
