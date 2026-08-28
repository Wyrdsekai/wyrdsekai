package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion's rewrite of a question may add words. It may not lose them.
 *
 * <p>Live, 2026-08-08 20:50. Asked what the librarian told Kestan about
 * <b>velsharas</b> in <b>glass tide</b>, she searched her library for</p>
 *
 * <pre>glass tine library vels hara kestan conversation scene novel text passage content summary</pre>
 *
 * <p><b>"glass tine". "vels hara".</b> She then answered from the wrong chapter
 * and described "a character named Hara" — her own corrupted query term coming
 * back as an invented person. Rarity ranking had just been fixed and worked
 * perfectly: "crane" (28,892 documents) and "hub" (16,604) are genuinely rare,
 * so it protected them. Nothing downstream can recover a word the query never
 * contained, and the words a paraphrase mangles are exactly the proper nouns
 * that carry the question.</p>
 *
 * <p>The second half is subtler. Restoring the person's literal "velsharas" is
 * not enough — measured on the live index it appears in <b>zero</b> documents,
 * while "velshara" appears in exactly one, the rarest term in 15.6 million.
 * A word that matches nothing is not a repair, so an absent term is resolved
 * against the corpus before it is ranked.</p>
 */
class PersonWordsSurviveTheRewriteTest {

    /** What the person said. */
    private static final String PERSON =
        "mia, can u look through my books and tell me what significant thing the "
        + "librarian told kestan about velsharas in glass tide?";

    /** What the companion searched for instead, verbatim from the log. */
    private static final String REWRITE =
        "glass tine library vels hara kestan conversation scene novel text passage "
        + "content summary";

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

    /**
     * The shape of the real failure: the right BOOK, the wrong CHAPTER.
     *
     * <p>A single Glass Tide document would make this test pass for the wrong
     * reason — "glass" and "kestan" alone would find it. Production has thousands
     * of chunks from the novel, so the garbled query landed on the drug-dealer
     * scene, which is dense in exactly the terms that survived her rewrite. The
     * decoy is what makes the control assertion below mean anything.</p>
     */
    private void seedCorpus() {
        for (int i = 0; i < 40; i++) {
            store.insertStudyItem("filler-" + i, "did:key:zOwner", "note", "T",
                "conversation scene novel passage content summary library number " + i,
                "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
        // The wrong chapter — what she actually cited. Dense in glass/tide/kestan.
        for (int i = 0; i < 6; i++) {
            store.insertStudyItem("decoy-" + i, "did:key:zOwner", "note", "Glass Tide",
                "Kestan has never heard of a drug called Glass Tide. A glass tide is "
                    + "computer lingo for a system tide. Kestan watches the avatar break "
                    + "into pixels; the hypercard is a passage of digital data. " + i,
                "books", System.currentTimeMillis(), 1, null, null, null, false);
        }
        // The answer: the only document holding the singular, as in reality.
        store.insertStudyItem("answer", "did:key:zOwner", "note", "The Librarian",
            "The Librarian told Kestan that a velshara is a speech with power — an "
                + "incantation that reprograms the listener.", "books",
            System.currentTimeMillis(), 1, null, null, null, false);
    }

    private List<String> terms(String q) {
        try {
            return List.of(store.withReader(SearchCollections.STUDY,
                r -> WyrdLuceneStore.keywordsOf(q, r)).split("\\s+"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** THE case: the words she dropped come back. */
    @Test
    void the_dropped_words_are_restored() {
        var merged = WyrdLuceneStore.withPersonTerms(REWRITE, PERSON);

        assertThat(merged).as("the rewrite must survive intact").startsWith(REWRITE);
        assertThat(merged).contains("tide").contains("velsharas");
    }

    /** Her own words are never thrown away — this adds, it does not replace. */
    @Test
    void the_rewrite_is_never_replaced() {
        var merged = WyrdLuceneStore.withPersonTerms(REWRITE, PERSON);

        for (var word : REWRITE.split(" ")) {
            assertThat(merged).as("lost '" + word + "' from the companion's own query")
                .contains(word);
        }
    }

    /** A term in no document is resolved to one that is — or left alone. */
    @Test
    void an_absent_plural_is_resolved_against_the_corpus() {
        seedCorpus();

        var t = terms("velsharas");

        assertThat(t).as("'velsharas' matches nothing; 'velshara' is the rarest term there is")
            .containsExactly("velshara");
    }

    /** Only when the corpus confirms it — this is not a stemmer. */
    @Test
    void a_plural_that_is_present_is_left_alone() {
        seedCorpus();

        assertThat(terms("books")).as("'books' is in the corpus; do not stem it")
            .containsExactly("books");
        assertThat(terms("zzzqs")).as("no singular either — leave it and let ranking cull it")
            .containsExactly("zzzqs");
    }

    /** Both halves together: the person's question actually finds the answer. */
    @Test
    void the_merged_query_retrieves_the_right_passage() {
        seedCorpus();
        var merged = WyrdLuceneStore.withPersonTerms(REWRITE, PERSON);

        var hits = store.searchStudy("did:key:zOwner",
            String.join(" ", terms(merged)), null, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id())
            .as("the rewrite alone found the wrong chapter; with her words it should not")
            .isEqualTo("answer");
    }

    /** And the rewrite alone must NOT — otherwise this test proves nothing. */
    @Test
    void the_rewrite_alone_still_misses() {
        seedCorpus();

        var hits = store.searchStudy("did:key:zOwner",
            String.join(" ", terms(REWRITE)), null, 1);

        assertThat(hits.isEmpty() || !hits.getFirst().id().equals("answer"))
            .as("if the garbled query already worked, the fix is untested")
            .isTrue();
    }

    /** Vocative and filler must not be dragged in. */
    @Test
    void stopwords_and_address_are_not_restored() {
        var merged = WyrdLuceneStore.withPersonTerms("glass tide", PERSON);
        var added = merged.substring("glass tide".length()).trim();

        assertThat(added.split("\\s+")).doesNotContain("can", "what", "the", "and", "about");
    }

    /**
     * The person's words are PROTECTED even when the rewrite already has them.
     *
     * <p>This test used to assert the opposite — that already-present words are
     * not re-appended — and that contract failed live (2026-08-09 15:49): her
     * rewrite contained "lazarus long humanity live forever", so nothing was
     * appended, and the rarity cull then discarded four of the five as too
     * common. Presence in the rewrite is not protection; the marked span is.
     * The cull dedups, so the words still reach BM25 exactly once.</p>
     */
    @Test
    void person_words_are_protected_even_when_already_present() {
        var merged = WyrdLuceneStore.withPersonTerms("librarian kestan", "librarian kestan");

        assertThat(merged).startsWith("librarian kestan");
        assertThat(WyrdLuceneStore.stripProtectionMarkers(merged))
            .as("markers flatten away for any consumer that wants plain words")
            .isNotEqualTo(merged);
        assertThat(List.of(WyrdLuceneStore.keywordsOf(merged, null).split("\\s+")))
            .as("and the cull dedups, so BM25 sees each word once")
            .containsExactly("librarian", "kestan");
    }

    /** The counter-case that forced the redesign: common person words survive. */
    @Test
    void a_conjunction_of_common_person_words_survives_the_cull() {
        // Model soup long enough to trigger the cull, with rare-ish padding.
        var soup = "reflection memorable achievement perspective paroemiological "
            + "bibliographical anthology compendium retrospective examination "
            + "quotation attribution commentary analysis interpretation";
        var merged = WyrdLuceneStore.withPersonTerms(soup,
            "what did lazarus long figure out about living forever");

        var t = List.of(WyrdLuceneStore.keywordsOf(merged, null).split("\\s+"));

        assertThat(t).as("the person's words do not compete with the model's: " + t)
            .contains("lazarus", "long", "living", "forever", "figure");
    }

    /** Degenerate input must not throw on a live dispatch path. */
    @Test
    void handles_null_and_blank() {
        assertThat(WyrdLuceneStore.withPersonTerms("q", null)).isEqualTo("q");
        assertThat(WyrdLuceneStore.withPersonTerms("q", "  ")).isEqualTo("q");
        assertThat(WyrdLuceneStore.withPersonTerms(null, "asked")).isEqualTo("asked");
        assertThat(WyrdLuceneStore.withPersonTerms(null, null)).isNull();
    }
}
