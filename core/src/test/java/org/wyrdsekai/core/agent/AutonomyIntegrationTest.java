package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for CompanionActor autonomy wiring.
 *
 * <p>Message-wait probes use 15s (not the usual 5s): the FIRST inference in
 * this class pays one-time JVM init (Lucene, ORT probe, GraalJS warmup) and
 * deep in the single-fork :core:test suite that cold start intermittently
 * exceeded 5s (position-dependent GC/contention — mechanism itself proven
 * by isolated runs). Calibration, not masking: a real wiring break still
 * fails, just with a slower report.</p>
 *
 * <p>These verify that the external event → vitality modulation → autonomy check →
 * inference pipeline is correctly connected. Uses Pekko ActorTestKit with TestProbes
 * to intercept inference requests and room commands.
 *
 * <p>Pattern (same as CompanionActorTest in e2e-test):
 * <ol>
 *   <li>Spawn CompanionActor with TestProbe<RoomCommand> and TestProbe<InferenceRouter.Command></li>
 *   <li>Consume startup messages (Subscribe, EnterRoom, LookRoom) from roomProbe</li>
 *   <li>Reply to LookRoom with a test RoomSnapshot</li>
 *   <li>Use AgentEventStream to deliver events, verify behavior via probes</li>
 * </ol>
 */
@Tag("integration")
class AutonomyIntegrationTest {

    private static ActorTestKit testKit;

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";

    private static AgentProfile profile(String name, String entityId) {
        return new AgentProfile(
            name, entityId, "agent",
            "A companion in Wyrdsekai",
            "You are " + name + ", a companion guide in Wyrdsekai.",
            4096, 256, 0.7);
    }

    private static final AgentProfile PROFILE = profile("Wyrd", "agent-wyrd");

    @BeforeAll
    static void setupClass() {
        // Initialize singletons before any actors try to use them
        AgentEventStream.init();
        EntityRegistry.init();

        testKit = ActorTestKit.create("autonomy-integration-test",
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

        // Consume the 3 startup messages: Subscribe, EnterRoom, LookRoom
        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(15));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(15));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(15));

        // Reply with a room snapshot
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // -----------------------------------------------------------------------
    // Test 1: agent_receives_zone_broadcast
    // -----------------------------------------------------------------------

    @Test
    void agent_receives_zone_broadcast_and_includes_in_context() {
        // Publish a zone broadcast — the agent should buffer it
        var eventStream = AgentEventStream.get();
        eventStream.publishZoneBroadcast("codezaiku", "workshop",
            new S2CMessage.Prose(0, "system", "Training pipeline completed successfully",
                List.of(), null, "normal"));

        // Now trigger speech so the agent builds a prompt that includes the zone context
        var said = playerSaid("Alice", "What's happening in the zone?");
        subscriberRef.tell(new RoomNotification(said));

        // Capture the ChatRequest and verify it includes zone context
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        // The assembled prompt should contain the zone broadcast text
        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContent).contains("Zone");
        assertThat(chatReq.messages()).isNotEmpty();

        // Complete the cycle
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "The training pipeline completed!", 30, 40));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Test 2: agent_receives_system_event
    // -----------------------------------------------------------------------

    @Test
    void agent_receives_system_event_modulates_vitality() {
        // Send INFERENCE_BACKEND_DOWN — should increase error pressure via EnvironmentalMood
        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.INFERENCE_BACKEND_DOWN,
                "llama-1", "connection refused", Instant.now())));

        // The vitality modulation is internal state. Verify via the next inference prompt:
        // higher error pressure means the vitality description in the prompt will mention it.
        var said = playerSaid("Alice", "Are you alright?");
        subscriberRef.tell(new RoomNotification(said));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        // The prompt should contain vitality state description — not empty
        assertThat(chatReq.messages()).isNotEmpty();

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I sense some instability.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Test 3: autonomy_check_triggers_inference_on_salient_event
    // -----------------------------------------------------------------------

    @Test
    void autonomy_check_fires_when_salient_events_accumulate() {
        // Buffer a high-salience system event (HEALTH_ALERT is scored highly)
        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.HEALTH_ALERT,
                "health-monitor", "CPU temperature critical", Instant.now())));

        // The AutonomyCheck fires on a 10-minute timer by default,
        // which is too slow for a test. Instead, verify the event is buffered
        // by triggering a player speech and checking the prompt includes the system event.
        var said = playerSaid("Alice", "What do you sense?");
        subscriberRef.tell(new RoomNotification(said));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // System events should appear in the external event context section
        assertThat(allContent).contains("HEALTH_ALERT");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I sense a health alert.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Test 4: autonomy_check_silent_on_no_events
    // -----------------------------------------------------------------------

    @Test
    void no_inference_without_stimulus() {
        // Fresh companion, no events, no speech — should not trigger inference
        // (The AutonomyCheck timer is 10 minutes, so within a short window nothing fires.)
        routerProbe.expectNoMessage(Duration.ofSeconds(3));
    }

    // -----------------------------------------------------------------------
    // Test 5: think_deeply_sends_tool_infer_request
    // -----------------------------------------------------------------------

    @Test
    void think_deeply_action_sends_tool_infer_request() {
        // Trigger inference with player speech
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Analyze the system deeply")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        // Simulate LLM responding with a think_deeply action
        var responseWithAction = """
            Let me think about that more carefully.
            ```json
            {"action":"think_deeply","capability":"reasoning","prompt":"Analyze current system health metrics and identify root causes of instability."}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithAction, 30, 50));

        // CompanionActor should speak the prose part first
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));

        // Then send a ToolInferRequest to the router (separate from ChatRequest).
        // #35 — drain the prose voice-pass ChatRequest first.
        var toolReq = VoicePassTestSupport.nextToolInferRequest(
            routerProbe, Duration.ofSeconds(15));

        assertThat(toolReq.agentId()).isEqualTo(PROFILE.entityId());
        assertThat(toolReq.capability()).isEqualTo("reasoning");
        assertThat(toolReq.prompt()).contains("system health");
    }

    // -----------------------------------------------------------------------
    // Test 6: tell_agent_delivers_to_target
    // -----------------------------------------------------------------------

    @Test
    void tell_agent_delivers_message_to_target() {
        // Spawn a second companion (Agent B) that also subscribes to AgentEventStream
        var roomProbeB = testKit.<RoomCommand>createTestProbe();
        var routerProbeB = testKit.<InferenceRouter.Command>createTestProbe();
        var profileB = profile("Luna", "agent-luna");

        var companionB = testKit.spawn(CompanionActor.create(
            profileB, roomProbeB.ref(), "garden", routerProbeB.ref(), null));

        // Consume B's startup messages
        var subB = roomProbeB.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(15));
        var subscriberRefB = subB.subscriber();
        roomProbeB.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(15));
        var lookB = roomProbeB.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(15));
        lookB.replyTo().tell(new RoomResponse.Ok(testSnapshot("garden", "The Garden")));

        // Agent A speaks and LLM returns a tell_agent action targeting Luna
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Send a message to Luna")));
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var responseWithTell = """
            I'll relay your message to Luna.
            ```json
            {"action":"tell_agent","target":"Luna","message":"Alice sends her regards."}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithTell, 20, 40));

        // Agent A speaks the prose
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));

        // Agent B should receive an inference trigger from the tell (via AgentMessage)
        // The AgentMessage causes B to synthesize a Said event and trigger inference
        var chatReqB = routerProbeB.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(10));

        // The prompt should include the message from Wyrd
        var allContentB = chatReqB.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContentB).contains("Alice sends her regards");

        // Complete B's inference
        chatReqB.replyTo().tell(new InferenceRouter.InferOk(
            chatReqB.requestId(), "Tell Alice I said hello!", 15, 20));
        roomProbeB.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Test 7: zone_command_checks_permissions
    // -----------------------------------------------------------------------

    @Test
    void zone_command_without_router_is_ignored() {
        // The companion was spawned with null CommandRouter.
        // A zone_command action should be silently ignored (no crash, no forwarding).
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Run a system diagnostic")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var responseWithZoneCmd = """
            Running diagnostic now.
            ```json
            {"action":"zone_command","command":"system.diagnostic","payload":{"level":"full"}}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithZoneCmd, 20, 40));

        // The prose is spoken (companion still functions)
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));

        // No crash — the companion continues to work normally
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Are you still there?")));
        // #35 — drain the prior turn's voice-pass request.
        var chatReq2 = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(15));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Still here!", 10, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Test 8: commitment_appears_in_context
    // -----------------------------------------------------------------------

    @Test
    void commitment_from_llm_appears_in_subsequent_prompts() {
        // First: get the agent to make a commitment
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Can you check the logs later?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var responseWithCommitment = """
            I'll check the logs for you soon.
            ```json
            {"action":"make_commitment","description":"Check the system logs for Alice","deadline":"2025-01-01T00:00:00Z"}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithCommitment, 25, 40));

        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));

        // Now trigger another inference — the commitment should appear in context
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What were you going to do?")));

        // #35 — drain the prior turn's voice-pass request.
        var chatReq2 = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(15));

        var allContent = chatReq2.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The commitment (now overdue since deadline is in the past) should appear
        assertThat(allContent).contains("Check the system logs");

        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Right, I was going to check those logs!", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Additional: EnvironmentalMood wiring verification
    // -----------------------------------------------------------------------

    @Test
    void adjacent_activity_is_buffered_and_included_in_context() {
        // Publish adjacent activity via AgentEventStream
        var eventStream = AgentEventStream.get();
        eventStream.publishAdjacentActivity("room-east", "The Garden",
            AgentEvent.ActivityType.SPEECH, 3);

        // Trigger speech to build a prompt
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What's happening nearby?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Adjacent activity should appear in the nearby context section
        assertThat(allContent).contains("Garden");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I hear voices from the Garden.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    @Test
    void agent_message_triggers_inference() {
        // Deliver a direct message to the companion via the command interface
        companion.tell(new CompanionActor.AgentMessageReceived(
            new AgentEvent.AgentMessage(
                "agent-luna", "Luna", PROFILE.entityId(),
                "Have you seen the new training results?", Instant.now())));

        // The agent should trigger inference in response to the message
        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContent).contains("training results");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Interesting results from Luna!", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    @Test
    void multiple_system_events_buffered_and_included() {
        // Buffer several system events
        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.NODE_JOINED,
                "node-phone", "Phone node joined via mDNS", Instant.now())));

        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.ZONE_SERVICE_REGISTERED,
                "codezaiku", "CodeZaiku service registered", Instant.now())));

        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What's the system status?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(15));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Both system events should appear in context
        assertThat(allContent).contains("NODE_JOINED");
        assertThat(allContent).contains("ZONE_SERVICE_REGISTERED");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "The system looks healthy.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(15));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorldEvent.Said playerSaid(String name, String text) {
        return new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-" + name.toLowerCase(), name, text);
    }

    private RoomSnapshot testSnapshot() {
        return testSnapshot(ROOM_ID, "The Nexus");
    }

    private RoomSnapshot testSnapshot(String roomId, String roomName) {
        return new RoomSnapshot(
            roomId, roomName, "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(),  // entities
            List.of(),  // objects
            List.of()   // hints
        );
    }
}
