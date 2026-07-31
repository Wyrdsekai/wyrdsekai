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
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Rapid-fire message handling — debounce and deferred-while-thinking.
 *
 * <p>Hard assertions: system survives rapid messages without crash,
 * at least one response received.
 * <p>Soft assertions: debounce collapses messages (fewer responses than inputs).
 */
@Tag("e2e")
class RapidFireE2ETest {

    private static final Duration INFERENCE_TIMEOUT = timeout(Duration.ofSeconds(60));

    private static E2eTestSupport.DualSetupResult inferenceSetup;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        inferenceSetup = E2eTestSupport.setupDualInference("e2e-rapid");
        server = new TestServerBootstrap(inferenceSetup.backends());
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void rapid_messages_debounced_to_few_responses() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(INFERENCE_TIMEOUT); // drain greeting

            // Fire 5 messages in rapid succession (~100ms apart)
            for (int i = 1; i <= 5; i++) {
                ws.sendSay("nexus", "Message number " + i);
                Thread.sleep(100);
            }

            // Wait for all responses to settle
            Thread.sleep(5000);
            var messages = ws.drainMessages();
            var proseCount = messages.stream()
                .filter(m -> "prose".equals(m.path("type").asText()))
                .count();

            // === HARD: Got at least one response (system didn't crash) ===
            assertTrue(proseCount >= 1,
                "[HARD] Should get at least 1 response from rapid messages, got: " + proseCount);

            // === SOFT: Debounce should collapse some messages ===
            if (proseCount < 5) {
                System.out.println("[E2E RapidFire] Debounce working: " +
                    proseCount + " responses for 5 inputs.");
            } else {
                System.out.println("[E2E RapidFire WARN] Got " + proseCount +
                    " responses for 5 inputs — debounce may not be effective. " +
                    "This could be due to fast inference or timing.");
            }
        }
    }

    @Test
    void message_while_thinking_deferred() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(INFERENCE_TIMEOUT);

            // Send a message that triggers inference
            ws.sendSay("nexus", "Tell me a long story about a dragon.");

            // Immediately send another while companion is thinking
            Thread.sleep(200);
            ws.sendSay("nexus", "Actually, make it about a phoenix.");

            // === HARD: Got at least one response (deferred didn't crash) ===
            var response = ws.waitForProse(timeout(Duration.ofSeconds(90)));
            assertProseReceived(response, "deferred message handling");

            // === SOFT: Response content ===
            softAssertSubstantive(response, "RapidFire.deferred", 20);
        }
    }
}
