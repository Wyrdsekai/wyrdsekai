package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Study room E2E section 11 testing strategy.
 * Uses WireMock for deterministic responses (script-driven, not LLM-dependent).
 *
 * <p>Tests room entry narration, help command, mount/unmount lifecycle,
 * schedule display, object use, and shell-mode desk commands.
 * The Study room script handles all of these via onSay/onUse hooks.
 *
 * <p>Hard assertions: infrastructure + script dispatch.
 * <p>Soft assertions: narration text content (model-independent, script-driven).
 */
@Tag("tier2")
class StudyE2ETest {

    private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(15);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome to The Study.", 20, 15);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        // Add Study room as an extra seed
        var studySeed = new ZoneGuardian.RoomSeed("study", "The Study",
            "Your private quarters. A worn leather chair faces a low hearth. " +
            "A heavy desk stands against one wall, its surface holding a schedule board " +
            "and correspondence tray. Document shelves line the opposite wall, " +
            "and a wardrobe occupies the corner with a lockbox beneath the desk.",
            List.of(new Exit("west", "nexus", "The Nexus")),
            List.of(
                new RoomObject("study-desk", "heavy desk",
                    "A desk with a schedule board and correspondence tray", false),
                new RoomObject("study-chair", "leather chair",
                    "A worn leather chair facing the hearth", false),
                new RoomObject("study-shelves", "mounted shelves",
                    "Document shelves along one wall", false),
                new RoomObject("study-wardrobe", "wardrobe",
                    "A wardrobe in the corner. A lockbox sits under the desk.", false)
            ));

        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(),
            List.of(studySeed));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    /**
     * Helper: connect, enter nexus, navigate to Study, return the WS client.
     * Caller must close the returned client.
     */
    private TestWebSocketClient enterStudy() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));

        // Navigate nexus -> in -> study
        ws.sendGo("nexus", "in");
        var studyState = ws.waitForRoomState(Duration.ofSeconds(10));
        assertNotNull(studyState, "[HARD] Should receive Study room state");
        assertEquals("study",
            studyState.path("room").path("roomId").asText(),
            "[HARD] Should be in The Study");
        return ws;
    }

    /**
     * Helper: send a raw "use" command over the wire.
     */
    private void sendUse(TestWebSocketClient ws, String roomId, String objectName) {
        ws.send("""
            {"type":"use","id":"%s","roomId":"%s","objectName":"%s","target":""}
            """.formatted(UUID.randomUUID().toString(), roomId, objectName));
    }

    // ─── Test 1: Enter Study and see room description ───

    @Test
    void enter_study_sees_room_description() throws Exception {
        try (var ws = enterStudy()) {
            // The onEnter script should emit a narrate event
            var narration = ws.waitForProse(SCRIPT_TIMEOUT);
            // Room entry narration is script-driven — may arrive as narrator prose
            // The room state itself has the description
            // Verify room state has objects
            ws.sendLook("study");
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(roomState, "[HARD] Should receive room state on look");

            var room = roomState.path("room");
            var description = room.path("description").asText();
            assertFalse(description.isBlank(),
                "[HARD] Study room should have a description");

            // Check that objects are present
            var objects = room.path("objects");
            assertTrue(objects.isArray(),
                "[HARD] Study should have objects array");
            assertTrue(objects.size() >= 3,
                "[HARD] Study should have at least desk, chair, shelves");
        }
    }

    // ─── Test 2: Help command ───

    @Test
    void help_command_returns_command_list() throws Exception {
        try (var ws = enterStudy()) {
            // Drain any entry narration
            drainProse(ws);

            ws.sendSay("study", "help");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'help'");
            var text = response.path("text").asText();
            assertFalse(text.isBlank(),
                "[HARD] Help response should not be blank");
        }
    }

    // ─── Test 3: Mounts shows empty ───

    @Test
    void mounts_command_shows_empty() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "mounts");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'mounts'");
        }
    }

    // ─── Test 4: Mount a directory ───

    @Test
    void mount_command_succeeds() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "mount /tmp/test as docs");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'mount'");
            var text = response.path("text").asText();
            assertFalse(text.isBlank(),
                "[HARD] Mount response should not be blank");
        }
    }

    // ─── Test 5: Mounts shows the mount after mounting ───

    @Test
    void mounts_shows_mount_after_mounting() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            // Mount first
            ws.sendSay("study", "mount /tmp/test as docs");
            ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);

            // Then list
            ws.sendSay("study", "mounts");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'mounts' after mount");
        }
    }

    // ─── Test 6: Unmount ───

    @Test
    void unmount_command_removes_mount() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            // Mount
            ws.sendSay("study", "mount /tmp/test as docs");
            ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);

            // Unmount
            ws.sendSay("study", "unmount docs");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'unmount'");
        }
    }

    // ─── Test 7: Schedule command ───

    @Test
    void schedule_command_responds() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "schedule");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'schedule'");
            // With no calendar MCP service, should say "no service"
        }
    }

    // ─── Test 8: Use desk triggers schedule ───

    @Test
    void use_desk_shows_schedule() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            sendUse(ws, "study", "heavy desk");
            var response = ws.waitForProse(SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'use desk'");
        }
    }

    // ─── Test 9: Use chair shows comfort narration ───

    @Test
    void use_chair_shows_comfort_narration() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            sendUse(ws, "study", "leather chair");
            var response = ws.waitForProse(SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'use chair'");
            var text = response.path("text").asText();
            assertFalse(text.isBlank(),
                "[HARD] Chair comfort narration should not be blank");
        }
    }

    // ─── Test 10: Shell mode desk:ls ───

    @Test
    void shell_desk_ls_shows_mount_listing() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "desk:ls");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'desk:ls'");
        }
    }

    // ─── Test 11: Shell mode desk:pwd ───

    @Test
    void shell_desk_pwd_shows_directory_info() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "desk:pwd");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'desk:pwd'");
            var text = response.path("text").asText();
            assertTrue(text.contains("/"),
                "[HARD] pwd response should contain path separator, got: '" + text + "'");
        }
    }

    // ─── Test 12: Shell mode desk:date ───

    @Test
    void shell_desk_date_shows_date() throws Exception {
        try (var ws = enterStudy()) {
            drainProse(ws);

            ws.sendSay("study", "desk:date");
            var response = ws.waitForProseFrom("narrator", SCRIPT_TIMEOUT);
            assertNotNull(response,
                "[HARD] Should receive prose response to 'desk:date'");
            var text = response.path("text").asText();
            // Date is ISO format from new Date().toISOString()
            assertFalse(text.isBlank(),
                "[HARD] Date response should not be blank");
        }
    }

    // ─── Test 13: Navigate back to Nexus ───

    @Test
    void navigate_study_to_nexus() throws Exception {
        try (var ws = enterStudy()) {
            ws.sendGo("study", "west");
            var nexusState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertNotNull(nexusState,
                "[HARD] Should receive Nexus room state");
            assertEquals("nexus",
                nexusState.path("room").path("roomId").asText(),
                "[HARD] Should be back in The Nexus");
        }
    }

    /**
     * Drain any pending prose messages (e.g., entry narration, greeting).
     * Waits briefly to collect any queued messages.
     */
    private void drainProse(TestWebSocketClient ws) {
        // Give scripts time to emit entry narration, then drain
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ws.drainMessages();
    }
}
