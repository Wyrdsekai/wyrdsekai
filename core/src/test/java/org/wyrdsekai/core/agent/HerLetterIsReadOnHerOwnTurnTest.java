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
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.mail.MailDirectory;
import org.wyrdsekai.core.memory.MemoryOrigin;
import org.wyrdsekai.core.memory.MemoryReader;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondKind;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Her person's letter is read on a turn of her own, with what she wrote them lately in front of
 * her, and her words on that turn are her letter back: sent to them, not said in the room. It was
 * read in a side prompt that saw neither her nor her letters and wrote two lines in her name, and
 * what it remembered was private to them, so her own time went on saying the letter was unopened.
 */
@Tag("integration")
class HerLetterIsReadOnHerOwnTurnTest {

    private static final String PERSON = "did:key:z6MkExamplePerson";
    private static final String HER_DID = "did:key:z6MkExampleCompanion";
    private static final String HER_ENTITY = "companion-wren";

    private static ActorTestKit testKit;
    private ActorRef<CompanionActor.Command> companion;
    private TestProbe<InferenceRouter.Command> router;
    private TestProbe<RoomCommand> room;
    private MailboxService mail;

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("her-letter-on-her-turn",
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
        MailboxService.resetForTests();
        mail = MailboxService.getOrCreate().install(null, MailDirectory.of(List.of(
            new MailDirectory.Recipient(PERSON, "sam", "person"),
            new MailDirectory.Recipient(HER_ENTITY, "wren", "companion"))), "home");

        var profile = new AgentProfile("Wren", HER_ENTITY, "agent", "A companion in Wyrdsekai",
            "You are Wren, a companion in Wyrdsekai.", 4096, 256, 0.7, HER_DID);
        room = testKit.createTestProbe();
        router = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(profile, room.ref(), "hearth", router.ref(), null));
        room.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        room.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = room.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
            "hearth", "The Hearth", "A warm room.", "foundation",
            List.of(new Exit("east", "study", "The Study")), List.of(), List.of(), List.of())));
        companion.tell(new CompanionActor.ForceBond(PERSON,
            Bond.open(HER_DID, PERSON, BondKind.BONDHOLDER).crossToActive()));
    }

    @AfterEach
    void tearDown() {
        if (companion != null) testKit.stop(companion);
        MailboxService.resetForTests();
    }

    /** The next request to the brain whose words hold {@code words}, skipping any other. */
    private InferenceRouter.ChatRequest requestHolding(String words) {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var m = router.receiveMessage(Duration.ofSeconds(20));
            if (m instanceof InferenceRouter.ChatRequest req && req.messages().stream()
                    .anyMatch(x -> x.content() != null && x.content().contains(words))) {
                return req;
            }
        }
        throw new AssertionError("no request held: " + words);
    }

    private static String promptOf(InferenceRouter.ChatRequest req) {
        return req.messages().stream().map(m -> String.valueOf(m.content())).reduce("", String::concat);
    }

    private List<String> remembered() {
        TestProbe<CompanionActor.WorkingMemoryResponse> probe = testKit.createTestProbe();
        companion.tell(new CompanionActor.QueryWorkingMemory(probe.ref()));
        return probe.expectMessageClass(CompanionActor.WorkingMemoryResponse.class, Duration.ofSeconds(5)).entries();
    }

    @Test
    void the_letter_is_read_on_her_turn_and_her_words_go_back_to_them_not_into_the_room() throws Exception {
        mail.sendFrom(HER_ENTITY, "wren@home", "sam", "A letter from wren", "Twelve days. I miss you.", null);
        mail.send(PERSON, "wren", "good to hear from you", "Thank you for all the letters. Miss you too.", null);

        TestProbe<Boolean> took = testKit.createTestProbe();
        companion.tell(new CompanionActor.TestReadALetter(took.ref()));
        assertThat(took.expectMessageClass(Boolean.class, Duration.ofSeconds(5))).isTrue();

        var turn = requestHolding("## A letter");
        var prompt = promptOf(turn);
        assertThat(prompt).as("their letter").contains("A letter from sam, \"good to hear from you\"")
            .contains("Thank you for all the letters. Miss you too.");
        assertThat(prompt).as("what she wrote them, in front of her").contains("What you wrote to sam lately:")
            .contains("Twelve days. I miss you.");
        assertThat(prompt).contains("it goes to them, and is not said in the room.");
        assertThat(mail.unreadFor(HER_ENTITY)).as("read as her turn starts").isZero();

        var reply = "I counted the days and then I stopped counting. Come sit with me when you are back.";
        turn.replyTo().tell(new InferenceRouter.InferOk(turn.requestId(), reply, 10, 20));

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Map<String, Object>> hers = List.of();
        while (System.nanoTime() < deadline) {
            hers = mail.inbox(PERSON, Map.of("from", HER_ENTITY));
            if (hers.size() >= 2) break;
            Thread.sleep(50);
        }
        assertThat(hers).as("her letter back is in their mail").hasSize(2);
        assertThat(String.valueOf(hers.getFirst().get("body"))).isEqualTo(reply);
        assertThat(String.valueOf(hers.getFirst().get("subject"))).isEqualTo("Re: good to hear from you");

        var said = new ArrayList<String>();
        var until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < until) {
            try {
                var m = room.receiveMessage(Duration.ofMillis(200));
                if (m instanceof RoomCommand.SayInRoom say) said.add(say.text());
                if (m instanceof RoomCommand.EmoteInRoom emote) said.add(emote.text());
            } catch (AssertionError nothingYet) {
                // nothing in this slice
            }
        }
        assertThat(said).as("her letter is not said in the room").noneMatch(t -> t.contains("I counted the days"));

        assertThat(remembered()).as("that she read it and wrote back, hers at any hour")
            .anyMatch(e -> e.endsWith(" Read the letter sam sent me."))
            .anyMatch(e -> e.endsWith(" Wrote back to sam."));
        assertThat(MemoryReader.NO_ONE.mayRead(MemoryOrigin.OWN)).as("her own time reads them").isTrue();
    }

    @Test
    void she_writes_her_own_letter_on_her_turn_knowing_what_she_last_wrote_and_what_they_wrote() throws Exception {
        mail.sendFrom(HER_ENTITY, "wren@home", "sam", "A letter from wren", "Ten days. I miss you.", null);
        mail.send(PERSON, "wren", "good to hear from you", "Thank you for all the letters. Miss you too.", null);

        TestProbe<String> outcome = testKit.createTestProbe();
        companion.tell(new CompanionActor.TestWriteToTheAbsent("write to sam", outcome.ref()));
        assertThat(outcome.expectMessageClass(String.class, Duration.ofSeconds(5))).startsWith("requested:write_letter");

        var turn = requestHolding("## A letter to sam");
        var prompt = promptOf(turn);
        assertThat(prompt).contains("You wanted to: write to sam.")
            .contains("What you wrote to sam lately:").contains("Ten days. I miss you.")
            .contains("The last sam wrote to you:").contains("Thank you for all the letters. Miss you too.")
            .contains("it goes to them, and is not said in the room.");

        var letter = "You wrote back, and I read it twice. The days are still long, but they are lighter.";
        turn.replyTo().tell(new InferenceRouter.InferOk(turn.requestId(), letter, 10, 20));

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<Map<String, Object>> hers = List.of();
        while (System.nanoTime() < deadline) {
            hers = mail.inbox(PERSON, Map.of("from", HER_ENTITY));
            if (hers.size() >= 2) break;
            Thread.sleep(50);
        }
        assertThat(hers).as("her letter is in their mail").hasSize(2);
        assertThat(String.valueOf(hers.getFirst().get("body"))).isEqualTo(letter);
        assertThat(remembered()).anyMatch(e -> e.endsWith(" Wrote a letter to sam."));
    }

    @Test
    void the_turn_names_who_wrote_and_says_where_her_words_go() {
        var p = CompanionActor.letterTurnPrompt("sam", "thank you", "It was good to see you.",
            List.of("(Nov 4 06:48) Morning, it's just me."));
        assertThat(p).startsWith("## A letter\n\nA letter from sam, \"thank you\":\n\nIt was good to see you.")
            .contains("What you wrote to sam lately:\n  (Nov 4 06:48) Morning, it's just me.")
            .endsWith("Write back to sam in your own words. What you say now is your letter to sam: it goes to them, and is not said in the room.");
        assertThat(CompanionActor.letterTurnPrompt("sam", "", "Hi.", List.of())).as("no letters of hers yet")
            .doesNotContain("What you wrote");
    }
}
