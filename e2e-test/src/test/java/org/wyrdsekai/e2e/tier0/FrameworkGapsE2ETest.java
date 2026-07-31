package org.wyrdsekai.e2e.tier0;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.event.InProcessEventBus;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.TriageClassifier;
import org.wyrdsekai.core.ingest.IngestPipeline;
import org.wyrdsekai.core.ingest.TextExtractor;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for framework gap features:
 * - Model routing per task (triage classifier + cap: prefix)
 * - Delegate action (subagent context isolation)
 * - Schema validation (malformed actions rejected gracefully)
 * - Event bus and ingest pipeline initialization
 */
@Tag("integration")
class FrameworkGapsE2ETest {

    private static final String COMPANION = "Wyrd";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();

        // Initial greeting
        wireMock.stubChatCompletion("Welcome, traveler.", 20, 10);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock-gaps", client, 10, List.of(), null);

        server = new TestServerBootstrap(List.of(backend));
        server.start();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (wireMock != null) wireMock.stop();
    }

    private TestWebSocketClient connectAndDrain() throws Exception {
        var ws = TestWebSocketClient.connect(server.baseUrl());
        ws.waitForRoomState(Duration.ofSeconds(10));
        drainAllProse(ws, 3);
        return ws;
    }

    private void drainAllProse(TestWebSocketClient ws, int maxMessages) {
        for (int i = 0; i < maxMessages; i++) {
            var msg = ws.waitForProse(Duration.ofSeconds(2));
            if (msg == null) break;
        }
    }

    // --- Triage Classifier (integration-level, verifies full classify+capability chain) ---

    @Test
    void triage_greetings_classified_as_routine() {
        assertEquals(TriageClassifier.Tier.ROUTINE, TriageClassifier.classify("hi"));
        assertEquals(TriageClassifier.Tier.ROUTINE, TriageClassifier.classify("thanks!"));
        assertEquals(TriageClassifier.Tier.ROUTINE, TriageClassifier.classify("ok"));
    }

    @Test
    void triage_complex_classified_correctly() {
        assertEquals(TriageClassifier.Tier.COMPLEX,
            TriageClassifier.classify("explain the bond system architecture in detail"));
        assertEquals(TriageClassifier.Tier.COMPLEX,
            TriageClassifier.classify("investigate why predictions are failing for this user's time series data"));
    }

    @Test
    void triage_simple_classified_correctly() {
        assertEquals(TriageClassifier.Tier.SIMPLE,
            TriageClassifier.classify("how are you?"));
    }

    @Test
    void triage_capability_mapping_valid() {
        assertEquals("quick", TriageClassifier.tierToCapability(TriageClassifier.Tier.ROUTINE));
        assertEquals("default", TriageClassifier.tierToCapability(TriageClassifier.Tier.SIMPLE));
        assertEquals("reasoning", TriageClassifier.tierToCapability(TriageClassifier.Tier.COMPLEX));
    }

    // --- Delegate Action E2E ---

    @Test
    void delegate_action_produces_companion_response() throws Exception {
        // Stub: companion delegates a task
        wireMock.stubChatCompletion(
            "Let me research that for you.\n" +
            "```json\n" +
            "{\"action\":\"delegate\",\"task\":\"Find books about neural networks\"," +
            "\"context\":\"Library has 5 knowledge packs\"}\n" +
            "```",
            100, 50);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "find me books about neural networks");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            assertNotNull(prose, "Should receive prose from companion");
            var text = prose.path("text").asText();
            assertTrue(text.contains("research") || text.contains("Let me"),
                "Expected delegation prose, got: " + text);
        }
    }

    // --- Schema Validation E2E ---

    @Test
    void malformed_action_still_returns_prose() throws Exception {
        // Stub: companion emits malformed action (go_to_room without target)
        wireMock.stubChatCompletion(
            "I'll go check that out.\n" +
            "```json\n" +
            "{\"action\":\"go_to_room\"}\n" +
            "```",
            50, 20);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "go explore the library");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            assertNotNull(prose, "Should get prose even with malformed action");
        }
    }

    // --- Event Bus (direct instantiation test — TestServerBootstrap skips Main.java singletons) ---

    @Test
    void event_bus_can_be_created_and_used() {
        var bus = new InProcessEventBus();
        var received = new ArrayList<>();
        bus.subscribe("e2e-test", null, received::add);
        bus.publish(new AgentEvent.SystemEvent(
            AgentEvent.SystemEventType.NODE_JOINED,
            "e2e", "test", Instant.now()));
        assertEquals(1, received.size());
    }

    // --- Ingest Pipeline (direct instantiation test) ---

    @Test
    void ingest_pipeline_processes_text() {
        var pipeline = new IngestPipeline();
        pipeline.registerExtractor(new TextExtractor());

        var result = pipeline.processText("E2E test event", "e2e-user");
        assertTrue(result.success());
        assertEquals("E2E test event", result.extractedText());
    }
}
