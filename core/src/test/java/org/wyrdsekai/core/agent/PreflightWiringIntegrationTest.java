package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 2 integration test that
 * exercises {@link CompanionActor#simulatePlanPreflight}. Confirms both gates
 * (M3 mental simulator + M2 plan scorer) fire on plan creation, with the
 * expected prompt content shape.
 *
 * <p>This complements the unit tests in {@code MentalSimulatorTest} and
 * {@code M2PlanScorerTest}, which cover the deterministic glue but never
 * exercise the actual CompanionActor wiring path.</p>
 */
@Tag("integration")
class PreflightWiringIntegrationTest {

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-wyrd-preflight";

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", ENTITY_ID, "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();

        testKit = ActorTestKit.create("preflight-wiring-test",
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

        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    @Test
    void plan_creation_fires_both_preflight_gates() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "research liquid neural networks for me")));

        var initialReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Companion emits a task_plan action — this is what triggers
        // simulatePlanPreflight() inside handleCreateTaskPlan.
        initialReq.replyTo().tell(new InferenceRouter.InferOk(
            initialReq.requestId(),
            """
            I will research that.
            ```json
            {"action": "task_plan", "description": "Research liquid neural networks", "goals": ["Search the library for liquid networks", "Read the top result", "Summarize for Alice"]}
            ```
            """, 100, 50));

        // Companion speaks the prose first
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // simulatePlanPreflight fires both M3 + M2 in parallel via AskPattern.ask
        // (which is tell-under-the-hood). Collect the next two router messages.
        // Order is non-deterministic since both futures fire from the same
        // method body in the same actor message handler.
        // #35 — the prose voice-pass ChatRequest may interleave with the two
        // preflight calls; drain it so we collect exactly the M2 + M3 requests.
        var preflightCalls = new ArrayList<InferenceRouter.ChatRequest>();
        for (int i = 0; i < 2; i++) {
            preflightCalls.add(VoicePassTestSupport.nextChatRequest(
                routerProbe, Duration.ofSeconds(5)));
        }

        // Identify which is which from the system-prompt content. The shapes
        // are fully disjoint:
        //   M3 system prompt → contains "simulating a companion's plan"
        //   M2 system prompt → contains "scoring agent plan quality"
        InferenceRouter.ChatRequest m3 = null, m2 = null;
        for (var req : preflightCalls) {
            var system = req.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(m -> m.content())
                .findFirst().orElse("");
            if (system.contains("simulating a companion's plan")) m3 = req;
            else if (system.contains("scoring agent plan quality")) m2 = req;
        }

        assertThat(m3)
            .as("M3 mental simulator request must fire on plan creation")
            .isNotNull();
        assertThat(m2)
            .as("M2 plan scorer request must fire on plan creation")
            .isNotNull();

        // M3 prompt should contain the rendered world model prefix (ZONE STATE MAP)
        // and the candidate goal sequence.
        var m3System = m3.messages().get(0).content();
        var m3User = m3.messages().get(1).content();
        assertThat(m3System).contains("ZONE STATE MAP");
        assertThat(m3System).contains("ACTION CONSEQUENCES");
        assertThat(m3System).contains("KNOWN PATTERNS");
        assertThat(m3User).contains("Search the library for liquid networks");
        assertThat(m3User).contains("Respond JSON");

        // M2 prompt should contain in-context examples + the candidate plan.
        var m2System = m2.messages().get(0).content();
        var m2User = m2.messages().get(1).content();
        assertThat(m2System).contains("[EXAMPLE 1:");
        assertThat(m2User).contains("Search the library for liquid networks");
        assertThat(m2User).contains("Respond JSON");

        // Both must use cap:reasoning model hint and localOnly=true so the
        // simulator never burns cross-zone CU.
        assertThat(m3.model()).isEqualTo("cap:reasoning");
        assertThat(m2.model()).isEqualTo("cap:reasoning");
        assertThat(m3.localOnly()).isTrue();
        assertThat(m2.localOnly()).isTrue();
    }

    /**
     * + Path A — combined hard-gate.
     * Default policy is M2 ∧ M3: both must reject (cleanly) for the
     * {@code [Preflight]} hint to land. This test exercises the AND case where
     * both gates agree; sibling tests cover the disagreement asymmetries.
     */
    @Test
    void both_gates_low_confidence_injects_steering_hint() {
        var hintLanded = runPlanWithGateReplies(
            /*plan goals*/ List.of("examine crystal", "examine crystal", "examine crystal"),
            /*plan description*/ "Examine loop",
            /*prose*/ "I'll examine the crystal three times.",
            /*M3 body*/ """
            {"steps":[{"action":"examine crystal","success":"no","outcome":"loop"},
                      {"action":"examine crystal","success":"no","outcome":"loop"},
                      {"action":"examine crystal","success":"no","outcome":"loop"}],
             "final_state":"loop","confidence":0.10,
             "reasoning":"three identical examines, no progress"}
            """,
            /*M2 body*/ """
            {"predicted_outcome":"loop","confidence":0.10,
             "reasoning":"Three identical examines is the canonical loop antipattern."}
            """);
        assertThat(hintLanded)
            .as("Hard-gate should fire when M2 ∧ M3 both reject")
            .isTrue();
    }

    @Test
    void m2_reject_but_m3_accept_does_not_fire_hardgate() {
        var hintLanded = runPlanWithGateReplies(
            List.of("library_search", "summarize", "tell_agent"),
            "Research request",
            "On it.",
            /*M3 high-conf*/ """
            {"steps":[{"action":"library_search","success":"yes","outcome":"ok"},
                      {"action":"summarize","success":"yes","outcome":"ok"},
                      {"action":"tell_agent","success":"yes","outcome":"delivered"}],
             "final_state":"answered","confidence":0.92,
             "reasoning":"clean research path"}
            """,
            /*M2 low-conf*/ """
            {"predicted_outcome":"premature_done","confidence":0.10,
             "reasoning":"M2 over-rejects this shape"}
            """);
        assertThat(hintLanded)
            .as("AND-gate must NOT fire when M3 votes accept (split decision)")
            .isFalse();
    }

    @Test
    void m3_reject_but_m2_accept_does_not_fire_hardgate() {
        var hintLanded = runPlanWithGateReplies(
            List.of("introspect", "tell_agent", "goal_done"),
            "Recall query",
            "Let me check.",
            /*M3 low-conf*/ """
            {"steps":[{"action":"introspect","success":"no","outcome":"empty"},
                      {"action":"tell_agent","success":"no","outcome":"nothing to say"},
                      {"action":"goal_done","success":"no","outcome":"premature"}],
             "final_state":"failed","confidence":0.05,
             "reasoning":"M3 over-rejects short conversational plans"}
            """,
            /*M2 high-conf*/ """
            {"predicted_outcome":"completed","confidence":0.93,
             "reasoning":"trivial recall — clean shape"}
            """);
        assertThat(hintLanded)
            .as("AND-gate must NOT fire when M2 votes accept (split decision)")
            .isFalse();
    }

    /**
     * Helper: drive the actor through plan creation, identify M2/M3 gate calls,
     * inject canned replies, then poll working memory for ≤5s and return whether
     * a [Preflight] hint landed.
     */
    private boolean runPlanWithGateReplies(List<String> goals,
                                            String planDesc,
                                            String prose,
                                            String m3Body,
                                            String m2Body) {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "test prompt for " + planDesc)));

        var initialReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        var goalsJson = goals.stream()
            .map(g -> "\"" + g.replace("\"", "\\\"") + "\"")
            .reduce((a, b) -> a + "," + b).orElse("");
        initialReq.replyTo().tell(new InferenceRouter.InferOk(
            initialReq.requestId(),
            prose + "\n```json\n{\"action\":\"task_plan\",\"description\":\""
                + planDesc + "\",\"goals\":[" + goalsJson + "]}\n```\n",
            100, 50));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        InferenceRouter.ChatRequest m3 = null, m2 = null;
        for (int i = 0; i < 2; i++) {
            // #35 — drain the prose voice-pass request so only the M2/M3 gate
            // calls are counted.
            var req = VoicePassTestSupport.nextChatRequest(
                routerProbe, Duration.ofSeconds(5));
            var system = req.messages().stream()
                .filter(m -> "system".equals(m.role()))
                .map(m -> m.content())
                .findFirst().orElse("");
            if (system.contains("simulating a companion's plan")) m3 = req;
            else if (system.contains("scoring agent plan quality")) m2 = req;
        }
        assertThat(m3).isNotNull();
        assertThat(m2).isNotNull();

        m3.replyTo().tell(new InferenceRouter.InferOk(m3.requestId(), m3Body, 100, 50));
        m2.replyTo().tell(new InferenceRouter.InferOk(m2.requestId(), m2Body, 100, 50));

        var memoryProbe = testKit.<CompanionActor.WorkingMemoryResponse>createTestProbe();
        for (int i = 0; i < 20; i++) {
            companion.tell(new CompanionActor.QueryWorkingMemory(memoryProbe.ref()));
            var resp = memoryProbe.receiveMessage(Duration.ofSeconds(2));
            for (var entry : resp.entries()) {
                if (entry.contains("[Preflight]")
                        && entry.contains("Plan flagged as low-quality")) {
                    return true;
                }
            }
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    @Test
    void empty_goal_plan_does_not_fire_preflight() {
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "make a plan")));

        var initialReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Companion emits a task_plan with no goals — preflight should
        // short-circuit before either gate fires.
        initialReq.replyTo().tell(new InferenceRouter.InferOk(
            initialReq.requestId(),
            """
            ```json
            {"action": "task_plan", "description": "empty plan", "goals": []}
            ```
            """, 50, 20));

        // Within a short window, no preflight inference call should arrive
        // because simulatePlanPreflight short-circuits on empty goals.
        // (The plan-start timer is 3s; we check well before that fires.)
        routerProbe.expectNoMessage(Duration.ofSeconds(2));
    }

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of());
    }
}
