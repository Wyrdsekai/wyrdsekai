package org.wyrdsekai.core.library;

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
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.AgentEventStream;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionRegistry;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.EntityRegistry;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondKind;
import org.wyrdsekai.core.test.TestDb;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A person's Study books are granted to a companion under her DID (the sharing page lists her by
 * it) or her entity id (her own request files under it). Her search asked with the DID and read
 * from the books; the checks that let her open one asked with the entity id, which held no
 * grant, so no book from the Study opened. Either id now opens it.
 */
@Tag("integration")
class ABookHerPersonGrantedOpensTest {

    private static final String OWNER = "did:key:z6MkExamplePerson";
    private static final String HER_DID = "did:key:z6MkExampleCompanion";
    private static final String HER_ENTITY = "companion-wren";
    private static final String BOOK = "The New Eve - Robert Lewis.epub";

    private static ActorTestKit testKit;
    @TempDir Path tmp;
    private WyrdLuceneStore store;
    private HomeClient home;
    private ActorRef<CompanionActor.Command> companion;

    @BeforeAll
    static void setupClass() {
        AgentEventStream.init();
        EntityRegistry.init();
        testKit = ActorTestKit.create("a-book-her-person-granted",
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
        store = new WyrdLuceneStore(tmp.resolve("index"), 8);
        for (int p = 1; p <= 3; p++) {
            store.insertStudyItem("eve" + p, OWNER, "document", BOOK + " (part " + p + "/3)",
                "Part " + p + " of the new eve: a woman becoming who she is meant to be.", "books",
                System.currentTimeMillis(), 1, null, null, null, false);
        }
        ReadingLedger.install(new ReadingLedger(TestDb.createInMemory()));
        var jdbc = SchemaInitializer.initialize(tmp.resolve("home.db"));
        home = new HomeClient(testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc))), testKit.system());
        new CompanionRegistry(jdbc).register(HER_DID, HER_ENTITY, "wren", "zone");
        HomeClients.set(home);

        var profile = new AgentProfile("Wren", HER_ENTITY, "agent", "A companion in Wyrdsekai",
            "You are Wren, a companion in Wyrdsekai.", 4096, 256, 0.7, HER_DID);
        TestProbe<RoomCommand> room = testKit.createTestProbe();
        TestProbe<InferenceRouter.Command> router = testKit.createTestProbe();
        companion = testKit.spawn(CompanionActor.create(profile, room.ref(), "hearth", router.ref(), null));
        room.expectMessageClass(RoomCommand.Subscribe.class, Duration.ofSeconds(5));
        room.expectMessageClass(RoomCommand.EnterRoom.class, Duration.ofSeconds(5));
        var look = room.expectMessageClass(RoomCommand.LookRoom.class, Duration.ofSeconds(5));
        look.replyTo().tell(new RoomResponse.Ok(new RoomSnapshot(
            "hearth", "The Hearth", "A warm room.", "foundation",
            List.of(new Exit("east", "study", "The Study")), List.of(), List.of(), List.of())));
        companion.tell(new CompanionActor.SetLuceneStore(store));
        companion.tell(new CompanionActor.ForceBond(OWNER,
            Bond.open(HER_DID, OWNER, BondKind.BONDHOLDER).crossToActive()));
    }

    @AfterEach
    void tearDown() throws Exception {
        HomeClients.set(null);
        ReadingLedger.install(null);
        if (companion != null) testKit.stop(companion);
        if (store != null) store.close();
    }

    private String readBook(String book) {
        TestProbe<String> answer = testKit.createTestProbe();
        companion.tell(new CompanionActor.TestReadBook(book, answer.ref()));
        return answer.expectMessageClass(String.class, Duration.ofSeconds(10));
    }

    @Test
    void a_book_granted_to_her_did_opens_by_its_name() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");

        var page = readBook("The New Eve");

        assertThat(page).doesNotContain("is on a shelf you can reach").doesNotStartWith("Not done");
        assertThat(page).contains("of the new eve");
        assertThat(ReadingLedger.shared().all(HER_DID)).hasSize(1);
    }

    @Test
    void a_book_granted_to_her_entity_id_opens_too() {
        new StudyService(store, home).grantAccess(OWNER, HER_ENTITY, "books");

        assertThat(readBook("The New Eve")).contains("of the new eve");
        assertThat(ReadingLedger.shared().all(HER_DID)).hasSize(1);
    }

    @Test
    void with_no_grant_the_book_stays_shut() {
        var page = readBook("The New Eve");

        assertThat(page).contains("no book called 'The New Eve' is on a shelf you can reach");
        assertThat(ReadingLedger.shared().all(HER_DID)).isEmpty();
    }

    @Test
    void a_revoked_grant_shuts_a_book_she_had_open() {
        var study = new StudyService(store, home);
        study.grantAccess(OWNER, HER_DID, "books");
        assertThat(readBook("The New Eve")).contains("of the new eve");

        study.revokeAccess(OWNER, HER_DID, "books");

        assertThat(readBook(null)).contains("the grant that let you read it does not stand now");
    }
}
