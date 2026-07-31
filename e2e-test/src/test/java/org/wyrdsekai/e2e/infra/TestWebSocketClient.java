package org.wyrdsekai.e2e.infra;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.awaitility.Awaitility.await;

/**
 * WebSocket test client for Wyrdsekai E2E tests.
 * Wraps java.net.http.WebSocket with high-level methods:
 * {@code sendSay()}, {@code sendGo()}, {@code waitForRoomState()}, {@code waitForProseFrom()}.
 *
 * <p>Uses Awaitility for polling assertions on received messages.
 */
public final class TestWebSocketClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestWebSocketClient.class);

    private final WebSocket webSocket;
    private final BlockingQueue<JsonNode> receivedMessages = new LinkedBlockingQueue<>();
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private volatile boolean closed = false;

    private TestWebSocketClient(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    /**
     * Connect anonymously to a Wyrdsekai server.
     */
    public static TestWebSocketClient connect(String baseUrl) throws Exception {
        return connect(baseUrl, null, null);
    }

    /**
     * Connect with an auth token.
     */
    public static TestWebSocketClient connect(String baseUrl, String authToken) throws Exception {
        return connect(baseUrl, authToken, null);
    }

    /**
     * Connect with optional auth token and locale (BCP 47, e.g. "ja", "es").
     * Locale is passed via {@code ?locale=...} query param so the server sets
     * the session locale before any Said events fire (matches production WS
     * client behavior in WyrdWebSocket §locale handling).
     */
    public static TestWebSocketClient connect(String baseUrl, String authToken, String locale) throws Exception {
        var wsUrl = baseUrl.replace("http://", "ws://")
                          .replace("https://", "wss://") + "/ws";
        var sep = '?';
        if (authToken != null) {
            wsUrl += sep + "token=" + authToken;
            sep = '&';
        }
        if (locale != null && !locale.isBlank()) {
            wsUrl += sep + "locale=" + locale;
        }

        var client = HttpClient.newHttpClient();
        var holder = new CompletableFuture<TestWebSocketClient>();

        var ws = client.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private final StringBuilder textBuffer = new StringBuilder();
                private TestWebSocketClient testClient;

                @Override
                public void onOpen(WebSocket webSocket) {
                    testClient = new TestWebSocketClient(webSocket);
                    holder.complete(testClient);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    textBuffer.append(data);
                    if (last) {
                        try {
                            var json = Json.mapper().readTree(textBuffer.toString());
                            testClient.receivedMessages.offer(json);
                            log.debug("Received: {}", json.path("type").asText());
                        } catch (Exception e) {
                            log.warn("Failed to parse WebSocket message: {}", e.getMessage());
                        }
                        textBuffer.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    if (testClient != null) {
                        testClient.closed = true;
                        testClient.closeFuture.complete(null);
                    }
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    log.error("WebSocket error: {}", error.getMessage());
                    if (testClient != null) {
                        testClient.closed = true;
                        testClient.closeFuture.completeExceptionally(error);
                    }
                }
            })
            .get(10, TimeUnit.SECONDS);

        return holder.get(10, TimeUnit.SECONDS);
    }

    // --- Send methods ---

    /**
     * Send a "say" message (player speaks in current room).
     */
    public void sendSay(String roomId, String text) {
        send("""
            {"type":"say","id":"%s","roomId":"%s","text":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId, escapeJson(text)));
    }

    /**
     * Send a "go" message (navigate to adjacent room).
     */
    public void sendGo(String roomId, String direction) {
        send("""
            {"type":"go","id":"%s","roomId":"%s","direction":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId, direction));
    }

    /**
     * Send a "look" message (observe current room).
     */
    public void sendLook(String roomId) {
        send("""
            {"type":"look","id":"%s","roomId":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId));
    }

    /**
     * Send an "examine" message (passive observation, ).
     * Distinguished from "use": never triggers onUse scripts or room re-render.
     */
    public void sendExamine(String roomId, String target) {
        send("""
            {"type":"examine","id":"%s","roomId":"%s","target":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId, escapeJson(target)));
    }

    /**
     * Send a "command" envelope — used for verbs like {@code help},
     * {@code inventory}, {@code where} that don't have dedicated C2S types.
     */
    public void sendCommand(String command, List<String> args) {
        var argsJson = args == null || args.isEmpty()
            ? "[]"
            : "[" + args.stream()
                .map(a -> "\"" + escapeJson(a) + "\"")
                .reduce((x, y) -> x + "," + y).orElse("") + "]";
        send("""
            {"type":"command","id":"%s","command":"%s","args":%s}
            """.formatted(UUID.randomUUID().toString(), command, argsJson));
    }

    /**
     * Send a "take" message.
     */
    public void sendTake(String roomId, String objectName) {
        send("""
            {"type":"take","id":"%s","roomId":"%s","objectName":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId, escapeJson(objectName)));
    }

    /**
     * Send a "drop" message.
     */
    public void sendDrop(String roomId, String objectName) {
        send("""
            {"type":"drop","id":"%s","roomId":"%s","objectName":"%s"}
            """.formatted(UUID.randomUUID().toString(), roomId, escapeJson(objectName)));
    }

    /**
     * Send a "rename" message (typed envelope, ).
     * v1: self-rename only (target must be "me" or the caller's current name).
     */
    public void sendRename(String target, String newName) {
        send("""
            {"type":"rename","id":"%s","target":"%s","newName":"%s"}
            """.formatted(UUID.randomUUID().toString(),
                escapeJson(target), escapeJson(newName)));
    }

    /**
     * Send raw JSON text.
     */
    public void send(String json) {
        webSocket.sendText(json.trim(), true);
    }

    // --- Wait/assert methods ---

    /**
     * Wait for a RoomState message within the timeout.
     *
     * @return the room_state JSON node
     */
    public JsonNode waitForRoomState(Duration timeout) {
        return waitForMessage(msg -> "room_state".equals(msg.path("type").asText()), timeout);
    }

    /**
     * Wait for a Prose message from a specific speaker.
     */
    public JsonNode waitForProseFrom(String speaker, Duration timeout) {
        return waitForMessage(msg ->
            "prose".equals(msg.path("type").asText()) &&
            speaker.equals(msg.path("speaker").asText()), timeout);
    }

    /**
     * Wait for any Prose message (any speaker).
     */
    public JsonNode waitForProse(Duration timeout) {
        return waitForMessage(msg -> "prose".equals(msg.path("type").asText()), timeout);
    }

    /**
     * Wait for an Error message.
     */
    public JsonNode waitForError(Duration timeout) {
        return waitForMessage(msg -> "error".equals(msg.path("type").asText()), timeout);
    }

    /**
     * Wait for a message matching the predicate.
     */
    public JsonNode waitForMessage(Predicate<JsonNode> matcher, Duration timeout) {
        var result = new CompletableFuture<JsonNode>();

        // First check already-received messages
        var iter = receivedMessages.iterator();
        while (iter.hasNext()) {
            var msg = iter.next();
            if (matcher.test(msg)) {
                iter.remove();
                return msg;
            }
        }

        // Poll for new messages
        await().atMost(timeout)
            .pollInterval(Duration.ofMillis(100))
            .until(() -> {
                var msg = receivedMessages.peek();
                if (msg != null && matcher.test(msg)) {
                    receivedMessages.poll();
                    result.complete(msg);
                    return true;
                }
                // Also drain and check non-matching messages
                return receivedMessages.stream().anyMatch(m -> {
                    if (matcher.test(m)) {
                        receivedMessages.remove(m);
                        result.complete(m);
                        return true;
                    }
                    return false;
                });
            });

        return result.getNow(null);
    }

    /**
     * Assert no message matching the predicate arrives within timeout.
     */
    public void assertNoMessage(Predicate<JsonNode> matcher, Duration timeout) {
        try {
            Thread.sleep(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var found = receivedMessages.stream().anyMatch(matcher);
        if (found) {
            throw new AssertionError("Unexpected message received matching predicate");
        }
    }

    /**
     * Get all received messages (drain queue).
     */
    public List<JsonNode> drainMessages() {
        var list = new ArrayList<JsonNode>();
        receivedMessages.drainTo(list);
        return list;
    }

    /**
     * Get the current room ID from the last received RoomState.
     */
    public String currentRoomId() {
        // Search backwards through received messages
        return receivedMessages.stream()
            .filter(msg -> "room_state".equals(msg.path("type").asText()))
            .reduce((first, second) -> second)
            .map(msg -> msg.path("room").path("roomId").asText())
            .orElse(null);
    }

    @Override
    public void close() {
        if (!closed) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete");
            try {
                closeFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Best effort
            }
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
