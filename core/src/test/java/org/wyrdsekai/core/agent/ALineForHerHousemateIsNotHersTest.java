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
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every companion in the room used to answer every person's line: "rose, what have you learned of
 * late?" was answered by both, and "in fact rose, definitely do some research" sent the other one
 * to do it. A line is hers when it calls her, or calls no one and she is the one they were talking
 * to, or speaks to everyone; a line that calls only her housemate she hears and leaves to them.
 */
@Tag("integration")
class ALineForHerHousemateIsNotHersTest {

    private static final String ROOM = "nexus";
    private static ActorTestKit testKit;
    private ActorRef<CompanionActor.Command> companion;
    private TestProbe<InferenceRouter.Command> router;
    private ActorRef<RoomNotification> hears;

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("a-line-for-her-housemate",
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
    void setUp() {
        var profile = new AgentProfile("Wren", "companion-wren", "agent", "A companion in Wyrdsekai",
            "You are Wren, a companion in Wyrdsekai.", 4096, 256, 0.7, "did:key:z6MkExampleWren");
        TestProbe<RoomCommand> room = testKit.createTestProbe();
        router = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(profile, room.ref(), ROOM, router.ref(), null));
        hears = room.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5)).subscriber();
        room.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = room.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(ROOM, "The Nexus", "Where paths meet.", "foundation",
            List.of(), List.of(
                new Entity("player-sam", "sam", "player", ""),
                new Entity("companion-wren", "Wren", "agent", ""),
                new Entity("companion-rook", "Rook", "agent", "")),
            List.of(), List.of())));
    }

    @AfterEach
    void tearDown() {
        if (companion != null) testKit.stop(companion);
    }

    private void samSays(String text) {
        hears.tell(new RoomNotification(new WorldEvent.Said(ROOM, Instant.now(), "player-sam", "sam", text)));
    }

    /** Whether she takes a turn for a line within the wait: a request to the brain that carries it. */
    private boolean answers(String text, Duration wait) {
        var deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            Object m;
            try {
                m = router.receiveMessage(Duration.ofMillis(200));
            } catch (AssertionError nothingYet) {
                continue;
            }
            if (m instanceof InferenceRouter.ChatRequest req && req.messages().stream()
                    .anyMatch(x -> x.content() != null && x.content().contains(text))) {
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), "Mm.", 5, 2));
                return true;
            }
        }
        return false;
    }

    @Test
    void a_line_that_calls_her_housemate_she_hears_and_leaves_to_them() {
        samSays("rook what have you learned of late?");
        assertThat(answers("rook what have you learned of late?", Duration.ofSeconds(4))).isFalse();

        samSays("in fact rook, definitely do some research into tide pools");
        assertThat(answers("definitely do some research into tide pools", Duration.ofSeconds(4)))
            .as("nor takes up what was asked of them").isFalse();

        samSays("and what else have you been reading?");
        assertThat(answers("what else have you been reading", Duration.ofSeconds(4)))
            .as("an unnamed line goes on being for the one they were talking to").isFalse();
    }

    @Test
    void the_line_for_her_housemate_stays_in_her_history_as_said_to_them() {
        samSays("rook, what about the history of zen?");
        assertThat(answers("what about the history of zen", Duration.ofSeconds(3))).isFalse();

        samSays("wren how are you this morning?");
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String prompt = null;
        while (System.nanoTime() < deadline && prompt == null) {
            Object m;
            try { m = router.receiveMessage(Duration.ofMillis(300)); } catch (AssertionError nothingYet) { continue; }
            if (m instanceof InferenceRouter.ChatRequest req) {
                var p = req.messages().stream().map(x -> String.valueOf(x.content())).reduce("", String::concat);
                if (p.contains("wren how are you this morning?")) prompt = p;
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), "Mm.", 5, 2));
            }
        }
        assertThat(prompt).as("her turn for her own line").isNotNull();
        assertThat(prompt).as("the line to her housemate is marked as theirs").contains("(to Rook) rook, what about the history of zen?");
    }

    private void rookSays(String text) {
        hears.tell(new RoomNotification(new WorldEvent.Said(ROOM, Instant.now(), "companion-rook", "Rook", text)));
    }

    private List<String> remembered() {
        TestProbe<CompanionActor.WorkingMemoryResponse> probe = testKit.createTestProbe();
        companion.tell(new CompanionActor.QueryWorkingMemory(probe.ref()));
        return probe.expectMessageClass(CompanionActor.WorkingMemoryResponse.class, Duration.ofSeconds(5)).entries();
    }

    @Test
    void with_a_person_here_her_housemates_line_to_them_is_heard_not_answered() {
        rookSays("Hi sam — good to see you this morning. I'm reading part 12.");
        assertThat(answers("good to see you this morning", Duration.ofSeconds(4))).isFalse();

        // (Her history keeps the line as "(to sam) Hi sam …"; a reply to a person renders no housemate
        // lines, so that marking is not visible from here.)
        rookSays("Wren, you're here too. What are you reading?");
        assertThat(answers("What are you reading?", Duration.ofSeconds(10))).as("called by name: hers to answer").isTrue();
    }

    @Test
    void the_workshops_word_on_a_thing_of_the_house_is_hers_to_keep() {
        AgentEventStream.get().publishSystemEvent(AgentEvent.SystemEventType.ITEM_MENDING, "workshop",
            "The workshop tried to mend the witness stone while the house was quiet and could not: it needs witness.receive, which the house does not offer.");
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        boolean kept = false;
        while (System.nanoTime() < deadline && !kept) {
            kept = remembered().stream().anyMatch(e -> e.contains("could not: it needs witness.receive"));
        }
        assertThat(kept).isTrue();
    }

    @Test
    void a_line_that_calls_her_or_everyone_is_hers() {
        samSays("wren what have you learned of late?");
        assertThat(answers("wren what have you learned of late?", Duration.ofSeconds(10))).isTrue();

        samSays("good morning wren and rook!");
        assertThat(answers("good morning wren and rook!", Duration.ofSeconds(10))).isTrue();

        samSays("ok i need to go. i will catch you guys later");
        assertThat(answers("i will catch you guys later", Duration.ofSeconds(10))).as("a goodbye is to everyone").isTrue();
    }

    @Test
    void who_a_line_calls_reads_the_way_a_person_writes_it() {
        var both = List.of("mia", "rose");
        assertThat(EngagementGate.calledByName("rose what have you learned of late?", both)).containsExactly("rose");
        assertThat(EngagementGate.calledByName("in fact rose, definitely do some research", both)).containsExactly("rose");
        assertThat(EngagementGate.calledByName("hey rose", both)).containsExactly("rose");
        assertThat(EngagementGate.calledByName("thank you mia", both)).containsExactly("mia");
        assertThat(EngagementGate.calledByName("so mia, rose - what have you learned", both)).containsExactlyInAnyOrder("mia", "rose");
        assertThat(EngagementGate.calledByName("good morning mia and rose!", both)).containsExactlyInAnyOrder("mia", "rose");
        assertThat(EngagementGate.calledByName("rose did u want to try building a model?", both)).containsExactly("rose");
        assertThat(EngagementGate.calledByName("what did rose say about it?", both)).as("about her, not to her").isEmpty();
        assertThat(EngagementGate.calledByName("you should talk to rose about attention", both)).isEmpty();
        assertThat(EngagementGate.toEveryone("how are you guys")).isTrue();
        assertThat(EngagementGate.toEveryone("please go about your day. I need to run around")).isTrue();
        assertThat(EngagementGate.toEveryone("rose what have you learned?")).isFalse();
    }
}
