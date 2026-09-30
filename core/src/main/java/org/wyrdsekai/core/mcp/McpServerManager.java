package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Manages MCP server lifecycle: create transports, discover tools, route invocations.
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);
    private static volatile McpServerManager instance;

    private final Map<String, McpTransportHandler> handlers = new ConcurrentHashMap<>();
    /** Services whose calls carry a credential from the key store — the far side proves WHO we are. */
    private final java.util.Set<String> authenticated = ConcurrentHashMap.newKeySet();
    private final McpToolIndex toolIndex = new McpToolIndex();
    private final McpKeyStore keyStore;

    /** The one door every call passes (the process-wide gateway when unset). */
    private volatile McpGatewayService gateway;

    public McpServerManager(McpKeyStore keyStore) {
        this.keyStore = keyStore;
        instance = this;
    }

    /** Global accessor for MCP tool discovery. */
    public static McpServerManager get() { return instance; }

    /**
     * Whether calls to {@code serviceId} carry a credential that names the caller. A library
     * protocol daemon resolves a bearer token to ONE patron and refuses a body that asserts a
     * different did — so a patron speaking through such a service must not assert one.
     */
    public boolean isAuthenticated(String serviceId) { return serviceId != null && authenticated.contains(serviceId); }

    /** The gateway invokeTool routes through; tests set it, the server installs the shared one. */
    public void setGateway(McpGatewayService gateway) {
        this.gateway = gateway;
    }

    // Connect to an MCP server and discover its tools.
    public List<String> connect(McpServiceConfig config) throws Exception {
        var authHeader = config.requiresAuth() && keyStore != null
            ? keyStore.resolveAuth(config) : null;

        var handler = McpTransportFactory.create(config, authHeader);
        var initResult = handler.initialize();
        if (authHeader != null && !authHeader.isBlank()) authenticated.add(config.id()); else authenticated.remove(config.id());

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

    /**
     * Invoke a tool on behalf of a caller (the companion, or the person whose item it
     * is). The call goes through {@link McpGatewayService}: grants, circuit breaker,
     * rate limit, spend cap, output quarantine and cost, the same door room scripts
     * use. No gateway, no call.
     */
    public String invokeTool(String qualifiedName, Map<String, Object> arguments,
                              String callerDid) throws Exception {
        var route = toolIndex.lookup(qualifiedName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool: " + qualifiedName));
        var door = gateway != null ? gateway : McpGatewayService.shared();
        if (door == null) {
            throw new IllegalStateException("MCP gateway not available");
        }
        return door.call(callerDid, zone(), route.serverId(), route.rawToolName(),
            arguments == null ? Map.of() : arguments);
    }

    /**
     * A person's own yes to a {@code library_research} the librarian asked them about: the
     * same door as {@link #invokeTool}, the one call that may carry {@code allow}
     * ({@link McpGatewayService#callWithPersonsYes}). {@code personId} is the person saying yes.
     */
    public String invokeWithPersonsYes(String qualifiedName, Map<String, Object> arguments,
                                       String personId) throws Exception {
        var route = toolIndex.lookup(qualifiedName)
            .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool: " + qualifiedName));
        var door = gateway != null ? gateway : McpGatewayService.shared();
        if (door == null) {
            throw new IllegalStateException("MCP gateway not available");
        }
        return door.callWithPersonsYes(personId, zone(), route.serverId(), route.rawToolName(),
            arguments == null ? Map.of() : arguments);
    }

    /** Call a tool on a service's live connection. Only the gateway calls this, after its checks. */
    McpGatewayService.Reply callConnected(String serverId, String rawToolName,
                                          Map<String, Object> arguments) throws Exception {
        var handler = handlers.get(serverId);
        if (handler == null || !handler.isAlive()) {
            throw new IllegalStateException("MCP server '" + serverId + "' not connected");
        }
        var result = handler.callTool(rawToolName, arguments);
        return new McpGatewayService.Reply(result.textContent(), result.reportedCost());
    }

    private static String zone() {
        try {
            var z = WyrdConfig.get().zoneId();
            return z == null || z.isBlank() ? "local" : z;
        } catch (RuntimeException e) {
            return "local";
        }
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
