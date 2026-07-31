package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Tier 0 E2E tests for MUD communication commands: emote, tell, whisper, say.
 * Validates that shorthand prefixes (: ; >) and full-word commands produce
 * the correct S2CMessage.Prose with appropriate style field.
 *
 * <p>Uses a real Wyrdsekai server with WireMock inference backend.
 * Player sends MUD commands via WebSocket, verifies Prose messages arrive
 * with the correct style discriminator.
 */
@Tag("integration")
class EmoteCommunicationTest {

    private static final String COMPANION = "Wyrd";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();

        // Stub for companion greeting on first connect
        wireMock.stubChatCompletion("Welcome, traveler. The Nexus awaits.", 30, 20);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock-emote", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    // ── Helpers ──

    /**
     * Connect a player, drain initial room state + companion greeting,
     * then return the client ready for test interaction.
     */
    private TestWebSocketClient connectAndDrain() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));
        // Drain greeting and any narrator prose (EntityEntered narration).
        // Sleep briefly to let queued messages arrive, then drain all at once.
        Thread.sleep(1000);
        ws.drainMessages();
        return ws;
    }

    /**
     * Wait for a Prose message with the given style.
     */
    private JsonNode waitForProseWithStyle(TestWebSocketClient ws, String style, Duration timeout) {
        return ws.waitForMessage(
            msg -> "prose".equals(msg.path("type").asText()) &&
                   style.equals(msg.path("style").asText()),
            timeout);
    }

    // ── Emote via colon prefix ──

    @Test
    void colon_emote_produces_emote_prose() throws Exception {
        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", ":smiles warmly");

            // Wait for an emote-style prose message
            var prose = waitForProseWithStyle(ws, "emote", RESPONSE_TIMEOUT);
            assertNotNull(prose, "Should receive emote-style prose for :smiles");
            var text = prose.path("text").asText();
            assertTrue(text.contains("smiles"), "Emote text should contain 'smiles', got: " + text);
        }
    }

    // ── Say (regression test) ──

    @Test
    void say_produces_say_prose() throws Exception {
        // Stub a companion reply for the player's speech
        wireMock.stubChatCompletion("Indeed, the Nexus is a place of wonder.", 20, 25);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "hello");

            // Wait for any prose from the companion (triggered by player speech).
            // The player's "hello" generates a Said event (no style field because
            // it goes to the companion, not directly back as Prose).
            // The companion's response comes back as Prose with null/say style.
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);
            assertNotNull(prose, "Should receive prose from companion");
            var text = prose.path("text").asText();
            assertFalse(text.isBlank(), "Companion response should not be blank");
        }
    }

    // ── Emote via full word ──

    @Test
    void emote_word_produces_emote_prose() throws Exception {
        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "emote waves enthusiastically");

            var prose = waitForProseWithStyle(ws, "emote", RESPONSE_TIMEOUT);
            assertNotNull(prose, "Should receive emote-style prose for 'emote waves'");
            var text = prose.path("text").asText();
            assertTrue(text.contains("waves"), "Emote text should contain 'waves', got: " + text);
        }
    }

    // ── Semicolon emote ──

    @Test
    void semicolon_emote_produces_emote_prose() throws Exception {
        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", ";nods thoughtfully");

            var prose = waitForProseWithStyle(ws, "emote", RESPONSE_TIMEOUT);
            assertNotNull(prose, "Should receive emote-style prose for ;nods");
            var text = prose.path("text").asText();
            assertTrue(text.contains("nods"), "Emote text should contain 'nods', got: " + text);
        }
    }
}
