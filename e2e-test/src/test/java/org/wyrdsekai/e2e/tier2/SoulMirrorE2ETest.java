package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Soul Mirror room E2E — tests mirror script interaction (§87.2).
 * WireMock-based (tier2), deterministic.
 */
@Tag("tier2")
class SoulMirrorE2ETest {

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(15);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("The mirror awaits.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of(SoulRoomSeeds.soulMirror()));
        server.start();

        addNexusExit("up", "soul-mirror", "The Soul Mirror");
    }

    private static void addNexusExit(String direction, String targetRoom, String label)
            throws Exception {
        RoomRegistry.get().<RoomResponse>askRoom("nexus",
            ref -> new RoomCommand.AddExit(direction, targetRoom, label, ref),
            Duration.ofSeconds(5)
        ).toCompletableFuture().get();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    private TestWebSocketClient enterMirror() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));

        ws.sendGo("nexus", "up");
        var mirrorState = ws.waitForRoomState(Duration.ofSeconds(10));
        assertNotNull(mirrorState, "[HARD] Should receive Soul Mirror room state");
        assertEquals("soul-mirror",
            mirrorState.path("room").path("roomId").asText(),
            "[HARD] Should be in The Soul Mirror");
        return ws;
    }

    @Test
    void look_mirror_no_soul_shows_dark() throws Exception {
        try (var ws = enterMirror()) {
            drainProse(ws);

            ws.sendSay("soul-mirror", "look mirror");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'look mirror'");
            var text = response.path("text").asText();
            // Without a soul manifest, mirror should be dark
            assertTrue(text.contains("dark") || text.contains("no soul"),
                "[SOFT] Mirror response should mention dark/no soul, got: '" + text + "'");
        }
    }

    @Test
    void examine_drift_responds() throws Exception {
        try (var ws = enterMirror()) {
            drainProse(ws);

            ws.sendSay("soul-mirror", "examine drift");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'examine drift'");
        }
    }

    @Test
    void touch_mirror_responds() throws Exception {
        try (var ws = enterMirror()) {
            drainProse(ws);

            ws.sendSay("soul-mirror", "touch mirror");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'touch mirror'");
            var text = response.path("text").asText();
            assertFalse(text.isBlank(),
                "[HARD] Touch mirror response should not be blank");
        }
    }

    @Test
    void meditate_responds() throws Exception {
        try (var ws = enterMirror()) {
            drainProse(ws);

            ws.sendSay("soul-mirror", "meditate");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'meditate'");
        }
    }

    @Test
    void look_around_shows_chamber() throws Exception {
        try (var ws = enterMirror()) {
            drainProse(ws);

            ws.sendSay("soul-mirror", "look around");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'look around'");
            var text = response.path("text").asText();
            assertTrue(text.contains("mirror") || text.contains("drift"),
                "[SOFT] Room description should mention mirror or drift-stones");
        }
    }

    private void drainProse(TestWebSocketClient ws) {
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.drainMessages();
    }
}
