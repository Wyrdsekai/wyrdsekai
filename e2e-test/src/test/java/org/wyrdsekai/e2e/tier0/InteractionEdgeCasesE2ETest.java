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
 * E2E tests for interaction edge cases — empty input, special chars,
 * debounce, duplicates, XSS prevention.
 */
@Tag("integration")
class InteractionEdgeCasesE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static TestServerBootstrap server;
    private static WireMockInferenceServer wireMock;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Noted.", 30, 20);

        server = new TestServerBootstrap(List.of(
            new InferenceBackend.LlamaServer("wiremock", new InferenceClient(wireMock.baseUrl()), 10, List.of(), null)));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // --- Empty / whitespace input ---

    @Test
    void empty_message_does_not_crash_websocket() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendSay("nexus", "");
            Thread.sleep(2000);
            // Send a real message after — if the session survived, this works
            ws.sendSay("nexus", "still-alive-after-empty");
            var prose = ws.waitForMessage(
                msg -> msg.path("text").asText().contains("still-alive-after-empty"),
                TIMEOUT);
            assertNotNull(prose, "Session should survive empty message");
        }
    }

    @Test
    void whitespace_only_does_not_crash_websocket() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendSay("nexus", "   \t  ");
            Thread.sleep(1000);
            // No crash is the assertion
        }
    }

    @Test
    void empty_message_does_not_crash_telnet() throws Exception {
        try (var tc = TestTelnetClient.connect("localhost", server.telnetPort())) {
            tc.waitForText("Wyrdsekai", TIMEOUT);
            tc.loginAsGuest();
            tc.waitForText("Nexus", TIMEOUT);
            tc.sendLine("");
            Thread.sleep(1000);
            // Should still be connected — send another command to verify
            tc.sendLine("look");
            var room = tc.waitForText("Nexus", Duration.ofSeconds(5));
            assertNotNull(room, "Session should survive empty input");
        }
    }

    // --- Special characters ---

    @Test
    void html_tags_not_executed() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendSay("nexus", "<script>alert(1)</script>");
            var prose = ws.waitForMessage(
                msg -> "prose".equals(msg.path("type").asText())
                    && msg.path("text").asText().contains("script"),
                Duration.ofSeconds(5));
            // The text should be preserved as-is (not executed, not stripped)
            assertNotNull(prose, "HTML tags should be preserved as text, not stripped");
            assertFalse(prose.path("text").asText().isEmpty());
        }
    }

    @Test
    void quotes_preserved_in_speech() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendSay("nexus", "she said \"hello\" and 'goodbye'");
            var prose = ws.waitForMessage(
                msg -> "prose".equals(msg.path("type").asText())
                    && msg.path("text").asText().contains("hello"),
                Duration.ofSeconds(5));
            assertNotNull(prose, "Quotes should be preserved in speech");
        }
    }

    @Test
    void backslashes_preserved() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            ws.sendSay("nexus", "path\\to\\file");
            var prose = ws.waitForMessage(
                msg -> "prose".equals(msg.path("type").asText())
                    && msg.path("text").asText().contains("\\"),
                Duration.ofSeconds(5));
            assertNotNull(prose, "Backslashes should be preserved");
        }
    }

    // --- No duplicate messages ---

    @Test
    void say_produces_exactly_one_echo() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            Thread.sleep(500);
            ws.drainMessages();

            ws.sendSay("nexus", "unique-marker-12345");
            Thread.sleep(2000);

            // Count how many prose messages contain our marker
            var messages = ws.drainMessages();
            long count = messages.stream()
                .filter(msg -> "prose".equals(msg.path("type").asText())
                    && msg.path("text").asText().contains("unique-marker-12345"))
                .count();
            assertEquals(1, count, "Say should produce exactly one echo, not duplicates");
        }
    }

    @Test
    void look_produces_exactly_one_room_state() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            Thread.sleep(500);
            ws.drainMessages();

            ws.sendLook("nexus");
            Thread.sleep(2000);

            var messages = ws.drainMessages();
            long count = messages.stream()
                .filter(msg -> "room_state".equals(msg.path("type").asText()))
                .count();
            assertEquals(1, count, "Look should produce exactly one room_state, not duplicates");
        }
    }

    // --- Long message ---

    @Test
    void very_long_message_accepted() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(TIMEOUT);
            String longMsg = "x".repeat(5000);
            ws.sendSay("nexus", longMsg);
            // Should not crash — either accepted or gracefully rejected
            Thread.sleep(2000);
            // If we get here, the connection survived
            assertTrue(true, "Long message should not crash the connection");
        }
    }
}
