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

import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Multi-user E2E — visibility, broadcast, room isolation.
 * Uses WireMock for deterministic responses (infrastructure test).
 *
 * <p>Hard assertions: multiple clients connect, messages broadcast correctly,
 * room isolation prevents cross-room visibility.
 */
@Tag("e2e")
class MultiUserE2ETest {

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("I see you both here!", 30, 20);

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
    void two_clients_both_receive_room_state() throws Exception {
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl());
             var ws2 = TestWebSocketClient.connect(server.baseUrl())) {

            // === HARD: Both clients get RoomState ===
            var state1 = ws1.waitForRoomState(Duration.ofSeconds(10));
            var state2 = ws2.waitForRoomState(Duration.ofSeconds(10));
            assertRoomState(state1, "nexus");
            assertRoomState(state2, "nexus");
        }
    }

    @Test
    void agent_response_visible_to_both() throws Exception {
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl());
             var ws2 = TestWebSocketClient.connect(server.baseUrl())) {

            ws1.waitForRoomState(Duration.ofSeconds(10));
            ws2.waitForRoomState(Duration.ofSeconds(10));

            // Drain initial greetings
            ws1.waitForProse(Duration.ofSeconds(30));
            ws2.waitForProse(Duration.ofSeconds(30));

            // ws1 speaks, triggering agent response
            ws1.sendSay("nexus", "Agent, say something!");

            // === HARD: ws1 sees agent response ===
            var resp1 = ws1.waitForProse(Duration.ofSeconds(30));
            assertProseReceived(resp1, "ws1 agent response");

            // === HARD: ws2 also sees agent response (broadcast) ===
            var resp2 = ws2.waitForProse(Duration.ofSeconds(30));
            assertProseReceived(resp2, "ws2 broadcast of agent response");
        }
    }

    @Test
    void room_isolation_between_rooms() throws Exception {
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl());
             var ws2 = TestWebSocketClient.connect(server.baseUrl())) {

            ws1.waitForRoomState(Duration.ofSeconds(10));
            ws2.waitForRoomState(Duration.ofSeconds(10));

            // === HARD: ws2 moves to Terminal (nexus west→terminal; east is The Docks) ===
            ws2.sendGo("nexus", "west");
            var termState = ws2.waitForRoomState(Duration.ofSeconds(10));
            assertRoomState(termState, "terminal");

            // ws1 speaks in Nexus — ws2 (in Terminal) should NOT see it
            ws1.sendSay("nexus", "This message is only for Nexus.");

            // === HARD: Room isolation — no cross-room leakage ===
            ws2.assertNoMessage(
                m -> "prose".equals(m.path("type").asText()) &&
                     m.path("text").asText().contains("only for Nexus"),
                Duration.ofSeconds(3));
        }
    }
}
