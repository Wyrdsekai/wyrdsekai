package org.wyrdsekai.e2e.tier5;

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
 * Household degradation cascade — laptop down → phone standalone → recovery.
 * Two real llama-server Docker containers (0.6B phone + 4B laptop) that can
 * be stopped/restarted independently.
 *
 * <p>Note: Desktop (30B) requires more VRAM than available on single GPU.
 * When a remote desktop machine is available, add it as a third backend via
 * {@code WYRDSEKAI_INFERENCE_URL}.
 */
@Tag("household")
class HouseholdDegradationCascadeTest {

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

        llamaPhone = new LlamaDockerFixture(
            "phone-cascade", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaLaptop = new LlamaDockerFixture(
            "laptop-cascade", LAPTOP_MODEL, PortAllocator.allocate(), 4096);

        llamaPhone.start();
        llamaLaptop.start();

        nats = new NatsServerFixture();
        nats.start();

        var laptopBackend = llamaLaptop.createBackend("laptop-4b", 1);
        var phoneBackend = llamaPhone.createBackend("phone-0.6b", 100);

        server = new TestServerBootstrap(
            List.of(laptopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (nats != null) nats.stop();
        if (llamaLaptop != null) llamaLaptop.stop();
        if (llamaPhone != null) llamaPhone.stop();
    }

    @Test
    void laptop_down_phone_standalone() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(90));

            // Laptop goes down
            llamaLaptop.stop();
            Thread.sleep(2000);

            // Phone (0.6B) should handle inference now
            ws.sendSay("nexus", "Can you still respond?");
            var resp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(resp,
                "[HARD] Phone should take over when laptop is down");

            // Restart laptop for other tests
            llamaLaptop.restart();
        }
    }

    @Test
    void all_down_then_phone_recovers() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(90));

            // Both go down
            llamaLaptop.stop();
            llamaPhone.stop();
            Thread.sleep(2000);

            // No inference available — system should not crash
            ws.sendSay("nexus", "Anyone there?");
            // May get null or degraded — that's OK, testing crash resistance
            var resp = ws.waitForProse(Duration.ofSeconds(30));
            System.out.println("[E2E Cascade] Both down response: " +
                (resp != null ? resp.path("text").asText() : "null (expected)"));

            // Phone comes back
            llamaPhone.restart();
            Thread.sleep(5000);

            ws.sendSay("nexus", "Phone back?");
            var phoneResp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(phoneResp,
                "[HARD] Phone recovery should restore inference");
        }

        // Restart laptop for cleanup
        if (!llamaLaptop.isRunning()) llamaLaptop.restart();
    }

    @Test
    void cascade_recovery() throws Exception {
        // Ensure both backends are running before this test
        // (prior tests may have left them stopped)
        if (!llamaLaptop.isRunning()) llamaLaptop.restart();
        if (!llamaPhone.isRunning()) llamaPhone.restart();
        Thread.sleep(5000); // Let health checks recover

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // Send a message to confirm pipeline is working
            // (don't wait for greeting — companion may be degraded from prior tests)
            ws.sendSay("nexus", "Status check.");
            var warmup = ws.waitForProse(Duration.ofSeconds(120));
            assertNotNull(warmup, "[HARD] Pipeline should respond before cascade test");

            // Kill laptop
            llamaLaptop.stop();
            Thread.sleep(2000);

            // Phone standalone responds
            ws.sendSay("nexus", "Alone now.");
            var phoneResp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(phoneResp, "[HARD] Phone standalone should work");

            // Laptop recovers
            llamaLaptop.restart();
            Thread.sleep(15_000); // Wait for health check

            ws.sendSay("nexus", "Laptop back?");
            var laptopResp = ws.waitForProse(Duration.ofSeconds(90));
            assertNotNull(laptopResp,
                "[HARD] Should route to recovered laptop");
        }
    }
}
