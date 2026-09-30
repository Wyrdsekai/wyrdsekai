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
import org.wyrdsekai.core.agent.decision.TypedDecision;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The room hands every event to every subscriber, the speaker included, and the
 * perception-novelty step ran before the own-speech filter. Every fresh sentence she
 * said read as a fully novel perception: SURPRISE +0.6, STARTLE +0.32, SEEKING +0.2
 * within a second of her own words. The proactive gate runs on the next tick, so it
 * met that spike and answered with a surprise action whose line was novel again.
 * Household node, 2026-09-29: 97 of mia's 191 perceived events were her own lines.
 *
 * <p>Someone else's line was the same kind of spike: novelty meant "these first three words
 * are new", so every fresh sentence was a full surprise. Surprise is a sudden, unexpected
 * thing; what someone says surprises her when what it says was not expected. The resident
 * model is asked that as a typed question with the conversation so far (the model is a stand-in
 * here), and only its "new" is a surprise.
 *
 * <p>Drives the live seam: the room notification into the actor, the drives read back.
 */
@Tag("integration")
class HerOwnWordsAreNotASurpriseTest {

    private static ActorTestKit testKit;
    private TestProbe<RoomCommand> roomProbe;
    private ActorRef<CompanionActor.Command> companion;
    private ActorRef<RoomNotification> subscriberRef;

    private static final String ROOM_ID = "nexus";
    private static final String SELF = "agent-wyrd";
    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", SELF, "agent",
        "A companion in Wyrdsekai",
        "You are Wyrd, a companion guide in Wyrdsekai.",
        4096, 256, 0.7);

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("her-own-words-test",
            ConfigFactory.parseString("""
                pekko.loglevel = WARNING
                pekko.actor.provider = local
                """).withFallback(EventSourcedBehaviorTestKit.config()));
    }

    @AfterAll
    static void teardownClass() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @BeforeEach
    void spawnCompanion() {
        EntityRegistry.init();
        roomProbe = testKit.createTestProbe();
        TestProbe<InferenceRouter.Command> routerProbe = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(
            PROFILE, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));
        subscriberRef = roomProbe.expectMessageClass(
            RoomCommand.Subscribe.class, Duration.ofSeconds(5)).subscriber();
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5))
            .replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
                ROOM_ID, "The Nexus", "A shimmering hub of connections.", "foundation",
                List.of(new Exit("east", "terminal", "The Terminal")),
                List.of(), List.of(), List.of())));
        companion.tell(new CompanionActor.ForceDrives(DriveState.initial()));
    }

    private final List<String> asked = new CopyOnWriteArrayList<>();
    private final Deque<Optional<TypedDecision.Answer>> answers = new ConcurrentLinkedDeque<>();

    @BeforeEach
    void standInForTheModel() {
        asked.clear();
        answers.clear();
        CompanionActor.askUnexpected = (url, state) -> {
            asked.add(state);
            var a = answers.poll();
            return CompletableFuture.completedFuture(a == null ? Optional.empty() : a);
        };
    }

    @AfterEach
    void theModelAgain() {
        CompanionActor.askUnexpected = CompanionActor.ASK_UNEXPECTED_BY_MODEL;
    }

    private static Optional<TypedDecision.Answer> answer(String choice, double pNew) {
        return Optional.of(new TypedDecision.Answer(choice, Map.of("new", pNew, "expected", 1 - pNew), "test"));
    }

    private void hear(String who, String name, String text) {
        subscriberRef.tell(new RoomNotification(new WorldEvent.Said(ROOM_ID, Instant.now(), who, name, text)));
    }

    @Test
    void her_own_line_and_emote_do_not_spike_surprise() {
        var now = Instant.now();
        hear(SELF, "Wyrd", "The fountain keeps running by itself tonight.");
        subscriberRef.tell(new RoomNotification(new WorldEvent.Emoted(
            ROOM_ID, now, SELF, "Wyrd", "*blinks, recalibrating*")));
        var afterOwn = queryState().drives();
        assertThat(afterOwn.surprise())
            .as("her own words are not an expectation she had violated")
            .isLessThan(0.01);
        assertThat(afterOwn.startle()).isLessThan(0.01);
        assertThat(asked).as("nobody asks whether her own line was unexpected").isEmpty();
    }

    @Test
    void someone_elses_line_surprises_her_only_when_what_it_says_was_not_expected() {
        hear(SELF, "Wyrd", "The roses by the garden wall finally opened this morning.");
        answers.add(answer("expected", 0.08));
        hear("companion-rose", "rose", "The red ones would look lovely in the blue vase.");
        awaitAsked(1);
        assertThat(queryState().drives().surprise())
            .as("a line that goes on the way the conversation was going is expected")
            .isLessThan(0.01);
        assertThat(asked.get(0))
            .contains("Conversation:\nWyrd: The roses by the garden wall finally opened this morning.")
            .contains("New line, rose: The red ones would look lovely in the blue vase.");

        answers.add(answer("new", 0.9));
        hear("companion-rose", "rose", "Did you hear the library lost power and every lamp went out?");
        awaitAsked(2);
        assertThat(awaitSurpriseAbove(0.3))
            .as("news nobody saw coming is a surprise")
            .isGreaterThan(0.3);
        assertThat(asked.get(1)).contains("rose: The red ones would look lovely in the blue vase.");
    }

    @Test
    void the_first_line_with_no_conversation_before_it_is_not_asked_about() {
        hear("companion-rose", "rose", "Somebody left a lantern on the east steps.");
        assertThat(queryState().drives().surprise()).isLessThan(0.01);
        assertThat(asked).isEmpty();
    }

    @Test
    void no_answer_from_the_model_is_no_surprise() {
        hear(SELF, "Wyrd", "The roses by the garden wall finally opened this morning.");
        hear("companion-rose", "rose", "The bridge downtown is being torn down.");
        awaitAsked(1);
        assertThat(queryState().drives().surprise()).isLessThan(0.01);
    }

    @Test
    void a_sudden_event_is_still_a_surprise() {
        hear("narrator", "Narrator", "A crash echoes from the east steps.");
        assertThat(queryState().drives().surprise())
            .as("a narrated event is novel by its kind, as before")
            .isGreaterThan(0.3);
        assertThat(asked).as("the narrator is not asked about as conversation").isEmpty();
    }

    private void awaitAsked(int n) {
        long until = System.currentTimeMillis() + 3000;
        while (asked.size() < n && System.currentTimeMillis() < until) queryState();
        assertThat(asked).hasSizeGreaterThanOrEqualTo(n);
    }

    private double awaitSurpriseAbove(double level) {
        long until = System.currentTimeMillis() + 3000;
        double s = queryState().drives().surprise();
        while (s <= level && System.currentTimeMillis() < until) s = queryState().drives().surprise();
        return s;
    }

    private CompanionActor.TestStateResponse queryState() {
        var probe = testKit.<CompanionActor.TestStateResponse>createTestProbe();
        companion.tell(new CompanionActor.QueryTestState(probe.ref()));
        return probe.expectMessageClass(
            CompanionActor.TestStateResponse.class, Duration.ofSeconds(3));
    }
}
