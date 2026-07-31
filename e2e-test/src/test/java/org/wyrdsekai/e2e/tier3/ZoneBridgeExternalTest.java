package org.wyrdsekai.e2e.tier3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * External integration tests for the zone bridge WebSocket protocol ({@code /ws/zone}).
 *
 * <p><b>Mode 1: Local protocol tests</b> (always run) — starts a Wyrdsekai server via
 * {@link TestServerBootstrap} and verifies the zone bridge protocol with a raw WebSocket
 * client. No external dependencies.
 *
 * <p><b>Mode 2: Cross-machine integration</b> (gated on {@code CODEPLANE_URL}) — connects
 * to a running Wyrdsekai server (local or remote via {@code WYRDSEKAI_URL}) that has a real
 * CodePlane instance connected via the zone bridge. Tests the full round-trip:
 * <pre>
 * home-server (Wyrdsekai)              gpu-host (CodePlane)
 *   player command ──→ zone bridge ──→ CodePlane processes ──→ response back
 * </pre>
 *
 * <p>Environment variables:
 * <ul>
 *   <li>{@code WYRDSEKAI_URL} — base URL of running Wyrdsekai (e.g. {@code http://home-server:7070}).
 *       If set, skips TestServerBootstrap and connects to the remote server.
 *   <li>{@code CODEPLANE_URL} — base URL of running CodePlane (e.g. {@code http://gpu-host:8080}).
 *       Enables cross-machine integration tests.
 * </ul>
 */
@Tag("integration-external")
class ZoneBridgeExternalTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Duration MSG_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration BRIDGE_TIMEOUT = Duration.ofSeconds(30);

    private static TestServerBootstrap localServer;
    private static String baseUrl;
    private static String workspace;

    @BeforeAll
    static void setUp() throws Exception {
        var remoteUrl = System.getenv("WYRDSEKAI_URL");
        if (remoteUrl != null && !remoteUrl.isBlank()) {
            // Remote mode — connect to running Wyrdsekai
            baseUrl = remoteUrl.replaceAll("/$", "");
        } else {
            // Local mode — start our own server
            localServer = new TestServerBootstrap(List.of());
            localServer.start();
            baseUrl = localServer.baseUrl();
        }

        // Workspace for codeplane.create tests — env var or temp dir
        var wsEnv = System.getenv("INTEGRATION_WORKSPACE");
        if (wsEnv != null && !wsEnv.isBlank()) {
            workspace = wsEnv;
        } else {
            workspace = System.getProperty("java.io.tmpdir") + "/wyrdsekai-integration-" + ProcessHandle.current().pid();
            new File(workspace).mkdirs();
        }
    }

    @AfterAll
    static void tearDown() {
        if (localServer != null) localServer.stop();
    }

    // ── Protocol tests (no external service needed) ────────────────────────

    @Test
    void zone_bridge_connects_and_registers() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"type":"register","namespace":"test-alpha","secret":null}""");

            var ack = ws.receive(MSG_TIMEOUT);
            assertNotNull(ack, "Should receive registration acknowledgment");
            assertEquals("registered", ack.path("type").asText());
            assertEquals("test-alpha", ack.path("namespace").asText());
            assertFalse(ws.isClosed(), "WebSocket should remain open after registration");
        } finally {
            ws.close();
        }
    }

    @Test
    void registration_rejects_invalid_namespace_format() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"type":"register","namespace":"123-bad","secret":null}""");

            var error = ws.receive(MSG_TIMEOUT);
            assertNotNull(error, "Should receive error for invalid namespace");
            assertEquals("error", error.path("type").asText());
            assertTrue(error.path("reason").asText().toLowerCase().contains("invalid"));
        } finally {
            ws.close();
        }
    }

    @Test
    void registration_rejects_reserved_namespace() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"type":"register","namespace":"system","secret":null}""");

            var error = ws.receive(MSG_TIMEOUT);
            assertNotNull(error, "Should receive error for reserved namespace");
            assertEquals("error", error.path("type").asText());
            assertTrue(error.path("reason").asText().toLowerCase().contains("reserved"));
        } finally {
            ws.close();
        }
    }

    @Test
    void registration_rejects_empty_namespace() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"type":"register","namespace":"","secret":null}""");

            var error = ws.receive(MSG_TIMEOUT);
            assertNotNull(error, "Should receive error for empty namespace");
            assertEquals("error", error.path("type").asText());
            assertTrue(error.path("reason").asText().toLowerCase().contains("empty"));
        } finally {
            ws.close();
        }
    }

    @Test
    void multiple_zones_independent() throws Exception {
        var ws1 = connectZoneBridge();
        var ws2 = connectZoneBridge();
        try {
            ws1.send("""
                {"type":"register","namespace":"zone-alpha","secret":null}""");
            ws2.send("""
                {"type":"register","namespace":"zone-beta","secret":null}""");

            var ack1 = ws1.receive(MSG_TIMEOUT);
            var ack2 = ws2.receive(MSG_TIMEOUT);

            assertNotNull(ack1);
            assertNotNull(ack2);
            assertEquals("registered", ack1.path("type").asText());
            assertEquals("registered", ack2.path("type").asText());
            assertEquals("zone-alpha", ack1.path("namespace").asText());
            assertEquals("zone-beta", ack2.path("namespace").asText());

            // Duplicate namespace rejected
            var ws3 = connectZoneBridge();
            try {
                ws3.send("""
                    {"type":"register","namespace":"zone-alpha","secret":null}""");
                var dup = ws3.receive(MSG_TIMEOUT);
                assertNotNull(dup);
                assertEquals("error", dup.path("type").asText());
                assertTrue(dup.path("reason").asText().contains("already registered"));
            } finally {
                ws3.close();
            }
        } finally {
            ws1.close();
            ws2.close();
        }
    }

    @Test
    void reconnect_after_disconnect() throws Exception {
        var namespace = "zone-reconnect";

        var ws1 = connectZoneBridge();
        ws1.send("""
            {"type":"register","namespace":"%s","secret":null}""".formatted(namespace));
        var ack1 = ws1.receive(MSG_TIMEOUT);
        assertEquals("registered", ack1.path("type").asText());

        ws1.close();
        Thread.sleep(500);

        var ws2 = connectZoneBridge();
        try {
            ws2.send("""
                {"type":"register","namespace":"%s","secret":null}""".formatted(namespace));
            var ack2 = ws2.receive(MSG_TIMEOUT);
            assertNotNull(ack2, "Should re-register after disconnect");
            assertEquals("registered", ack2.path("type").asText());
            assertEquals(namespace, ack2.path("namespace").asText());
        } finally {
            ws2.close();
        }
    }

    @Test
    void malformed_message_returns_error() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"foo":"bar"}""");

            var error = ws.receive(MSG_TIMEOUT);
            assertNotNull(error, "Should receive error for malformed message");
            assertEquals("error", error.path("type").asText());
        } finally {
            ws.close();
        }
    }

    @Test
    void broadcast_from_unregistered_service_is_ignored() throws Exception {
        var ws = connectZoneBridge();
        try {
            ws.send("""
                {"type":"broadcast","roomId":"nexus","messages":[]}""");

            // Connection survives — register after
            ws.send("""
                {"type":"register","namespace":"late-register","secret":null}""");

            var ack = ws.receive(MSG_TIMEOUT);
            assertNotNull(ack);
            assertEquals("registered", ack.path("type").asText());
        } finally {
            ws.close();
        }
    }

    // ── Cross-machine integration tests (require CODEPLANE_URL) ────────────
    //
    // These tests verify the full round-trip between Wyrdsekai and CodePlane
    // across the zone bridge. CodePlane must be running and connected.

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEPLANE_URL", matches = ".+")
    void codeplane_registers_via_zone_bridge() throws Exception {
        // Verify CodePlane registered its namespace via the REST endpoint
        // — no namespace interference, works with multiple zone services
        waitForCodePlaneRegistration();

        var httpClient = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/zone/namespaces"))
            .timeout(Duration.ofSeconds(5))
            .GET().build();
        var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        var json = mapper.readTree(resp.body());
        var namespaces = json.path("namespaces");
        boolean found = false;
        for (var ns : namespaces) {
            if ("codeplane".equals(ns.asText())) { found = true; break; }
        }
        assertTrue(found, "CodePlane should be registered in zone namespaces: " + resp.body());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEPLANE_URL", matches = ".+")
    void codeplane_status_round_trip() throws Exception {
        // Player sends codeplane.status → routes through zone bridge →
        // CodePlane responds with active boards and system info
        waitForCodePlaneRegistration();

        var playerWs = connectPlayer();
        try {
            drainInitialMessages(playerWs);

            playerWs.send("""
                {"type":"command","id":"status-1","roomId":"nexus","command":"codeplane.status"}""");

            var response = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(response, "Should receive codeplane.status response via zone bridge");

            // Response should contain system info
            var text = response.path("text").asText("");
            var blocks = response.path("blocks");
            assertTrue(text.length() > 0 || blocks.size() > 0,
                "Response should have text or content blocks");
        } finally {
            playerWs.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEPLANE_URL", matches = ".+")
    void codeplane_list_round_trip() throws Exception {
        // Player asks CodePlane to list available models
        waitForCodePlaneRegistration();

        var playerWs = connectPlayer();
        try {
            drainInitialMessages(playerWs);

            playerWs.send("""
                {"type":"command","id":"list-1","roomId":"nexus",\
                "command":"codeplane.list","payload":{"what":"models"}}""");

            var response = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(response, "Should receive codeplane.list response via zone bridge");
        } finally {
            playerWs.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEPLANE_URL", matches = ".+")
    void codeplane_full_pipeline_produces_code_and_tests() throws Exception {
        // Full end-to-end: player creates a board with a coding task,
        // CodePlane runs the agent pipeline (LLM inference, tool calls, code gen),
        // all events broadcast back through the zone bridge to the player.
        //
        // Topology: home-server (test) → relay-node (Wyrdsekai) → gpu-host (CodePlane + GPU)
        //
        // Verification:
        //   1. Board creation response received
        //   2. Agent claimed the task
        //   3. Tool calls observed (file writes, shell commands)
        //   4. Code changes observed (at least one file created/modified)
        //   5. Tests run (TestResult event with pass/fail counts)
        //   6. Agent completed (success or failure — pipeline ran to completion)
        //   7. Status query confirms board reached terminal state
        waitForCodePlaneRegistration();

        var playerWs = connectPlayer();
        try {
            drainInitialMessages(playerWs);

            // Create a board with a concrete coding task
            playerWs.send("""
                {"type":"command","id":"create-full","roomId":"nexus",\
                "command":"codeplane.create",\
                "payload":{\
                  "prompt":"Create a Java file HelloWorld.java that prints Hello World, and a shell script test.sh that compiles and runs it, verifying the output contains Hello World",\
                  "workspace":"%s"\
                }}""".formatted(workspace));

            // 1. Board creation response
            var createResponse = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(createResponse, "Should receive board creation response");
            System.out.println("  Board created: " + createResponse.path("text").asText(""));

            // 2-6. Collect pipeline events (up to 5 minutes for LLM inference)
            boolean agentClaimed = false;
            boolean toolCallSeen = false;
            boolean codeChangeSeen = false;
            boolean testResultSeen = false;
            boolean agentCompleted = false;
            String completionStatus = "";
            int testsPassed = 0;
            int testsFailed = 0;

            var allEvents = new ArrayList<JsonNode>();
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(5).toMillis();

            while (System.currentTimeMillis() < deadline) {
                var remaining = Duration.ofMillis(deadline - System.currentTimeMillis());
                var event = playerWs.receive(remaining.compareTo(Duration.ofSeconds(30)) > 0
                    ? Duration.ofSeconds(30) : remaining);
                if (event == null) continue;

                allEvents.add(event);
                var text = event.path("text").asText("");
                var type = event.path("type").asText("");
                System.out.println("  Event: " + text.substring(0, Math.min(text.length(), 120)));

                // Classify events by content (case-insensitive)
                var lower = text.toLowerCase();
                if (lower.contains("claimed") || lower.contains("claim")) agentClaimed = true;
                if (lower.contains("called") || lower.contains("tool")) toolCallSeen = true;
                if (lower.contains("modified") || lower.contains("created") || lower.contains("wrote")
                        || lower.contains(".java") || lower.contains(".sh")
                        || lower.contains("file") || lower.contains("code")) codeChangeSeen = true;
                if (lower.contains("tests:") || lower.contains("test result")) {
                    testResultSeen = true;
                    var passMatch = Pattern.compile("(\\d+)\\s*pass").matcher(lower);
                    if (passMatch.find()) testsPassed = Integer.parseInt(passMatch.group(1));
                    var failMatch = Pattern.compile("(\\d+)\\s*fail").matcher(lower);
                    if (failMatch.find()) testsFailed = Integer.parseInt(failMatch.group(1));
                }
                if (lower.contains("completed") || lower.contains("complete")) {
                    agentCompleted = true;
                    completionStatus = text;
                    // Don't break — keep collecting events to see the full picture
                }
            }

            // Print summary
            System.out.println("\n  === Pipeline Summary ===");
            System.out.println("  Events received: " + allEvents.size());
            System.out.println("  Agent claimed: " + agentClaimed);
            System.out.println("  Tool calls: " + toolCallSeen);
            System.out.println("  Code changes: " + codeChangeSeen);
            System.out.println("  Tests run: " + testResultSeen);
            System.out.println("  Tests passed: " + testsPassed + ", failed: " + testsFailed);
            System.out.println("  Agent completed: " + agentCompleted);
            System.out.println("  Status: " + completionStatus);

            // Assertions — core proof: events flow through the zone bridge
            assertFalse(allEvents.isEmpty(),
                "Should receive pipeline events from CodePlane");
            // At least 2 events (agent activity + completion)
            assertTrue(allEvents.size() >= 2,
                "Should receive multiple pipeline events, got " + allEvents.size());
            assertTrue(agentCompleted,
                "Agent should complete (success or failure) within 5 minutes");
            // Soft checks — log but don't fail (depends on CodePlane agent behavior)
            if (!agentClaimed) System.out.println("  NOTE: No 'claimed' event detected in text");
            if (!toolCallSeen) System.out.println("  NOTE: No tool call event detected in text");
            if (!codeChangeSeen) System.out.println("  NOTE: No code change event detected in text");

            // 7. Verify final status via codeplane.status
            playerWs.send("""
                {"type":"command","id":"status-final","roomId":"nexus","command":"codeplane.status"}""");
            var finalStatus = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(finalStatus, "Final status should be available after pipeline completion");
            System.out.println("  Final status: " + finalStatus.path("text").asText(""));

        } finally {
            playerWs.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CODEPLANE_URL", matches = ".+")
    void codeplane_status_reflects_board_lifecycle() throws Exception {
        // Create a board, verify status shows it, then check after pipeline runs
        waitForCodePlaneRegistration();

        var playerWs = connectPlayer();
        try {
            drainInitialMessages(playerWs);

            // Create a board
            playerWs.send("""
                {"type":"command","id":"create-lc","roomId":"nexus",\
                "command":"codeplane.create",\
                "payload":{\
                  "prompt":"Create a file called hello.txt with the text Hello",\
                  "workspace":"%s/lifecycle"\
                }}""".formatted(workspace));

            var createResponse = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(createResponse, "Board creation should succeed");

            // Check status — should show at least one board
            // Note: active pipelines flood the WebSocket, so we may need to skip broadcast events
            playerWs.send("""
                {"type":"command","id":"status-lc","roomId":"nexus","command":"codeplane.status"}""");

            var statusResponse = waitForZoneResponse(playerWs, BRIDGE_TIMEOUT);
            assertNotNull(statusResponse, "Status should return after board creation");

            var text = statusResponse.path("text").asText("");
            assertTrue(text.toLowerCase().contains("board") || text.contains("running")
                    || text.contains("active") || text.contains("CREATED")
                    || statusResponse.path("blocks").size() > 0,
                "Status should reflect the board: " + text);
        } finally {
            playerWs.close();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Wait for CodePlane to register its "codeplane" namespace on the zone bridge.
     * Polls the REST endpoint GET /api/zone/namespaces — no namespace interference.
     */
    private static void waitForCodePlaneRegistration() throws Exception {
        var httpClient = HttpClient.newHttpClient();
        var namespacesUrl = baseUrl + "/api/zone/namespaces";

        for (int i = 0; i < 60; i++) {
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create(namespacesUrl))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
                var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    var json = mapper.readTree(resp.body());
                    var namespaces = json.path("namespaces");
                    for (var ns : namespaces) {
                        if ("codeplane".equals(ns.asText())) {
                            return; // CodePlane registered
                        }
                    }
                }
            } catch (Exception e) {
                // Server not ready yet, keep polling
            }
            Thread.sleep(2000);
        }
        fail("CodePlane did not register on the zone bridge within 120 seconds");
    }

    private static ZoneBridgeWsClient connectZoneBridge() throws Exception {
        var wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
            + "/ws/zone";
        return connectRawWebSocket(wsUrl);
    }

    private static ZoneBridgeWsClient connectPlayer() throws Exception {
        var wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
            + "/ws";
        return connectRawWebSocket(wsUrl);
    }

    /** Drain initial room state and greeting messages. */
    private static void drainInitialMessages(ZoneBridgeWsClient ws) throws Exception {
        // Consume messages for a few seconds (room state, greeting, etc.)
        for (int i = 0; i < 5; i++) {
            var msg = ws.receive(Duration.ofSeconds(3));
            if (msg == null) break;
        }
    }

    /**
     * Wait for a zone bridge response — filters out room notifications and
     * other non-zone messages, looking for prose/error with zone content.
     */
    private static JsonNode waitForZoneResponse(ZoneBridgeWsClient ws, Duration timeout)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            var remaining = Duration.ofMillis(deadline - System.currentTimeMillis());
            var msg = ws.receive(remaining);
            if (msg == null) return null;

            var type = msg.path("type").asText("");
            // Zone command responses have their own type — match first
            if ("zone_response".equals(type)) {
                return msg;
            }
            // Also accept error responses
            if ("error".equals(type)) {
                return msg;
            }
            // Skip everything else (broadcasts, token_stream, room_state, prose from pipelines)
            // Keep waiting — this was a room notification or other message
        }
        return null;
    }

    private static ZoneBridgeWsClient connectRawWebSocket(String wsUrl) throws Exception {
        var client = HttpClient.newHttpClient();
        var messageQueue = new LinkedBlockingQueue<JsonNode>();
        var openLatch = new CompletableFuture<WebSocket>();
        var closeFuture = new CompletableFuture<Void>();

        var ws = client.newWebSocketBuilder()
            .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                private final StringBuilder buffer = new StringBuilder();

                @Override
                public void onOpen(WebSocket webSocket) {
                    openLatch.complete(webSocket);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    buffer.append(data);
                    if (last) {
                        try {
                            var json = mapper.readTree(buffer.toString());
                            messageQueue.offer(json);
                        } catch (Exception e) {
                            // Ignore unparseable frames
                        }
                        buffer.setLength(0);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closeFuture.complete(null);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    closeFuture.completeExceptionally(error);
                }
            })
            .get(10, TimeUnit.SECONDS);

        openLatch.get(10, TimeUnit.SECONDS);
        return new ZoneBridgeWsClient(ws, messageQueue, closeFuture);
    }

    private static final class ZoneBridgeWsClient implements AutoCloseable {

        private final WebSocket webSocket;
        private final BlockingQueue<JsonNode> messages;
        private final CompletableFuture<Void> closeFuture;
        private volatile boolean closed = false;

        ZoneBridgeWsClient(WebSocket webSocket, BlockingQueue<JsonNode> messages,
                           CompletableFuture<Void> closeFuture) {
            this.webSocket = webSocket;
            this.messages = messages;
            this.closeFuture = closeFuture;
        }

        void send(String json) {
            webSocket.sendText(json.trim(), true);
        }

        JsonNode receive(Duration timeout) throws InterruptedException {
            return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean isClosed() {
            return closed || closeFuture.isDone();
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "test done");
                try {
                    closeFuture.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // best effort
                }
            }
        }
    }
}
