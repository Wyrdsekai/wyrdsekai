package org.wyrdsekai.core.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

/**
 * The live failure this exists to fix, 2026-08-07:
 *
 * <p>Asked what the Librarian told Kestan about velsharas in Glass Tide, the
 * companion searched {@code velshara}, got ONE irrelevant hit out of 13.7M
 * documents, and honestly reported the sources didn't contain it. The book was
 * in the library. Arden spells it <b>vel-shara</b>; the only bare "Velshara"
 * anywhere was a filename in a book about hacktivists.</p>
 *
 * <p>Nothing in the retrieval stack was broken. What was missing is what a
 * reader does without thinking: try another phrasing.</p>
 */
class QueryReformulatorTest {

    private static boolean has(List<String> vs, String want) {
        return vs.stream().anyMatch(v -> v.equalsIgnoreCase(want));
    }

    /** THE case. An unhyphenated compound must produce the hyphenated spelling. */
    @Test
    void velshara_yields_the_hyphenated_spelling_arden_actually_uses() {
        var vs = QueryReformulator.variants("velshara");
        assertTrue(has(vs, "vel-shara"),
            "must try 'vel-shara' — the spelling in the book — got: " + vs);
    }

    /** And the reverse: a hyphenated query must try the bare form. */
    @Test
    void hyphenated_terms_yield_the_bare_and_spaced_forms() {
        var vs = QueryReformulator.variants("vel-shara");
        assertTrue(has(vs, "velshara"), "got: " + vs);
        assertTrue(has(vs, "vel shara"), "got: " + vs);
    }

    /**
     * A question drowning a rare term in common words must yield the rare term.
     * 'Librarian velshara' found nothing because Librarian matches everything.
     */
    @Test
    void strips_common_words_down_to_the_discriminating_ones() {
        var vs = QueryReformulator.variants(
            "what significant thing did the librarian tell Kestan about velsharas");
        assertTrue(vs.stream().anyMatch(v -> v.toLowerCase().contains("kestan")),
            "a proper noun must survive: " + vs);
        assertTrue(vs.stream().noneMatch(v -> v.equalsIgnoreCase("what")),
            "must not emit a bare stopword");
        assertTrue(vs.stream().anyMatch(v -> !v.toLowerCase().contains("what")
                && !v.toLowerCase().contains("the ")),
            "must produce a phrasing without the filler: " + vs);
    }

    /** Pairs of rare terms — 'velshara Adrun' worked live where one term alone did not. */
    @Test
    void pairs_distinctive_terms() {
        var vs = QueryReformulator.variants("the velshara of Adrun tablet");
        assertTrue(vs.stream().anyMatch(v ->
                v.toLowerCase().contains("velshara") && v.toLowerCase().contains("adrun")),
            "must try the two rare terms together: " + vs);
    }

    /** Morphology must come before term-dropping — a spelling variant is near-certain. */
    @Test
    void morphological_variants_are_offered_before_narrowed_ones() {
        var vs = QueryReformulator.variants("velshara");
        assertFalse(vs.isEmpty());
        assertTrue(vs.get(0).contains("-"),
            "the first thing to try for a compound is its hyphenation: " + vs);
    }

    /** Never re-issue the phrasing that already failed. */
    @Test
    void never_returns_the_original() {
        for (var q : new String[]{"velshara", "vel-shara", "Kestan Protagonist", "greenhouse"}) {
            assertTrue(QueryReformulator.variants(q).stream().noneMatch(v -> v.equalsIgnoreCase(q)),
                "must not repeat the failed query: " + q);
        }
    }

    /** Degenerate input must not explode. */
    @Test
    void handles_empty_and_stopword_only_input() {
        assertTrue(QueryReformulator.variants(null).isEmpty());
        assertTrue(QueryReformulator.variants("").isEmpty());
        assertTrue(QueryReformulator.variants("   ").isEmpty());
        QueryReformulator.variants("what is the"); // must not throw
    }

    /** Each variant costs a search — the cap must hold. */
    @Test
    void respects_the_limit() {
        var vs = QueryReformulator.variants(
            "what significant thing did the librarian tell Kestan about velsharas in Glass Tide", 4);
        assertTrue(vs.size() <= 4, "got " + vs.size());
    }

    /** Variants must be distinct — repeating a search wastes the budget. */
    @Test
    void variants_are_unique() {
        var vs = QueryReformulator.variants("vel-shara of Adrun");
        assertTrue(vs.size() == vs.stream().distinct().count(), "duplicates in " + vs);
    }

    /** A single ordinary word has little to offer, and must not fabricate nonsense. */
    @Test
    void short_common_word_produces_nothing_absurd() {
        var vs = QueryReformulator.variants("the");
        assertTrue(vs.isEmpty() || vs.stream().noneMatch(String::isBlank));
    }
}
