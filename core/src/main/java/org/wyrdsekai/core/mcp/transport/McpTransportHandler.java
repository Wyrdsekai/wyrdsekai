package org.wyrdsekai.core.mcp.transport;

import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;
import java.util.Map;

// Transport abstraction for MCP server communication.
// Implementations handle the specifics of Stdio, HTTP, WebSocket, and SSE.
public interface McpTransportHandler extends AutoCloseable {
    // Initialize the MCP server connection. Returns server capabilities.
    JsonRpcMessage.InitializeResult initialize() throws Exception;

    // List available tools, with optional pagination cursor.
    JsonRpcMessage.ToolListResult listTools(String cursor) throws Exception;

    // Call a tool by name with arguments.
    JsonRpcMessage.ToolCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception;

    // Send a raw JSON-RPC request and get the response.
    JsonRpcMessage.Response sendRequest(JsonRpcMessage.Request request) throws Exception;

    // Check if the transport is still connected/alive.
    boolean isAlive();

    // Graceful shutdown.
    @Override
    void close() throws Exception;
}
