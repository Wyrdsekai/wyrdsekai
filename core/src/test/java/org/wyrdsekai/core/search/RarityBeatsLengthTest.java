package org.wyrdsekai.core.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cull must keep the words that identify the answer.
 *
 * <p><b>Third attempt at the same ranking, and the first with evidence.</b>
 * Ranking by LENGTH kept "mention"/"explains"/"structure" and dropped "glass"
 * and "kestan". Ranking by SUBJECT-vs-INSTRUCTION fixed that case and failed the
 * next one live: on 2026-08-08 at 20:01 a companion wrote herself a 69-word
 * query, kept {@code "dialogue content summary explanation reality library
 * holdings particular conversation"}, dropped every discriminating term, and
 * answered a question about Glass Tide out of a screenwriting textbook and a
 * court document — because "dialogue" and "conversation" are not instruction
 * words, so they counted as subject and then won on length.</p>
 *
 * <p>Measured against the live 15.6M-document index, the two groups are not
 * close: velshara 1 document, shara 350, kestan 1,465 — against conversation
 * 553,578 and particular 735,987. The index already knew which words were the
 * question. The fix is to ask it.</p>
 *
 * <p>Frequencies below are scaled-down but order-preserving versions of those
 * real numbers, so this test fails for the same reason production did.</p>
 */
class RarityBeatsLengthTest {

    /** The 69-word query the model actually produced, verbatim from the log. */
    private static final String LIVE_QUERY =
        "librarian to Kestan Vel-Shara Glass Tide reference dialogue content summary "
        + "excerpt quote explanation of how a name-thing works on top of and below "
        + "reality what that is meant for the world itself not just as an object but "
        + "as something you can stand inside without being caught by its shape or "
        + "weight either one — including any mention in library holdings about this "
        + "particular conversation between librarian and kestan";

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
     * Document frequencies measured on the live 15.6M-document Study index,
     * scaled to 1,000 documents and rounded up. The ORDER is what matters and
     * it is preserved exactly, so this fixture fails and passes for the same
     * reasons production did.
     *
     * <p>Real counts, for the record: particular 735,987 · conversation 553,578
     * · content 382,814 · glass 365,600 · library 324,574 · explanation 319,217
     * · reality 318,300 · tide 106,946 · summary 68,061 · dialogue 54,523 ·
     * librarian 21,493 · holdings 16,221 · vel 12,953 · kestan 1,465 · shara 350.</p>
     */
    private static final String[][] CORPUS = {
        {"particular", "47"}, {"conversation", "36"}, {"content", "25"},
        {"glass", "23"}, {"library", "21"}, {"explanation", "20"}, {"reality", "20"},
        {"tide", "7"}, {"summary", "4"}, {"dialogue", "4"},
        {"librarian", "2"}, {"holdings", "1"}, {"vel", "1"}, {"kestan", "1"},
    };

    private void seedCorpus() {
        for (var row : CORPUS) {
            var term = row[0];
            int n = Integer.parseInt(row[1]);
            for (int i = 0; i < n; i++) {
                store.insertStudyItem(term + "-" + i, "did:key:zOwner", "note", "T",
                    "a document that happens to use the word " + term + ", number " + i,
                    "books", System.currentTimeMillis(), 1, null, null, null, false);
            }
        }
        // The answer: the single document carrying the rarest terms of all.
        store.insertStudyItem("answer", "did:key:zOwner", "note", "Glass Tide",
            "The Librarian told Kestan that a vel-shara is a speech with power — "
                + "an incantation that reprograms the listener.", "books",
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

    /**
     * THE case: the words that name the answer survive a paragraph of packaging.
     *
     * <p>Note "Vel-Shara" reaches BM25 as {@code vel} and {@code shara} — the
     * analyzer splits the hyphen, and so does the book text. There is no
     * {@code velshara} token in this query at all.</p>
     */
    @Test
    void the_discriminating_terms_survive_a_69_word_query() {
        seedCorpus();

        var t = terms(LIVE_QUERY);

        assertThat(t).as("what actually reached BM25: " + t)
            .contains("shara", "kestan", "vel", "librarian", "tide");
        assertThat(t).as("and the packaging must be what loses: " + t)
            .doesNotContain("conversation", "particular", "content");
    }

    /** Which is only worth anything if it finds the document. */
    @Test
    void the_query_now_retrieves_the_answer() {
        seedCorpus();

        var hits = store.searchStudy("did:key:zOwner",
            String.join(" ", terms(LIVE_QUERY)), null, 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().id())
            .as("top hit should be the one document that mentions the vel-shara")
            .isEqualTo("answer");
    }

    /** A term in no document cannot match, so it must not consume a slot. */
    @Test
    void an_absent_term_loses_to_a_rare_one() {
        seedCorpus();

        // "velhara" is the model's real typo, from an earlier live query.
        var t = terms("velhara " + LIVE_QUERY);

        assertThat(t).as("a word in zero documents can never match — it must not "
                + "take a slot from one that can: " + t)
            .doesNotContain("velhara");
        assertThat(t).contains("shara", "kestan");
    }

    /** With no corpus to ask, behaviour must be exactly what it was before. */
    @Test
    void it_falls_back_when_there_is_no_reader() {
        var t = List.of(WyrdLuceneStore.keywordsOf(LIVE_QUERY, null).split("\\s+"));

        assertThat(t).as("no index, no rarity — but still a cull, not a tide")
            .hasSizeLessThanOrEqualTo(12);
        assertThat(WyrdLuceneStore.keywordsOf(LIVE_QUERY))
            .isEqualTo(WyrdLuceneStore.keywordsOf(LIVE_QUERY, null));
    }

    /**
     * An empty index must not be read as "every term is dead" — that would
     * invert the cull instead of disabling it.
     */
    @Test
    void an_empty_corpus_is_not_a_corpus_of_zeroes() {
        var t = terms(LIVE_QUERY);

        assertThat(t).hasSizeLessThanOrEqualTo(12).isNotEmpty();
    }

    /** A short query is under the cap and must pass through untouched. */
    @Test
    void a_short_query_is_still_left_alone() {
        seedCorpus();

        // "Left alone" means NOT CULLED — every word survives, in order. The
        // absent-term rescue still runs (2026-08-10): the corpus has no
        // "velshara" token, only the hyphenated pair, so the fused form splits
        // to the two tokens the book actually uses.
        assertThat(terms("librarian told kestan something velshara Glass Tide"))
            .containsExactly("librarian", "told", "kestan", "vel", "shara", "glass", "tide");
    }

    /** The earlier regression must stay fixed: subject terms beat instructions. */
    @Test
    void the_previous_case_does_not_regress() {
        seedCorpus();

        var t = terms("velshara OR Glass Tide AND (librarian OR Kestan) — any mention of a "
            + "scene where a Librarian explains VelSharas to Kestan. Look for: the term "
            + "velhara, what it means in that moment, and if there's anything about its "
            + "role or structure. Also check surrounding pages");

        // Since 2026-08-10 the fused "velshara" no longer rides as a dead
        // token: this corpus spells it only as the hyphenated pair, so the
        // rescue splits it into the tokens that can actually match.
        assertThat(t).contains("vel", "shara", "kestan", "librarian");
    }
}
