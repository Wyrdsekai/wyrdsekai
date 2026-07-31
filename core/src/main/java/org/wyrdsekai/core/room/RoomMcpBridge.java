package org.wyrdsekai.core.room;

import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.scripting.api.McpGatewayProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Bridges room scripts' {@code world.mcp()} to the process-wide
 * {@link McpGatewayService} (W1).
 *
 * <p>Until 2026-07-11, {@link RoomScriptEngine} accepted an
 * {@link McpGatewayProvider} but nothing production-side ever passed one —
 * every {@code world.mcp()} call in every room answered "MCP gateway not
 * available". Server startup calls {@link #install} once the gateway exists;
 * RoomScriptEngine falls back to {@link #get()} when its constructor wasn't
 * handed a provider explicitly.</p>
 */
public final class RoomMcpBridge implements McpGatewayProvider {

    private static volatile RoomMcpBridge instance;

    /** Install the process-wide bridge (server startup, after gateway creation). */
    public static void install(McpGatewayService gateway) {
        instance = gateway == null ? null : new RoomMcpBridge(gateway);
    }

    /** The installed bridge, or null before server startup wires it. */
    public static RoomMcpBridge get() {
        return instance;
    }

    private final McpGatewayService gateway;

    private RoomMcpBridge(McpGatewayService gateway) {
        this.gateway = gateway;
    }

    @Override
    public Map<String, Object> execute(String agentId, String zoneId,
                                       String serviceId, String toolName,
                                       Map<String, Object> params) {
        var result = gateway.execute(agentId, zoneId, serviceId, toolName,
            params == null ? Map.of() : params);
        // GraalJS-friendly shape: no nulls (Map values must be host-readable),
        // empty string is falsy in JS so `result.error ? ...` still works.
        var out = new HashMap<String, Object>();
        out.put("success", result.success());
        out.put("data", result.data() == null ? "" : result.data());
        out.put("error", result.error() == null ? "" : result.error());
        out.put("cost", result.cost() == null ? 0.0 : result.cost());
        out.put("latencyMs", result.latencyMs());
        out.put("serviceId", result.serviceId() == null ? "" : result.serviceId());
        out.put("toolName", result.toolName() == null ? "" : result.toolName());
        return out;
    }

    @Override
    public boolean isAvailable(String serviceId) {
        return gateway.isAvailable(serviceId);
    }

    @Override
    public int remainingBudget(String agentId, String serviceId) {
        return gateway.remainingBudget(agentId, serviceId);
    }
}
