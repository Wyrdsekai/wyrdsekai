package org.wyrdsekai.e2e.tier4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.LlamaDockerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.SharedLlamaPool;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Phone context pressure — tests that the system handles many exchanges
 * without crashing under context window pressure.
 * Uses real 4B llama-server for substantive responses.
 */
@Tag("relay")
class PhoneContextPressureTest {

    private static final String DESKTOP_MODEL = "Qwen3-4B-Q4_K_M.gguf";

    private static LlamaDockerFixture llamaDesktop;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaDockerFixture.assumeAvailable();
        LlamaDockerFixture.assumeModelAvailable(DESKTOP_MODEL);

        llamaDesktop = SharedLlamaPool.acquire(
            "desktop-ctx", DESKTOP_MODEL, PortAllocator.allocate(), 4096);

        var backend = llamaDesktop.createBackend("desktop", 10);
        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void system_handles_many_exchanges_gracefully() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            for (int i = 0; i < 10; i++) {
                ws.sendSay("nexus", "This is exchange number " + (i + 1) +
                    " — I'm testing context window behavior with lots of messages.");
                var resp = ws.waitForProse(Duration.ofSeconds(60));
                assertNotNull(resp,
                    "[HARD] Should get response for exchange " + (i + 1));
            }

            ws.sendSay("nexus", "Are you still coherent?");
            var finalResp = ws.waitForProse(Duration.ofSeconds(60));
            assertNotNull(finalResp,
                "[HARD] System should remain responsive under context pressure");
        }
    }

    @Test
    void desktop_retains_longer_history() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            for (int i = 0; i < 5; i++) {
                ws.sendSay("nexus",
                    "Exchange " + (i + 1) + ": The weather is lovely today.");
                ws.waitForProse(Duration.ofSeconds(60));
            }

            ws.sendSay("nexus", "What have we been talking about?");
            var resp = ws.waitForProse(Duration.ofSeconds(60));

            assertProseReceived(resp, "extended conversation response");
            softAssertContinuity(resp, "ContextPressure.history", "weather");
        }
    }
}
