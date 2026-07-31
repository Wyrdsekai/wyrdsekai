package org.wyrdsekai.core.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Unified result from an MCP gateway call.
 * Consumed by room scripts via world.mcp().
 *
 * @param success   Whether the call succeeded
 * @param data      Result data (JSON string or narrative text)
 * @param error     Error message if failed
 * @param cost      Cost in tokens/credits (null if free/local)
 * @param latencyMs How long the call took
 * @param serviceId Which MCP service handled this
 * @param toolName  Which tool was called
 */
public record McpResult(
    @JsonProperty("success") boolean success,
    @JsonProperty("data") String data,
    @JsonProperty("error") String error,
    @JsonProperty("cost") Double cost,
    @JsonProperty("latencyMs") long latencyMs,
    @JsonProperty("serviceId") String serviceId,
    @JsonProperty("toolName") String toolName
) {
    @JsonCreator
    public McpResult {}

    /** Create a successful result. */
    public static McpResult ok(String data, String serviceId, String toolName,
                                long latencyMs, Double cost) {
        return new McpResult(true, data, null, cost, latencyMs, serviceId, toolName);
    }

    /** Create a failed result. */
    public static McpResult error(String error, String serviceId, String toolName,
                                   long latencyMs) {
        return new McpResult(false, null, error, null, latencyMs, serviceId, toolName);
    }

    /** Create a rate-limited result with narrative feedback. */
    public static McpResult rateLimited(String narrative, String serviceId, String toolName) {
        return new McpResult(false, null, narrative, null, 0, serviceId, toolName);
    }

    /** Create a circuit-breaker-open result. */
    public static McpResult circuitOpen(String narrative, String serviceId, String toolName) {
        return new McpResult(false, null, narrative, null, 0, serviceId, toolName);
    }
}
