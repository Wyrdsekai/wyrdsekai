package org.wyrdsekai.e2e.tier4;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.LlamaDockerFixture;
import org.wyrdsekai.e2e.infra.NatsServerFixture;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.SharedLlamaPool;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Phone relay happy path — phone node routes inference to desktop's real 4B model.
 * Two real llama-server Docker containers: 0.6B (phone) + 4B (laptop/desktop).
 */
@Tag("relay")
class PhoneRelayHappyPathTest {

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

        llamaPhone = SharedLlamaPool.acquire(
            "phone", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaDesktop = SharedLlamaPool.acquire(
            "desktop", DESKTOP_MODEL, PortAllocator.allocate(), 4096);

        nats = new NatsServerFixture();
        nats.start();

        // Desktop = primary (lower priority number), Phone = fallback
        var desktopBackend = llamaDesktop.createBackend("desktop-relay", 10);
        var phoneBackend = llamaPhone.createBackend("phone-local", 100);

        server = new TestServerBootstrap(List.of(desktopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (nats != null) nats.stop();
    }

    @Test
    void phone_routes_to_desktop_quality_response() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            ws.sendSay("nexus", "Tell me about yourself.");
            var response = ws.waitForProse(Duration.ofSeconds(60));

            assertProseReceived(response, "desktop relay response");
            softAssertSubstantive(response, "PhoneRelay.quality", 50);
        }
    }

    @Test
    void phone_receives_coherent_response() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            ws.sendSay("nexus", "What can you see in this room?");
            var response = ws.waitForProse(Duration.ofSeconds(60));

            assertProseReceived(response, "coherent room response");
            softAssertMentions(response, "PhoneRelay.coherent",
                "nexus", "crystal", "room", "see", "connection", "hub");
        }
    }
}
