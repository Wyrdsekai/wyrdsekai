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
import java.util.Optional;

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

    /** Names who a request proves it is, or empty when it proves no one. */
    @FunctionalInterface
    public interface CallerResolver {
        Optional<McpToolRegistry.Caller> resolve(Context ctx);
    }

    private final ActorSystem<?> system;
    private final McpToolRegistry toolRegistry;
    /** Null: the door asks no one who they are. Set: a request that proves no one is refused. */
    private final CallerResolver callers;

    public McpEndpoint(ActorSystem<?> system) {
        this(system, new McpToolRegistry(), "/mcp");
    }

    /** A door serving a specific registry at a specific path (e.g. the library at /mcp/library). */
    public McpEndpoint(ActorSystem<?> system, McpToolRegistry registry, String path) {
        this(system, registry, path, null);
    }

    /** A door that serves only callers {@code callers} can name. */
    public McpEndpoint(ActorSystem<?> system, McpToolRegistry registry, String path,
                       CallerResolver callers) {
        this.system = system;
        this.toolRegistry = registry;
        this.path = path == null || path.isBlank() ? "/mcp" : path;
        this.callers = callers;
    }

    private final String path;

    /** The tool registry this door serves — so other parts of the server can add tools. */
    public McpToolRegistry toolRegistry() { return toolRegistry; }

    /** Register MCP routes on the Javalin app. */
    public void register(JavalinDefaultRoutingApi app) {
        app.post(path, this::handleMcp);
        if ("/mcp".equals(path)) app.get("/.well-known/mcp/server-card.json", this::handleServerCard);
        log.info("MCP endpoint registered at POST {}", path);
    }

    public String path() { return path; }

    private void handleMcp(Context ctx) {
        McpToolRegistry.Caller caller = null;
        if (callers != null) {
            try {
                caller = callers.resolve(ctx).orElse(null);
            } catch (RuntimeException e) {
                log.warn("MCP caller check failed on {}: {}", path, e.toString());
            }
            if (caller == null) {
                ctx.status(401).header("WWW-Authenticate", "Bearer")
                    .json(errorResponse(null, -32001, "This door needs a household login session or a "
                        + "library reader token (Authorization: Bearer ...)."));
                return;
            }
        }
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

            var result = dispatch(method, params, caller);
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

    private JsonNode dispatch(String method, JsonNode params, McpToolRegistry.Caller caller) {
        return switch (method) {
            case "initialize" -> handleInitialize(params);
            case "tools/list" -> handleToolsList();
            case "tools/call" -> handleToolsCall(params, caller);
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

    private JsonNode handleToolsCall(JsonNode params, McpToolRegistry.Caller caller) {
        var toolName = params.path("name").asText("");
        var toolArgs = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();
        return toolRegistry.call(toolName, toolArgs, caller);
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
