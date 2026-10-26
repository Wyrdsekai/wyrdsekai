package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.RecordGuard;
import org.wyrdsekai.core.agent.interiority.WantActBridge;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.test.TestDb;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion can read a book from start to finish, and move on to the next one.
 *
 * <p>The library was a search engine and nothing else: every read returned the passage most like
 * her words, never the next page, so "finish reading" could neither land nor miss, and one
 * companion visited that want 28 times in a day and took it for a person's name (household node,
 * 2026-10-03). operator: "we need to build a means for them to be able to read books from start to
 * finish. also to move on to the next book etc. they need to learn."
 */
@Tag("integration")
class ABookCanBeReadThroughTest {

    @TempDir Path tmp;
    private WyrdLuceneStore store;
    private ReadingLedger ledger;

    private static final String OWNER = "did:key:z6MkExamplePerson";
    private static final String BOOK = "The New Eve - Robert Lewis.epub";
    private static final String OTHER = "The Diamond Age - Neal Arden.epub";
    private static final String HER = "did:key:z6MkExampleCompanion";

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, 8);
        ledger = new ReadingLedger(TestDb.createInMemory());
        for (int p = 1; p <= 3; p++) {
            store.insertStudyItem("eve" + p, OWNER, "document", BOOK + " (part " + p + "/3)",
                "Part " + p + " of the new eve: a woman becoming who she is meant to be.", "books",
                System.currentTimeMillis(), 1, null, null, null, false);
        }
        for (int p = 1; p <= 2; p++) {
            store.insertStudyItem("da" + p, OWNER, "document", OTHER + " (part " + p + "/2)",
                "Part " + p + " of the diamond age.", "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
        store.insertStudyItem("note", OWNER, "note", "A note", "A note is not a book.", "notes",
            System.currentTimeMillis(), 1, null, null, null, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    @Test
    void the_shelf_knows_its_books_by_a_few_words_and_serves_a_part_by_number() {
        var found = store.booksMatching(SearchCollections.STUDY, Reading.words("finish reading eve lewis"), 8);
        assertThat(found).extracting(WyrdLuceneStore.BookRef::group).containsExactly(BOOK);
        var ref = found.getFirst();
        assertThat(ref.parts()).isEqualTo(3);
        assertThat(ref.ownerDid()).isEqualTo(OWNER);
        assertThat(ref.studyCollection()).isEqualTo("books");
        assertThat(store.booksMatching(SearchCollections.STUDY, List.of("age"), 8))
            .extracting(WyrdLuceneStore.BookRef::group).containsExactly(OTHER);
        assertThat(store.booksMatching(SearchCollections.STUDY, List.of("note"), 8)).as("a note is not a book").isEmpty();
        assertThat(store.booksMatching(SearchCollections.STUDY, List.of("lighthouse"), 8)).isEmpty();
        assertThat(store.partOf(SearchCollections.STUDY, BOOK, 2).content()).startsWith("Part 2 of the new eve");
        assertThat(store.partOf(SearchCollections.STUDY, BOOK, 4)).isNull();
        assertThat(store.book(SearchCollections.STUDY, BOOK).parts()).isEqualTo(3);
    }

    @Test
    void her_place_is_kept_and_the_last_part_finishes_the_book() {
        var b = ledger.open(HER, BOOK, SearchCollections.STUDY, OWNER, "books", 3);
        assertThat(b.part()).isZero();
        assertThat(b.next()).isEqualTo(1);
        assertThat(b.title()).isEqualTo("The New Eve - Robert Lewis");
        assertThat(ledger.current(HER)).isPresent();
        b = ledger.read(HER, BOOK, 1);
        assertThat(b.part()).isEqualTo(1);
        assertThat(b.finished()).isFalse();
        assertThat(ledger.open(HER, BOOK, SearchCollections.STUDY, OWNER, "books", 3).part())
            .as("opening it again comes back to the page").isEqualTo(1);
        ledger.read(HER, BOOK, 2);
        var done = ledger.read(HER, BOOK, 3);
        assertThat(done.finished()).isTrue();
        assertThat(ledger.current(HER)).as("nothing open once it is finished").isEmpty();
        var page = Reading.page(done, "the last page", false);
        assertThat(page).startsWith("The New Eve - Robert Lewis — part 3 of 3:")
            .contains("You have finished The New Eve - Robert Lewis: all 3 parts.");
        var mid = Reading.page(ledger.read(HER, BOOK, 1), "a page", true);
        assertThat(mid).contains("[…this part was cut at").contains("read_book again reads part 2 of 3");
        ledger.open(HER, OTHER, SearchCollections.STUDY, OWNER, "books", 2);
        assertThat(ledger.all(HER)).hasSize(2);
        assertThat(Reading.shelf(ledger.all(HER))).contains("The Diamond Age - Neal Arden — at part 0 of 2")
            .contains("The New Eve - Robert Lewis — at part 1 of 3");
        assertThat(Reading.hersMatching(ledger.all(HER), Reading.words("the diamond one"))).hasSize(1);
        var offers = Reading.nextOffers(ledger.all(HER), store.booksMatching(SearchCollections.STUDY, List.of("the"), 8));
        assertThat(offers).contains("you are at part").doesNotContain("not yet begun");
    }

    @Test
    void the_words_of_asking_are_not_the_words_of_a_book() {
        assertThat(Reading.words("finish reading eve lewis")).containsExactly("eve", "lewis");
        assertThat(Reading.words("keep reading the book about the diamond age")).containsExactly("diamond", "age");
        assertThat(Reading.words("the next chapter")).isEmpty();
    }

    @Test
    void a_want_to_read_on_is_answered_with_the_book_and_the_verb_is_hers_on_her_own_time() {
        var d = WantActBridge.decideByHerWords("finish reading eve lewis", Map.of("Curiosity", 0.9), null);
        assertThat(d.verb()).isEqualTo("read_book");
        assertThat(d.mode()).isEqualTo(WantActBridge.Mode.FORCE_TOOL);
        assertThat(WantActBridge.decideByHerWords("read the rest of the diamond age", Map.of("Curiosity", 0.9), null).verb())
            .isEqualTo("read_book");
        assertThat(ActionPolicy.forAction("read_book").requiredTier()).isZero();
        assertThat(ActionPolicy.AUTONOMY_TIERS.get("read_book")).isEqualTo(ActionPolicy.AutonomyTier.AMBIENT);
        assertThat(SchemaInitializer.SCHEMA_VERSION).isGreaterThanOrEqualTo(22);
        for (var lang : List.of("en", "es", "ja")) {
            assertThat(ScriptMessageCatalog.forLang(lang).get("item.read_book.descriptor")).isNotEqualTo("item.read_book.descriptor");
        }
    }

    @Test
    void a_name_that_is_a_books_is_answered_as_the_book() {
        var spoken = RecordGuard.personsSpokenOf("I'll go find Eve Lewis in the quiet room where she's been sitting.", Set.of("mia"));
        assertThat(spoken).containsExactly("Eve Lewis");
        var asBooks = RecordGuard.namesThatAreBooks(spoken, Set.of("The New Eve - Robert Lewis"));
        assertThat(asBooks).containsEntry("Eve Lewis", "The New Eve - Robert Lewis");
        assertThat(RecordGuard.bookNote(asBooks)).isEqualTo("Eve Lewis is not a person: \"The New Eve - Robert Lewis\" is a book"
            + " you have been reading. What you know of it is what you read; read_book reads on from where you left it.");
        assertThat(RecordGuard.namesThatAreBooks(spoken, Set.of("The Diamond Age - Neal Arden"))).isEmpty();
    }

    /**
     * From a search to a book: a passage knows its book and its part, so the search can say which
     * book it read from and "read more of that" opens it there (second-node, 2026-10-04: a day of searches
     * that read from books, and not one book opened).
     */
    @Test
    void a_passage_knows_its_book_and_part_and_a_note_knows_it_is_none() {
        var place = store.placeOf(org.wyrdsekai.core.search.SearchCollections.STUDY, "eve2").orElseThrow();
        assertThat(place.book().group()).isEqualTo(BOOK);
        assertThat(place.part()).isEqualTo(2);
        assertThat(place.book().parts()).isEqualTo(3);
        assertThat(place.book().ownerDid()).isEqualTo(OWNER);
        assertThat(store.placeOf(org.wyrdsekai.core.search.SearchCollections.STUDY, "note")).as("a note is no book").isEmpty();
        assertThat(store.placeOf(org.wyrdsekai.core.search.SearchCollections.STUDY, "no-such")).isEmpty();
        assertThat(ReadingLedger.titleOf(place.book().group())).isEqualTo("The New Eve - Robert Lewis");
    }

    @Test
    void the_search_names_its_books_and_read_more_of_that_opens_the_book_there() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("var fromBooks = booksTheseCameFrom(result);");
        assertThat(ca).contains("These passages came from books you can read through:");
        assertThat(ca).contains("if (book == null && freshBookFromSearch() != null) {");
        assertThat(ca).contains("if (part == null && fromSearch != null && fromSearch.book().group().equals(book.book()) && book.part() < 1) {");
        assertThat(ca).as("a book found by a search counts as open for her plain 'I'll read'").contains("|| freshBookFromSearch() != null;");
    }

    @Test
    void the_verb_is_wired_into_her_turn() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var from = Path.of("..", rel);
        var ca = Files.readString(Files.exists(from) ? from : Path.of(rel));
        assertThat(ca).contains("if (\"read_book\".equals(toolItem.builtinHandler())) {\n                handleReadBook(node);");
        assertThat(ca).contains("allowed.add(\"read_book\");");
        // Named in the prompt, read_book survives the trim as a kept door (2026-10-26), not as the one pin.
        assertThat(ca).contains("\"shape_recipe\", \"sending_stone\", \"dispatch_task\", \"close_room\", \"read_book\", \"project_note\");");
        assertThat(ca).contains("for (var door : DOORS_NAMED_IN_THE_PROMPT) if (autonomyPromptLower.contains(door)) kept.add(door);");
        assertThat(ca).contains("noteInteriorityEnactment(\"read_book\");");
        assertThat(ca).contains("finishWantsNaming(after.title());");
        assertThat(ca).contains("HouseFacts.setStanding(profile.entityId(), \"reading\"");
        // A Study book is read only while the grant stands; a pack is always hers.
        assertThat(ca).contains("if (!mayStillRead(book)) {");
        var kit = Files.readString(Files.exists(Path.of("..", "core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java"))
            ? Path.of("..", "core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java")
            : Path.of("core/src/main/java/org/wyrdsekai/core/item/ToolItemStarterKit.java"));
        assertThat(kit).contains("readBook(),");
    }
}
