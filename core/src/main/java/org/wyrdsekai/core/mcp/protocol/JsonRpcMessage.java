package org.wyrdsekai.core.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

// JSON-RPC 2.0 message types for MCP protocol communication.
public final class JsonRpcMessage {
    private JsonRpcMessage() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Request(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") long id,
        @JsonProperty("method") String method,
        @JsonProperty("params") Map<String, Object> params
    ) {
        @JsonCreator public Request {}
        public static Request create(long id, String method, Map<String, Object> params) {
            return new Request("2.0", id, method, params);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(
        @JsonProperty("jsonrpc") String jsonrpc,
        @JsonProperty("id") long id,
        @JsonProperty("result") Object result,
        @JsonProperty("error") RpcError error
    ) {
        @JsonCreator public Response {}
        public boolean isError() { return error != null; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RpcError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data
    ) {
        @JsonCreator public RpcError {}
    }

    // MCP-specific result types
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitializeResult(
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("capabilities") Map<String, Object> capabilities,
        @JsonProperty("serverInfo") ServerInfo serverInfo
    ) {
        @JsonCreator public InitializeResult {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerInfo(
        @JsonProperty("name") String name,
        @JsonProperty("version") String version
    ) {
        @JsonCreator public ServerInfo {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolListResult(
        @JsonProperty("tools") List<McpTool> tools,
        @JsonProperty("nextCursor") String nextCursor
    ) {
        @JsonCreator public ToolListResult {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpTool(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("inputSchema") Map<String, Object> inputSchema
    ) {
        @JsonCreator public McpTool {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCallResult(
        @JsonProperty("content") List<ContentBlock> content,
        @JsonProperty("isError") boolean isError
    ) {
        @JsonCreator public ToolCallResult {}
        public String textContent() {
            if (content == null || content.isEmpty()) return "";
            return content.stream()
                .filter(c -> "text".equals(c.type()))
                .map(ContentBlock::text)
                .reduce("", (a, b) -> a + b);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text
    ) {
        @JsonCreator public ContentBlock {}
    }
}
