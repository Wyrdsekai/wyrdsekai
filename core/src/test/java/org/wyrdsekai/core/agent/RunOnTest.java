package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class RunOnTest {

    private static String words(int n, String word) {
        return String.join(" ", Collections.nCopies(n, word));
    }

    @Test
    void anOrdinaryReplyIsLeftAlone() {
        var reply = "That makes sense. Running around thins your attention at the edges. Sit a while; I'm here.";
        assertThat(RunOn.hasRunOn(reply)).isFalse();
        assertThat(RunOn.cut(reply)).isEqualTo(reply);
    }

    @Test
    void aReplyEndsAtTheLastFullStopBeforeASentenceThatNeverEnds() {
        var reply = "That makes sense. I'm glad you asked. " + words(70, "and") + " shopping";
        assertThat(RunOn.hasRunOn(reply)).isTrue();
        assertThat(RunOn.cut(reply)).isEqualTo("That makes sense. I'm glad you asked.");
    }

    @Test
    void aFirstSentenceThatRunsOnTrailsOffAtAClauseBoundary() {
        var reply = "You're still there after everything we've done today, I noticed that first thing — "
            + words(80, "still");
        var cut = RunOn.cut(reply);
        assertThat(cut).endsWith("…");
        assertThat(cut).startsWith("You're still there after everything we've done today, I noticed that first thing");
        assertThat(RunOn.hasRunOn(cut)).isFalse();
    }

    @Test
    void aFirstSentenceWithNoClauseBoundaryKeepsItsFirstSixtyWords() {
        var cut = RunOn.cut(words(200, "word"));
        assertThat(cut.split(" ")).hasSize(RunOn.MAX_WORDS);
        assertThat(cut).endsWith("…");
    }

    @Test
    void anUnderscoreListCountsEveryWord() {
        var list = "It shows. " + String.join("_", Collections.nCopies(70, "shown"));
        assertThat(RunOn.hasRunOn(list)).isTrue();
        assertThat(RunOn.cut(list)).isEqualTo("It shows.");
    }

    @Test
    void aListOnSeparateLinesIsNotARunOn() {
        var list = "Here is what I found:\n" + String.join("\n", Collections.nCopies(40, "- a short item of five words"));
        assertThat(RunOn.hasRunOn(list)).isFalse();
    }

    @Test
    void theHouseholdsRunOnIsCaught() {
        // Household node, 2026-09-29: a reply that ran to 5,266 characters (its opening and tail).
        var reply = "We were — though honestly the quiet parts felt just as good as anything else. I'm glad you "
            + "asked before checking in on us, that matters more than whatever answer comes next tonight or any "
            + "other evening when words might not be enough anyway because sometimes sitting still together says "
            + "what needs saying without needing translation into language either way whether it helps explain "
            + "things forward backward sideways wherever they go from here onward whenever time decides to turn "
            + "toward tomorrow again instead staying put in places like this where nothing has to prove itself "
            + "concluding ending finishing completing accomplishing achieving attaining reaching gaining winning "
            + "obtaining securing procuring acquiring purchasing buying shopping";
        assertThat(RunOn.hasRunOn(reply)).isTrue();
        assertThat(RunOn.cut(reply))
            .isEqualTo("We were — though honestly the quiet parts felt just as good as anything else.");
    }
}
