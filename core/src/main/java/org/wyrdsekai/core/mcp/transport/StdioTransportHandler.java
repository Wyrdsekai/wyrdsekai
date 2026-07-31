package org.wyrdsekai.core.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.mcp.protocol.JsonRpcMessage;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// MCP transport over stdin/stdout of a child process.
// The primary transport for most MCP servers in the ecosystem.
public class StdioTransportHandler implements McpTransportHandler {

    private static final Logger log = LoggerFactory.getLogger(StdioTransportHandler.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final ObjectMapper mapper = Json.mapper();
    private final AtomicLong nextId = new AtomicLong(1);

    private Process process;
    private BufferedWriter stdin;
    private BufferedReader stdout;

    public StdioTransportHandler(String command, List<String> args, Map<String, String> env) {
        this.command = command;
        this.args = args != null ? args : List.of();
        this.env = env != null ? env : Map.of();
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) return;

        var cmdList = new ArrayList<String>();
        cmdList.add(command);
        cmdList.addAll(args);

        var pb = new ProcessBuilder(cmdList);
        pb.redirectErrorStream(false);
        env.forEach((k, v) -> pb.environment().put(k, v));

        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));

        log.info("MCP stdio process started: {} (PID {})", command, process.pid());
    }

    @Override
    public JsonRpcMessage.InitializeResult initialize() throws Exception {
        ensureStarted();

        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "initialize",
            Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "wyrdsekai", "version", "1.0")
            ));

        var response = sendRequest(request);
        if (response.isError()) {
            throw new IOException("MCP initialize failed: " + response.error().message());
        }

        // Send initialized notification (no response expected)
        var notification = Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
        var notifJson = mapper.writeValueAsString(notification);
        stdin.write(notifJson);
        stdin.newLine();
        stdin.flush();

        return mapper.convertValue(response.result(), JsonRpcMessage.InitializeResult.class);
    }

    @Override
    public JsonRpcMessage.ToolListResult listTools(String cursor) throws Exception {
        ensureStarted();

        var params = new HashMap<String, Object>();
        if (cursor != null) params.put("cursor", cursor);

        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/list", params);
        var response = sendRequest(request);

        if (response.isError()) {
            throw new IOException("tools/list failed: " + response.error().message());
        }
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolListResult.class);
    }

    @Override
    public JsonRpcMessage.ToolCallResult callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ensureStarted();

        var request = JsonRpcMessage.Request.create(nextId.getAndIncrement(), "tools/call",
            Map.of("name", toolName, "arguments", arguments != null ? arguments : Map.of()));

        var response = sendRequest(request);

        if (response.isError()) {
            throw new IOException("tools/call failed for '" + toolName + "': " + response.error().message());
        }
        return mapper.convertValue(response.result(), JsonRpcMessage.ToolCallResult.class);
    }

    @Override
    public JsonRpcMessage.Response sendRequest(JsonRpcMessage.Request request) throws Exception {
        var json = mapper.writeValueAsString(request);
        stdin.write(json);
        stdin.newLine();
        stdin.flush();

        var responseLine = stdout.readLine();
        if (responseLine == null) {
            throw new IOException("MCP process closed stdout unexpectedly");
        }
        return mapper.readValue(responseLine, JsonRpcMessage.Response.class);
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public void close() throws Exception {
        if (process != null && process.isAlive()) {
            log.info("Shutting down MCP stdio process: {} (PID {})", command, process.pid());
            try {
                stdin.close();
            } catch (IOException ignored) {}
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
