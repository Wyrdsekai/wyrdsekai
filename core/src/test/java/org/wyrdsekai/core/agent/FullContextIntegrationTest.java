package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.ImageAttachment;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.skill.SchedulerService;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests verifying the full wiring of ALL context
 * awareness features in Wyrdsekai: vision, location, calendar, notifications,
 * watchers, schedules, zone broadcasts, system events, think_deeply, codex actions.
 *
 * <p>Uses Pekko ActorTestKit with TestProbe for InferenceRouter and RoomCommand
 * (same pattern as AutonomyIntegrationTest). Each test proves a specific context
 * pipeline flows end-to-end through CompanionActor.
 *
 * <p>Singleton services (LocationContext, CalendarContext, NotificationService,
 * WatcherService, SchedulerService) are initialized in @BeforeAll and used by
 * tests to inject context that the CompanionActor picks up during prompt assembly.
 */
@Tag("integration")
class FullContextIntegrationTest {

    private static ActorTestKit testKit;
    private static WatcherService watcherService;
    private static SchedulerService schedulerService;
    private static final List<String> deliveredNotifications = new CopyOnWriteArrayList<>();
    private static final List<String> deliveredTargets = new CopyOnWriteArrayList<>();

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-wyrd-ctx";

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
        // Initialize singletons before any actors try to use them
        AgentEventStream.init();
        EntityRegistry.init();
        LocationContext.init();
        CalendarContext.init();

        // NotificationService — global singleton used by CompanionActor
        NotificationService.init();
        NotificationService.get().setDeliveryCallback((target, notif) -> {
            deliveredTargets.add(target);
            deliveredNotifications.add(notif.message());
            return true;
        });

        // WatcherService
        WatcherService.init(NotificationService.get(), script -> {
            return switch (script.strip()) {
                case "true" -> true;
                case "false" -> false;
                default -> script;
            };
        });
        watcherService = WatcherService.get();

        // SchedulerService
        SchedulerService.init(new StubSkillRegistry());
        schedulerService = SchedulerService.get();

        testKit = ActorTestKit.create("full-context-integration-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        testKit.shutdownTestKit();
        LocationContext.reset();
        CalendarContext.reset();
    }

    @BeforeEach
    void spawnCompanion() {
        // Clear per-test state
        deliveredNotifications.clear();
        deliveredTargets.clear();

        // Reset location + calendar to clean slate
        LocationContext.get().update(0.0, 0.0, "");
        CalendarContext.get().updateEvents(List.of());

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();

        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        // Consume the 3 startup messages: Subscribe, EnterRoom, LookRoom
        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();

        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));

        // Reply with a room snapshot
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));
    }

    // -----------------------------------------------------------------------
    // Test 1: image_attachment_vision_unavailable
    // -----------------------------------------------------------------------

    @Test
    void image_attachment_flows_from_say_to_companion_vision_unavailable() {
        // CompanionActor is created WITHOUT CompanionCapabilities (via 5-param create),
        // so capabilities is null → vision capability is NOT available.
        // When a Said with ImageAttachment arrives, the companion should include
        // the degraded vision context "[Image shared but vision analysis unavailable]"
        // in the prompt.
        var image = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");
        var said = new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-alice", "Alice",
            "What's this plant?", "en", List.of(image));

        subscriberRef.tell(new RoomNotification(said));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Since vision is unavailable, the degraded context should be included
        assertThat(allContent).contains("vision analysis unavailable");

        // Complete the cycle
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I can see you shared a photo but I cannot analyze images right now.", 30, 40));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 2: vision_analysis_feeds_into_identity_inference (with capabilities)
    // -----------------------------------------------------------------------

    @Test
    void vision_analysis_feeds_into_identity_inference() {
        // Spawn a VISION-CAPABLE companion using the 10-param overload
        var visionRoomProbe = testKit.<RoomCommand>createTestProbe();
        var visionRouterProbe = testKit.<InferenceRouter.Command>createTestProbe();

        // Create capabilities with vision available
        var caps = new CompanionCapabilities(
            null, null, null, null, false, 0, null, false,
            null, null, "vision, reasoning");

        var visionCompanion = testKit.spawn(CompanionActor.create(
            PROFILE, visionRoomProbe.ref(), ROOM_ID, visionRouterProbe.ref(),
            null, null, null, null, null, caps));

        // Consume startup messages
        var sub = visionRoomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        var visionSubscriberRef = sub.subscriber();
        visionRoomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = visionRoomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(testSnapshot()));

        // Send a Said with image attachment
        var image = ImageAttachment.fromBase64("aW1hZ2VkYXRh", "image/jpeg");
        var said = new WorldEvent.Said(
            ROOM_ID, Instant.now(), "player-alice", "Alice",
            "What's this plant?", "en", List.of(image));

        visionSubscriberRef.tell(new RoomNotification(said));

        // Step 1: CompanionActor should send a ToolInferRequest with capability "vision"
        var toolReq = visionRouterProbe.expectMessageClass(
            InferenceRouter.ToolInferRequest.class, Duration.ofSeconds(5));

        assertThat(toolReq.capability()).isEqualTo("vision");
        assertThat(toolReq.prompt()).contains("What's this plant?");
        assertThat(toolReq.agentId()).isEqualTo(ENTITY_ID);

        // Reply with vision analysis
        toolReq.replyTo().tell(new InferenceRouter.InferOk(
            toolReq.requestId(), "A Monstera deliciosa plant with fenestrated leaves.", 20, 30));

        // Step 2: CompanionActor should now send identity inference ChatRequest
        // with the vision analysis included in the prompt
        var chatReq = visionRouterProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The identity inference prompt should include the vision analysis text
        assertThat(allContent).contains("Monstera deliciosa");

        // Complete the cycle
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "That's a beautiful Monstera!", 40, 20));
        visionRoomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 3: location_update_reaches_agent_prompt
    // -----------------------------------------------------------------------

    @Test
    void location_update_via_event_stream_reaches_agent_prompt() {
        // Update location (simulating phone GPS push)
        LocationContext.get().update(37.7749, -122.4194, "home");

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Where am I?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The prompt should include location context
        assertThat(allContent).contains("Human Location");
        assertThat(allContent).contains("home");
        assertThat(allContent).contains("HOME");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "You're at home!", 20, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 4: location_state_change_triggers_salience
    // -----------------------------------------------------------------------

    @Test
    void location_state_change_triggers_salience() {
        // Location update from HOME to AWAY — SalienceScorer should rate at 0.6
        var event = new AgentEvent.LocationUpdate(
            37.7749, -122.4194, "office",
            LocationContext.LocationState.WORK, Instant.now());

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);
        double score = SalienceScorer.score(event, normalVitality, GenomeProfile.defaults());

        // Known state scores 0.6 (moderate)
        assertThat(score).isEqualTo(0.6);

        // Default threshold is 0.5 — this event passes
        double threshold = SalienceScorer.calculateAttentionThreshold(normalVitality);
        assertThat(score).isGreaterThanOrEqualTo(threshold);

        // UNKNOWN state would score low (routine GPS ping)
        var unknownEvent = new AgentEvent.LocationUpdate(
            0.0, 0.0, "", LocationContext.LocationState.UNKNOWN, Instant.now());
        double lowScore = SalienceScorer.score(unknownEvent, normalVitality, GenomeProfile.defaults());
        assertThat(lowScore).isEqualTo(0.3);
    }

    // -----------------------------------------------------------------------
    // Test 5: calendar_events_appear_in_agent_prompt
    // -----------------------------------------------------------------------

    @Test
    void calendar_events_appear_in_agent_prompt() {
        // Set CalendarContext with an event 10 minutes from now
        var now = Instant.now();
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Sprint Review",
                now.plusSeconds(600), now.plusSeconds(4200), "Zoom", false)
        ));

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "What's on the schedule?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The prompt should include the calendar context
        assertThat(allContent).contains("Upcoming Schedule");
        assertThat(allContent).contains("Sprint Review");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "You have a Sprint Review in 10 minutes on Zoom.", 20, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 6: meeting_detection_raises_attention_threshold
    // -----------------------------------------------------------------------

    @Test
    void meeting_detection_raises_attention_threshold() {
        // Set CalendarContext with a currently-active event
        var now = Instant.now();
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Board Meeting",
                now.minusSeconds(600), now.plusSeconds(3000), null, false)
        ));
        assertThat(CalendarContext.get().isInMeeting()).isTrue();

        var normalVitality = new VitalityState(0.5, 0.5, 0.7, 0.3, 0.0, 0.3, 0.5, 0.5);

        double normalThreshold = SalienceScorer.calculateAttentionThreshold(normalVitality, false);
        double meetingThreshold = SalienceScorer.calculateAttentionThreshold(normalVitality, true);

        // Meeting adds +0.2 to the threshold
        assertThat(meetingThreshold).isEqualTo(normalThreshold + 0.2);
        assertThat(meetingThreshold).isEqualTo(0.7);

        // A routine zone broadcast (score 0.3) should be below meeting threshold
        var routine = new AgentEvent.ZoneBroadcast("codeplane", "room-1",
            new S2CMessage.Prose(1L, "zone", "Heartbeat: all nominal",
                List.of(), null, null, null, false, List.of()),
            now);
        double score = SalienceScorer.score(routine, normalVitality, GenomeProfile.defaults());
        assertThat(score).isLessThan(meetingThreshold);

        // Also verify the meeting context string appears in prompt
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Can you check something?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContent).contains("meeting");

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I'll keep it brief — you're in a meeting.", 20, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 7: agent_notify_action_delivers_to_player
    // -----------------------------------------------------------------------

    @Test
    void agent_notify_action_delivers_to_player() throws InterruptedException {
        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Let me know when the build finishes")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Simulate LLM responding with a notify action
        var responseWithNotify = """
            I'll notify you when it's done!
            ```json
            {"action":"notify","message":"Build #42 completed successfully","priority":"normal","target":"steward"}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithNotify, 30, 40));

        // The prose is spoken
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // The notification delivery happens asynchronously after prose extraction.
        // Poll briefly for the callback to fire.
        for (int i = 0; i < 50 && deliveredNotifications.isEmpty(); i++) {
            Thread.sleep(20);
        }

        assertThat(deliveredNotifications).contains("Build #42 completed successfully");
        assertThat(deliveredTargets).contains("steward");
    }

    // -----------------------------------------------------------------------
    // Test 8: watcher_creation_and_context
    // -----------------------------------------------------------------------

    @Test
    void watcher_creation_and_context() {
        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Watch the GPU temperature for me")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Simulate LLM responding with a watch action
        var responseWithWatch = """
            I'll keep an eye on that.
            ```json
            {"action":"watch","name":"gpu-temp","check":"true","interval":"1m","alert_on":"failure","message":"GPU overheating!","priority":"critical"}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithWatch, 30, 40));

        // The prose is spoken
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Now trigger another inference and verify the watcher appears in context
        // (The watcher was created inside the actor's message handler after speak();
        // verifying via the next prompt proves end-to-end wiring.)
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What are you monitoring?")));

        // #35 — drain the prior turn's voice-pass request.
        var chatReq2 = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(5));

        var allContent = chatReq2.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContent).contains("Active Watchers");
        assertThat(allContent).contains("gpu-temp");

        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "I'm monitoring the GPU temperature.", 20, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 9: schedule_creation_and_context
    // -----------------------------------------------------------------------

    @Test
    void schedule_creation_and_context() {
        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Check the API every hour")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Simulate LLM responding with a schedule action
        var responseWithSchedule = """
            I'll set that up for you!
            ```json
            {"action":"schedule","skill":"workbench.api-monitor","interval":"1h","params":{"url":"https://api.example.com/health"}}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithSchedule, 30, 40));

        // The prose is spoken
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Now trigger another inference and verify the schedule appears in context
        // (The schedule was created inside the actor's message handler after speak();
        // verifying via the next prompt proves end-to-end wiring.)
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What's scheduled?")));

        // #35 — drain the prior turn's voice-pass request.
        var chatReq2 = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(5));

        var allContent = chatReq2.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        assertThat(allContent).contains("Active Schedules");
        assertThat(allContent).contains("workbench.api-monitor");

        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "I have the API monitor running hourly.", 20, 15));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 10: all_context_sources_appear_in_single_prompt
    // -----------------------------------------------------------------------

    @Test
    void all_context_sources_appear_in_single_prompt() {
        // Set location to HOME
        LocationContext.get().update(37.7749, -122.4194, "home");

        // Set calendar event 30 minutes from now
        var now = Instant.now();
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Team Standup",
                now.plusSeconds(1800), now.plusSeconds(3600), null, false)
        ));

        // Publish zone broadcast
        var eventStream = AgentEventStream.get();
        eventStream.publishZoneBroadcast("codeplane", "workshop",
            new S2CMessage.Prose(0, "system", "Training pipeline started",
                List.of(), null, "normal"));

        // Publish system event
        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.NODE_JOINED,
                "node-phone", "Phone node joined", Instant.now())));

        // Publish adjacent activity
        eventStream.publishAdjacentActivity("room-east", "The Garden",
            AgentEvent.ActivityType.SPEECH, 2);

        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Give me the full picture")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Verify ALL context sources are present
        assertThat(allContent).contains("Human Location");      // location
        assertThat(allContent).contains("home");                 // location name
        assertThat(allContent).contains("Upcoming Schedule");    // calendar
        assertThat(allContent).contains("Team Standup");         // calendar event
        assertThat(allContent).contains("Zone");                 // zone activity
        assertThat(allContent).contains("NODE_JOINED");          // system status
        assertThat(allContent).contains("Garden");               // nearby activity

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "Here's everything I can see.", 40, 20));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 11: codex_action_routes_as_zone_command
    // -----------------------------------------------------------------------

    @Test
    void codex_action_routes_as_zone_command_with_no_router_is_safe() {
        // CompanionActor was spawned with null CommandRouter.
        // A zone_command action (which is how codex operations are routed)
        // should be silently ignored — no crash, companion continues normally.
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Commit the changes")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Simulate LLM returning a zone_command (the agent's way of doing codex ops)
        var responseWithZoneCmd = """
            Committing the changes now.
            ```json
            {"action":"zone_command","command":"codeplane.codex","payload":{"operation":"commit","message":"Fix bug"}}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithZoneCmd, 25, 30));

        // The prose is still spoken (companion doesn't crash)
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Companion still functions normally after the ignored zone command
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Are you still working?")));
        // #35 — drain the prior turn's voice-pass request.
        var chatReq2 = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(5));
        chatReq2.replyTo().tell(new InferenceRouter.InferOk(
            chatReq2.requestId(), "Still here!", 10, 10));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    // -----------------------------------------------------------------------
    // Test 12: think_deeply_then_speak
    // -----------------------------------------------------------------------

    @Test
    void think_deeply_then_speak() {
        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "Analyze the architecture deeply")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        // Simulate LLM responding with a think_deeply action
        var responseWithThinkDeeply = """
            Let me analyze that more carefully.
            ```json
            {"action":"think_deeply","capability":"reasoning","prompt":"Analyze the current system architecture and identify bottlenecks."}
            ```""";

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), responseWithThinkDeeply, 30, 50));

        // CompanionActor speaks the prose part first
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));

        // Then sends a ToolInferRequest to the router (via AskPattern).
        // #35 — drain the prose voice-pass ChatRequest first.
        var toolReq = VoicePassTestSupport.nextToolInferRequest(
            routerProbe, Duration.ofSeconds(5));

        assertThat(toolReq.agentId()).isEqualTo(ENTITY_ID);
        assertThat(toolReq.capability()).isEqualTo("reasoning");
        assertThat(toolReq.prompt()).contains("architecture");

        // Reply with analysis via the AskPattern replyTo
        toolReq.replyTo().tell(new InferenceRouter.InferOk(
            toolReq.requestId(),
            "The main bottleneck is in the message serialization layer.",
            50, 40));

        // CompanionActor should now trigger a follow-up identity inference
        // that includes the tool analysis result.
        // #35 — drain the prose voice-pass request.
        var followUpReq = VoicePassTestSupport.nextChatRequest(
            routerProbe, Duration.ofSeconds(5));

        var allContent = followUpReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The follow-up prompt should include the tool analysis result
        assertThat(allContent).contains("Tool Analysis Result");
        assertThat(allContent).contains("serialization layer");

        // Reply with the agent's interpreted speech
        followUpReq.replyTo().tell(new InferenceRouter.InferOk(
            followUpReq.requestId(),
            "After analyzing the system, the main bottleneck is in serialization.",
            60, 30));

        // The agent speaks the interpreted result in the room
        var sayCmd = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(sayCmd.text()).contains("serialization");
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

    // --- Stub SkillRegistry for SchedulerService ---
    private static class StubSkillRegistry extends SkillRegistry {
        StubSkillRegistry() { super(null, null); }

        @Override
        public SkillResult execute(String skillId, Map<String, Object> params,
                                    SkillContext context) {
            return SkillResult.ok("stub result", Map.of(), 0, SkillTier.NATIVE, skillId);
        }
    }
}
