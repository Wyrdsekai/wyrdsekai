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
 * Home room E2E — tests per-agent memory palace script interaction (§87.1).
 * WireMock-based (tier2), deterministic.
 */
@Tag("tier2")
class HomeE2ETest {

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(15);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome home.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of(SoulRoomSeeds.home()));
        server.start();

        addNexusExit("home", "home", "Your Home");
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

    private TestWebSocketClient enterHome() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));

        ws.sendGo("nexus", "home");
        var homeState = ws.waitForRoomState(Duration.ofSeconds(10));
        assertNotNull(homeState, "[HARD] Should receive Home room state");
        assertEquals("home",
            homeState.path("room").path("roomId").asText(),
            "[HARD] Should be in Home");
        return ws;
    }

    @Test
    void enter_home_sees_sanctuary() throws Exception {
        try (var ws = enterHome()) {
            // Entry narration from onEnter
            ws.sendLook("home");
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(roomState, "[HARD] Should receive room state on look");
            var desc = roomState.path("room").path("description").asText();
            assertFalse(desc.isBlank(),
                "[HARD] Home should have a description");
        }
    }

    @Test
    void say_status_shows_info() throws Exception {
        try (var ws = enterHome()) {
            drainProse(ws);

            ws.sendSay("home", "status");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'status'");
            var text = response.path("text").asText();
            assertFalse(text.isBlank(),
                "[HARD] Status response should not be blank");
        }
    }

    @Test
    void say_rest_responds() throws Exception {
        try (var ws = enterHome()) {
            drainProse(ws);

            ws.sendSay("home", "rest");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'rest'");
        }
    }

    @Test
    void say_wake_responds() throws Exception {
        try (var ws = enterHome()) {
            drainProse(ws);

            // First rest, then wake
            ws.sendSay("home", "rest");
            ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);

            ws.sendSay("home", "wake");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'wake'");
        }
    }

    @Test
    void say_check_mail_responds() throws Exception {
        try (var ws = enterHome()) {
            drainProse(ws);

            ws.sendSay("home", "check mail");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'check mail'");
        }
    }

    @Test
    void look_around_shows_room() throws Exception {
        try (var ws = enterHome()) {
            drainProse(ws);

            ws.sendSay("home", "look around");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'look around'");
        }
    }

    private void drainProse(TestWebSocketClient ws) {
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.drainMessages();
    }
}
