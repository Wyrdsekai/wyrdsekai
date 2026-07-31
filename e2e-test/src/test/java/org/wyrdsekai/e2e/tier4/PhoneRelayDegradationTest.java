package org.wyrdsekai.e2e.tier4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.LlamaDockerFixture;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phone relay degradation — desktop offline → local fallback → desktop recovery.
 * Two real llama-server Docker containers that can be stopped/restarted.
 */
@Tag("relay")
class PhoneRelayDegradationTest {

    private static final String PHONE_MODEL = "Qwen3-0.6B-Q8_0.gguf";
    private static final String DESKTOP_MODEL = "Qwen3-4B-Q4_K_M.gguf";

    private static LlamaDockerFixture llamaPhone;
    private static LlamaDockerFixture llamaDesktop;
    private static NatsServerFixture nats;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaDockerFixture.assumeAvailable();
        LlamaDockerFixture.assumeModelAvailable(PHONE_MODEL);
        LlamaDockerFixture.assumeModelAvailable(DESKTOP_MODEL);
        NatsServerFixture.assumeAvailable();

        llamaPhone = new LlamaDockerFixture(
            "phone-deg", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaDesktop = new LlamaDockerFixture(
            "desktop-deg", DESKTOP_MODEL, PortAllocator.allocate(), 4096);

        llamaPhone.start();
        llamaDesktop.start();

        nats = new NatsServerFixture();
        nats.start();

        var desktopBackend = llamaDesktop.createBackend("desktop-relay", 10);
        var phoneBackend = llamaPhone.createBackend("phone-local", 100);

        server = new TestServerBootstrap(List.of(desktopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (nats != null) nats.stop();
        if (llamaDesktop != null) llamaDesktop.stop();
        if (llamaPhone != null) llamaPhone.stop();
    }

    @Test
    void desktop_offline_falls_back_to_local() throws Exception {
        // Kill the desktop container
        llamaDesktop.stop();

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // Should still get response from phone's local 0.6B
            var greeting = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(greeting,
                "[HARD] Should get greeting via phone-local fallback");
        }

        // Restart for other tests
        llamaDesktop.restart();
    }

    @Test
    void desktop_reconnect_restores_quality() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            // Desktop goes down mid-conversation
            llamaDesktop.stop();
            Thread.sleep(2000);

            // Falls back to phone-local
            ws.sendSay("nexus", "Hello after desktop died.");
            var fallbackResp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(fallbackResp,
                "[HARD] Should get fallback response from phone-local");

            // Restart desktop
            llamaDesktop.restart();
            Thread.sleep(15_000); // Wait for health check recovery

            ws.sendSay("nexus", "Hello after desktop is back.");
            var recoveredResp = ws.waitForProse(Duration.ofSeconds(60));
            assertNotNull(recoveredResp,
                "[HARD] Should get response after desktop recovery");
        }
    }

    @Test
    void graceful_degradation_preserves_conversation() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            // Start conversation via desktop
            ws.sendSay("nexus", "Let's have a chat.");
            ws.waitForProse(Duration.ofSeconds(60));

            // Desktop dies
            llamaDesktop.stop();
            Thread.sleep(2000);

            // Continue via phone-local — conversation shouldn't crash
            ws.sendSay("nexus", "Are you still there?");
            var resp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(resp,
                "[HARD] Conversation should continue via phone-local fallback");

            // Restart for cleanup
            llamaDesktop.restart();
        }
    }
}
