package org.wyrdsekai.core.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * A tool answered with an error: the call reached the service and the service said no.
 *
 * <p>Before this, every transport turned a JSON-RPC error into a plain {@link IOException}
 * carrying only the message, so a caller could not tell one refusal from another. A service
 * that gives its errors a stable code (ResearchZosho 0.5.0: {@code -32006} with
 * {@code data.code = "declined"}, {@code -32007} with {@code "confirm"}) needs the code kept.
 * The same answer over the service's REST door comes as HTTP 422 (HTTP 429 for
 * {@code budget_exceeded}) with {@code {"error": {"code", "message"}}} and is read into the
 * same shape.
 *
 * <p>The error's {@code data} object is kept whole ({@link #data}): ResearchZosho 0.5.1 puts the
 * helplines of a {@code confirm} there ({@code language}, {@code helplines}), and over its REST
 * door beside {@code code} and {@code message} in {@code error}.
 *
 * <p>It is still an IOException, so callers that catch IOException keep working, and
 * {@link #getMessage()} reads as before ({@code tools/call failed for '<tool>': <message>}).
 * It is never an outage: the request arrived, so it must never be retried.
 */
public class McpToolException extends IOException {

    /** ResearchZosho: a model declined a step of the work. */
    public static final int DECLINED = -32006;
    /** ResearchZosho: the question needs a person's yes before the library researches it. */
    public static final int CONFIRM = -32007;
    /** ResearchZosho before 0.1.6: the patron's daily research budget is used (HTTP 429 on its REST door). */
    public static final int BUDGET_EXCEEDED = -32005;

    private final String tool;
    private final int rpcCode;
    private final String dataCode;
    private final String toolMessage;
    private final int httpStatus;
    /** The error's data object as the service sent it, or null. */
    private final transient JsonNode data;

    public McpToolException(String tool, int rpcCode, String dataCode, String toolMessage, int httpStatus) {
        this(tool, rpcCode, dataCode, toolMessage, httpStatus, null);
    }

    public McpToolException(String tool, int rpcCode, String dataCode, String toolMessage, int httpStatus,
                            JsonNode data) {
        super("tools/call failed for '" + tool + "': " + (toolMessage == null ? "" : toolMessage));
        this.tool = tool;
        this.rpcCode = rpcCode;
        this.dataCode = dataCode == null || dataCode.isBlank() ? null : dataCode.strip();
        this.toolMessage = toolMessage == null ? "" : toolMessage;
        this.httpStatus = httpStatus;
        this.data = data != null && data.isObject() ? data.deepCopy() : null;
    }

    /** The tool that answered. */
    public String tool() { return tool; }
    /** The JSON-RPC error code, or 0 when the answer came from a REST door. */
    public int rpcCode() { return rpcCode; }
    /** {@code error.data.code} over JSON-RPC, {@code error.code} over REST; null when none. */
    public String dataCode() { return dataCode; }
    /** The service's own message, as it sent it. */
    public String toolMessage() { return toolMessage; }
    /** The HTTP status the answer came with, or 0 over stdio and websocket. */
    public int httpStatus() { return httpStatus; }
    /** The error's data object (a copy), or null when it had none. */
    public JsonNode data() { return data == null ? null : data.deepCopy(); }

    public boolean isConfirm() {
        return "confirm".equals(dataCode) || (dataCode == null && rpcCode == CONFIRM);
    }

    public boolean isDeclined() {
        return "declined".equals(dataCode) || (dataCode == null && rpcCode == DECLINED);
    }

    public boolean isBudgetExceeded() {
        return "budget_exceeded".equals(dataCode) || (dataCode == null && rpcCode == BUDGET_EXCEEDED);
    }

    /** A JSON-RPC error answer to a {@code tools/call}. */
    public static McpToolException fromRpcError(String tool, JsonRpcMessage.RpcError error) {
        return fromRpcError(tool, error, 0);
    }

    static McpToolException fromRpcError(String tool, JsonRpcMessage.RpcError error, int httpStatus) {
        if (error == null) return new McpToolException(tool, 0, null, "", httpStatus);
        String code = null;
        if (error.data() instanceof Map<?, ?> m && m.get("code") != null) code = String.valueOf(m.get("code"));
        JsonNode data = null;
        if (error.data() != null) {
            try {
                data = Json.mapper().valueToTree(error.data());
            } catch (IllegalArgumentException e) {
                data = null;
            }
        }
        return new McpToolException(tool, error.code(), code, error.message(), httpStatus, data);
    }

    /**
     * An HTTP error body read as a tool's answer, or null when it is not one. Two shapes are
     * read: the REST door's {@code {"error": {"code": "declined", "message": "…"}}}, and a
     * JSON-RPC envelope whose {@code error} carries a numeric code.
     */
    public static McpToolException fromHttpBody(String tool, int status, String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = Json.mapper().readTree(body);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        var err = root.get("error");
        if (err == null || !err.isObject()) return null;
        var code = err.get("code");
        var message = err.path("message").asText("");
        if (code != null && code.isTextual()) {
            var c = code.asText().strip().toLowerCase(Locale.ROOT);
            int rpc = switch (c) {
                case "declined" -> DECLINED;
                case "confirm" -> CONFIRM;
                case "budget_exceeded" -> BUDGET_EXCEEDED;
                default -> 0;
            };
            // The REST door puts what the data would carry beside the code and the message.
            var data = ((ObjectNode) err).deepCopy();
            data.remove("message");
            return new McpToolException(tool, rpc, c, message, status, data);
        }
        if (code != null && code.isIntegralNumber()) {
            var dataNode = err.path("data");
            var dataCode = dataNode.path("code");
            return new McpToolException(tool, code.asInt(), dataCode.isTextual() ? dataCode.asText() : null,
                message, status, dataNode.isObject() ? dataNode : null);
        }
        return null;
    }

    /** The tool name a {@code tools/call} request names, or null for any other request. */
    static String toolOf(JsonRpcMessage.Request request) {
        if (request == null || !"tools/call".equals(request.method()) || request.params() == null) return null;
        var name = request.params().get("name");
        return name == null ? null : String.valueOf(name);
    }
}
