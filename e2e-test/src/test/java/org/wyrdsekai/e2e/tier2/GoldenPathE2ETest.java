package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.InferenceServerFixture;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Golden path E2E — quality + continuity assertions.
 *
 * <p>Hard assertions: pipeline operates correctly.
 * <p>Soft assertions: semantic domain engagement, conversation continuity.
 */
@Tag("e2e")
class GoldenPathE2ETest {

    private static final Duration INFERENCE_TIMEOUT = timeout(Duration.ofSeconds(60));

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-golden");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void response_mentions_role_or_identity() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(INFERENCE_TIMEOUT); // drain greeting

            ws.sendSay("nexus", "Who are you? What is your role here?");
            var response = ws.waitForProse(INFERENCE_TIMEOUT);

            // === HARD: Got a response ===
            assertProseReceived(response, "identity question");

            // === SOFT: Does the agent engage with identity/role? ===
            softAssertSubstantive(response, "GoldenPath.identity", 30);
            softAssertMentions(response, "GoldenPath.identity",
                "companion", "guide", "assistant", "help", "here", "nexus",
                "welcome", "world", "wyrd", "agent", "name", "role");
        }
    }

    @Test
    void hints_present_in_room_state() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));

            // === HARD: Foundation room structure ===
            assertFoundationRoom(roomState);

            var room = roomState.path("room");
            var exits = room.path("exits");
            assertTrue(exits.isArray() && exits.size() > 0,
                "[HARD] Nexus should have exits");
        }
    }

    @Test
    void conversation_continuity() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(INFERENCE_TIMEOUT);

            // Introduce a name
            ws.sendSay("nexus", "My name is Zephyr. Please remember it.");
            var ack = ws.waitForProse(INFERENCE_TIMEOUT);

            // === HARD: Agent responded to introduction ===
            assertProseReceived(ack, "name introduction acknowledgment");

            // Ask about the name
            ws.sendSay("nexus", "What is my name?");
            var recall = ws.waitForProse(INFERENCE_TIMEOUT);

            // === HARD: Agent responded to recall question ===
            assertProseReceived(recall, "name recall question");

            // === SOFT: Did the agent actually recall "Zephyr"? ===
            boolean recalled = softAssertContinuity(recall, "GoldenPath.continuity", "zephyr");
            if (!recalled) {
                System.out.println("[E2E GoldenPath.continuity] Ack response: " +
                    ack.path("text").asText());
                System.out.println("[E2E GoldenPath.continuity] Recall response: " +
                    recall.path("text").asText());
            }
        }
    }
}
