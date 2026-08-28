package org.wyrdsekai.core.agent.affordance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tool that never reaches the menu cannot be called, however plainly it
 * answers the question.
 *
 * <p>Live, 2026-08-07 — "ari, can u look through my books/library and tell me
 * what the librarian told kestan about velsharas in glass tide?". The book was
 * indexed. {@code library_search} is the native action wired to the household's
 * own volumes. She called the CodeZaiku workshop's {@code library_shelves}
 * furnishing instead.</p>
 *
 * <p>Why: the book/library intent cues belonged only to {@code library_card}, a
 * <b>craftable</b> starter item. {@code library_search} had none, so it scored on
 * name overlap alone — "library" out of {library, search} = 0.5 — a dead tie with
 * any furnishing that merely has "library" in its name.</p>
 */
class LibraryRequestReachesLibraryToolsTest {

    /** The exact sentence that failed. */
    private static final String THE_UTTERANCE =
        "ari, can u look through my books/library and tell me what significant "
        + "thing did the librarian tell kestan about velsharas in glass tide?";

    private static double score(String request, String tool) {
        return RequestRelevance.score(request, tool, null);
    }

    /** THE case: the request must reach the tool built to answer it. */
    @Test
    void the_live_request_scores_library_search_at_full_confidence() {
        assertThat(score(THE_UTTERANCE, "library_search")).isEqualTo(1.0);
    }

    /** And it must beat the workshop furnishing that merely shares a word. */
    @Test
    void library_search_outranks_a_furnishing_that_shares_its_name() {
        assertThat(score(THE_UTTERANCE, "library_search"))
            .as("a 0.5 name-overlap tie is how she reached for the wrong shelf")
            .isGreaterThan(score(THE_UTTERANCE, "library_shelves"));
    }

    /** The craftable item keeps its cues — a companion who has one may still use it. */
    @Test
    void library_card_keeps_its_cues() {
        assertThat(score(THE_UTTERANCE, "library_card")).isEqualTo(1.0);
    }

    /** Plain book-shaped requests, not just the one that failed. */
    @Test
    void ordinary_book_requests_reach_library_search() {
        for (var request : new String[]{
                "what do my books say about sourdough",
                "check the library for anything on Sumerian myth",
                "is there a novel by Arden on the shelf",
                "which volume covers chapter nine",
                "anything in my reading about velsharas"}) {
            assertThat(score(request, "library_search"))
                .as("should reach the library for: " + request)
                .isEqualTo(1.0);
        }
    }

    /**
     * Nouns only. "search", "look" and "find" apply to every tool — cueing on
     * them is what once sent "find the sum of these numbers" to web search at
     * full confidence.
     */
    @Test
    void bare_verbs_do_not_cue_the_library() {
        assertThat(score("find the sum of these numbers", "library_search"))
            .isLessThan(1.0);
        assertThat(score("look at the calculator", "library_search"))
            .isLessThan(1.0);
        assertThat(score("search for a good ramen place", "library_search"))
            .isLessThan(1.0);
    }

    /** An arithmetic request must still belong to the calculator, not the library. */
    @Test
    void does_not_steal_requests_from_other_tools() {
        var request = "what is 17 times 3";
        assertThat(score(request, "calculator")).isEqualTo(1.0);
        assertThat(score(request, "library_search")).isLessThan(1.0);
    }

    /** Web-shaped requests stay web-shaped. */
    @Test
    void a_web_request_does_not_become_a_library_request() {
        var request = "google the news about the outage";
        assertThat(score(request, "searching_glass")).isEqualTo(1.0);
        assertThat(score(request, "library_search")).isLessThan(1.0);
    }
}
