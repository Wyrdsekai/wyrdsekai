package org.wyrdsekai.core.agent;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.test.TestDb;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parental inference quota at the CompanionActor speech-trigger choke point:
 * a SPEAKER whose daily inference quota is spent gets the canned in-voice
 * line and NO ChatRequest reaches the router; a speaker with quota left
 * triggers inference normally and the quota decrements. Mirrors
 * {@link AutonomyIntegrationTest}'s probe harness.
 */
@Tag("integration")
class CompanionActorParentalQuotaTest {

    private static ActorTestKit testKit;

    private static final String ROOM_ID = "nexus";
    private static final String SPEAKER_ID = "player-alice";

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "agent-wyrd", "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<RoomNotification> subscriberRef;
    private ParentalControlService service;
    private String stewardId;

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("parental-quota-test",
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
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        var auth = new AuthService(jdbcUrl);
        stewardId = auth.register("operator", "password123", "Masumi").orElseThrow().userId();
        service = ParentalControlService.init(jdbcUrl, new SqlDialect.SQLite(), auth);

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        var companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));

        // Consume the 3 startup messages: Subscribe, EnterRoom, LookRoom.
        var subscribe = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        subscriberRef = subscribe.subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(
            RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
            ROOM_ID, "The Nexus", "A shimmering hub of connections.",
            "foundation",
            List.of(new Exit("east", "terminal", "The Terminal")),
            List.of(), List.of(), List.of())));
    }

    @AfterEach
    void tearDown() {
        ParentalControlService.resetForTests();
    }

    private WorldEvent.Said speakerSaid(String text) {
        return new WorldEvent.Said(ROOM_ID, Instant.now(), SPEAKER_ID, "Alice", text);
    }

    @Test
    void speaker_with_spent_quota_gets_canned_line_and_no_inference() {
        service.setControls(stewardId, SPEAKER_ID, null, List.of(), 0, "off");

        subscriberRef.tell(new RoomNotification(speakerSaid("Hello, are you there?")));

        // The companion answers with the canned in-voice line — no LLM behind it.
        var say = roomProbe.expectMessageClass(
            RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
        assertThat(say.text()).contains("thinking-budget");
        assertThat(say.entityId()).isEqualTo(PROFILE.entityId());

        // And the router never sees a request.
        routerProbe.expectNoMessage(Duration.ofSeconds(3));
    }

    @Test
    void speaker_with_quota_left_triggers_inference_and_decrements() {
        service.setControls(stewardId, SPEAKER_ID, null, List.of(), 2, "off");

        subscriberRef.tell(new RoomNotification(speakerSaid("What do you see?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        assertThat(chatReq.messages()).isNotEmpty();

        // The accepted trigger counted one inference against the speaker.
        assertThat(service.inferencesRemaining(SPEAKER_ID)).isEqualTo(1);

        chatReq.replyTo().tell(new InferenceRouter.InferOk(
            chatReq.requestId(), "I see the nexus shimmer.", 20, 30));
        roomProbe.expectMessageClass(RoomCommand.SayInRoom.class, Duration.ofSeconds(5));
    }

    @Test
    void uncontrolled_speaker_is_untouched() {
        // No controls row for the speaker — quota gate must be a no-op.
        subscriberRef.tell(new RoomNotification(speakerSaid("Anyone home?")));

        var chatReq = routerProbe.expectMessageClass(
            InferenceRouter.ChatRequest.class, Duration.ofSeconds(5));
        assertThat(chatReq.messages()).isNotEmpty();
        assertThat(service.usageToday(SPEAKER_ID).inferencesUsed()).isZero();
    }
}
