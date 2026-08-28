package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * What actually reaches BM25.
 *
 * <p>Two live problems, 2026-08-08.</p>
 *
 * <p><b>One.</b> {@code keywordsOf} was called at exactly one site — the rerank's
 * candidate fetch — so the Study leg searched cleaned terms while the
 * knowledge-pack leg searched the raw string. Same query, two treatments, and the
 * pack side returned a StackExchange gardening post for a question about Glass
 * Tide because it matched "glass".</p>
 *
 * <p><b>Two.</b> The model writes queries like {@code "velshara OR Glass Tide AND
 * (librarian OR Kestan) — any mention of a scene where a Librarian explains
 * VelSharas to Kestan. Look for: the term velhara, what it means in that moment..."}
 * — 45 words, of which two discriminate. Stopword removal still leaves ~25
 * content words and the rare ones are outvoted.</p>
 *
 * <p>The obvious cap — keep the longest terms — is <b>wrong</b>, and this is the
 * test that caught it: search-instruction vocabulary is systematically longer
 * than the names that matter, so ranking by length kept "mention", "explains",
 * "structure", "surrounding" and dropped <b>"glass"</b> and <b>"kestan"</b>.</p>
 */
class QueryNormalisationTest {

    /** The real query the model produced, live. */
    private static final String MODEL_SOUP =
        "velshara OR Glass Tide AND (librarian OR Kestan) — any mention of a scene "
        + "where a Librarian explains VelSharas to Kestan. Look for: the term velhara, "
        + "what it means in that moment, and if there's anything about its role or "
        + "structure. Also check surrounding pages";

    private static List<String> terms(String q) {
        var out = WyrdLuceneStore.keywordsOf(q);
        return List.of(out.split("\\s+"));
    }

    /** THE case: the discriminating terms must survive the cull. */
    @Test
    void the_words_that_matter_survive_a_paragraph_of_instructions() {
        var t = terms(MODEL_SOUP);

        assertThat(t).as("ranking by length dropped these: " + t)
            .contains("velshara", "glass", "tide", "librarian", "kestan");
    }

    /** And the query must actually get shorter, or the dilution remains. */
    @Test
    void a_45_word_query_is_reduced() {
        var t = terms(MODEL_SOUP);

        assertThat(t.size())
            .as("too many terms and the rare ones are outvoted: " + t)
            .isLessThanOrEqualTo(12);
        assertThat(t.size()).isGreaterThan(3);
    }

    /** Boolean soup must not survive as literal terms. */
    @Test
    void operators_and_punctuation_are_stripped() {
        var t = terms(MODEL_SOUP);

        assertThat(t).doesNotContain("or", "and", "(librarian", "kestan.", "velhara,");
    }

    /** A short, well-formed query must pass through essentially untouched. */
    @Test
    void a_good_query_is_left_alone() {
        assertThat(terms("librarian told kestan something velshara Glass Tide"))
            .containsExactly("librarian", "told", "kestan", "velshara", "glass", "tide");
    }

    /** Short queries keep instruction words — they cost nothing there. */
    @Test
    void instruction_words_are_demoted_not_banned() {
        assertThat(terms("what does the book say about narrative structure"))
            .as("'structure' is the SUBJECT here, not an instruction")
            .contains("structure");
    }

    /** Hyphenated terms split rather than vanish. */
    @Test
    void hyphenated_terms_survive_as_parts() {
        assertThat(terms("vel-shara of Adrun")).contains("vel", "shara", "adrun");
    }

    /** An all-stopword query keeps its own terms rather than becoming silence. */
    @Test
    void an_all_stopword_query_falls_back_to_itself() {
        assertThat(WyrdLuceneStore.keywordsOf("the")).isEqualTo("the");
        assertThat(WyrdLuceneStore.keywordsOf("what is that")).isNotBlank();
    }

    /** Degenerate input must not throw. */
    @Test
    void handles_null_and_blank() {
        assertThat(WyrdLuceneStore.keywordsOf(null)).isNull();
        assertThat(WyrdLuceneStore.keywordsOf("")).isEmpty();
        assertThat(WyrdLuceneStore.keywordsOf("   ")).isBlank();
    }

    /** Normalisation must happen for EVERY lexical path, not one call site. */
    @Test
    void every_bm25_path_is_normalised() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/search/WyrdLuceneStore.java";
        var fromCore = Paths.get("..", rel);
        var src = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));

        assertThat(src)
            .as("normalising inside sparseSearch covers TEXT_ONLY, HYBRID and the rerank")
            .contains("QueryParser.escape(keywordsOf(queryText, searcher.getIndexReader()))");
        assertThat(src)
            .as("the one-call-site version is what created the asymmetry")
            .doesNotContain("sparseSearch(collection, keywordsOf(queryText)");
    }
}
