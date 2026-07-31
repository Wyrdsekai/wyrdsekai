package org.wyrdsekai.scripting.api;

import java.util.Map;

/**
 * Provides MCP gateway access for room scripts via world.mcp() (§86.1).
 * Defined in scripting module to avoid circular dependency (scripting cannot reference core).
 * Core provides the implementation wrapping McpGatewayService.
 */
public interface McpGatewayProvider {

    /**
     * Execute an MCP tool call through the gateway.
     *
     * @param agentId   Agent (entity) making the request
     * @param zoneId    Zone the agent is in
     * @param serviceId MCP service to call
     * @param toolName  Tool within the service
     * @param params    Tool parameters
     * @return Result map: { success, data, error, cost, latencyMs, serviceId, toolName }
     */
    Map<String, Object> execute(String agentId, String zoneId,
                                 String serviceId, String toolName,
                                 Map<String, Object> params);

    /**
     * Check if a service is available (registered, enabled, circuit not open).
     */
    boolean isAvailable(String serviceId);

    /**
     * Get remaining budget for an agent+service combination.
     */
    int remainingBudget(String agentId, String serviceId);
}
