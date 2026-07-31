package org.wyrdsekai.core.skill.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenClaw Gateway executor — bridges to OpenClaw's containerized skill
 * catalogue (13,729+ skills) over WebSocket. Skills are dynamically loaded
 * from the gateway on connect.
 *
 * <p>Protocol: JSON messages over WebSocket.
 * <ul>
 *   <li>{@code {"type":"catalogue"}} — request skill catalogue</li>
 *   <li>{@code {"type":"invoke","skillId":"...","params":{...},"requestId":"...","timeout":N}} — invoke a skill</li>
 *   <li>Inbound catalogue, result, and error messages</li>
 * </ul>
 *
 * <p>Connection lifecycle: lazy connect on first use, automatic reconnect
 * with exponential backoff (1s-30s), clean shutdown via {@link #close()}.
 * HTTP health check retained as secondary availability probe.
 */
public class OpenClawGatewayExecutor implements SkillExecutor, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OpenClawGatewayExecutor.class.getName());

    /** Minimum reconnect delay. */
    static final long RECONNECT_MIN_MS = 1_000;
    /** Maximum reconnect delay. */
    static final long RECONNECT_MAX_MS = 30_000;
    /** Default timeout for catalogue loading. */
    static final long CATALOGUE_TIMEOUT_MS = 10_000;

    private final String gatewayUrl;
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final HttpClient http;
    private final ObjectMapper mapper;

    // WebSocket state
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private volatile WebSocket webSocket;
    private final Object connectLock = new Object();

    // Pending invocations: requestId -> future
    private final ConcurrentHashMap<String, CompletableFuture<SkillResult>> pendingInvocations = new ConcurrentHashMap<>();

    // Catalogue loading
    private volatile CompletableFuture<Void> catalogueFuture;

    // Reconnect
    private final AtomicLong reconnectDelayMs = new AtomicLong(RECONNECT_MIN_MS);
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed = false;

    // Message accumulator for fragmented WebSocket text messages
    private final StringBuilder messageAccumulator = new StringBuilder();

    /**
     * Connection state machine.
     */
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING
    }

    public OpenClawGatewayExecutor(String gatewayUrl) {
        this(gatewayUrl, new ObjectMapper(), null);
    }

    /**
     * Constructor with injectable dependencies (for testing).
     */
    public OpenClawGatewayExecutor(String gatewayUrl, ObjectMapper mapper,
                                    ScheduledExecutorService scheduler) {
        this.gatewayUrl = gatewayUrl != null ? gatewayUrl : "ws://localhost:18789";
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.scheduler = scheduler != null ? scheduler :
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "openclaw-reconnect");
                t.setDaemon(true);
                return t;
            });
    }

    // ---- SkillExecutor interface ----

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (!skills.containsKey(skillId)) {
            return SkillResult.unavailable(skillId);
        }

        long start = System.currentTimeMillis();

        // Ensure connected
        ConnectionState currentState = state.get();
        if (currentState != ConnectionState.CONNECTED) {
            if (currentState == ConnectionState.DISCONNECTED) {
                connectAsync();
            }
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(
                I18n.get("skill.openclaw.not_running"),
                elapsed, SkillTier.OPENCLAW, skillId);
        }

        // Build invocation message
        String requestId = UUID.randomUUID().toString();
        String message;
        try {
            message = buildInvokeMessage(skillId, params, requestId, context.timeoutMs());
        } catch (JsonProcessingException e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(
                I18n.get("skill.openclaw.error", e.getMessage()),
                elapsed, SkillTier.OPENCLAW, skillId);
        }

        // Register pending future
        CompletableFuture<SkillResult> future = new CompletableFuture<>();
        pendingInvocations.put(requestId, future);

        try {
            // Send via WebSocket
            WebSocket ws = this.webSocket;
            if (ws == null) {
                long elapsed = System.currentTimeMillis() - start;
                pendingInvocations.remove(requestId);
                return SkillResult.error(
                    I18n.get("skill.openclaw.not_running"),
                    elapsed, SkillTier.OPENCLAW, skillId);
            }

            ws.sendText(message, true);

            // Wait for response with timeout
            SkillResult result = future.get(context.timeoutMs(), TimeUnit.MILLISECONDS);
            return result;

        } catch (TimeoutException e) {
            pendingInvocations.remove(requestId);
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(
                I18n.get("skill.openclaw.timeout"),
                elapsed, SkillTier.OPENCLAW, skillId);

        } catch (Exception e) {
            pendingInvocations.remove(requestId);
            long elapsed = System.currentTimeMillis() - start;
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            return SkillResult.error(
                I18n.get("skill.openclaw.error", cause.getMessage()),
                elapsed, SkillTier.OPENCLAW, skillId);
        }
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        return List.copyOf(skills.values());
    }

    @Override
    public boolean supports(String skillId) {
        return skills.containsKey(skillId);
    }

    @Override
    public SkillTier tier() { return SkillTier.OPENCLAW; }

    // ---- Connection management ----

    /**
     * Initiate an asynchronous connection to the gateway.
     * Thread-safe — only one connection attempt at a time.
     */
    public CompletableFuture<Void> connectAsync() {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Executor is closed"));
        }

        synchronized (connectLock) {
            ConnectionState current = state.get();
            if (current == ConnectionState.CONNECTED || current == ConnectionState.CONNECTING) {
                return catalogueFuture != null ? catalogueFuture : CompletableFuture.completedFuture(null);
            }

            state.set(ConnectionState.CONNECTING);
        }

        LOG.info(() -> I18n.get("skill.openclaw.connecting"));

        catalogueFuture = new CompletableFuture<>();
        CompletableFuture<Void> catFuture = catalogueFuture;

        http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .buildAsync(URI.create(gatewayUrl), new GatewayListener())
            .whenComplete((ws, ex) -> {
                if (ex != null) {
                    LOG.log(Level.WARNING, "WebSocket connect failed", ex);
                    state.set(ConnectionState.DISCONNECTED);
                    catFuture.completeExceptionally(ex);
                    scheduleReconnect();
                } else {
                    this.webSocket = ws;
                    state.set(ConnectionState.CONNECTED);
                    reconnectDelayMs.set(RECONNECT_MIN_MS);
                    // Request catalogue
                    requestCatalogue(ws);
                }
            });

        return catFuture;
    }

    /**
     * Request the skill catalogue from the gateway.
     */
    private void requestCatalogue(WebSocket ws) {
        try {
            String msg = mapper.writeValueAsString(Map.of("type", "catalogue"));
            ws.sendText(msg, true);

            // Timeout for catalogue loading
            scheduler.schedule(() -> {
                CompletableFuture<Void> cf = catalogueFuture;
                if (cf != null && !cf.isDone()) {
                    cf.complete(null); // Complete even without catalogue — skills stay empty
                    LOG.warning("Catalogue loading timed out");
                }
            }, CATALOGUE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        } catch (JsonProcessingException e) {
            LOG.log(Level.SEVERE, "Failed to serialize catalogue request", e);
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     */
    private void scheduleReconnect() {
        if (closed) return;

        long delay = reconnectDelayMs.get();
        LOG.info(() -> I18n.get("skill.openclaw.reconnecting") + " (delay: " + delay + "ms)");

        state.set(ConnectionState.RECONNECTING);

        scheduler.schedule(() -> {
            if (!closed) {
                // Increase backoff for next attempt
                reconnectDelayMs.updateAndGet(d -> Math.min(d * 2, RECONNECT_MAX_MS));
                // Reset state so connectAsync will proceed
                state.set(ConnectionState.DISCONNECTED);
                connectAsync();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * Close the executor, shutting down the WebSocket and scheduler.
     */
    @Override
    public void close() {
        closed = true;
        state.set(ConnectionState.DISCONNECTED);

        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error closing WebSocket", e);
            }
            this.webSocket = null;
        }

        // Fail all pending invocations
        pendingInvocations.forEach((id, future) ->
            future.completeExceptionally(new CancellationException("Executor closing")));
        pendingInvocations.clear();

        // Complete catalogue future if pending
        CompletableFuture<Void> cf = catalogueFuture;
        if (cf != null && !cf.isDone()) {
            cf.completeExceptionally(new CancellationException("Executor closing"));
        }

        scheduler.shutdownNow();

        LOG.info(() -> I18n.get("skill.openclaw.disconnected"));
    }

    // ---- Message handling ----

    /**
     * Process an inbound message from the gateway.
     */
    void handleMessage(String text) {
        try {
            JsonNode node = mapper.readTree(text);
            String type = node.path("type").asText("");

            switch (type) {
                case "catalogue" -> handleCatalogue(node);
                case "result" -> handleResult(node);
                case "error" -> handleError(node);
                default -> LOG.warning("Unknown message type from gateway: " + type);
            }
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Failed to parse gateway message", e);
        }
    }

    /**
     * Parse catalogue response and populate skills map.
     */
    private void handleCatalogue(JsonNode node) {
        JsonNode skillsNode = node.path("skills");
        if (!skillsNode.isArray()) {
            LOG.warning("Catalogue response missing 'skills' array");
            return;
        }

        int loaded = 0;
        for (JsonNode skillNode : skillsNode) {
            SkillDefinition def = parseCatalogueSkill(skillNode);
            if (def != null) {
                skills.put(def.id(), def);
                loaded++;
            }
        }

        int count = loaded;
        LOG.info(() -> I18n.get("skill.openclaw.catalogue_loaded", count));
        LOG.info(() -> I18n.get("skill.openclaw.connected", count));

        // Complete the catalogue future
        CompletableFuture<Void> cf = catalogueFuture;
        if (cf != null && !cf.isDone()) {
            cf.complete(null);
        }
    }

    /**
     * Parse a single skill from the catalogue response.
     */
    SkillDefinition parseCatalogueSkill(JsonNode skillNode) {
        String id = skillNode.path("id").asText(null);
        String name = skillNode.path("name").asText(null);
        if (id == null || name == null) {
            return null;
        }

        String description = skillNode.path("description").asText(name);
        String room = skillNode.path("room").asText("openclaw");

        // Parse params
        List<SkillParam> params = new ArrayList<>();
        JsonNode paramsNode = skillNode.path("params");
        if (paramsNode.isArray()) {
            for (JsonNode p : paramsNode) {
                String pName = p.path("name").asText(null);
                if (pName == null) continue;
                String pType = p.path("type").asText("string");
                String pDesc = p.path("description").asText("");
                boolean pReq = p.path("required").asBoolean(false);

                List<String> enumValues = new ArrayList<>();
                JsonNode enumNode = p.path("enumValues");
                if (enumNode.isArray()) {
                    for (JsonNode ev : enumNode) {
                        enumValues.add(ev.asText());
                    }
                }
                params.add(new SkillParam(pName, pType, pDesc, pReq, enumValues));
            }
        }

        return new SkillDefinition(
            id, name, description, room,
            SkillTier.OPENCLAW,
            "openclaw",
            "MIT",
            params,
            SkillAuth.NONE,
            SkillLocality.ANY,
            false
        );
    }

    /**
     * Handle a result response from the gateway.
     */
    private void handleResult(JsonNode node) {
        String requestId = node.path("requestId").asText(null);
        if (requestId == null) {
            // Fall back to matching by skillId if requestId is absent (legacy protocol)
            LOG.fine("Result message without requestId");
            return;
        }

        CompletableFuture<SkillResult> future = pendingInvocations.remove(requestId);
        if (future == null) {
            LOG.fine(() -> "No pending invocation for requestId: " + requestId);
            return;
        }

        boolean success = node.path("success").asBoolean(false);
        String output = node.path("output").asText("");
        long latencyMs = node.path("latencyMs").asLong(0);
        String skillId = node.path("skillId").asText("unknown");

        // Parse meta as Map
        Map<String, Object> meta = new LinkedHashMap<>();
        JsonNode metaNode = node.path("meta");
        if (metaNode.isObject()) {
            metaNode.fields().forEachRemaining(entry ->
                meta.put(entry.getKey(), jsonNodeToObject(entry.getValue())));
        }

        if (success) {
            future.complete(SkillResult.ok(output, meta, latencyMs, SkillTier.OPENCLAW, skillId));
        } else {
            future.complete(SkillResult.error(output, latencyMs, SkillTier.OPENCLAW, skillId));
        }
    }

    /**
     * Handle an error response from the gateway.
     */
    private void handleError(JsonNode node) {
        String requestId = node.path("requestId").asText(null);
        String skillId = node.path("skillId").asText("unknown");
        String message = node.path("message").asText("Unknown error");

        if (requestId != null) {
            CompletableFuture<SkillResult> future = pendingInvocations.remove(requestId);
            if (future != null) {
                future.complete(SkillResult.error(
                    I18n.get("skill.openclaw.error", message),
                    0, SkillTier.OPENCLAW, skillId));
                return;
            }
        }

        LOG.warning(() -> "Gateway error (skillId=" + skillId + "): " + message);
    }

    // ---- Message building ----

    /**
     * Build a JSON invocation message.
     */
    String buildInvokeMessage(String skillId, Map<String, Object> params,
                              String requestId, long timeoutMs) throws JsonProcessingException {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("type", "invoke");
        msg.put("skillId", skillId);
        msg.put("requestId", requestId);
        msg.put("timeout", timeoutMs);

        ObjectNode paramsNode = mapper.valueToTree(params);
        msg.set("params", paramsNode);

        return mapper.writeValueAsString(msg);
    }

    // ---- HTTP health check (secondary probe) ----

    /**
     * Check gateway health via HTTP GET. Returns true if healthy.
     */
    public boolean checkHealth() {
        String healthUrl = toHttpUrl(gatewayUrl) + "/health";
        try {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Accessors ----

    public String gatewayUrl() { return gatewayUrl; }

    public ConnectionState connectionState() { return state.get(); }

    public int skillCount() { return skills.size(); }

    public boolean isClosed() { return closed; }

    /**
     * Expose pending invocations count (for monitoring/testing).
     */
    public int pendingCount() { return pendingInvocations.size(); }

    // ---- Internals ----

    private static String toHttpUrl(String wsUrl) {
        return wsUrl.replace("ws://", "http://").replace("wss://", "https://");
    }

    /**
     * Convert a JsonNode to a Java object for meta maps.
     */
    private static Object jsonNodeToObject(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNull()) return null;
        return node.toString();
    }

    // ---- For testing: allow injecting skills directly ----

    /**
     * Register a skill definition directly (for testing without a gateway).
     */
    void registerSkill(SkillDefinition def) {
        skills.put(def.id(), def);
    }

    /**
     * Set the connection state directly (for testing).
     */
    void setConnectionState(ConnectionState newState) {
        state.set(newState);
    }

    /**
     * Set the WebSocket directly (for testing).
     */
    void setWebSocket(WebSocket ws) {
        this.webSocket = ws;
    }

    /**
     * Get the pending invocations map (for testing).
     */
    ConcurrentHashMap<String, CompletableFuture<SkillResult>> pendingInvocations() {
        return pendingInvocations;
    }

    // ---- WebSocket listener ----

    /**
     * WebSocket.Listener that receives messages from the OpenClaw gateway,
     * handles text message fragments, and triggers reconnection on close/error.
     */
    private class GatewayListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            LOG.fine("WebSocket opened to gateway");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageAccumulator.append(data);
            if (last) {
                String fullMessage = messageAccumulator.toString();
                messageAccumulator.setLength(0);
                handleMessage(fullMessage);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOG.info(() -> I18n.get("skill.openclaw.disconnected")
                + " (code=" + statusCode + ", reason=" + reason + ")");
            OpenClawGatewayExecutor.this.webSocket = null;
            state.set(ConnectionState.DISCONNECTED);

            // Fail all pending invocations
            pendingInvocations.forEach((id, future) ->
                future.completeExceptionally(
                    new RuntimeException("WebSocket closed: " + reason)));
            pendingInvocations.clear();

            if (!closed) {
                scheduleReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOG.log(Level.WARNING, "WebSocket error", error);
            OpenClawGatewayExecutor.this.webSocket = null;
            state.set(ConnectionState.DISCONNECTED);

            // Fail all pending invocations
            pendingInvocations.forEach((id, future) ->
                future.completeExceptionally(error));
            pendingInvocations.clear();

            if (!closed) {
                scheduleReconnect();
            }
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }
    }
}
