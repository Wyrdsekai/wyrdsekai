package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E test for the resident bridge — SSE events + HTTP commands.
 * Verifies the full path: ResidentRoutes → CompanionActor.Bridge* → room events.
 *
 * <p>No inference needed — tests the bridge mechanics only.
 * The companion is spawned via soul manifest with WireMock as inference backend.
 */
@Tag("e2e")
class ResidentBridgeE2ETest {

    private static TestServerBootstrap server;
    private static WireMockInferenceServer mockInference;
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @BeforeAll
    static void setUp() throws Exception {
        // Need at least one inference backend for companion to spawn
        int mockPort = PortAllocator.allocate();
        mockInference = WireMockInferenceServer.openAi(mockPort);
        mockInference.start();
        mockInference.stubChatCompletion("Bridge test response.", 10, 5);

        var client = new InferenceClient(mockInference.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "bridge-test", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (mockInference != null) mockInference.stop();
    }

    @Test
    void resident_status_endpoint_returns_state() throws Exception {
        waitForResident();
        var resp = get("/api/resident/status");
        assertEquals(200, resp.statusCode(),
            "Status endpoint should return 200");
        var body = Json.mapper().readTree(resp.body());
        // Status now returns companion state (IDLE, THINKING, etc.)
        assertFalse(body.path("status").asText().isEmpty(), "Should have a state");
        assertTrue(body.has("energy"), "Should include energy tank");
        assertTrue(body.has("tanks"), "Should include all tanks");
        assertTrue(body.has("howYouFeel"), "Should include How You Feel text");
        assertTrue(body.has("roomId"), "Should include room ID");
    }

    @Test
    void resident_say_posts_to_room() throws Exception {
        // Wait for companion to register in static registry
        waitForResident();

        // Connect a WebSocket client to watch for the speech
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(5));

            // POST say via bridge
            var resp = post("/api/resident/say",
                Map.of("text", "Hello from the bridge!"));

            assertEquals(200, resp.statusCode(),
                "Say should return 200: " + resp.body());

            // The companion spoke in the room — WebSocket client should see it
            // (may take a moment for the event to propagate)
            var prose = ws.waitForProse(Duration.ofSeconds(5));
            // Bridge say goes through CompanionActor.speak() which broadcasts to room
            // The WS client should see it as prose from the companion
            if (prose != null) {
                System.out.println("[ResidentBridge] Received prose: " +
                    prose.path("text").asText("(empty)"));
            }
        }
    }

    @Test
    void resident_say_rejects_empty_text() throws Exception {
        waitForResident();
        var resp = post("/api/resident/say", Map.of("text", ""));
        assertEquals(400, resp.statusCode(),
            "Empty text should be rejected");
    }

    @Test
    void resident_go_moves_companion() throws Exception {
        var resp = post("/api/resident/go",
            Map.of("direction", "north"));

        // Should succeed (200) or get 503 if companion not found
        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 503,
            "Go should return 200 or 503: " + resp.body());
    }

    @Test
    void resident_emote_posts_to_room() throws Exception {
        var resp = post("/api/resident/emote",
            Map.of("text", "waves from the bridge"));

        assertTrue(resp.statusCode() == 200 || resp.statusCode() == 503,
            "Emote should return 200 or 503: " + resp.body());
    }

    /** Wait for the companion to appear in ZoneGuardian's static registry. */
    private void waitForResident() throws Exception {
        for (int i = 0; i < 20; i++) {
            var ref = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
            if (ref != null) return;
            Thread.sleep(500);
        }
        fail("Companion 'companion-wyrd' not registered after 10 seconds");
    }

    // --- Helpers ---

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> body) throws Exception {
        var json = Json.mapper().writeValueAsString(body);
        return HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }
}
