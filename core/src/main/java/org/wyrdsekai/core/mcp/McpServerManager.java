package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Manages MCP server lifecycle: create transports, discover tools, route invocations.
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static volatile McpServerManager instance;

    private final Map<String, McpTransportHandler> handlers = new ConcurrentHashMap<>();
    private final McpToolIndex toolIndex = new McpToolIndex();
    private final McpKeyStore keyStore;

    /**
     * Optional grant-based authorization ( MCP_TOOL). When set
     * {@code invokeTool(..., callerDid)} consults the check; {@code listToolsFor}
     * filters to tools the caller holds.
     */
    private volatile McpGrantCheck grantCheck;

    public McpServerManager(McpKeyStore keyStore) {
        this.keyStore = keyStore;
        instance = this;
    }

    /** Global accessor for MCP tool discovery. */
    public static McpServerManager get() { return instance; }

    /** Attach a grant-based authorization check ( MCP_TOOL). */
    public void setGrantCheck(McpGrantCheck check) {
        this.grantCheck = check;
    }

    // Connect to an MCP server and discover its tools.
    public List<String> connect(McpServiceConfig config) throws Exception {
        var authHeader = config.requiresAuth() && keyStore != null
            ? keyStore.resolveAuth(config) : null;

        var handler = McpTransportFactory.create(config, authHeader);
        var initResult = handler.initialize();

        log.info("MCP server '{}' initialized: {} v{} (transport: {})",
            config.id(),
            initResult.serverInfo() != null ? initResult.serverInfo().name() : "unknown",
            initResult.serverInfo() != null ? initResult.serverInfo().version() : "?",
            config.transport());

        handlers.put(config.id(), handler);

        // Discover tools with pagination
        var qualifiedNames = new ArrayList<String>();
        String cursor = null;
        do {
            var toolList = handler.listTools(cursor);
            if (toolList.tools() != null) {
                for (var tool : toolList.tools()) {
                    var qn = toolIndex.register(config.id(), tool);
                    qualifiedNames.add(qn);
                    log.debug("Discovered tool: {} -> {}", qn, tool.description());
                }
            }
            cursor = toolList.nextCursor();
        } while (cursor != null);

        log.info("MCP server '{}': discovered {} tools", config.id(), qualifiedNames.size());
        return qualifiedNames;
    }

    // Invoke a tool by qualified name (unauthenticated path — backward compatible).
    public String invokeTool(String qualifiedName, Map<String, Object> arguments) throws Exception {
        return invokeTool(qualifiedName, arguments, null);
    }

    /**
     * Invoke a tool on behalf of a specific caller. When a grant check is
     * configured and {@code callerDid} is non-null, the caller must hold a
     * {@code use} grant on {@code home://{callerDid}/mcp-tool/{server}/{tool}}.
     */
    public String invokeTool(String qualifiedName, Map<String, Object> arguments,
                              String callerDid) throws Exception {
        var route = toolIndex.lookup(qualifiedName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool: " + qualifiedName));

        var check = grantCheck;
        if (check != null && callerDid != null
                && !check.canUse(callerDid, route.serverId(), route.rawToolName())) {
            throw new SecurityException("MCP tool '" + qualifiedName
                + "' denied: no grant for " + callerDid);
        }

        var handler = handlers.get(route.serverId());
        if (handler == null || !handler.isAlive()) {
            throw new IllegalStateException("MCP server '" + route.serverId() + "' not connected");
        }

        var result = handler.callTool(route.rawToolName(), arguments);
        return result.textContent();
    }

    // Disconnect a server and remove its tools.
    public void disconnect(String serverId) {
        var handler = handlers.remove(serverId);
        if (handler != null) {
            try { handler.close(); } catch (Exception e) {
                log.warn("Error closing MCP server '{}': {}", serverId, e.getMessage());
            }
        }
        toolIndex.removeServer(serverId);
    }

    // Disconnect all servers.
    public void shutdown() {
        for (var id : new ArrayList<>(handlers.keySet())) {
            disconnect(id);
        }
    }

    // Get the tool index for external queries.
    public McpToolIndex toolIndex() { return toolIndex; }

    // Check if a server is connected.
    public boolean isConnected(String serverId) {
        var handler = handlers.get(serverId);
        return handler != null && handler.isAlive();
    }

    // List connected server IDs.
    public Set<String> connectedServers() {
        return Set.copyOf(handlers.keySet());
    }
}
