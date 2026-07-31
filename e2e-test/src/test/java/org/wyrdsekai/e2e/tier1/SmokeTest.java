package org.wyrdsekai.e2e.tier1;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * THE most important test — golden path with a real LLM.
 *
 * <p>Backend-agnostic: uses WYRDSEKAI_E2E_BACKEND env var to select
 * inference server (sglang, vllm, llama-server, claude).
 * Default is sglang (Docker, Qwen3-8B FP8).
 *
 * <p>If an external inference server is already running (e.g., started
 * by e2e-test.sh), the test will connect to it directly.
 *
 * <p>Hard assertions: pipeline works end-to-end.
 * <p>Soft assertions: response quality.
 */
@Tag("smoke")
class SmokeTest {

    private static final Duration INFERENCE_TIMEOUT = timeout(Duration.ofSeconds(60));

    private static E2eTestSupport.SetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupInference("smoke");
        server = new TestServerBootstrap(List.of(inferenceSetup.backend()));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void golden_path_boot_connect_greet_respond() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            // === HARD: Infrastructure pipeline ===

            // 1. Receive initial RoomState (Nexus)
            var roomState = ws.waitForRoomState(Duration.ofSeconds(10));
            assertRoomState(roomState, "nexus");
            assertFoundationRoom(roomState);

            // 2. Wait for companion greeting
            var greeting = ws.waitForProse(INFERENCE_TIMEOUT);
            assertProseReceived(greeting, "companion greeting on room entry");

            // 3. Send a message and receive response
            ws.sendSay("nexus", "Hello, what is this place?");
            var response = ws.waitForProse(INFERENCE_TIMEOUT);
            assertProseReceived(response, "companion response to player speech");

            // === SOFT: Response quality ===

            softAssertSubstantive(greeting, "SmokeTest.greeting", 10);
            softAssertSubstantive(response, "SmokeTest.response", 20);

            softAssertMentions(response, "SmokeTest.response",
                "nexus", "room", "place", "welcome", "world", "here");
        }
    }
}
