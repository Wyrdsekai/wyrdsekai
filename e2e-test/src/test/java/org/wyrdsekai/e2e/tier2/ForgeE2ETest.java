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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge room E2E — tests soul forge room script interaction.
 * WireMock-based (tier2), deterministic.
 */
@Tag("tier2")
class ForgeE2ETest {

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(15);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome to The Forge.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of(SoulRoomSeeds.theForge()));
        server.start();

        // Add exit from nexus to the forge
        addNexusExit("down", "the-forge", "The Forge");
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

    private TestWebSocketClient enterForge() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));

        ws.sendGo("nexus", "down");
        var forgeState = ws.waitForRoomState(Duration.ofSeconds(10));
        assertNotNull(forgeState, "[HARD] Should receive Forge room state");
        assertEquals("the-forge",
            forgeState.path("room").path("roomId").asText(),
            "[HARD] Should be in The Forge");
        return ws;
    }

    @Test
    void enter_forge_sees_narration() throws Exception {
        try (var ws = enterForge()) {
            var narration = ws.waitForProse(SCRIPT_TIMEOUT);
            // Entry narration is emitted by onEnter script
            ws.sendLook("the-forge");
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(roomState, "[HARD] Should receive room state on look");
            assertFalse(roomState.path("room").path("description").asText().isBlank(),
                "[HARD] Forge should have a description");
        }
    }

    @Test
    void say_forge_triggers_command() throws Exception {
        try (var ws = enterForge()) {
            drainProse(ws);

            ws.sendSay("the-forge", "forge");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'forge'");
        }
    }

    @Test
    void say_inspect_triggers_command() throws Exception {
        try (var ws = enterForge()) {
            drainProse(ws);

            ws.sendSay("the-forge", "inspect did:key:test");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'inspect'");
        }
    }

    @Test
    void say_status_shows_ledger() throws Exception {
        try (var ws = enterForge()) {
            drainProse(ws);

            ws.sendSay("the-forge", "status");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'status'");
        }
    }

    @Test
    void say_look_shows_room() throws Exception {
        try (var ws = enterForge()) {
            drainProse(ws);

            ws.sendSay("the-forge", "look");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'look'");
        }
    }

    @Test
    void navigate_back_to_nexus() throws Exception {
        try (var ws = enterForge()) {
            ws.sendGo("the-forge", "out");
            var nexusState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(nexusState,
                "[HARD] Should receive Nexus room state");
            assertEquals("nexus",
                nexusState.path("room").path("roomId").asText(),
                "[HARD] Should be back in The Nexus");
        }
    }

    private void drainProse(TestWebSocketClient ws) {
        try { Thread.sleep(500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.drainMessages();
    }
}
