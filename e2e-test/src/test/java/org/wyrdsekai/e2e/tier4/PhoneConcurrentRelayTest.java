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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrent phone relay — multiple clients sharing real llama-server backends.
 * Tests that the system doesn't starve any client under concurrent load.
 */
@Tag("relay")
class PhoneConcurrentRelayTest {

    private static final String PHONE_MODEL = "Qwen3-0.6B-Q8_0.gguf";
    private static final String DESKTOP_MODEL = "Qwen3-4B-Q4_K_M.gguf";

    private static LlamaDockerFixture llamaPhone;
    private static LlamaDockerFixture llamaDesktop;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        LlamaDockerFixture.assumeAvailable();
        LlamaDockerFixture.assumeModelAvailable(PHONE_MODEL);
        LlamaDockerFixture.assumeModelAvailable(DESKTOP_MODEL);

        llamaPhone = SharedLlamaPool.acquire(
            "phone-conc", PHONE_MODEL, PortAllocator.allocate(), 2048);
        llamaDesktop = SharedLlamaPool.acquire(
            "desktop-conc", DESKTOP_MODEL, PortAllocator.allocate(), 4096);

        var desktopBackend = llamaDesktop.createBackend("desktop-relay", 10);
        var phoneBackend = llamaPhone.createBackend("phone-local", 100);

        server = new TestServerBootstrap(List.of(desktopBackend, phoneBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void three_clients_all_get_responses() throws Exception {
        var clients = new ArrayList<TestWebSocketClient>();
        try {
            for (int i = 0; i < 3; i++) {
                var ws = TestWebSocketClient.connect(server.baseUrl());
                ws.waitForRoomState(Duration.ofSeconds(10));
                clients.add(ws);
            }

            for (int i = 0; i < clients.size(); i++) {
                clients.get(i).sendSay("nexus", "Hello from client " + (i + 1));
            }

            for (int i = 0; i < clients.size(); i++) {
                var resp = clients.get(i).waitForProse(Duration.ofSeconds(120));
                assertNotNull(resp,
                    "[HARD] Client " + (i + 1) + " should get a response");
            }
        } finally {
            for (var ws : clients) {
                ws.close();
            }
        }
    }

    @Test
    void concurrent_requests_no_starvation() throws Exception {
        var clients = new ArrayList<TestWebSocketClient>();
        try {
            for (int i = 0; i < 3; i++) {
                var ws = TestWebSocketClient.connect(server.baseUrl());
                ws.waitForRoomState(Duration.ofSeconds(10));
                ws.waitForProse(Duration.ofSeconds(60));
                clients.add(ws);
            }

            var futures = new ArrayList<CompletableFuture<Boolean>>();
            for (int i = 0; i < clients.size(); i++) {
                final int idx = i;
                var ws = clients.get(idx);
                var future = CompletableFuture.supplyAsync(() -> {
                    ws.sendSay("nexus", "Concurrent message from " + (idx + 1));
                    var resp = ws.waitForProse(Duration.ofSeconds(120));
                    return resp != null;
                });
                futures.add(future);
            }

            for (int i = 0; i < futures.size(); i++) {
                var got = futures.get(i).get(
                    120, TimeUnit.SECONDS);
                assertTrue(got,
                    "[HARD] Client " + (i + 1) + " should not starve");
            }
        } finally {
            for (var ws : clients) {
                ws.close();
            }
        }
    }

    @Test
    void timeout_doesnt_block_others() throws Exception {
        try (var ws1 = TestWebSocketClient.connect(server.baseUrl());
             var ws2 = TestWebSocketClient.connect(server.baseUrl())) {

            ws1.waitForRoomState(Duration.ofSeconds(10));
            ws2.waitForRoomState(Duration.ofSeconds(10));
            ws1.waitForProse(Duration.ofSeconds(60));
            ws2.waitForProse(Duration.ofSeconds(60));

            ws1.sendSay("nexus", "A".repeat(1000));

            ws2.sendSay("nexus", "Quick hello!");
            var resp2 = ws2.waitForProse(Duration.ofSeconds(120));
            assertNotNull(resp2,
                "[HARD] Other clients should not be blocked");
        }
    }
}
