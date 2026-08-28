package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A word typed solid finds the passage that hyphenates it.
 *
 * <p>Books hyphenate what readers type from memory: the text's "vel-shara"
 * indexes as {@code vel} and {@code shara}, so a person asking about
 * "velsharas" contributes a term found in no document — the question's rarest
 * concept exerting zero pull. Live 2026-08-10, the first question ever asked
 * of the fresh 13.7M-chunk corpus: her person's word "significant" appeared
 * verbatim in a decoy passage about Dr. Vesk, "velsharas" matched nothing to
 * outvote it, and she answered the wrong scene from the right book. The same
 * question with the hyphen answered with the book's own sentences. dev39
 * taught the summariser about odd spellings; retrieval was never taught.</p>
 *
 * <p>The rescue never guesses from rules — it acts only when the index
 * confirms both halves exist. And it takes the UNION of attested forms: on
 * the live corpus a lone document contains "velshara" fused (df=1 of 13.7M),
 * and a singular-then-stop rescue would anchor there and never reach the
 * hundreds of real vel-shara passages.</p>
 */
class AFusedWordFindsItsHyphenatedSelfTest {

    @TempDir Path tmp;
    private WyrdLuceneStore store;

    @BeforeEach
    void setUp() {
        store = new WyrdLuceneStore(tmp, 8);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) store.close();
    }

    private void seed() {
        // The answer: hyphenated, as the book actually spells it.
        store.insertStudyItem("answer", "did:key:zOwner", "note", "Glass Tide (part 40/120)",
            "The Librarian told Kestan that the vel-shara of Adrun was a speech with "
                + "power — an incantation that changed the speech in men's mouths.",
            "books", System.currentTimeMillis(), 1, null, null, null, false);
        // The decoy that won live: carries the person's common word "significant".
        store.insertStudyItem("decoy", "did:key:zOwner", "note", "Glass Tide (part 12/120)",
            "Vesk was a self-taught researcher who specialized in finding "
                + "significant information in vast amounts of irrelevant detail.",
            "books", System.currentTimeMillis(), 1, null, null, null, false);
        // Background chatter so df(vel)/df(shara) > 0 in more than one doc.
        for (int i = 0; i < 3; i++) {
            store.insertStudyItem("bg-" + i, "did:key:zOwner", "note", "notes",
                "margin note " + i + " mentioning the vel-shara in passing.",
                "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
    }

    private List<String> terms(String q) {
        try {
            return List.of(store.withReader(SearchCollections.STUDY,
                r -> WyrdLuceneStore.keywordsOf(q, r)).split("\\s+"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** THE case: the fused plural splits into the two tokens the book uses. */
    @Test
    void velsharas_typed_solid_becomes_vel_and_shara() {
        seed();

        var t = terms("librarian kestan velsharas glass tide");

        assertThat(t).as("what reached BM25: " + t).contains("vel", "shara");
        assertThat(t).as("the dead fused form must not ride along: " + t)
            .doesNotContain("velsharas");
    }

    /** Which is only worth anything if the right passage now wins. */
    @Test
    void the_split_query_retrieves_the_hyphenated_passage() {
        seed();

        var hits = store.searchStudy("did:key:zOwner",
            String.join(" ", terms("significant thing librarian told kestan about velsharas")),
            null, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id())
            .as("the vel-shara passage must beat the 'significant' decoy")
            .isEqualTo("answer");
    }

    /** A split half of a protected person-word inherits the protection. */
    @Test
    void protection_rides_through_the_split() {
        seed();

        var t = terms("query packaging words ⟦librarian velsharas⟧");

        assertThat(t).contains("vel", "shara", "librarian");
    }

    /**
     * The live counter-case, one hour after the first version shipped:
     * df-based selection chose "nams hubs" — both halves common, never
     * adjacent. Adjacency is the signal: the split must reconstruct a pair
     * the corpus actually prints side by side.
     */
    @Test
    void common_but_never_adjacent_halves_lose_to_the_real_pair() {
        seed();
        // Make the wrong pair's halves individually common — far more
        // documents than vel/shara have — but never next to each other.
        for (int i = 0; i < 8; i++) {
            store.insertStudyItem("nams-" + i, "did:key:zOwner", "note", "notes",
                "the nams family appears in this record, entry " + i + ".",
                "books", System.currentTimeMillis(), 1, null, null, null, false);
            store.insertStudyItem("hubs-" + i, "did:key:zOwner", "note", "notes",
                "regional transit hubs are listed here, entry " + i + ".",
                "books", System.currentTimeMillis(), 1, null, null, null, false);
        }

        var t = terms("librarian kestan velsharas glass tide");

        assertThat(t).as("adjacency must beat popularity: " + t).contains("vel", "shara");
        assertThat(t).doesNotContain("nams", "hubs");
    }

    /**
     * A word absent from a SMALL collection must not shatter into indexed
     * fragments that never stood together — live, the pack-collection leg
     * split "mattered" into "matte red".
     */
    @Test
    void an_ordinary_word_missing_from_a_small_index_does_not_shatter() {
        seed();
        store.insertStudyItem("paint-1", "did:key:zOwner", "note", "notes",
            "a matte finish on the frame.", "books",
            System.currentTimeMillis(), 1, null, null, null, false);
        store.insertStudyItem("paint-2", "did:key:zOwner", "note", "notes",
            "the red pigment faded over years.", "books",
            System.currentTimeMillis(), 1, null, null, null, false);

        var t = terms("librarian mattered kestan");

        assertThat(t).as("never-adjacent halves are no rescue: " + t)
            .contains("mattered")
            .doesNotContain("matte", "red");
    }

    /** No attested split → the term rides through unchanged, as before. */
    @Test
    void a_genuinely_unknown_word_is_left_alone() {
        seed();

        var t = terms("librarian zxqvbnmp kestan");

        assertThat(t).as("no rescue invented anything: " + t)
            .contains("zxqvbnmp", "librarian", "kestan");
    }

    /** The singular rescue still works and still stops at an attested form. */
    @Test
    void the_plural_rescue_is_not_regressed() {
        seed();

        var t = terms("librarians kestan");

        assertThat(t).contains("librarian", "kestan");
    }
}
