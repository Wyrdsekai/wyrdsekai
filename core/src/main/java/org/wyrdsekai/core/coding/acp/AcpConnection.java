package org.wyrdsekai.core.coding.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * Newline-delimited JSON-RPC 2.0 over a byte stream pair — the ACP stdio
 * transport ("Messages are delimited by newlines and MUST NOT contain
 * embedded newlines", protocol/v1/transports).
 *
 * <p>Bidirectional: we issue requests to the agent AND the agent issues
 * requests to us ({@code session/request_permission}, {@code fs/*}).
 * A single virtual reader thread dispatches: responses complete pending
 * futures by id; agent requests go through {@link #agentRequestHandler}
 * and their return value is written back as the response; notifications
 * go to {@link #notificationHandler}.</p>
 *
 * <p>Transport-agnostic on purpose — production wraps a spawned agent
 * process; tests wrap piped streams with a scripted fake agent. No
 * process assumptions live here.</p>
 */
public final class AcpConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpConnection.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Handles agent→client requests; return value becomes the JSON-RPC result. */
    @FunctionalInterface
    public interface AgentRequestHandler {
        JsonNode handle(String method, JsonNode params);
    }

    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final BufferedReader in;
    private final BufferedWriter out;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private volatile AgentRequestHandler agentRequestHandler =
        (method, params) -> MAPPER.createObjectNode();
    private volatile BiConsumer<String, JsonNode> notificationHandler =
        (method, params) -> {};
    private volatile boolean closed;
    private final Thread reader;

    public AcpConnection(InputStream fromAgent, OutputStream toAgent) {
        this.rawIn = fromAgent;
        this.rawOut = toAgent;
        this.in = new BufferedReader(
            new InputStreamReader(fromAgent, StandardCharsets.UTF_8));
        this.out = new BufferedWriter(
            new OutputStreamWriter(toAgent, StandardCharsets.UTF_8));
        this.reader = Thread.ofVirtual().name("acp-reader").start(this::readLoop);
    }

    public void onAgentRequest(AgentRequestHandler handler) {
        this.agentRequestHandler = handler != null ? handler
            : (m, p) -> MAPPER.createObjectNode();
    }

    public void onNotification(BiConsumer<String, JsonNode> handler) {
        this.notificationHandler = handler != null ? handler : (m, p) -> {};
    }

    /** Send a request, await the result (or a raised JSON-RPC error). */
    public JsonNode request(String method, JsonNode params, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        var id = nextId.getAndIncrement();
        var future = new CompletableFuture<JsonNode>();
        pending.put(id, future);
        var msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        writeLine(msg);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("ACP " + method + " failed: " + e.getCause().getMessage(),
                e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    /** Send a notification (no id, no reply). */
    public void notify(String method, JsonNode params) throws IOException {
        var msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        writeLine(msg);
    }

    private synchronized void writeLine(ObjectNode msg) throws IOException {
        // MUST NOT contain embedded newlines — Jackson's default compact
        // writer never emits them.
        out.write(MAPPER.writeValueAsString(msg));
        out.write('\n');
        out.flush();
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg;
                try {
                    msg = MAPPER.readTree(line);
                } catch (Exception e) {
                    log.debug("acp: non-JSON line from agent ignored: {}",
                        line.length() > 120 ? line.substring(0, 120) + "…" : line);
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException e) {
            if (!closed) log.debug("acp: reader closed: {}", e.getMessage());
        } finally {
            var eof = new IOException("agent connection closed");
            pending.values().forEach(f -> f.completeExceptionally(eof));
        }
    }

    private void dispatch(JsonNode msg) {
        var hasId = msg.hasNonNull("id");
        var method = msg.path("method").asText(null);

        if (method == null && hasId) {                      // response to us
            var future = pending.get(msg.get("id").asLong());
            if (future == null) return;
            if (msg.has("error")) {
                future.completeExceptionally(new IOException(
                    "code " + msg.path("error").path("code").asInt()
                        + ": " + msg.path("error").path("message").asText("")));
            } else {
                future.complete(msg.path("result"));
            }
            return;
        }
        if (method != null && hasId) {                      // agent request to us
            JsonNode result;
            try {
                result = agentRequestHandler.handle(method, msg.path("params"));
            } catch (Exception e) {
                respondError(msg.get("id").asLong(), e.getMessage());
                return;
            }
            respond(msg.get("id").asLong(), result);
            return;
        }
        if (method != null) {                               // notification to us
            try {
                notificationHandler.accept(method, msg.path("params"));
            } catch (Exception e) {
                log.warn("acp: notification handler failed for {}: {}", method, e.getMessage());
            }
        }
    }

    private void respond(long id, JsonNode result) {
        var msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.set("result", result != null ? result : MAPPER.createObjectNode());
        try {
            writeLine(msg);
        } catch (IOException e) {
            log.warn("acp: failed to answer agent request {}: {}", id, e.getMessage());
        }
    }

    private void respondError(long id, String message) {
        var msg = MAPPER.createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        var err = msg.putObject("error");
        err.put("code", -32603);
        err.put("message", message != null ? message : "internal error");
        try {
            writeLine(msg);
        } catch (IOException e) {
            log.warn("acp: failed to send error for {}: {}", id, e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        // Close the RAW streams, never the BufferedReader: its close() is
        // synchronized on the same monitor readLine() holds, so closing it
        // from the caller thread deadlocks against a reader parked on a
        // quiet connection (live-found 2026-08-15 — every "silent" ACP test
        // run was this hang). Closing the underlying stream is unsynchronized
        // and forcibly unblocks readLine with an IOException instead.
        try { rawIn.close(); } catch (IOException ignored) { }
        try { rawOut.close(); } catch (IOException ignored) { }
        reader.interrupt();
    }

    static ObjectMapper mapper() { return MAPPER; }

    /** Convenience for handlers built from lambdas over ObjectNode. */
    public static ObjectNode obj(Function<ObjectNode, ObjectNode> build) {
        return build.apply(MAPPER.createObjectNode());
    }
}
