package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Tag;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.core.config.ConfigValidator;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the 7 operational gaps:
 * ActionPolicy enforcement, abort/cancellation, context compression,
 * token budget, conversation persistence, config validation, tool metadata.
 *
 * <p>Uses Pekko ActorTestKit with TestProbes for InferenceRouter and RoomCommand.
 * Same pattern as FullContextIntegrationTest.
 */
@Tag("integration")
@Tag("needs-inference")
@Tag("needs-network")
class OperationalGapsIntegrationTest {

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-wyrd-ops";

    private static AgentProfile profile() {
        return new AgentProfile(
            "Wyrd", ENTITY_ID, "agent",
            "A companion in Wyrdsekai",
            "You are Wyrd, a companion guide in Wyrdsekai.",
            4096, 256, 0.7);
    }

    private static final AgentProfile PROFILE = profile();

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();

        testKit = ActorTestKit.create("ops-gaps-integration-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        // Consume 3 startup messages: Subscribe, EnterRoom, LookRoom
        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // -----------------------------------------------------------------------
    // 1. ActionPolicy: Reactive inference bypasses tier enforcement
    // -----------------------------------------------------------------------

    @Test
    void reactive_inference_allows_high_tier_action() {
        // Human says something → agent responds with create_room (tier 3)
        // Since this is reactive (human-triggered), tier enforcement is bypassed
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Build me a garden room")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Agent responds with a create_room action (tier 3)
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            I'll create a garden for you!
            ```json
            {"action": "create_room", "name": "Garden", "description": "A peaceful garden", "exits": [{"direction": "west", "target": "nexus"}]}
            ```
            """, 30, 50));

        // Should speak the prose (not be blocked)
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("garden");
    }

    @Test
    void reactive_inference_allows_web_search_for_nascent_agent() {
        // Nascent agent (tier 0) can still web_search when human asks
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Search the web for the weather")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            Let me search for that.
            ```json
            {"action": "web_search", "query": "current weather", "type": "general"}
            ```
            """, 20, 30));

        // Should speak the prose (web_search is tier 1, but reactive bypasses)
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("search");
    }

    // -----------------------------------------------------------------------
    // 2. Abort/cancellation: AbortSignal cancels active inference
    // -----------------------------------------------------------------------

    @Test
    void abort_signal_cancels_thinking_and_acknowledges() {
        // Start an inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Tell me a long story")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Before the inference completes, send abort signal
        var eventStream = AgentEventStream.get();
        eventStream.publishAbort("player-alice", "Alice", ROOM_ID);

        // Agent should acknowledge the abort
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).containsIgnoringCase("stop");
    }

    @Test
    void abort_signal_abandons_active_plan() {
        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Find me a book about dragons")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Agent creates a plan
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(),
            """
            I'll make a plan for that.
            ```json
            {"action": "task_plan", "description": "find dragon books", "goals": ["Go to Library", "Search for dragons", "Report back"]}
            ```
            """, 30, 50));

        // Consume the prose
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Now abort
        AgentEventStream.get().publishAbort("player-alice", "Alice", ROOM_ID);

        // Agent should acknowledge
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).containsIgnoringCase("stop");
    }

    @Test
    void abort_signal_in_different_room_is_ignored() {
        // Start inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Hello")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Abort in a DIFFERENT room — should be ignored
        AgentEventStream.get().publishAbort("player-bob", "Bob", "other-room");

        // Complete the inference normally
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Hello Alice!", 10, 20));

        // Normal response should come through
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("Hello");
    }

    // -----------------------------------------------------------------------
    // 3. Context compression: verify prompt stays within budget
    // -----------------------------------------------------------------------

    @Test
    void long_conversation_does_not_overflow_context() {
        // Send many messages to build up conversation history
        for (int i = 0; i < 10; i++) {
            subscriberRef.tell(new RoomNotification(
                playerSaid("Alice", "Message number " + i + " with some extra text")));

            var req = routerProbe.expectMessageClass(
                InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

            req.replyTo().tell(new InferenceRouter.InferOk(
                req.requestId(),
                "Response to message " + i + " with more detail.", 20, 30));

            roomProbe.expectMessageClass(
                RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        }

        // Final message — verify prompt doesn't explode past context window
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What were we talking about?")));

        var finalReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Total tokens should be reasonable for a 4096-token window
        int totalChars = finalReq.messages().stream()
            .mapToInt(m -> m.content().length())
            .sum();
        int estimatedTokens = totalChars / 4;

        // Should be well under context window (4096 * 0.85 = 3481 usable)
        assertThat(estimatedTokens).isLessThan(3500);
    }

    // -----------------------------------------------------------------------
    // 4. Token budget: GoalExecutor budget-aware decisions
    // -----------------------------------------------------------------------

    @Test
    void goal_executor_escalates_on_exhausted_budget() {
        var plan = TaskPlan.create("p1", "research task", "u1", "mas",
            List.of("Search", "Analyze", "Report"));

        plan.recordAttempt("library_search", "topic", "no results", false);

        // Budget fully consumed
        var budget = new PlanTokenBudget(10000, 10000, 4000);

        var decision = GoalExecutor.evaluate(
            plan, false, "no results", "library_search",
            null, null, 0.8, budget);

        assertThat(decision).isInstanceOf(GoalExecutor.Decision.Escalate.class);
        assertThat(((GoalExecutor.Decision.Escalate) decision).message())
            .contains("budget");
    }

    @Test
    void goal_executor_tightens_retries_under_budget_pressure() {
        var plan = TaskPlan.create("p1", "research task", "u1", "mas",
            List.of("Search", "Report"));

        // Two attempts already made
        plan.recordAttempt("library_search", "a", "fail", false);
        plan.recordAttempt("library_search", "b", "fail", false);

        // 80% budget consumed → should not retry, should skip or escalate
        var budget = new PlanTokenBudget(10000, 8000, 2000);

        var decision = GoalExecutor.evaluate(
            plan, false, "still failing", "library_search",
            null, null, 0.8, budget);

        // Should NOT be Retry (budget-tight reduces retries)
        assertThat(decision).isNotInstanceOf(GoalExecutor.Decision.Retry.class);
    }

    @Test
    void plan_token_budget_tracks_usage_across_goals() {
        var plan = TaskPlan.create("p1", "multi-step", null, null,
            List.of("Step 1", "Step 2", "Step 3"));
        var budget = PlanTokenBudget.forPlan(plan, 4096);

        assertThat(budget.totalTokens()).isEqualTo(4096 * 3 * 2);
        assertThat(budget.remaining()).isEqualTo(budget.totalTokens());

        // Simulate inference usage
        budget = budget.withUsed(PlanTokenBudget.estimateFromChars(2000, 500));
        assertThat(budget.usedTokens()).isEqualTo(625);
        assertThat(budget.remaining()).isEqualTo(budget.totalTokens() - 625);

        // Prompt context includes budget info
        var ctx = budget.buildPromptContext();
        assertThat(ctx).contains("remaining");
    }

    // -----------------------------------------------------------------------
    // 5. Conversation persistence: checkpoint serialization
    // -----------------------------------------------------------------------

    @Test
    void checkpoint_serializes_and_deserializes() {
        var plan = TaskPlan.create("p1", "find books", "u1", "mas",
            List.of("Navigate", "Search", "Report"));
        plan.recordAttempt("go_to_room", "library", "arrived", true);

        var checkpoint = new ConversationCheckpoint(
            "agent-wyrd",
            List.of("10:00 navigated to Library", "10:01 searched for dragons"),
            plan,
            Instant.now()
        );

        var json = checkpoint.toJson();
        assertThat(json).isNotEmpty();
        assertThat(json).contains("agent-wyrd");
        assertThat(json).contains("find books");

        var restored = ConversationCheckpoint.fromJson(json);
        assertThat(restored).isNotNull();
        assertThat(restored.agentId()).isEqualTo("agent-wyrd");
        assertThat(restored.workingMemory()).hasSize(2);
        assertThat(restored.activePlan()).isNotNull();
        assertThat(restored.activePlan().description()).isEqualTo("find books");
    }

    @Test
    void checkpoint_from_invalid_json_returns_null() {
        assertThat(ConversationCheckpoint.fromJson(null)).isNull();
        assertThat(ConversationCheckpoint.fromJson("")).isNull();
        assertThat(ConversationCheckpoint.fromJson("not json")).isNull();
        assertThat(ConversationCheckpoint.fromJson("{}")).isNotNull(); // empty but valid
    }

    @Test
    void checkpoint_with_null_plan_serializes() {
        var checkpoint = new ConversationCheckpoint(
            "agent-test", List.of("memory entry"), null, Instant.now());

        var json = checkpoint.toJson();
        var restored = ConversationCheckpoint.fromJson(json);

        assertThat(restored).isNotNull();
        assertThat(restored.activePlan()).isNull();
        assertThat(restored.workingMemory()).containsExactly("memory entry");
    }

    // -----------------------------------------------------------------------
    // 6. Config validation: comprehensive checks
    // -----------------------------------------------------------------------

    @Test
    void valid_config_produces_no_fatal_errors() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                database.backend = "sqlite"
                inference {
                    backends = [{type = "ollama", url = "http://localhost:11434"}]
                    default-model = "qwen3.5:9b"
                }
                telnet.port = 7071
                ssh.port = 7022
                http.port = 7070
            }
            """);

        var errors = ConfigValidator.validate(config);
        var fatals = errors.stream()
            .filter(e -> e.severity() == ConfigValidator.Severity.ERROR)
            .toList();
        assertThat(fatals).isEmpty();
    }

    @Test
    void port_collision_detected() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                telnet.port = 7070
                ssh.port = 7070
                http.port = 7080
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertThat(errors).anyMatch(e ->
            e.message().contains("conflicts") &&
            e.severity() == ConfigValidator.Severity.ERROR);
    }

    @Test
    void invalid_inference_backend_type_detected() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                inference.backends = [{type = "chatgpt", url = "http://localhost:8080"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertThat(errors).anyMatch(e ->
            e.path().contains("type") &&
            e.severity() == ConfigValidator.Severity.ERROR);
    }

    // -----------------------------------------------------------------------
    // 7. Tool metadata: ActionPolicy completeness + CapabilityContext wiring
    // -----------------------------------------------------------------------

    @Test
    void action_policy_covers_all_action_types_with_valid_metadata() {
        for (var entry : ActionPolicy.REGISTRY.entrySet()) {
            var p = entry.getValue();
            assertThat(p.requiredTier()).isBetween(0, 3);
            assertThat(p.budgetCost()).isGreaterThanOrEqualTo(0.0);
            assertThat(p.domain()).isNotBlank();
            assertThat(p.actionType()).isEqualTo(entry.getKey());
        }
        // Minimum coverage: at least 30 actions
        assertThat(ActionPolicy.REGISTRY).hasSizeGreaterThanOrEqualTo(30);
    }

    @Test
    void capability_context_shows_tier_aware_actions() {
        var sb = new StringBuilder();
        // Tier 0 agent — should see tier 0 actions, locked tier 1+
        CapabilityContextBuilder.appendBuiltInActions(sb, 0);
        var ctx = sb.toString();

        assertThat(ctx).contains("go_to_room");
        assertThat(ctx).contains("library_search");
        assertThat(ctx).contains("tell_agent");
        // Tier 2+ should be locked
        assertThat(ctx).contains("Locked");
        assertThat(ctx).contains("tier 3"); // create_room etc shown as locked
    }

    @Test
    void capability_context_tier_3_shows_all_unlocked() {
        var sb = new StringBuilder();
        CapabilityContextBuilder.appendBuiltInActions(sb, 3);
        var ctx = sb.toString();

        assertThat(ctx).contains("create_room");
        assertThat(ctx).contains("workbench_submit");
        // Nothing should be locked for tier 3
        assertThat(ctx).doesNotContain("Locked");
    }

    @Test
    void action_policy_budget_costs_increase_with_tier() {
        // Tier 0 actions should be free
        assertThat(ActionPolicy.forAction("go_to_room").budgetCost()).isEqualTo(0.0);
        assertThat(ActionPolicy.forAction("tell_agent").budgetCost()).isEqualTo(0.0);

        // Tier 1 actions cost something
        assertThat(ActionPolicy.forAction("web_search").budgetCost()).isGreaterThan(0.0);

        // Tier 3 actions cost the most
        assertThat(ActionPolicy.forAction("create_room").budgetCost())
            .isGreaterThan(ActionPolicy.forAction("web_search").budgetCost());
    }

    @Test
    void action_policy_type_extraction_covers_all_known_actions() {
        // Every action in the registry should be extractable from an AgentAction instance
        // Test a sampling of key actions across tiers
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.GoToRoom("Library", "research")))
            .isEqualTo("go_to_room");
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.WebSearch("weather", "general")))
            .isEqualTo("web_search");
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.CreateRoom("Garden", "A garden", null, null, null)))
            .isEqualTo("create_room");
        assertThat(ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.GoalDone("completed")))
            .isEqualTo("goal_done");
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: Cancellation token propagation
    // -----------------------------------------------------------------------

    @Test
    void cancellation_propagates_through_parent_child_chain() {
        var parent = new CancellationToken();
        var child1 = parent.child();
        var child2 = parent.child();
        var grandchild = child1.child();

        assertThat(parent.isCancelled()).isFalse();
        assertThat(child1.isCancelled()).isFalse();
        assertThat(grandchild.isCancelled()).isFalse();

        parent.cancel("user abort");

        assertThat(parent.isCancelled()).isTrue();
        assertThat(child1.isCancelled()).isTrue();
        assertThat(child2.isCancelled()).isTrue();
        assertThat(grandchild.isCancelled()).isTrue();
        assertThat(parent.reason()).isEqualTo("user abort");
        assertThat(child1.reason()).contains("parent");
    }

    @Test
    void cancellation_is_idempotent() {
        var token = new CancellationToken();
        token.cancel("first");
        token.cancel("second");

        assertThat(token.reason()).isEqualTo("first");
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: Memory compaction under pressure
    // -----------------------------------------------------------------------

    @Test
    void memory_compactor_deduplicates_and_preserves_importance() {
        // Build memory buffer with duplicates and mixed importance
        var buffer = String.join("\n",
            "10:00 go_to_room Library",
            "10:01 library_search for mythology",
            "10:02 Found an interesting book about dragons",
            "10:03 go_to_room Nexus",
            "10:04 library_search for history",
            "10:05 told player about the book found"
        );

        // Compact with tight budget (force compaction but not total truncation)
        // Memory budget = remainingTokenBudget * 0.20, so 200 gives 40 tokens = ~160 chars
        var compacted = MemoryCompactor.compact(buffer, 200);

        // Should be shorter than original (some entries dropped)
        assertThat(compacted.length()).isLessThan(buffer.length());
        // Deduplicated: only last go_to_room and library_search
        var lines = compacted.split("\n");
        long goToCount = Arrays.stream(lines)
            .filter(l -> l.contains("go_to_room")).count();
        assertThat(goToCount).isLessThanOrEqualTo(1);
    }

    @Test
    void conversation_compressor_preserves_recent_messages() {
        var history = new ArrayList<InferenceClient.ChatMessage>();
        for (int i = 0; i < 20; i++) {
            history.add(new InferenceClient.ChatMessage(
                "user", "Message " + i + " " + "x".repeat(100)));
            history.add(new InferenceClient.ChatMessage(
                "assistant", "Response " + i + " " + "y".repeat(100)));
        }

        var compressed = ConversationCompressor.compress(history, 512, 128);

        // Should be smaller than original
        assertThat(compressed.size()).isLessThan(history.size());
        // Last 3 messages preserved
        assertThat(compressed.getLast().content()).isEqualTo(history.getLast().content());
        // Summary at the beginning
        assertThat(compressed.getFirst().role()).isEqualTo("system");
        assertThat(compressed.getFirst().content()).startsWith("[Earlier");
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: CommandParser abort commands
    // -----------------------------------------------------------------------

    @Test
    void command_parser_recognizes_abort_commands() {
        var abort = CommandParser.parse("abort");
        assertThat(abort).isInstanceOf(
            CommandParser.ParsedCommand.AbortPlan.class);

        var stop = CommandParser.parse("stop");
        assertThat(stop).isInstanceOf(
            CommandParser.ParsedCommand.AbortPlan.class);

        var cancel = CommandParser.parse("cancel");
        assertThat(cancel).isInstanceOf(
            CommandParser.ParsedCommand.AbortPlan.class);

        var nevermind = CommandParser.parse("nevermind");
        assertThat(nevermind).isInstanceOf(
            CommandParser.ParsedCommand.AbortPlan.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(),  // entities
            List.of(),  // objects
            List.of()   // hints
        );
    }
}
