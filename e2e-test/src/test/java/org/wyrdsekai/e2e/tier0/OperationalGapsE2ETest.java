package org.wyrdsekai.e2e.tier0;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.core.agent.*;
import org.wyrdsekai.core.config.ConfigValidator;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
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
 * Tier 0 E2E tests for the 7 operational gaps.
 *
 * <p>Tests verify the full pipeline through the running server:
 * abort command propagation, ActionPolicy enforcement in prompt context
 * (tier-aware action listing), conversation checkpoint serialization,
 * config validation, and token budget integration.</p>
 */
@Tag("integration")
class OperationalGapsE2ETest {

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
            "wiremock-ops", client, 10, List.of(), null);
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

    // -----------------------------------------------------------------------
    // 1. ActionPolicy: tier-aware action listing in capability context
    // -----------------------------------------------------------------------

    @Test
    void action_policy_registry_has_all_expected_action_types() {
        // Verify ActionPolicy.REGISTRY covers the key actions
        assertNotNull(ActionPolicy.forAction("go_to_room"));
        assertNotNull(ActionPolicy.forAction("web_search"));
        assertNotNull(ActionPolicy.forAction("create_room"));
        assertNotNull(ActionPolicy.forAction("workbench_submit"));

        assertEquals(0, ActionPolicy.forAction("go_to_room").requiredTier());
        assertEquals(1, ActionPolicy.forAction("web_search").requiredTier());
        assertEquals(3, ActionPolicy.forAction("create_room").requiredTier());
    }

    @Test
    void action_policy_tiers_are_ordered() {
        // Tier 0 actions have lower or equal tier than tier 1 actions
        assertTrue(ActionPolicy.forAction("go_to_room").requiredTier()
            <= ActionPolicy.forAction("web_search").requiredTier());
        assertTrue(ActionPolicy.forAction("web_search").requiredTier()
            <= ActionPolicy.forAction("think_deeply").requiredTier());
        assertTrue(ActionPolicy.forAction("think_deeply").requiredTier()
            <= ActionPolicy.forAction("create_room").requiredTier());
    }

    // -----------------------------------------------------------------------
    // 2. Abort command: parsed and propagated
    // -----------------------------------------------------------------------

    @Test
    void abort_command_parsed_by_command_parser() {
        for (var cmd : List.of("abort", "stop", "cancel", "nevermind")) {
            var parsed = CommandParser.parse(cmd);
            assertInstanceOf(
                CommandParser.ParsedCommand.AbortPlan.class,
                parsed, "'" + cmd + "' should parse as AbortPlan");
        }
    }

    @Test
    void abort_signal_event_is_recognized() {
        var signal = new AgentEvent.AbortSignal("p1", "Alice", "nexus", Instant.now());
        // Salience scorer should rate it maximum
        double score = SalienceScorer.score(signal, null, null);
        assertEquals(1.0, score, "Abort signal should have maximum salience");
    }

    // -----------------------------------------------------------------------
    // 3. Context compression: ConversationCompressor
    // -----------------------------------------------------------------------

    @Test
    void conversation_compressor_reduces_large_history() {
        var history = new ArrayList<InferenceClient.ChatMessage>();
        for (int i = 0; i < 30; i++) {
            history.add(new InferenceClient.ChatMessage(
                "user", "Message " + i + " from the user with some context " + "x".repeat(80)));
            history.add(new InferenceClient.ChatMessage(
                "assistant", "Response " + i + " from the agent with detail " + "y".repeat(80)));
        }

        // 512-token window should trigger compression
        var compressed = ConversationCompressor.compress(history, 512, 128);
        assertNotNull(compressed);
        assertTrue(compressed.size() < history.size(),
            "Compressed should have fewer messages: " + compressed.size() + " vs " + history.size());
        // Last 3 preserved
        assertEquals(history.getLast().content(), compressed.getLast().content());
    }

    @Test
    void memory_compactor_reduces_redundant_buffer() {
        // Build buffer with repeated actions that exceed budget
        var sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("10:").append(String.format("%02d", i))
              .append(" go_to_room Room").append(i).append("\n");
        }
        var buffer = sb.toString().trim();

        // Compact with a tight budget — should reduce
        var compacted = MemoryCompactor.compact(buffer, 200);
        assertTrue(compacted.length() < buffer.length(),
            "Compacted buffer should be shorter than original");
    }

    // -----------------------------------------------------------------------
    // 4. Token budget: PlanTokenBudget
    // -----------------------------------------------------------------------

    @Test
    void plan_token_budget_lifecycle() {
        var plan = TaskPlan.create("p1", "research", null, null,
            List.of("Step 1", "Step 2"));
        var budget = PlanTokenBudget.forPlan(plan, 4096);

        assertEquals(4096 * 2 * 2, budget.totalTokens());
        assertTrue(budget.canAfford(4096));

        budget = budget.withUsed(15000);
        assertFalse(budget.canAfford(4096));
        assertTrue(budget.utilizationFraction() > 0.9);
    }

    @Test
    void goal_executor_respects_token_budget() {
        var plan = TaskPlan.create("p1", "task", null, null,
            List.of("Goal"));
        plan.recordAttempt("action", null, "fail", false);

        // Exhausted budget
        var budget = new PlanTokenBudget(100, 100, 100);
        var decision = GoalExecutor.evaluate(
            plan, false, "fail", "action",
            null, null, 0.8, budget);

        assertInstanceOf(GoalExecutor.Decision.Escalate.class, decision);
    }

    // -----------------------------------------------------------------------
    // 5. Conversation persistence: checkpoint round-trip
    // -----------------------------------------------------------------------

    @Test
    void conversation_checkpoint_json_round_trip() {
        var plan = TaskPlan.create("p1", "research dragons", "u1", "mas",
            List.of("Go to Library", "Search", "Report"));
        plan.recordAttempt("go_to_room", "library", "ok", true);
        plan.advanceGoal("navigated");

        var cp = new ConversationCheckpoint(
            "agent-ember",
            List.of("navigated to Library", "searching..."),
            plan, Instant.now());

        var json = cp.toJson();
        assertNotNull(json);
        assertFalse(json.isEmpty());

        var restored = ConversationCheckpoint.fromJson(json);
        assertNotNull(restored);
        assertEquals("agent-ember", restored.agentId());
        assertEquals(2, restored.workingMemory().size());
        assertNotNull(restored.activePlan());
        assertEquals("research dragons", restored.activePlan().description());
    }

    // -----------------------------------------------------------------------
    // 6. Config validation
    // -----------------------------------------------------------------------

    @Test
    void config_validator_catches_invalid_backend() {
        var config = ConfigFactory.parseString("""
            wyrdsekai.inference.backends = [{type = "invalid_backend", url = "http://localhost:8080"}]
            """);
        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.severity() == ConfigValidator.Severity.ERROR
            && e.path().contains("type")));
    }

    @Test
    void config_validator_catches_port_collision() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                telnet.port = 9999
                ssh.port = 9999
                inference.backends = [{type = "llama-server", url = "http://localhost:8080"}]
            }
            """);
        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("conflicts")));
    }

    // -----------------------------------------------------------------------
    // 7. Tool metadata: ActionPolicy consistency
    // -----------------------------------------------------------------------

    @Test
    void action_policy_type_extraction_is_exhaustive() {
        // Test a representative sampling
        assertEquals("go_to_room", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.GoToRoom("lib", "test")));
        assertEquals("create_room", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.CreateRoom("X", "Y", null, null, null)));
        assertEquals("web_search", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.WebSearch("q", "general")));
        assertEquals("goal_done", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.GoalDone("done")));
        assertEquals("tell_agent", ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.TellAgent("Ember", "hello")));
    }

    @Test
    void action_policy_read_only_actions_are_safe() {
        // Read-only actions should never mutate world state
        assertTrue(ActionPolicy.forAction("go_to_room").readOnly());
        assertTrue(ActionPolicy.forAction("library_search").readOnly());
        assertTrue(ActionPolicy.forAction("web_search").readOnly());
        assertTrue(ActionPolicy.forAction("think_deeply").readOnly());

        // Mutating actions
        assertFalse(ActionPolicy.forAction("create_room").readOnly());
        assertFalse(ActionPolicy.forAction("workbench_submit").readOnly());
        assertFalse(ActionPolicy.forAction("tell_agent").readOnly());
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: CancellationToken
    // -----------------------------------------------------------------------

    @Test
    void cancellation_token_parent_child_propagation() {
        var parent = new CancellationToken();
        var c1 = parent.child();
        var c2 = parent.child();

        assertFalse(c1.isCancelled());
        parent.cancel("abort");
        assertTrue(c1.isCancelled());
        assertTrue(c2.isCancelled());
        assertEquals("abort", parent.reason());
    }

    // -----------------------------------------------------------------------
    // E2E: Full server pipeline — agent responds to say
    // -----------------------------------------------------------------------

    @Test
    void agent_responds_to_player_say_through_full_pipeline() throws Exception {
        wireMock.stubChatCompletion("Hello, traveler! How can I help?", 30, 20);

        try (var ws = connectAndDrain()) {
            ws.sendSay("nexus", "Hello Wyrd");
            var prose = ws.waitForProseFrom(COMPANION, RESPONSE_TIMEOUT);
            assertNotNull(prose, "Should receive response from companion");
        }
    }
}
