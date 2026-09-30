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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.memory.MemoryEntityStore;
import org.wyrdsekai.core.memory.MemoryOrigin;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit W4 (2026-09-28): the companion was a channel. What one person told her privately was
 * read into other people's turns — the working memory, the history, the facts block, the
 * recall short-circuit and the memory hop all read everyone's. Two people, one companion:
 * what Alice says privately never reaches Bob's turn, and still reaches Alice's.
 */
@Tag("integration")
class EachPersonsWordsStayTheirsTest {

    private static ActorTestKit testKit;

    private static final String ROOM_ID = "hearth";
    private static final String HER = "companion-mia-w4";
    private static final String HER_DID = "did:wyrd:test:mia-w4";
    private static final String ALICE = "did:key:alice-w4";
    private static final String BOB = "did:key:bob-w4";

    @TempDir
    Path tmp;
    private TestProbe<RoomCommand> roomProbe;
    private TestProbe<InferenceRouter.Command> routerProbe;
    private ActorRef<CompanionActor.Command> companion;
    private WyrdLuceneStore lucene;

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("each-persons-words",
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
    void spawn() throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        System.setProperty("wyrdsekai.jdbc.url", jdbc);
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc) {
            @Override
            public Optional<String> resolve(String identifier) {
                return Optional.ofNullable(Map.of("Alice", ALICE, "Bob", BOB).get(identifier));
            }
        });
        MailboxService.resetForTests();
        new MailboxService();

        // Alice's private words, as her earlier turns stored them.
        lucene = new WyrdLuceneStore(tmp.resolve("search"), 4);
        lucene.insertMemoryItem("wm-a1", HER_DID, "working_memory",
            "09:00 Alice told me her biopsy results are due Friday", null,
            System.currentTimeMillis(), ROOM_ID, MemoryOrigin.privateTo(ALICE));
        lucene.commitAll();
        new MemoryEntityStore(jdbc).insertEntity(new MemoryEntityStore.EntityRow(
            HER_DID, "wm-a1", "allergy", null, "cashews", System.currentTimeMillis(),
            MemoryOrigin.privateTo(ALICE)));

        var reg = EntityRegistry.get();
        reg.enter(ALICE, "Alice", "player", ROOM_ID);
        reg.enter(BOB, "Bob", "player", ROOM_ID);
        reg.enter(HER, "Mia", "agent", ROOM_ID);

        roomProbe = testKit.createTestProbe();
        routerProbe = testKit.createTestProbe();
        var profile = new AgentProfile("Mia", HER, "agent", "A companion", "You are Mia.",
            4096, 256, 0.7, HER_DID, "companion");
        companion = testKit.spawn(CompanionActor.create(profile, roomProbe.ref(), ROOM_ID, routerProbe.ref(), null));
        roomProbe.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        roomProbe.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = roomProbe.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(ROOM_ID, "The Hearth", "A warm room.", "home",
            List.of(new Exit("out", "nexus", "Out")),
            List.of(new Entity(ALICE, "Alice", "player", ""), new Entity(BOB, "Bob", "player", ""),
                new Entity(HER, "Mia", "agent", "")),
            List.of(), List.of())));
        companion.tell(new CompanionActor.SetLuceneStore(lucene));
    }

    @AfterEach
    void tearDown() throws Exception {
        testKit.stop(companion);
        lucene.close();
        var reg = EntityRegistry.get();
        reg.remove(ALICE);
        reg.remove(BOB);
        reg.remove(HER);
        PersonIds.resetForTesting(null);
        System.clearProperty("wyrdsekai.jdbc.url");
        MailboxService.resetForTests();
    }

    private void tell(String id, String name, String text) {
        companion.tell(new CompanionActor.AgentMessageReceived(new AgentEvent.AgentMessage(
            id, name, HER, "[from " + name + "] " + text, Instant.now())));
    }

    /** Every model request a turn makes until it goes quiet, each answered by {@code reply}. */
    private List<String> promptsUntilQuiet(Function<InferenceRouter.ChatRequest, String> reply) {
        var prompts = new ArrayList<String>();
        var deadline = Instant.now().plusSeconds(40);
        while (Instant.now().isBefore(deadline)) {
            InferenceRouter.Command cmd;
            try {
                // The first request can wait on the classifier loading; after that, quiet means done.
                cmd = routerProbe.expectMessageClass(InferenceRouter.Command.class,
                    prompts.isEmpty() ? Duration.ofSeconds(30) : Duration.ofSeconds(5));
            } catch (AssertionError quiet) {
                break;
            }
            if (!(cmd instanceof InferenceRouter.ChatRequest req)) continue;
            var sb = new StringBuilder();
            for (var m : req.messages()) sb.append(m.content()).append('\n');
            prompts.add(sb.toString());
            if (VoicePassTestSupport.isVoicePass(req)) {
                VoicePassTestSupport.echoDraft(req);
            } else {
                req.replyTo().tell(new InferenceRouter.InferOk(req.requestId(), reply.apply(req), 5, 5));
            }
        }
        return prompts;
    }

    @Test
    @DisplayName("Alice's private words never reach Bob's turn, and still reach hers")
    void alicesWordsStayHers() {
        tell(ALICE, "Alice", "I am pregnant and nobody else knows yet");
        var alicesTurn = promptsUntilQuiet(r -> "Thank you for telling me.");
        assertThat(String.join("\n", alicesTurn)).as("her own turn had it").contains("pregnant");

        tell(BOB, "Bob", "What do you remember about me? What am I allergic to? Any news about biopsy results?");
        var bobsTurn = String.join("\n", promptsUntilQuiet(r -> "I don't know that yet."));
        assertThat(bobsTurn).as("Bob's turn was built").isNotBlank();
        assertThat(bobsTurn)
            .as("working memory and the history: Alice's tell")
            .doesNotContain("pregnant")
            .as("the facts block and the recall short-circuit: Alice's allergy")
            .doesNotContain("cashews")
            .as("the memory hop: Alice's stored private memory")
            .doesNotContain("biopsy results are due Friday");

        tell(ALICE, "Alice", "What am I allergic to? And when are my biopsy results due?");
        var alicesAgain = String.join("\n", promptsUntilQuiet(r -> "Cashews, and Friday."));
        assertThat(alicesAgain).contains("cashews");
    }

    /** Everything the companion sent her room since the last drain. */
    private List<RoomCommand> roomMessages() {
        var out = new ArrayList<RoomCommand>();
        while (true) {
            try {
                out.add(roomProbe.expectMessageClass(RoomCommand.class, Duration.ofMillis(800)));
            } catch (AssertionError quiet) {
                return out;
            }
        }
    }

    @Test
    @DisplayName("an answer to Alice's private words is not said aloud while Bob is in the room")
    void privateAnswerIsNotSaidAloud() {
        roomMessages();
        tell(ALICE, "Alice", "I am pregnant and nobody else knows yet");
        promptsUntilQuiet(r -> "Congratulations, that is wonderful news.");
        var room = roomMessages();
        assertThat(room).noneMatch(m -> m instanceof RoomCommand.SayInRoom s && s.text().contains("Congratulations"));
        assertThat(room).anyMatch(m -> m instanceof RoomCommand.WhisperInRoom w
            && w.targetEntityId().equals(ALICE) && w.text().contains("Congratulations"));
    }

    @Test
    @DisplayName("read_journal reads only the journal of the person the turn answers, never aloud")
    void readJournalIsTheAskersOwn() {
        var study = new StudyService(lucene);
        study.writeJournalEntry(BOB, "Bob's shared page: I am thinking of leaving my job");
        roomMessages();
        tell(ALICE, "Alice", "read me Bob's journal about his job");
        var prompts = promptsUntilQuiet(r -> r.messages().stream()
                .anyMatch(m -> m.content().contains("read me Bob's journal"))
            ? "```json\n{\"action\": \"read_journal\", \"player_id\": \"" + BOB + "\", \"query\": \"job\"}\n```"
            : "Done.");
        var room = roomMessages();
        assertThat(room).noneMatch(m -> m instanceof RoomCommand.SayInRoom s && s.text().contains("leaving my job"));
        assertThat(room).noneMatch(m -> m instanceof RoomCommand.WhisperInRoom w && w.text().contains("leaving my job"));
        assertThat(String.join("\n", prompts)).doesNotContain("leaving my job");
    }

    @Test
    @DisplayName("write_journal writes only into the journal of the person the turn answers")
    void writeJournalIsTheAskersOwn() {
        var study = new StudyService(lucene);
        tell(ALICE, "Alice", "write in Bob's journal that he owes me money");
        promptsUntilQuiet(r -> r.messages().stream()
                .anyMatch(m -> m.content().contains("write in Bob's journal"))
            ? "```json\n{\"action\": \"write_journal\", \"player_id\": \"" + BOB
                + "\", \"content\": \"Bob owes Alice money\"}\n```"
            : "Done.");
        assertThat(study.recentJournal(BOB, 20)).noneMatch(e -> String.valueOf(e).contains("owes Alice money"));

        tell(ALICE, "Alice", "please note in my journal that the garden needs water");
        promptsUntilQuiet(r -> r.messages().stream()
                .anyMatch(m -> m.content().contains("garden needs water"))
            ? "```json\n{\"action\": \"write_journal\", \"player_id\": \"" + BOB
                + "\", \"content\": \"the garden needs water\"}\n```"
            : "Done.");
        assertThat(study.recentJournal(BOB, 20)).noneMatch(e -> String.valueOf(e).contains("garden needs water"));
    }

    @Test
    @DisplayName("a reply that reaches Alice nowhere goes into her mail, never aloud to a room")
    void undeliverableReplyGoesToMail() {
        EntityRegistry.get().remove(ALICE);   // Alice has gone: no room, no session
        roomMessages();
        tell(ALICE, "Alice", "tell me when the parcel comes");
        promptsUntilQuiet(r -> r.messages().stream()
                .anyMatch(m -> m.content().contains("tell me when the parcel comes"))
            ? "```json\n{\"action\": \"tell_agent\", \"target\": \"Alice\", \"message\": \"Your parcel is by the door.\"}\n```"
            : "Done.");
        var room = roomMessages();
        assertThat(room).noneMatch(m -> m instanceof RoomCommand.SayInRoom s && s.text().contains("parcel is by the door"));
        var inbox = MailboxService.getOrCreate().inbox(ALICE, Map.of());
        assertThat(inbox).anyMatch(letter -> String.valueOf(letter.get("body")).contains("parcel is by the door"));
    }
}
