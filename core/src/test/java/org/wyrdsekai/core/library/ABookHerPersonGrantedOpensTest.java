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
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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
    private TestProbe<InferenceRouter.Command> router;
    private String homeJdbc;
    private static final String ROOK_DID = "did:key:z6MkExampleRook";

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
    void setUp() throws Exception {
        store = new WyrdLuceneStore(tmp.resolve("index"), 8);
        for (int p = 1; p <= 3; p++) {
            store.insertStudyItem("eve" + p, OWNER, "document", BOOK + " (part " + p + "/3)",
                "Part " + p + " of the new eve: a woman becoming who she is meant to be.", "books",
                System.currentTimeMillis(), 1, null, null, null, false);
        }
        ReadingLedger.install(new ReadingLedger(TestDb.createInMemory()));
        var asksDb = TestDb.createInMemory();
        try (var c = DriverManager.getConnection(asksDb)) { LibraryAsks.ensure(c); }
        LibraryAsks.install(new LibraryAsks(asksDb));
        var jdbc = SchemaInitializer.initialize(tmp.resolve("home.db"));
        home = new HomeClient(testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc))), testKit.system());
        new CompanionRegistry(jdbc).register(HER_DID, HER_ENTITY, "wren", "zone");
        homeJdbc = jdbc;
        HomeClients.set(home);

        var profile = new AgentProfile("Wren", HER_ENTITY, "agent", "A companion in Wyrdsekai",
            "You are Wren, a companion in Wyrdsekai.", 4096, 256, 0.7, HER_DID);
        TestProbe<RoomCommand> room = testKit.createTestProbe();
        router = testKit.createTestProbe();
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
        System.clearProperty("wyrdsekai.jdbc.url");
        HomeClients.set(null);
        ReadingLedger.install(null);
        LibraryAsks.install(null);
        if (companion != null) testKit.stop(companion);
        if (store != null) store.close();
    }

    private String browse(String words) {
        TestProbe<String> answer = testKit.createTestProbe();
        companion.tell(new CompanionActor.TestBrowseShelves(words, answer.ref()));
        return answer.expectMessageClass(String.class, Duration.ofSeconds(10));
    }

    private String readTurn() {
        TestProbe<String> answer = testKit.createTestProbe();
        companion.tell(new CompanionActor.TestReadTurn(answer.ref()));
        return answer.expectMessageClass(String.class, Duration.ofSeconds(10));
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

    // ── The reading turn reads her situation ─────────────────────────────

    @Test
    void with_nothing_open_the_turn_asks_her_to_search() {
        var turn = readTurn();

        assertThat(turn).startsWith("Read something in the library. ");
        assertThat(turn).contains("\nUse library_card with a query in your own words. Then write one note with quill (kind: note): what you found, and the question you want to ask next.");
        assertThat(turn).doesNotContain("You are reading").doesNotContain("asked the library about");
    }

    @Test
    void with_a_book_open_the_turn_leads_with_reading_on() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        readBook("The New Eve");

        var turn = readTurn();

        assertThat(turn).contains("You are reading 'The New Eve - Robert Lewis': at part 1 of 3. read_book reads the next part.");
        assertThat(turn).contains("\nOr use library_card with a query in your own words.");
        assertThat(turn).doesNotContain("\nUse library_card");
    }

    @Test
    void a_subject_asked_often_is_named_with_how_often_and_how_thin() {
        var asks = LibraryAsks.shared();
        asks.record(HER_DID, "cross-attention in diffusion models", false, 0);
        asks.record(HER_DID, "diffusion models cross-attention mechanism", false, 0);
        asks.record(HER_DID, "diffusion model U-Net cross-attention architecture", true, 10);

        var turn = readTurn();

        assertThat(turn).contains("You have asked the library about \"diffusion model U-Net cross-attention architecture\" 3 times in the last week; 2 of those found nothing on the shelves.");
        assertThat(turn).as("the card still leads: no book is open").contains("\nUse library_card with a query in your own words. Then write one note with quill (kind: note): what you found, and the question you want to ask next.");
    }

    @Test
    void a_subject_asked_twice_is_not_yet_often() {
        var asks = LibraryAsks.shared();
        asks.record(HER_DID, "tide pool ecology", false, 0);
        asks.record(HER_DID, "ecology of tide pools", false, 0);

        assertThat(readTurn()).doesNotContain("asked the library about");
    }

    // ── What she reads stays with her ────────────────────────────────────

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

    @Test
    void the_page_she_opens_on_her_own_time_is_handed_to_her_and_what_she_takes_is_kept() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        readBook("The New Eve");

        var turn = requestHolding("This is the part you just opened:");
        var prompt = turn.messages().stream().map(m -> String.valueOf(m.content())).reduce("", String::concat);
        assertThat(prompt).as("the page itself").contains("Part 1 of the new eve: a woman becoming who she is meant to be.");
        assertThat(prompt).contains("What do you take from it?");

        turn.replyTo().tell(new InferenceRouter.InferOk(turn.requestId(),
            "It says a woman becomes who she is meant to be. It leaves me wanting to know what she gave up for it.", 10, 20));

        var ledger = ReadingLedger.shared();
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (ledger.lastNote(HER_DID, BOOK).isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        var note = ledger.lastNote(HER_DID, BOOK);
        assertThat(note).as("what she took is kept with the book").isPresent();
        assertThat(note.get().part()).isEqualTo(1);
        assertThat(note.get().text()).contains("what she gave up for it");

        deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (FindingsLedger.list(store, HER_DID, null, 5).isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        var findings = FindingsLedger.list(store, HER_DID, null, 5);
        assertThat(findings).as("and as a finding of hers, with the book as its source").hasSize(1);
        assertThat(findings.getFirst().claim()).contains("what she gave up for it");
        assertThat(findings.getFirst().sources()).hasSize(1);
        assertThat(findings.getFirst().sources().getFirst().line()).isEqualTo("The New Eve - Robert Lewis (part 1/3) (eve1)");
        assertThat(findings.getFirst().query()).as("a reading is not a question to follow").isEmpty();

        assertThat(readBook(null)).as("the next part opens with it")
            .startsWith("Last time, after part 1, you took from it: \"It says a woman becomes who she is meant to be.");
        assertThat(readTurn()).contains("After part 1 you took from it: \"It says a woman becomes who she is meant to be.");
    }

    // ── Two of them reading the same book ───────────────────────────────

    /** Rook, her housemate, is in the book at part 2. */
    private void rookIsReading() {
        System.setProperty("wyrdsekai.jdbc.url", homeJdbc);
        new CompanionRegistry(homeJdbc).register(ROOK_DID, "companion-rook", "Rook", "zone");
        var ledger = ReadingLedger.shared();
        ledger.open(ROOK_DID, BOOK, "study", OWNER, "books", 3);
        ledger.read(ROOK_DID, BOOK, 2);
    }

    @Test
    void her_housemates_book_is_on_her_reading_turn_with_the_door_to_it() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        rookIsReading();

        var turn = readTurn();

        assertThat(turn).contains("\nRook is reading 'The New Eve - Robert Lewis': at part 2 of 3. "
            + "read_book with book: 'The New Eve - Robert Lewis' opens it from the start.");
        assertThat(turn).as("her own name is not a housemate's").doesNotContain("wren is reading");

        assertThat(readBook("The New Eve - Robert Lewis")).as("the door opens it for her at the start").contains("Part 1 of the new eve");
        assertThat(readTurn()).contains("Rook is reading 'The New Eve - Robert Lewis': at part 2 of 3. You are both in it.");
    }

    @Test
    void a_housemates_study_book_she_has_no_grant_for_is_named_without_a_door() {
        rookIsReading();

        var turn = readTurn();

        assertThat(turn).contains("\nRook is reading 'The New Eve - Robert Lewis': at part 2 of 3.");
        assertThat(turn).doesNotContain("opens it from the start");
    }

    // ── Choosing a book ─────────────────────────────────────────────────

    /** Whether a request to the brain holding {@code words} comes within {@code wait}. */
    private boolean anyRequestHolding(String words, Duration wait) {
        var deadline = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < deadline) {
            Object m;
            try {
                m = router.receiveMessage(Duration.ofMillis(200));
            } catch (AssertionError nothingYet) {
                continue;
            }
            if (m instanceof InferenceRouter.ChatRequest req && req.messages().stream()
                    .anyMatch(x -> x.content() != null && x.content().contains(words))) {
                return true;
            }
        }
        return false;
    }

    private static String promptOf(InferenceRouter.ChatRequest req) {
        return req.messages().stream().map(m -> String.valueOf(m.content())).reduce("", String::concat);
    }

    @Test
    void asked_by_its_authors_name_the_books_that_fit_are_handed_to_her_to_choose_from() {
        for (int p = 1; p <= 2; p++) {
            store.insertStudyItem("adam" + p, OWNER, "document", "The Old Adam - Robert Lewis.epub (part " + p + "/2)",
                "Part " + p + " of the old adam.", "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");

        assertThat(readBook("Robert Lewis")).startsWith("Not done yet: several books fit 'Robert Lewis':");

        var prompt = promptOf(requestHolding("## The shelves"));
        assertThat(prompt).as("the books themselves, on her next turn").contains("The New Eve - Robert Lewis").contains("The Old Adam - Robert Lewis")
            .contains("If one of them calls to you, read_book with book: '<its name>' opens it.");
        assertThat(ReadingLedger.shared().all(HER_DID)).as("she has not chosen yet").isEmpty();
    }

    @Test
    void a_name_no_book_has_is_handed_to_her_with_the_shelves_door_and_not_twice_in_a_row() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");

        assertThat(readBook("Nowhere Atlas")).contains("no book called 'Nowhere Atlas' is on a shelf you can reach")
            .contains("browse_shelves shows the books there are");
        var turn = requestHolding("What the shelves answered you just now:");
        assertThat(promptOf(turn)).contains("no book called 'Nowhere Atlas'").doesNotContain("If one of them calls to you");
        turn.replyTo().tell(new InferenceRouter.InferOk(turn.requestId(), "Nothing there by that name.", 10, 5));

        readBook("Nowhere Atlas");
        assertThat(anyRequestHolding("What the shelves answered you just now:", Duration.ofSeconds(3)))
            .as("the same answer again is not handed back").isFalse();
    }

    @Test
    void with_no_book_open_a_plain_read_is_handed_back_with_the_shelves_named() {
        var answer = readBook(null);
        assertThat(answer).startsWith("You have no book open.").contains("browse_shelves shows books to choose from");
        assertThat(promptOf(requestHolding("What the shelves answered you just now:"))).contains("You have no book open.");
    }

    // ── Her reading by asking has a place in a book ───────────────────────

    /**
     * A second book by the same author, and a finding of hers that cites its part 2, as her search
     * left one (a passage id carries its collection, as on a node).
     */
    private void herFindingCitesAnotherOfHisBooks() {
        for (int p = 1; p <= 3; p++) {
            store.insertStudyItem("study-books:road" + p, OWNER, "document", "The Long Road - Robert Lewis.epub (part " + p + "/3)",
                "Part " + p + " of the long road.", "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
        FindingsLedger.recordDraft(store, HER_DID, "a road walked slowly", "The road is walked one step at a time.",
            List.of("The Long Road - Robert Lewis.epub (part 2/3) (study-books:road2)"), "companion:wren",
            FindingsLedger.ClaimType.SYNTHESIS);
    }

    @Test
    void the_door_a_search_names_reaches_the_turn_after_it_however_long_the_findings() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        var findings = ("The sources describe a woman becoming who she is meant to be, slowly and against the grain of "
            + "what was expected of her. ").repeat(12);
        assertThat(findings.length()).as("longer than the record keeps").isGreaterThan(1000);

        companion.tell(new CompanionActor.TestScriptedToolResult("library_card", Map.of(
            "findings", findings,
            "query", "a woman becoming who she is",
            "sources", List.of("The New Eve - Robert Lewis.epub (part 2/3)"),
            "source_ids", List.of("eve2"))));

        var prompt = promptOf(requestHolding("These passages came from books you can read through:"));
        assertThat(prompt).contains("'The New Eve - Robert Lewis' (the passage was part 2 of 3)")
            .contains("read_book with book: 'The New Eve - Robert Lewis' opens it there and reads on from that part.");
    }

    @Test
    void a_name_her_reading_came_from_opens_that_book_where_the_passage_was() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        herFindingCitesAnotherOfHisBooks();

        var page = readBook("Robert Lewis");

        assertThat(page).as("the author's name fits two books on the shelves; her reading came from one")
            .doesNotStartWith("Not done").contains("Part 2 of the long road");
        assertThat(ReadingLedger.shared().all(HER_DID)).extracting(ReadingLedger.Bookmark::part).containsExactly(2);
    }

    @Test
    void a_name_that_fits_no_book_is_answered_with_the_books_her_reading_came_from() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        herFindingCitesAnotherOfHisBooks();

        assertThat(readBook("what is in front of me")).contains("no book called 'what is in front of me'")
            .contains("The books your reading came from lately:\n  'The Long Road - Robert Lewis' (a passage was part 2 of 3)");
        assertThat(promptOf(requestHolding("## The shelves"))).as("handed to her as books to choose from")
            .contains("'The Long Road - Robert Lewis' (a passage was part 2 of 3)")
            .contains("If one of them calls to you, read_book with book: '<its name>' opens it.");
    }

    @Test
    void with_nothing_open_a_plain_read_names_the_books_her_reading_came_from() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        herFindingCitesAnotherOfHisBooks();

        assertThat(readBook(null)).startsWith("You have no book open.")
            .contains("The books your reading came from lately:\n  'The Long Road - Robert Lewis' (a passage was part 2 of 3)");
    }

    @Test
    void her_word_to_read_is_kept_when_the_turn_for_it_comes_back_as_words_about_the_tool() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        readBook("The New Eve");
        var first = requestHolding("This is the part you just opened:");
        first.replyTo().tell(new InferenceRouter.InferOk(first.requestId(), "A woman becoming.", 10, 5));
        assertThat(ReadingLedger.shared().all(HER_DID)).extracting(ReadingLedger.Bookmark::part).containsExactly(1);

        companion.tell(new CompanionActor.KeepHerWord("I'll read the next part.", "read_book"));
        var forced = requestHolding("Nobody asked this of you; it was your own word.");
        forced.replyTo().tell(new InferenceRouter.InferOk(forced.requestId(),
            "read_book reads the next part of the book I have open. It continues from where I left off at part 1. No arguments needed; just continue reading.", 10, 30));

        var page = requestHolding("This is the part you just opened:");
        assertThat(promptOf(page)).as("the next part was read for her").contains("Part 2 of the new eve");
        assertThat(ReadingLedger.shared().all(HER_DID)).extracting(ReadingLedger.Bookmark::part).containsExactly(2);
    }

    @Test
    void she_can_browse_the_shelves_and_the_view_is_handed_to_her() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");

        var view = browse(null);

        assertThat(view).contains("Picked at random from the shelves:\n  'The New Eve - Robert Lewis' — 3 parts");
        assertThat(view).endsWith("read_book with book: '<its name>' opens one. browse_shelves with words: '<a subject or an author>' looks for others.");
        var turn = requestHolding("## The shelves");
        var prompt = turn.messages().stream().map(m -> String.valueOf(m.content())).reduce("", String::concat);
        assertThat(prompt).contains("'The New Eve - Robert Lewis' — 3 parts")
            .contains("If one of them calls to you, read_book with book: '<its name>' opens it.");
    }

    @Test
    void browsing_by_name_shows_the_books_that_hold_it_and_how_far_she_is() {
        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        readBook("The New Eve");

        assertThat(browse("new eve")).startsWith("Books whose names hold 'new eve':\n  'The New Eve - Robert Lewis' — 3 parts — you are at part 1");
        assertThat(browse("lighthouses")).startsWith("No book on the shelves you may read has 'lighthouses' in its name.");
    }

    @Test
    void with_no_grant_the_study_is_not_on_her_shelves() {
        assertThat(browse(null)).startsWith("The shelves you may read hold no books to read through.");
        assertThat(browse("new eve")).startsWith("No book on the shelves you may read has 'new eve' in its name.");
    }

    @Test
    void with_no_book_open_the_reading_turn_names_the_shelves_and_with_one_open_it_does_not() {
        assertThat(readTurn()).contains("\nbrowse_shelves shows the books on the shelves you may read.");

        new StudyService(store, home).grantAccess(OWNER, HER_DID, "books");
        readBook("The New Eve");

        assertThat(readTurn()).doesNotContain("browse_shelves shows");
    }
}
