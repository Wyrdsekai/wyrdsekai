package org.wyrdsekai.core.mcp;

import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Namespaced tool index for MCP servers. Qualified names prevent collisions
// when multiple servers provide tools with the same raw name.
// Format: mcp__{serverId}__{toolName}
public class McpToolIndex {

    public record ToolRoute(String serverId, String rawToolName, JsonRpcMessage.McpTool metadata) {}

    private final Map<String, ToolRoute> routes = new ConcurrentHashMap<>();

    // Register a tool from a server. Returns the qualified name.
    public String register(String serverId, JsonRpcMessage.McpTool tool) {
        var qualified = qualifyName(serverId, tool.name());
        routes.put(qualified, new ToolRoute(serverId, tool.name(), tool));
        return qualified;
    }

    // Look up a tool by qualified name.
    public Optional<ToolRoute> lookup(String qualifiedName) {
        return Optional.ofNullable(routes.get(qualifiedName));
    }

    // Remove all tools for a server.
    public void removeServer(String serverId) {
        var prefix = "mcp__" + normalize(serverId) + "__";
        routes.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // List all qualified tool names.
    public Set<String> allToolNames() {
        return Set.copyOf(routes.keySet());
    }

    // List tools for a specific server.
    public List<ToolRoute> toolsForServer(String serverId) {
        var prefix = "mcp__" + normalize(serverId) + "__";
        return routes.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    }

    // Number of registered tools.
    public int size() { return routes.size(); }

    public static String qualifyName(String serverId, String toolName) {
        return "mcp__" + normalize(serverId) + "__" + normalize(toolName);
    }

    private static String normalize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
    }
}
