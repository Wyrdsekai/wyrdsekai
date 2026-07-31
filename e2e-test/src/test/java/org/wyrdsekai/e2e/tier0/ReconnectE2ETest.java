package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.wyrdsekai.e2e.infra.*;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E tests for reconnection behavior — message replay, state persistence, dedup.
 */
@Tag("integration")
class ReconnectE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome back.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void websocket_reconnect_gets_room_state() throws Exception {
        // First connection
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl())) {
            var room = ws1.waitForRoomState(TIMEOUT);
            assertNotNull(room, "First connection should get room state");
        }

        Thread.sleep(1000); // Let server process disconnect

        // Second connection — should get room state again
        try (var ws2 = TestWebSocketClient.connect(server.baseUrl())) {
            var room = ws2.waitForRoomState(TIMEOUT);
            assertNotNull(room, "Reconnected session should get room state");
        }
    }

    @Test
    void telnet_reconnect_enters_room() throws Exception {
        // First connection
        try (var tc1 = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc1.waitForText("Wyrdsekai", TIMEOUT);
            tc1.loginAsGuest();
            tc1.waitForText("Nexus", TIMEOUT);
        }

        // Second connection — should still be able to enter
        try (var tc2 = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc2.waitForText("Wyrdsekai", TIMEOUT);
            tc2.loginAsGuest();
            tc2.waitForText("Nexus", TIMEOUT);
        }
    }

    @Test
    void rapid_reconnect_no_crash() throws Exception {
        // Connect and disconnect rapidly 5 times
        for (int i = 0; i < 5; i++) {
            try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
                ws.waitForRoomState(Duration.ofSeconds(5));
            }
            Thread.sleep(100);
        }
        // Final connection should still work
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var room = ws.waitForRoomState(TIMEOUT);
            assertNotNull(room, "Server should handle rapid reconnects");
        }
    }

    @Test
    void rapid_telnet_reconnect_no_crash() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
                tc.waitForText("Wyrdsekai", Duration.ofSeconds(5));
                tc.loginAsGuest();
                tc.waitForText("Nexus", Duration.ofSeconds(5));
            }
            Thread.sleep(200);
        }
        // Final connection should work
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            var room = tc.waitForText("Nexus", TIMEOUT);
            assertNotNull(room, "Server should handle rapid telnet reconnects");
        }
    }
}
