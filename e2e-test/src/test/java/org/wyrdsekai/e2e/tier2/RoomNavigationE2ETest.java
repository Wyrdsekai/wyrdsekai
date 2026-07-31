package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Room navigation E2E — moving between Foundation rooms.
 * Uses real inference backend (SGLang/llama-server), so needs longer timeouts
 * for companion greetings during room transitions.
 */
@Tag("e2e")
class RoomNavigationE2ETest {

    private static final Duration MOVE_TIMEOUT = Duration.ofSeconds(15);

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-nav");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void nexus_to_terminal_and_back() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            var roomState = ws.waitForRoomState(MOVE_TIMEOUT);
            assertEquals("nexus", roomState.path("room").path("roomId").asText());

            // Nexus → west → Terminal (east is The Docks); see TestServerBootstrap.foundationRoomSeeds.
            ws.sendGo("nexus", "west");
            var terminalState = ws.waitForRoomState(MOVE_TIMEOUT);
            assertNotNull(terminalState);
            assertEquals("terminal",
                terminalState.path("room").path("roomId").asText());

            ws.sendGo("terminal", "east");
            var nexusState = ws.waitForRoomState(MOVE_TIMEOUT);
            assertNotNull(nexusState);
            assertEquals("nexus",
                nexusState.path("room").path("roomId").asText());
        }
    }

    @Test
    void visit_all_foundation_rooms() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(MOVE_TIMEOUT);

            // Foundation compass (see TestServerBootstrap.foundationRoomSeeds):
            // nexus west→terminal, south→vault, east→docks, north→bridge.
            ws.sendGo("nexus", "west");
            var state = ws.waitForRoomState(MOVE_TIMEOUT);
            assertEquals("terminal", state.path("room").path("roomId").asText());

            ws.sendGo("terminal", "east");
            ws.waitForRoomState(MOVE_TIMEOUT);

            ws.sendGo("nexus", "south");
            state = ws.waitForRoomState(MOVE_TIMEOUT);
            assertEquals("vault", state.path("room").path("roomId").asText());

            ws.sendGo("vault", "north");
            ws.waitForRoomState(MOVE_TIMEOUT);

            ws.sendGo("nexus", "east");
            state = ws.waitForRoomState(MOVE_TIMEOUT);
            assertEquals("docks", state.path("room").path("roomId").asText());

            ws.sendGo("docks", "west");
            ws.waitForRoomState(MOVE_TIMEOUT);

            ws.sendGo("nexus", "north");
            state = ws.waitForRoomState(MOVE_TIMEOUT);
            assertEquals("bridge", state.path("room").path("roomId").asText());
        }
    }
}
