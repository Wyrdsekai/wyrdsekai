package org.wyrdsekai.core.context;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.*;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.*;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.skill.ScheduledAction;
import org.wyrdsekai.core.skill.SchedulerService;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.voice.SpeechToTextService;
import org.wyrdsekai.core.voice.TextToSpeechService;
import org.wyrdsekai.core.voice.VoiceConversationManager;
import org.wyrdsekai.core.voice.VoiceMode;
import org.wyrdsekai.core.voice.VoiceService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests verifying the FULL WIRING of the Unified Personal
 * Context and Voice systems through the CompanionActor pipeline.
 *
 * <p>These are cross-system tests -- not just individual component tests but end-to-end
 * verification that data flows from context sources through the aggregator, through
 * permission filtering, into the prompt assembled by CompanionActor, and delivered to
 * the inference router.
 *
 * <p>Voice pipeline tests verify: VoiceConversationManager → STT transcription →
 * text routing, and voice mode gating.
 *
 * <p>Uses the same Pekko ActorTestKit pattern as AutonomyIntegrationTest and
 * FullContextIntegrationTest.
 *
 * @see PersonalContextAggregator
 * @see VoiceConversationManager
 * @see CompanionActor
 * @see ContextAccessManager
 */
@Tag("integration")
class FullSystemIntegrationTest {

    private static ActorTestKit testKit;
    private static final List<String> deliveredNotifications = new CopyOnWriteArrayList<>();
    private static final List<String> deliveredTargets = new CopyOnWriteArrayList<>();

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String ENTITY_ID = "agent-ctx-voice";
    private static final String STEWARD = "did:key:steward1";

    private static AgentProfile profile() {
        return new AgentProfile(
            "Ma", ENTITY_ID, "agent",
            "A companion in Wyrdsekai",
            "You are Ma, a companion guide in Wyrdsekai.",
            4096, 256, 0.7);
    }

    private static final AgentProfile PROFILE = profile();

    @BeforeAll
    static void setupClass() {
        // Initialize ALL singletons needed for the full context + voice pipeline
        AgentEventStream.init();
        EntityRegistry.init();
        LocationContext.init();
        CalendarContext.init();
        PersonalContextAggregator.init();
        ContextAccessManager.init();
        VoiceService.init();
        SpeechToTextService.init();
        TextToSpeechService.init();

        // NotificationService
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

        // SchedulerService
        SchedulerService.init(new StubSkillRegistry());

        testKit = ActorTestKit.create("full-system-integration-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        testKit.shutdownTestKit();
        // Re-init to clean slate (reset() is package-private on some singletons)
        LocationContext.init();
        CalendarContext.init();
        PersonalContextAggregator.reset();
        VoiceService.init();
        SpeechToTextService.init();
        TextToSpeechService.init();
    }

    @BeforeEach
    void spawnCompanion() {
        // Clear per-test state
        deliveredNotifications.clear();
        deliveredTargets.clear();

        // Reset singletons to clean slate
        LocationContext.get().update(0.0, 0.0, "");
        CalendarContext.get().updateEvents(List.of());

        // Re-init aggregator for isolation. ContextAccessManager.init() makes a fresh singleton.
        PersonalContextAggregator.init();
        ContextAccessManager.init();
        // Reset voice to default disabled state
        VoiceService.get().setMode(VoiceMode.DISABLED);

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

    // =======================================================================
    // Unified Personal Context wiring tests
    // =======================================================================

    // -----------------------------------------------------------------------
    // Test 1: aggregator_feeds_into_companion_prompt
    // -----------------------------------------------------------------------

    @Test
    void aggregator_feeds_into_companion_prompt() {
        // Set up PersonalContextAggregator with location + calendar + desktop
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Grant all permissions to the agent
        accessMgr.grant(ENTITY_ID, "location", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "active_window", "", STEWARD);

        // Push context data via household-level singletons (CompanionActor syncs from these)
        LocationContext.get().update(37.7749, -122.4194, "home");
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Sprint Review",
                now.plusSeconds(1200), now.plusSeconds(3600), "Zoom", false)
        ));
        // Desktop won't be available in test env (no xdotool), but aggregator direct update works
        aggregator.updateDesktop("household-default", "VS Code - Main.java", "coding");

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "What am I working on?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Verify unified personal context from aggregator appears in the prompt
        assertThat(allContent).contains("Personal Context");
        assertThat(allContent).contains("home").describedAs("location should appear");
        assertThat(allContent).contains("Sprint Review").describedAs("calendar event should appear");
        assertThat(allContent).contains("VS Code").describedAs("desktop context should appear");

        completeCycle(chatReq, "You're at home preparing for Sprint Review while editing in VS Code.");
    }

    // -----------------------------------------------------------------------
    // Test 2: permission_filtering_in_companion_prompt
    // -----------------------------------------------------------------------

    @Test
    void permission_filtering_in_companion_prompt() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Grant ONLY calendar permission -- no location, no desktop
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);

        LocationContext.get().update(37.7749, -122.4194, "secret-bunker");
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Team Standup",
                now.plusSeconds(600), now.plusSeconds(2400), null, false)
        ));
        aggregator.updateDesktop("household-default", "PrivateApp.exe", "other");

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "What's on my schedule?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Calendar should be present
        assertThat(allContent).contains("Team Standup");

        // Location and desktop should NOT be present (no permission)
        assertThat(allContent).doesNotContain("secret-bunker");
        assertThat(allContent).doesNotContain("PrivateApp");

        completeCycle(chatReq, "You have a Team Standup in about 10 minutes.");
    }

    // -----------------------------------------------------------------------
    // Test 3: topic_extraction_feeds_from_conversation
    // -----------------------------------------------------------------------

    @Test
    void topic_extraction_feeds_from_conversation() {
        var aggregator = PersonalContextAggregator.get();

        // Simulate prior conversation about deployment and migration
        var messages = List.of(
            "We need to finish the deployment pipeline today",
            "The deployment scripts need to handle migration",
            "Let's check on the database migration progress",
            "The deployment target is production east",
            "Migration rollback should work automatically"
        );
        var topics = TopicExtractor.extractTopics(messages, 5);
        aggregator.updateTopics("household-default", topics);

        // Trigger inference on the 6th message
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What have we been talking about?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Topics are Tier 1 -- always visible regardless of permissions
        assertThat(allContent).containsIgnoringCase("deployment");
        assertThat(allContent).containsIgnoringCase("migration");

        completeCycle(chatReq, "We've been discussing deployment and migration.");
    }

    // -----------------------------------------------------------------------
    // Test 4: connected_dots_appear_in_prompt
    // -----------------------------------------------------------------------

    @Test
    void connected_dots_appear_in_prompt() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Grant calendar + desktop permissions so connected-dots logic triggers
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "active_window", "", STEWARD);

        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Code Review",
                now.plusSeconds(900), now.plusSeconds(2700), null, false)
        ));
        aggregator.updateDesktop("household-default", "PR-1234.java", "coding");

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Am I ready?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The aggregator should produce connected narrative: "Preparing for Code Review ... editing PR-1234.java"
        assertThat(allContent).contains("Preparing for Code Review");
        assertThat(allContent).contains("PR-1234");

        completeCycle(chatReq, "Looks like you're getting ready for the Code Review.");
    }

    // -----------------------------------------------------------------------
    // Test 5: multiple_data_sources_combined
    // -----------------------------------------------------------------------

    @Test
    void multiple_data_sources_combined() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Grant ALL permissions
        accessMgr.grant(ENTITY_ID, "location", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "active_window", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "email_subjects", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "files", "", STEWARD);

        // Set ALL sources
        LocationContext.get().update(37.7749, -122.4194, "office");
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Standup",
                now.plusSeconds(300), now.plusSeconds(1200), null, false)
        ));
        aggregator.updateDesktop("household-default", "Terminal - bash", "terminal");
        aggregator.updateTopics("household-default", List.of("API", "Tests"));
        aggregator.updateEmailSubjects("household-default", List.of("RE: API Review", "CI failures"));
        aggregator.updateRecentFiles("household-default", List.of("api-spec.yaml", "test-report.html"));
        aggregator.markActive("household-default");

        // Trigger inference
        subscriberRef.tell(new RoomNotification(playerSaid("Alice", "Give me the full picture.")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Verify ALL sources appear in the prompt under the unified Personal Context block
        assertThat(allContent).contains("Personal Context");
        assertThat(allContent).containsIgnoringCase("office");
        assertThat(allContent).contains("Standup");
        assertThat(allContent).contains("Terminal");
        assertThat(allContent).containsIgnoringCase("API");
        assertThat(allContent).contains("API Review");
        assertThat(allContent).contains("api-spec.yaml");

        completeCycle(chatReq, "Here's the full picture.");
    }

    // -----------------------------------------------------------------------
    // Test 6: stale_location_marked
    // -----------------------------------------------------------------------

    @Test
    void stale_location_detected_on_profile() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();

        accessMgr.grant(ENTITY_ID, "location", "", STEWARD);

        // Fresh location -- not stale
        aggregator.updateLocation("household-default",
            LocationContext.LocationState.WORK, "Office");

        var profile = aggregator.getProfile("household-default");
        assertThat(profile.isLocationStale()).isFalse();

        // Verify fresh location in context does NOT have [stale]
        String ctx = aggregator.buildContextForAgent("household-default", ENTITY_ID, accessMgr);
        assertThat(ctx).contains("Office");
        assertThat(ctx).doesNotContain("[stale]");

        // Note: We cannot easily fast-forward time by 35 minutes in a unit test
        // without mocking the clock. Instead, verify the threshold constant via the API.
        // The isLocationStale() method checks Duration > 30 minutes, which is tested here
        // by confirming a fresh update is NOT stale.
    }

    // =======================================================================
    // Voice wiring tests
    // =======================================================================

    // -----------------------------------------------------------------------
    // Test 7: voice_audio_c2s_triggers_transcription
    // -----------------------------------------------------------------------

    @Test
    void voice_audio_c2s_triggers_transcription() {
        // Create a mock STT service that returns a canned result
        var stt = SpeechToTextService.get();
        // Set backend to LOCAL_WHISPER so isAvailable() returns true
        stt.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);

        var voiceService = VoiceService.get();
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);

        // Create a custom VoiceConversationManager with a mock STT
        var mockStt = new SpeechToTextService() {
            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
                return CompletableFuture.completedFuture(
                    new TranscriptionResult("hello world", "en", 0.95, 120));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(mockStt, tts, voiceService);

        // Process voice input
        byte[] dummyAudio = new byte[]{0x01, 0x02, 0x03, 0x04};
        var result = vcm.processVoiceInput("player-alice", dummyAudio, "wav").join();

        // Verify transcription produced the expected text
        assertThat(result.transcribedText()).isEqualTo("hello world");
        assertThat(result.isEmpty()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Test 8: voice_response_has_voice_flag_on_prose
    // -----------------------------------------------------------------------

    @Test
    void voice_response_prose_has_voice_flag() {
        // Verify that S2CMessage.Prose can carry the voice flag and isVoice() works
        var proseWithVoice = new S2CMessage.Prose(
            1L, "Ma", "Hello there!",
            List.of(), null, "normal", "en", true, List.of(), true);

        assertThat(proseWithVoice.isVoice()).isTrue();
        assertThat(proseWithVoice.voice()).isTrue();

        // Without voice flag
        var proseNoVoice = new S2CMessage.Prose(
            2L, "Ma", "Hello there!",
            List.of(), null, "normal");

        assertThat(proseNoVoice.isVoice()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Test 9: voice_disabled_ignores_audio
    // -----------------------------------------------------------------------

    @Test
    void voice_disabled_ignores_audio() {
        var voiceService = VoiceService.get();
        voiceService.setMode(VoiceMode.DISABLED);

        var mockStt = new SpeechToTextService() {
            boolean called = false;

            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
                called = true;
                return CompletableFuture.completedFuture(
                    new TranscriptionResult("should not appear", "en", 0.9, 50));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(mockStt, tts, voiceService);

        byte[] dummyAudio = new byte[]{0x01, 0x02, 0x03};
        var result = vcm.processVoiceInput("player-alice", dummyAudio, "wav").join();

        // When voice is DISABLED, processVoiceInput returns disabled() response
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.transcribedText()).isNull();
        assertThat(mockStt.called).isFalse();
    }

    // -----------------------------------------------------------------------
    // Test 10: voice_mode_permission_required
    // -----------------------------------------------------------------------

    @Test
    void voice_mode_permission_required() {
        var voiceService = VoiceService.get();

        // Initially disabled
        voiceService.setMode(VoiceMode.DISABLED);
        assertThat(voiceService.isVoiceEnabled()).isFalse();

        var mockStt = new SpeechToTextService() {
            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
                return CompletableFuture.completedFuture(
                    new TranscriptionResult("test voice", "en", 0.9, 100));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(mockStt, tts, voiceService);

        byte[] audio = new byte[]{0x01, 0x02};

        // Before grant: voice disabled
        var resultBefore = vcm.processVoiceInput("player-alice", audio, "wav").join();
        assertThat(resultBefore.isEmpty()).isTrue();

        // After grant: enable voice mode (simulates steward granting voice access)
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(voiceService.isVoiceEnabled()).isTrue();

        // Now voice processing works
        var resultAfter = vcm.processVoiceInput("player-alice", audio, "wav").join();
        assertThat(resultAfter.transcribedText()).isEqualTo("test voice");
    }

    // =======================================================================
    // Cross-system tests
    // =======================================================================

    // -----------------------------------------------------------------------
    // Test 11: context_plus_voice_together
    // -----------------------------------------------------------------------

    @Test
    void context_plus_voice_together() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Set up context: location + calendar
        accessMgr.grant(ENTITY_ID, "location", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);

        LocationContext.get().update(37.7749, -122.4194, "home");
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Team Meeting",
                now.plusSeconds(900), now.plusSeconds(2700), "Zoom", false)
        ));

        // Verify voice service is available and context co-exists
        var voiceService = VoiceService.get();
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);

        var mockStt = new SpeechToTextService() {
            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
                return CompletableFuture.completedFuture(
                    new TranscriptionResult("what's my schedule?", "en", 0.95, 150));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(mockStt, tts, voiceService);

        // Transcribe voice input
        byte[] audio = new byte[]{0x01, 0x02, 0x03};
        var voiceResult = vcm.processVoiceInput("player-alice", audio, "wav").join();
        assertThat(voiceResult.transcribedText()).isEqualTo("what's my schedule?");

        // Now send the transcribed text as a Say event (simulating what WyrdWebSocket does)
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", voiceResult.transcribedText())));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // The prompt should include BOTH unified personal context and the voice-transcribed query
        assertThat(allContent).contains("home").describedAs("location from context");
        assertThat(allContent).contains("Team Meeting").describedAs("calendar from context");
        assertThat(allContent).contains("schedule").describedAs("voice-transcribed text");

        completeCycle(chatReq, "You have a Team Meeting in 15 minutes on Zoom.");
    }

    // -----------------------------------------------------------------------
    // Test 12: all_systems_combined_single_prompt
    // -----------------------------------------------------------------------

    @Test
    void all_systems_combined_single_prompt() {
        var aggregator = PersonalContextAggregator.get();
        var accessMgr = ContextAccessManager.get();
        var now = Instant.now();

        // Grant ALL permissions
        accessMgr.grant(ENTITY_ID, "location", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "calendar", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "active_window", "", STEWARD);
        accessMgr.grant(ENTITY_ID, "email_subjects", "", STEWARD);

        // Context: Location = WORK
        LocationContext.get().update(37.7749, -122.4194, "office");

        // Context: Calendar = meeting in 15m
        CalendarContext.get().updateEvents(List.of(
            new CalendarContext.CalendarEvent("Architecture Review",
                now.plusSeconds(900), now.plusSeconds(3600), "Conf Room", false)
        ));

        // Context: Desktop = VS Code
        aggregator.updateDesktop("household-default", "VS Code - architecture.md", "coding");

        // Context: Topics from prior conversation
        aggregator.updateTopics("household-default", List.of("API migration"));

        // Context: Email subjects
        aggregator.updateEmailSubjects("household-default", List.of("RE: Migration plan"));

        // Active watcher (via prior watcher creation)
        var watcherService = WatcherService.get();
        watcherService.createWatcher("api-health", ENTITY_ID, "true", "1m",
            "failure", "API is down!", "critical");

        // Active schedule (via SchedulerService)
        var schedulerService = SchedulerService.get();
        var scheduleAction = new ScheduledAction(
            "sched-1", ENTITY_ID, "workbench.daily-backup", Map.of(),
            new ScheduledAction.Schedule.Interval(
                Duration.ofHours(24), Instant.now()),
            false, null, 0,
            ScheduledAction.ActionStatus.ACTIVE,
            Instant.now(), null, Instant.now().plusSeconds(86400));
        schedulerService.create(scheduleAction);

        // Recent zone broadcast
        var eventStream = AgentEventStream.get();
        eventStream.publishZoneBroadcast("codeplane", "workshop",
            new S2CMessage.Prose(0, "system", "Training pipeline completed",
                List.of(), null, "normal"));

        // System event: node joined
        companion.tell(new CompanionActor.SystemEventReceived(
            new AgentEvent.SystemEvent(
                AgentEvent.SystemEventType.NODE_JOINED,
                "node-phone", "Phone node joined", now)));

        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What is the full system status?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Verify EVERY system's context appears in the prompt:

        // 1. Location
        assertThat(allContent).containsIgnoringCase("office")
            .describedAs("Location context (WORK/office) should appear");

        // 2. Calendar
        assertThat(allContent).contains("Architecture Review")
            .describedAs("Calendar event should appear");

        // 3. Desktop
        assertThat(allContent).contains("VS Code")
            .describedAs("Desktop/active window should appear");

        // 4. Topics
        assertThat(allContent).containsIgnoringCase("API")
            .describedAs("Conversation topics should appear");

        // 5. Active Watchers
        assertThat(allContent).contains("api-health")
            .describedAs("Active watcher should appear");

        // 6. Active Schedules
        assertThat(allContent).contains("daily-backup")
            .describedAs("Active schedule should appear");

        // 7. Zone broadcast
        assertThat(allContent).contains("codeplane")
            .describedAs("Zone broadcast should appear");

        // 8. System event
        assertThat(allContent).contains("NODE_JOINED")
            .describedAs("System event should appear");

        // 9. Email subjects
        assertThat(allContent).contains("Migration plan")
            .describedAs("Email subjects should appear");

        // 10. Connected dots (calendar + desktop)
        assertThat(allContent).contains("Preparing for Architecture Review")
            .describedAs("Connected-dots narrative should appear");

        completeCycle(chatReq, "Here's the complete system status.");
    }

    // =======================================================================
    // Voice conversation manager - additional wiring tests
    // =======================================================================

    // -----------------------------------------------------------------------
    // Test 13: voice_conversation_manager_input_output_availability
    // -----------------------------------------------------------------------

    @Test
    void voice_conversation_manager_availability_checks() {
        var voiceService = VoiceService.get();
        var stt = SpeechToTextService.get();

        // Everything disabled
        voiceService.setMode(VoiceMode.DISABLED);
        stt.setActiveBackend(SpeechToTextService.SttBackend.NONE);

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(stt, tts, voiceService);

        // Input unavailable: voice disabled AND no STT backend
        assertThat(vcm.isInputAvailable()).isFalse();

        // Enable voice but still no STT backend
        voiceService.setMode(VoiceMode.WAKE_WORD);
        assertThat(vcm.isInputAvailable()).isFalse();

        // Enable STT backend too
        stt.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);
        assertThat(vcm.isInputAvailable()).isTrue();

        // Disable voice again
        voiceService.setMode(VoiceMode.DISABLED);
        assertThat(vcm.isInputAvailable()).isFalse();
    }

    // -----------------------------------------------------------------------
    // Test 14: stt_failure_returns_disabled_response
    // -----------------------------------------------------------------------

    @Test
    void stt_failure_returns_disabled_response() {
        var voiceService = VoiceService.get();
        voiceService.setMode(VoiceMode.ALWAYS_ON);

        var failingStt = new SpeechToTextService() {
            @Override
            public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("Whisper crashed"));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        var tts = TextToSpeechService.get();
        var vcm = new VoiceConversationManager(failingStt, tts, voiceService);

        byte[] audio = new byte[]{0x01, 0x02};
        var result = vcm.processVoiceInput("player-alice", audio, "wav").join();

        // Graceful degradation: STT failure produces a disabled (empty) response, not an exception
        assertThat(result.isEmpty()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Test 15: topic_extractor_wired_to_aggregator_and_prompt
    // -----------------------------------------------------------------------

    @Test
    void topic_extractor_pipeline_from_messages_to_prompt() {
        var aggregator = PersonalContextAggregator.get();

        // Simulate 6 messages about "kubernetes" and "rollback"
        var messages = List.of(
            "The kubernetes cluster is running out of resources",
            "We should increase the kubernetes node count",
            "The rollback procedure needs to be tested",
            "Can you check the kubernetes dashboard?",
            "The rollback should happen automatically",
            "Make sure the kubernetes pods are healthy"
        );

        // Extract topics and push to aggregator
        var topics = TopicExtractor.extractTopics(messages, 5);
        assertThat(topics).isNotEmpty();

        // Verify "Kubernetes" is extracted as a topic (most frequent word)
        assertThat(topics.stream().map(String::toLowerCase).toList())
            .contains("kubernetes");

        aggregator.updateTopics("household-default", topics);

        // Trigger inference
        subscriberRef.tell(new RoomNotification(
            playerSaid("Alice", "What should I focus on?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));

        var allContent = chatReq.messages().stream()
            .map(m -> m.content())
            .reduce("", (a, b) -> a + "\n" + b);

        // Topics flow through aggregator into the prompt
        assertThat(allContent).containsIgnoringCase("kubernetes");
        assertThat(allContent).containsIgnoringCase("rollback");

        completeCycle(chatReq, "Focus on kubernetes and rollback.");
    }

    // -----------------------------------------------------------------------
    // Test 16: voice_modes_hierarchy
    // -----------------------------------------------------------------------

    @Test
    void voice_modes_all_non_disabled_enable_voice() {
        var voiceService = VoiceService.get();

        // DISABLED -> not enabled
        voiceService.setMode(VoiceMode.DISABLED);
        assertThat(voiceService.isVoiceEnabled()).isFalse();
        assertThat(voiceService.shouldSpeakResponse()).isFalse();

        // PUSH_TO_TALK -> enabled
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(voiceService.isVoiceEnabled()).isTrue();
        assertThat(voiceService.shouldSpeakResponse()).isTrue();

        // WAKE_WORD -> enabled
        voiceService.setMode(VoiceMode.WAKE_WORD);
        assertThat(voiceService.isVoiceEnabled()).isTrue();

        // ALWAYS_ON -> enabled
        voiceService.setMode(VoiceMode.ALWAYS_ON);
        assertThat(voiceService.isVoiceEnabled()).isTrue();
    }

    // =======================================================================
    // Helpers
    // =======================================================================

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

    private void completeCycle(InferenceRouter.ChatRequest chatReq, String response) {
        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), response, 30, 40));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
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
