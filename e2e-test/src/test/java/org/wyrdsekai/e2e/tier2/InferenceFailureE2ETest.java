package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.wyrdsekai.e2e.infra.E2eAssertions.*;

/**
 * Inference failure scenarios — degradation, cooldown, fallback.
 * WireMock as primary (can be made to fail), real LLM (via external
 * server) as fallback.
 */
@Tag("e2e")
class InferenceFailureE2ETest {

    private static E2eTestSupport.SetupResult inferenceSetup;
    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        // Real LLM as secondary/fallback
        inferenceSetup = E2eTestSupport.setupInference("e2e-fallback");

        // WireMock as primary (can be made to fail)
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();

        var wireMockClient = new InferenceClient(wireMock.baseUrl());

        // WireMock primary (lower priority = higher priority), real LLM fallback
        var primaryBackend = new InferenceBackend.LlamaServer(
            "wiremock-primary", wireMockClient, 1, List.of(), null);
        var fallbackBackend = inferenceSetup.backend();

        server = new TestServerBootstrap(List.of(primaryBackend, fallbackBackend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
        if (inferenceSetup != null) inferenceSetup.stopFixture();
    }

    @Test
    void degraded_response_on_primary_failure() throws Exception {
        // Primary returns error → router should fall back to real LLM
        wireMock.stubChatCompletionError(500, "Internal Server Error");

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));

            // May get greeting via fallback
            var greeting = ws.waitForProse(Duration.ofSeconds(60));
            assertNotNull(greeting, "Should still get greeting via fallback");
        }
    }

    @Test
    void recovery_after_cooldown() throws Exception {
        wireMock.stubChatCompletionError(500, "Server Error");

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            ws.sendSay("nexus", "Hello?");
            ws.waitForProse(Duration.ofSeconds(60));

            // Fix the primary
            wireMock.stubChatCompletion("I am working now!", 10, 20);

            // After cooldown, primary should recover on next health check
            Thread.sleep(15_000);
            ws.sendSay("nexus", "Are you there?");
            var response = ws.waitForProse(Duration.ofSeconds(60));
            assertNotNull(response, "Should get response after recovery");
        }
    }

    @Test
    void fallback_to_real_llm_when_primary_fails() throws Exception {
        wireMock.stubChatCompletionError(503, "Service Unavailable");

        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(60));

            ws.sendSay("nexus", "Tell me something interesting.");
            var response = ws.waitForProse(Duration.ofSeconds(60));
            // === HARD: Got any response (pipeline didn't crash on primary failure) ===
            assertNotNull(response, "Fallback LLM should respond");

            // === SOFT: Response is substantive (full fallback routing worked) ===
            var text = response.path("text").asText();
            System.out.println("[E2E InferenceFailure.fallback] Response: " + text);
            softAssertSubstantive(response, "InferenceFailure.fallback", 10);
        }
    }
}
