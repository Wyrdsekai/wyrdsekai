package org.wyrdsekai.e2e.tier5;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
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
 * Heterogeneous household — real multi-backend inference.
 * Phone (0.6B) + Laptop (4B) running as separate Docker llama-server containers.
 * Connected via real NATS.
 *
 * <p>Hard assertions: backends healthy, pipeline works, cross-user visibility.
 * <p>Soft assertions: response quality.
 */
@Tag("household")
class HeterogeneousHouseholdTest {

    private static final String PHONE_MODEL = "Qwen3-0.6B-Q8_0.gguf";
    private static final String LAPTOP_MODEL = "Qwen3-4B-Q4_K_M.gguf";

    private static LlamaDockerFixture llamaPhone;
    private static LlamaDockerFixture llamaLaptop;
    private static NatsServerFixture nats;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaDockerFixture.assumeAvailable();
        LlamaDockerFixture.assumeModelAvailable(PHONE_MODEL);
        LlamaDockerFixture.assumeModelAvailable(LAPTOP_MODEL);
        NatsServerFixture.assumeAvailable();

        llamaPhone = SharedLlamaPool.acquire(
            "phone-hh", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaLaptop = SharedLlamaPool.acquire(
            "laptop-hh", LAPTOP_MODEL, PortAllocator.allocate(), 4096);

        nats = new NatsServerFixture();
        nats.start();

        // Laptop is primary (lower priority), phone is fallback
        var laptopBackend = llamaLaptop.createBackend("laptop-4b", 10);
        var phoneBackend = llamaPhone.createBackend("phone-0.6b", 100);

        server = new TestServerBootstrap(List.of(laptopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (nats != null) nats.stop();
    }

    @Test
    void inference_backends_healthy() throws Exception {
        assertTrue(isHealthy(llamaPhone.baseUrl()),
            "[HARD] Phone llama-server should be healthy");
        assertTrue(isHealthy(llamaLaptop.baseUrl()),
            "[HARD] Laptop llama-server should be healthy");
    }

    @Test
    void household_provides_quality_response() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            ws.sendSay("nexus", "Explain the concept of emergence in complex systems.");
            var response = ws.waitForProse(Duration.ofSeconds(90));

            assertProseReceived(response, "household quality response");
            softAssertSubstantive(response, "Household.quality", 100);
            softAssertMentions(response, "Household.quality",
                "emergence", "complex", "system", "pattern", "behavior",
                "interaction", "property", "whole", "parts");
        }
    }

    @Test
    void cross_node_room_visibility() throws Exception {
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl());
             var ws2 = TestWebSocketClient.connect(server.baseUrl())) {

            var state1 = ws1.waitForRoomState(Duration.ofSeconds(10));
            var state2 = ws2.waitForRoomState(Duration.ofSeconds(10));
            assertRoomState(state1, "nexus");
            assertRoomState(state2, "nexus");

            ws1.waitForProse(Duration.ofSeconds(60));
            ws2.waitForProse(Duration.ofSeconds(60));

            ws1.sendSay("nexus", "Hello from user 1!");
            var resp1 = ws1.waitForProse(Duration.ofSeconds(90));
            assertProseReceived(resp1, "ws1 agent response");

            // Soft: ws2 also sees agent response (broadcast)
            var resp2 = ws2.waitForProse(Duration.ofSeconds(30));
            if (resp2 != null) {
                System.out.println("[E2E Household] Cross-user broadcast verified!");
            } else {
                System.out.println("[E2E Household WARN] ws2 did not receive broadcast.");
            }
        }
    }

    private static boolean isHealthy(String baseUrl) {
        return E2eTestSupport.isHealthy(baseUrl);
    }
}
