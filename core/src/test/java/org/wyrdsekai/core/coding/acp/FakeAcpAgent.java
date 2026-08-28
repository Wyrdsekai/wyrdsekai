package org.wyrdsekai.core.coding.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A scripted ACP v1 agent for fixture tests: reads newline-delimited
 * JSON-RPC from the client, answers per a small script, and records what
 * it saw. Runs on a virtual thread over piped streams — no subprocess,
 * no GPU, no network.
 */
final class FakeAcpAgent implements Runnable {

    private static final ObjectMapper M = new ObjectMapper();

    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    volatile String permissionAnswerSeen;
    /** What the permission request claims the tool call is about. */
    volatile String permissionTitle = "edit files";
    volatile String fsCallErrorSeen;

    private final BufferedReader in;
    private final BufferedWriter out;
    private final int echoVersion;
    private final boolean requestPermissionMidTurn;
    private final boolean callDeclinedFsMidTurn;
    private final List<ObjectNode> midTurnUpdates = new ArrayList<>();
    /** Key + document to hang under the prompt reply's {@code _meta}, or null. */
    private String metaKey;
    private ObjectNode metaDoc;

    FakeAcpAgent(InputStream fromClient, OutputStream toClient,
                 int echoVersion, boolean requestPermissionMidTurn,
                 boolean callDeclinedFsMidTurn) {
        this.in = new BufferedReader(new InputStreamReader(fromClient, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(new OutputStreamWriter(toClient, StandardCharsets.UTF_8));
        this.echoVersion = echoVersion;
        this.requestPermissionMidTurn = requestPermissionMidTurn;
        this.callDeclinedFsMidTurn = callDeclinedFsMidTurn;
    }

    /** Add a session/update the agent will stream during the prompt turn. */
    FakeAcpAgent withUpdate(ObjectNode update) {
        midTurnUpdates.add(update);
        return this;
    }

    static ObjectNode toolCallUpdate(String toolCallId, String... paths) {
        var u = M.createObjectNode();
        u.put("sessionUpdate", "tool_call");
        u.put("toolCallId", toolCallId);
        u.put("title", "edit files");
        u.put("kind", "edit");
        u.put("status", "completed");
        var locs = u.putArray("locations");
        for (var p : paths) locs.addObject().put("path", p);
        return u;
    }

    static ObjectNode messageChunk(String text) {
        var u = M.createObjectNode();
        u.put("sessionUpdate", "agent_message_chunk");
        u.putObject("content").put("type", "text").put("text", text);
        return u;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                var msg = M.readTree(line);
                received.add(msg);
                var method = msg.path("method").asText("");
                switch (method) {
                    case "initialize" -> respond(msg, r -> {
                        r.put("protocolVersion", echoVersion);
                        r.putObject("agentCapabilities");
                        return r;
                    });
                    case "session/new" -> respond(msg, r -> {
                        r.put("sessionId", "sess_fake_1");
                        return r;
                    });
                    case "session/prompt" -> handlePrompt(msg);
                    default -> { /* notifications (session/cancel) — record only */ }
                }
            }
        } catch (IOException ignored) {
            // client closed — done
        }
    }

    private void handlePrompt(JsonNode msg) throws IOException {
        var sessionId = msg.path("params").path("sessionId").asText();
        for (var update : midTurnUpdates) {
            var params = M.createObjectNode();
            params.put("sessionId", sessionId);
            params.set("update", update);
            notifyClient("session/update", params);
        }
        if (requestPermissionMidTurn) {
            var params = M.createObjectNode();
            params.put("sessionId", sessionId);
            var tc = params.putObject("toolCall");
            tc.put("toolCallId", "call_1");
            tc.put("title", permissionTitle);
            var opts = params.putArray("options");
            opts.addObject().put("optionId", "ok-once").put("name", "Allow once")
                .put("kind", "allow_once");
            opts.addObject().put("optionId", "no").put("name", "Reject")
                .put("kind", "reject_once");
            var reply = requestOfClient("session/request_permission", params);
            permissionAnswerSeen = reply.path("result").path("outcome")
                .path("optionId").asText(null);
        }
        if (callDeclinedFsMidTurn) {
            var params = M.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("path", "/etc/passwd");
            var reply = requestOfClient("fs/read_text_file", params);
            fsCallErrorSeen = reply.path("error").path("message").asText(null);
        }
        respond(msg, r -> {
            r.put("stopReason", "end_turn");
            if (metaKey != null) r.putObject("_meta").set(metaKey, metaDoc);
            return r;
        });
    }

    /** Put a result document under {@code _meta.<key>} of the prompt reply. */
    FakeAcpAgent withResultMeta(String key, ObjectNode doc) {
        this.metaKey = key;
        this.metaDoc = doc;
        return this;
    }

    private JsonNode requestOfClient(String method, ObjectNode params) throws IOException {
        var req = M.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1000);
        req.put("method", method);
        req.set("params", params);
        writeLine(req);
        // Sequential script: the very next inbound line is our answer
        // (the client's reader thread services agent requests promptly).
        String line = in.readLine();
        return M.readTree(line);
    }

    private void notifyClient(String method, ObjectNode params) throws IOException {
        var note = M.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", method);
        note.set("params", params);
        writeLine(note);
    }

    private void respond(JsonNode reqMsg, Function<ObjectNode, ObjectNode> build)
            throws IOException {
        var resp = M.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", reqMsg.get("id"));
        resp.set("result", build.apply(M.createObjectNode()));
        writeLine(resp);
    }

    private synchronized void writeLine(ObjectNode msg) throws IOException {
        out.write(M.writeValueAsString(msg));
        out.write('\n');
        out.flush();
    }
}
