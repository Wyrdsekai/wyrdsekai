package org.wyrdsekai.core.mcp.transport;

import org.wyrdsekai.core.mcp.McpServiceConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

// Factory for creating transport handlers based on service configuration.
public final class McpTransportFactory {
    private McpTransportFactory() {}

    // Create a transport handler for the given service config.
    public static McpTransportHandler create(McpServiceConfig config, String authHeader) {
        var transport = config.transport() != null ? config.transport().toLowerCase() : "http";
        return switch (transport) {
            case "stdio" -> createStdio(config);
            case "http", "https" -> new HttpTransportHandler(config.endpoint(),
                Map.of(), authHeader);
            case "websocket", "ws", "wss" -> new WebSocketTransportHandler(
                config.endpoint(), Map.of());
            case "sse" -> new SseTransportHandler(config.endpoint(), Map.of());
            default -> {
                // Legacy fallback: treat as HTTP endpoint (backward compat)
                yield new HttpTransportHandler(config.endpoint(), Map.of(), authHeader);
            }
        };
    }

    private static StdioTransportHandler createStdio(McpServiceConfig config) {
        // endpoint field contains "command arg1 arg2" for stdio transport
        var parts = config.endpoint().split("\\s+");
        var command = parts[0];
        var args = parts.length > 1
            ? List.of(Arrays.copyOfRange(parts, 1, parts.length))
            : List.<String>of();
        return new StdioTransportHandler(command, args, Map.of());
    }
}
