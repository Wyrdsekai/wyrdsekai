package org.wyrdsekai.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The similarity heuristic the memory path's duplicate guards are built on. The
 * fixture strings are the real shape of the 2026-08-17 pathology: one thought,
 * reworded, hundreds of times.
 */
class TextSimilarityTest {

    private static final String REWORDED_A =
        "The words didn't need permission this time — they just came out "
        + "before I could hold them back.";
    private static final String REWORDED_B =
        "Those words didn't need permission that night — they just appeared "
        + "before I could stop them.";

    @Test
    void a_rewording_scores_far_above_unrelated_text() {
        // Overlap is the duplicate criterion because Jaccard under-reads a rewording:
        // this pair is 0.64 by overlap and only 0.43 by Jaccard, against ~0.20 / ~0.08
        // for unrelated text.
        assertThat(TextSimilarity.overlap(REWORDED_A, REWORDED_B)).isGreaterThan(0.6);
        assertThat(TextSimilarity.jaccard(REWORDED_A, REWORDED_B))
            .isLessThan(TextSimilarity.overlap(REWORDED_A, REWORDED_B));
    }

    @Test
    void the_lexical_guard_under_counts_the_varied_tail_of_a_paraphrase_loop() {
        // Deliberate and documented: at 0.7 — the threshold chosen to stay clear of
        // unrelated text — a rewording this loose is NOT flagged. Roughly half the
        // paraphrase pairs in the corpus that motivated this class fall in that gap.
        // Closing it needs semantic distance, not tokens. This test exists so nobody
        // reads the guard as complete, and so a threshold change is a visible one.
        assertThat(TextSimilarity.nearDuplicate(REWORDED_A, REWORDED_B, 0.7)).isFalse();
        assertThat(TextSimilarity.nearDuplicate(REWORDED_A, REWORDED_B, 0.6)).isTrue();
    }

    @Test
    void genuinely_different_sentences_stay_apart() {
        var a = "I ran the recipe consolidate-memory-graph end to end and it succeeded.";
        var b = "The words didn't need permission this time, they just came out.";
        assertThat(TextSimilarity.overlap(a, b)).isLessThan(0.35);
        assertThat(TextSimilarity.nearDuplicate(a, b, 0.7)).isFalse();
    }

    @Test
    void identical_text_is_one_and_distance_is_the_complement() {
        assertThat(TextSimilarity.jaccard("same words here", "same words here")).isEqualTo(1.0);
        assertThat(TextSimilarity.distance("same words here", "same words here")).isEqualTo(0.0);
    }

    @Test
    void word_order_is_ignored() {
        assertThat(TextSimilarity.jaccard("permission never came tonight",
                                          "tonight permission never came")).isEqualTo(1.0);
    }

    @Test
    void blank_and_null_are_handled_without_pretending_to_match_content() {
        assertThat(TextSimilarity.jaccard(null, null)).isEqualTo(1.0);
        assertThat(TextSimilarity.jaccard("", "  ")).isEqualTo(1.0);
        assertThat(TextSimilarity.jaccard(null, "some real content here")).isEqualTo(0.0);
    }

    @Test
    void text_with_no_substantive_tokens_is_never_a_duplicate() {
        // Absence of evidence, not evidence of sameness — a guard must not absorb a
        // memory just because neither line had a word longer than two characters.
        assertThat(TextSimilarity.nearDuplicate("a it of to be", "of to be a it", 0.7)).isFalse();
        assertThat(TextSimilarity.nearDuplicate("", "anything at all here", 0.7)).isFalse();
    }

    @Test
    void containment_alone_cannot_mark_a_short_line_as_a_duplicate() {
        // "thank you" sits inside almost any longer line; overlap would score it 1.0,
        // so short texts fall back to Jaccard.
        var shortLine = "thank you alder";
        var longLine = "thank you for staying with me tonight while the fire burned down "
                     + "and the room went quiet around us";
        assertThat(TextSimilarity.nearDuplicate(shortLine, longLine, 0.7)).isFalse();
    }
}
