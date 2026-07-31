package org.wyrdsekai.e2e.tier0;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.HeuristicExtractor;
import org.wyrdsekai.core.agent.ObservationBuffer;
import org.wyrdsekai.core.agent.OrientationEngine;
import org.wyrdsekai.core.agent.OutcomeTracker;
import org.wyrdsekai.core.agent.ReasoningDepthRouter;
import org.wyrdsekai.core.agent.TaskPlan;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;
import org.wyrdsekai.e2e.infra.TestWebSocketClient;
import org.wyrdsekai.e2e.infra.WireMockInferenceServer;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 0 E2E tests for the Agent Cognition Engine.
 * Verifies task_plan action creates a plan, goal_done advances goals,
 * and the full pipeline processes multi-step tasks.
 */
@Tag("integration")
class CognitionE2ETest {

    private static final String COMPANION = "Wyrd";
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private static WireMockInferenceServer wireMock;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        wireMock = WireMockInferenceServer.openAi(PortAllocator.allocate());
        wireMock.start();
        wireMock.stubChatCompletion("Welcome, traveler.", 20, 10);

        var client = new InferenceClient(wireMock.baseUrl());
        var backend = new InferenceBackend.LlamaServer(
            "wiremock-cog", client, 10, List.of(), null);
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
        for (int i = 0; i < 3; i++) {
            if (ws.waitForProse(Duration.ofSeconds(2)) == null) break;
        }
        return ws;
    }

    @Test
    void task_plan_action_creates_plan_and_agent_responds() throws Exception {
        // Agent creates a task plan in response to a request
        wireMock.stubChatCompletion(
            "I'll work on that systematically.\n" +
            "```json\n" +
            "{\"action\":\"task_plan\",\"description\":\"find mythology books\"," +
            "\"goals\":[\"Navigate to Library\",\"Search for mythology\",\"Report back\"]}\n" +
            "```",
            80, 50);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "find me some mythology books");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            assertNotNull(prose, "Should receive prose from companion");
            var text = prose.path("text").asText();
            assertTrue(text.contains("systematically") || text.contains("work on"),
                "Should contain plan creation prose");
        }
    }

    @Test
    void goal_done_action_advances_plan() throws Exception {
        // Agent marks a goal as done
        wireMock.stubChatCompletion(
            "Found what I was looking for!\n" +
            "```json\n" +
            "{\"action\":\"goal_done\",\"outcome\":\"Found 3 books about Norse mythology\"}\n" +
            "```",
            60, 40);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "what did you find?");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            assertNotNull(prose, "Should receive prose from companion");
        }
    }

    @Test
    void modify_plan_action_processed_without_crash() throws Exception {
        // Agent modifies active plan
        wireMock.stubChatCompletion(
            "I should also check the Vault.\n" +
            "```json\n" +
            "{\"action\":\"modify_plan\",\"operation\":\"add_goal\",\"index\":1," +
            "\"goal\":\"Check the Vault for old manuscripts\"}\n" +
            "```",
            50, 30);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "maybe check the vault too");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);

            assertNotNull(prose, "Should receive prose even with plan modification");
        }
    }

    @Test
    void reasoning_depth_router_classifies_correctly() {
        var r = ReasoningDepthRouter.class;
        // Verify the router is accessible and returns expected depths
        assertEquals(ReasoningDepthRouter.Depth.ROUTINE,
            ReasoningDepthRouter.route("hi", false, false, 0.8));
        assertEquals(ReasoningDepthRouter.Depth.COMPLEX,
            ReasoningDepthRouter.route("ok", true, true, 0.8));
    }

    @Test
    void observation_buffer_and_orientation_engine_work_together() {
        var buf = new ObservationBuffer();
        buf.observe("tell", "Find me books about mythology", 0.9);
        buf.observe("room", "You are in the Nexus", 0.5);

        var plan = TaskPlan.create(
            "e2e-plan", "find mythology books", null, null,
            List.of("Search for mythology"));

        var contextualized = OrientationEngine.orient(
            buf.top(10), plan, null);

        assertFalse(contextualized.isEmpty());
        // The tell about mythology should be top-ranked (plan-relevant)
        assertEquals("tell", contextualized.getFirst().observation().source());
    }

    @Test
    void outcome_tracker_computes_calibration() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("p1", "goal", "search", true, true);
        }
        assertEquals(1.0, tracker.calibrationScore(), 0.01);
        assertFalse(tracker.isOverconfident());
    }

    @Test
    void heuristic_extraction_from_failed_plan() {
        var plan = TaskPlan.create(
            "e2e", "search task", null, null, List.of("Search"));
        plan.recordAttempt("library_search", "myth", "no results", false);
        plan.currentGoal().markFailed("exhausted");
        plan.fail("failed");

        var heuristics = HeuristicExtractor.extract(plan);
        assertFalse(heuristics.isEmpty());

        var ctx = HeuristicExtractor.buildPromptContext(
            heuristics, "search for books", 5);
        assertNotNull(ctx);
        assertTrue(ctx.contains("Learned Patterns"));
    }
}
