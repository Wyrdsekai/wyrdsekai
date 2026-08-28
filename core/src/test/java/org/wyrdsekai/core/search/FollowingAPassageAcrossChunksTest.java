package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A book is not 471 unrelated pieces.
 *
 * <p>{@code title} was a {@code StoredField} — written down, never indexed — so
 * nothing could ask what came before a passage. Chunks of one book had no
 * relation to each other that any query could see, which makes an answer that
 * straddles a boundary unreachable by construction rather than by bad luck.</p>
 *
 * <p>The live case that exposed it: Coleridge's <i>The Raven</i>, the poem
 * Finkle-McGraw sends Hackworth, is part 78 of <i>The Diamond Age</i>. The letter
 * naming both men is part 79. A question about the poem matches the letter and
 * never the verse — the poem contains none of the question's words. Searching
 * the person's own phrasing returned parts 79, 273, 77, 218, 179, 19, 21, 268
 * and never 78. The text sat in the library, retrievable in principle,
 * unreachable in practice.</p>
 */
class FollowingAPassageAcrossChunksTest {

    @TempDir Path tmp;
    private WyrdLuceneStore store;

    private static final String OWNER = "did:key:zOwner";
    private static final String BOOK = "The Diamond Age - Neal Arden.epub";

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, 8);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    private void chunk(String id, int part, String text) {
        store.insertStudyItem(id, OWNER, "document", BOOK + " (part " + part + "/471)",
            text, "books", System.currentTimeMillis(), 1, null, null, null, false);
    }

    /** The shape of the real corpus: the poem, then the letter about it. */
    private void seedTheBook() {
        chunk("p77", 77, "Hackworth walked home through the Leased Territories.");
        chunk("p78", 78, "THE RELAY_NODE, by Samuel Taylor Coleridge. Underneath an old oak tree "
            + "there was of swine a huge company, that grunted as they crunched the mast.");
        chunk("p79", 79, "Mr. Hackworth: I hope the above poem illuminates the ideas I only "
            + "touched on during our meeting of Tuesday last. Finkle-McGraw");
        chunk("p80", 80, "This was only the starting-point of a development lasting two years.");
    }

    /** THE case: the chunk the question CANNOT match is reachable from the one it can. */
    @Test
    void the_poem_is_reachable_from_the_letter_that_names_it() {
        seedTheBook();

        // What the question actually matches: the letter, not the verse.
        var hits = store.searchStudy(OWNER, "poem finkle mcgraw hackworth recite", null, 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id())
            .as("the question's words are in the letter")
            .isEqualTo("p79");

        var run = store.chunkWithNeighbours(SearchCollections.STUDY, "p79", 1);

        assertThat(run).extracting(WyrdLuceneStore.SearchResult::id)
            .as("and the poem is one page back — unreachable by any query, reachable by order")
            .containsExactly("p78", "p79", "p80");
        assertThat(run.get(0).content()).contains("THE RELAY_NODE");
    }

    /** Reading order, not relevance order — that is the whole point. */
    @Test
    void the_run_comes_back_in_reading_order() {
        seedTheBook();

        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "p79", 2))
            .extracting(WyrdLuceneStore.SearchResult::id)
            .containsExactly("p77", "p78", "p79", "p80");
    }

    /** Neighbours are within one document, never across the shelf. */
    @Test
    void it_does_not_wander_into_another_book() {
        seedTheBook();
        store.insertStudyItem("other78", OWNER, "document",
            "Glass Tide - Neal Arden.epub (part 78/512)", "Kestan Protagonist.",
            "books", System.currentTimeMillis(), 1, null, null, null, false);

        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "p79", 1))
            .extracting(WyrdLuceneStore.SearchResult::id)
            .doesNotContain("other78");
    }

    /** An unpaginated document has no neighbours, and says so by returning itself. */
    @Test
    void an_unpaginated_document_returns_only_itself() {
        store.insertStudyItem("note1", OWNER, "note", "A standalone note",
            "no parts here", "books", System.currentTimeMillis(), 1, null, null, null, false);

        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "note1", 1))
            .extracting(WyrdLuceneStore.SearchResult::id)
            .containsExactly("note1");
    }

    /** Edges must not fall off: part 1 has no predecessor. */
    @Test
    void the_first_chunk_has_no_page_before_it() {
        chunk("first", 1, "Chapter one.");
        chunk("second", 2, "Chapter two.");

        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "first", 1))
            .extracting(WyrdLuceneStore.SearchResult::id)
            .containsExactly("first", "second");
    }

    /** Degenerate input must not throw on a read path. */
    @Test
    void handles_unknown_and_null() {
        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "nope", 1)).isEmpty();
        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, null, 1)).isEmpty();
        assertThat(store.chunkWithNeighbours(null, "p79", 1)).isEmpty();
        assertThat(store.chunkWithNeighbours(SearchCollections.STUDY, "p79", -1)).isEmpty();
    }

    /** The backfill places documents written before the fields existed. */
    @Test
    void the_backfill_is_resumable_and_finishes() {
        seedTheBook();

        // Everything inserted now already carries its position, so a pass finds
        // nothing — which is exactly the property that makes it safe to run at
        // every boot.
        assertThat(store.backfillChunkOrder(SearchCollections.STUDY, 100, null))
            .as("a completed backfill must not rewrite the corpus on every start")
            .isZero();
    }

    /**
     * The "which are still missing?" query must survive the field EXISTING.
     *
     * <p>Live 2026-08-09: the first pass over a virgin index ran fine and every
     * pass after it died instantly, because {@code FieldExistsQuery} throws on a
     * {@code StringField} — no doc values, no norms, no vectors — and only starts
     * throwing once some document has the field. 500 documents of 15,585,914,
     * reported upward as completion. A batch smaller than the corpus is the only
     * way to catch it: run twice and require progress the second time.</p>
     */
    @Test
    void a_second_pass_still_finds_what_the_first_left() {
        for (int i = 1; i <= 12; i++) {
            store.insertLegacyStudyItemWithoutOrder("legacy" + i,
                BOOK + " (part " + i + "/471)", "page " + i);
        }

        // Batch size 5 against 12 documents: the pass MUST loop. Under the bug
        // it placed the first batch and then died on the next query, returning 5.
        long placed = store.backfillChunkOrder(SearchCollections.STUDY, 5, null);

        assertThat(placed)
            .as("stopping at the batch size is the signature of the broken query")
            .isEqualTo(12);
        assertThat(store.backfillChunkOrder(SearchCollections.STUDY, 5, null))
            .as("and a finished corpus gives a finished pass nothing to do")
            .isZero();
    }

    /** Searchability must survive the rebuild — the round-trip trap, again. */
    @Test
    void a_backfilled_document_is_still_searchable() {
        seedTheBook();
        store.backfillChunkOrder(SearchCollections.STUDY, 100, null);

        assertThat(store.searchStudy(OWNER, "swine", null, 5))
            .as("a rebuilt doc that lost its analyzed content is present but invisible")
            .isNotEmpty();
    }
}
