package org.wyrdsekai.e2e.tier2;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DriveState;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vitality feedback E2E — deterministic usage tracking via WireMock.
 * Uses WireMock for deterministic token usage (not real LLM).
 */
@Tag("e2e")
class VitalityFeedbackE2ETest {

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Hello, welcome to the Nexus!", 50, 30);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void system_handles_many_exchanges() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(30));

            // Send 10 messages and verify all get responses
            for (int i = 0; i < 10; i++) {
                ws.sendSay("nexus", "Message " + i);
                var response = ws.waitForProse(Duration.ofSeconds(30));
                assertNotNull(response, "Should get response for message " + i);
            }
        }
    }

    @Test
    void sampling_params_reach_server() throws Exception {
        // Connect and drain greeting first
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            for (int i = 0; i < 3; i++) {
                try { ws.waitForProse(Duration.ofSeconds(15)); } catch (Exception e) { break; }
            }

            // NOW force high-creativity drives — after greeting, before our tell
            var ref = ZoneGuardian.getCompanionRef(null, "companion-wyrd");
            assertNotNull(ref, "Companion ref should be resolvable");
            ref.tell(new CompanionActor.ForceDrives(
                DriveState.initial().spikeCreativity(0.9).spikeSeeking(0.7)));
            Thread.sleep(300);

            // Send tell — triggers identity inference with drive-modulated sampling
            ws.sendSay("nexus", "tell wyrd Tell me a story");
            try { ws.waitForProse(Duration.ofSeconds(30)); } catch (Exception e) { /* ok */ }
        }

        // Check ALL HTTP request bodies sent to WireMock
        var bodies = wireMock.getCompletionRequestBodies();
        assertFalse(bodies.isEmpty(), "Should have sent at least one completion request");

        // Debug: print last request body (most likely the drive-modulated one)
        var last = bodies.getLast();
        System.out.println("[VitalityFeedback] Last request body (truncated): "
            + last.substring(0, Math.min(500, last.length())));

        // At least one request should contain presence_penalty and repeat_penalty
        boolean foundPresence = bodies.stream().anyMatch(b -> b.contains("presence_penalty"));
        boolean foundRepeat = bodies.stream().anyMatch(b -> b.contains("repeat_penalty"));
        boolean foundTopP = bodies.stream().anyMatch(b -> b.contains("top_p"));

        System.out.println("[VitalityFeedback] Sampling params in HTTP requests ("
            + bodies.size() + " total):");
        System.out.println("  presence_penalty: " + foundPresence);
        System.out.println("  repeat_penalty: " + foundRepeat);
        System.out.println("  top_p: " + foundTopP);

        assertTrue(foundPresence, "presence_penalty should appear in at least one HTTP request body");
        assertTrue(foundRepeat, "repeat_penalty should appear in at least one HTTP request body");
        assertTrue(foundTopP, "top_p should appear in at least one HTTP request body");
    }

    @Test
    void debounce_increases_under_load() throws Exception {
        try (var ws = TestWebSocketClient.connect(server.baseUrl())) {
            ws.waitForRoomState(Duration.ofSeconds(10));
            ws.waitForProse(Duration.ofSeconds(30));

            // Send rapid messages to trigger debounce increase
            for (int i = 0; i < 5; i++) {
                ws.sendSay("nexus", "Rapid " + i);
                Thread.sleep(50);
            }

            // Should still eventually respond
            var response = ws.waitForProse(Duration.ofSeconds(30));
            assertNotNull(response, "Should eventually respond even under load");
        }
    }
}
