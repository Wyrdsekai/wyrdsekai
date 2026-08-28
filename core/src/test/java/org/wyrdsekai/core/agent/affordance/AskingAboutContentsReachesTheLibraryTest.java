package org.wyrdsekai.core.agent.affordance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A question about something written must put the library on the menu.
 *
 * <p>The cue list named only the furniture — library, shelf, volume — so it fired
 * on "look through my books" and scored <b>zero</b> on "what is the poem that
 * Finkle-McGraw sent to Hackworth? please recite the poem to me".</p>
 *
 * <p>Live, 2026-08-09, four runs of exactly that question. {@code library_card}
 * appeared on <b>none</b> of the eight ranked surfaces; she was offered
 * {@code go_to_room}, {@code promote_familiar}, {@code bear_the_wound}. With no
 * way to reach a source she went out the {@code use_item} escape hatch to the
 * WEB, where "Finkle-McGraw" matched "Norman Finkelstein (poet) — Wikipedia".
 * On the fourth run she composed a poem herself and recited it. The poem she was
 * asked for — Coleridge's <i>The Raven</i> — was in the library the whole time.</p>
 *
 * <p>Adding a tool to a menu costs nothing; leaving it off costs the answer.
 * That asymmetry is why the contents nouns belong here even though they will
 * sometimes fire on a question the library cannot answer.</p>
 */
class AskingAboutContentsReachesTheLibraryTest {

    private static double library(String request) {
        return RequestRelevance.score(request, "library_card", null);
    }

    /** THE case, verbatim. */
    @Test
    void the_question_that_reached_no_source_at_all() {
        assertThat(library("what is the poem that finkle-mcgraw sent to hackworth? "
            + "please recite the poem to me"))
            .as("this scored 0.0 and she went to the web instead")
            .isGreaterThanOrEqualTo(1.0);
    }

    /** The other names for a piece of writing. */
    @Test
    void contents_nouns_are_cues_too() {
        assertThat(library("what does that passage say")).isGreaterThanOrEqualTo(1.0);
        assertThat(library("the letter he sent her")).isGreaterThanOrEqualTo(1.0);
        assertThat(library("who is the author of that")).isGreaterThanOrEqualTo(1.0);
        assertThat(library("can you find the excerpt")).isGreaterThanOrEqualTo(1.0);
        assertThat(library("the lyrics to it")).isGreaterThanOrEqualTo(1.0);
    }

    /** The container nouns must keep working — this is additive. */
    @Test
    void the_original_case_does_not_regress() {
        assertThat(library("mia, can u look through my books and tell me what significant "
            + "thing the librarian told kestan about velsharas in glass tide?"))
            .isGreaterThanOrEqualTo(1.0);
        assertThat(library("what's on my shelves")).isGreaterThanOrEqualTo(1.0);
    }

    /**
     * The overfitting guard from the last round must still hold. These were real
     * false positives once, and a wider cue list is exactly how they come back.
     */
    @Test
    void verbs_that_look_like_cues_still_do_not_fire() {
        assertThat(library("book me a flight to osaka"))
            .as("'book' as a verb is not the library")
            .isLessThan(1.0);
        assertThat(library("shelf that idea for now")).isLessThan(1.0);
        assertThat(library("letter me know when you're done")).isLessThan(1.0);
    }

    /** And unrelated requests must not drift onto the library. */
    @Test
    void ordinary_requests_do_not_reach_for_it() {
        assertThat(library("what is 17 times 3")).isLessThan(1.0);
        assertThat(library("how are you feeling right now")).isLessThan(1.0);
        assertThat(library("go to the workshop")).isLessThan(1.0);
        assertThat(library("what's the weather in osaka")).isLessThan(1.0);
    }

    /** Both library tools move together — they answer the same kind of question. */
    @Test
    void library_search_and_library_card_agree() {
        var q = "recite the poem for me";
        assertThat(RequestRelevance.score(q, "library_search", null))
            .isEqualTo(RequestRelevance.score(q, "library_card", null));
    }

    /** Degenerate input must not throw on the ranking path. */
    @Test
    void handles_null_and_blank() {
        assertThat(RequestRelevance.score(null, "library_card", null)).isZero();
        assertThat(RequestRelevance.score("", "library_card", null)).isZero();
        assertThat(RequestRelevance.score("poem", null, null)).isZero();
    }
}
