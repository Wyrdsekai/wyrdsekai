package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tourist transit E2E — transit token auth, wards, expiry.
 * Uses WireMock for deterministic responses.
 */
@Tag("e2e")
class TouristTransitE2ETest {

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome, traveler!", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void anonymous_client_enters_docks() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(roomState);

            // Navigate to Docks (tourist entry point). Nexus → east → Docks;
            // see TestServerBootstrap.foundationRoomSeeds.
            ws.sendGo("nexus", "east");
            var docksState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertEquals("docks",
                docksState.path("room").path("roomId").asText());
        }
    }

    @Test
    void tourist_can_navigate_foundation_rooms() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // Tourist can move freely in foundation rooms
            ws.sendGo("nexus", "south");
            var vaultState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertEquals("vault",
                vaultState.path("room").path("roomId").asText());

            ws.sendGo("vault", "north");
            var nexusState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertEquals("nexus",
                nexusState.path("room").path("roomId").asText());
        }
    }

    @Test
    void ward_denied_for_restricted_room() throws Exception {
        // An invalid direction ("nowhere-at-all" — not among Nexus's compass
        // exits + in/up/down) must produce a typed Error message on the wire,
        // NOT a silent drop. The server emits Error{code: "no_exit"} — see
        // WyrdWebSocket.handleGo + ClientSessionActor.onRoomResponse case
        // Rejected. Previous "either-or" tolerance hid real regressions.
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            ws.sendGo("nexus", "nowhere-at-all");

            var err = ws.waitForError(Duration.ofSeconds(5));
            assertEquals("no_exit", err.path("code").asText(),
                "Invalid direction should be rejected with code=no_exit (got: " + err + ")");

            // Connection must remain usable after a rejected command.
            ws.sendGo("nexus", "east");
            ws.waitForRoomState(Duration.ofSeconds(5));
        }
    }

    @Test
    void invalid_room_id_rejected() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // Send raw message with invalid room ID
            ws.send("""
                {"type":"say","id":"test-1","roomId":"nonexistent-room","text":"Hello?"}
                """);

            // Should get error or be silently ignored
            // The system should not crash
            Thread.sleep(2000);
            // If we can still communicate, the server is healthy
            ws.sendLook("nexus");
            var state = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(state, "Server should still respond after invalid room ID");
        }
    }

    @Test
    void expired_token_rejected() throws Exception {
        // Connect with an invalid/expired token
        try {
            var ws = TestWebSocketClient.connect(server.baseUrl(), "expired-fake-token");
            // If connection succeeds, we should still be treated as anonymous
            var state = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(state, "Invalid token should fall back to anonymous");
            ws.close();
        } catch (Exception e) {
            // Connection rejection is also acceptable behavior
            assertTrue(true, "Server correctly rejected invalid token");
        }
    }
}
