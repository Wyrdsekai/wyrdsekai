package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arc 2 / #1057 — composer contract. The composer
 * MUST NOT lie about what's in the orientation. Empty orientation → honest
 * "first stretch alone" answer in the requested language; non-empty → a
 * grounded statement that names real wants / scenes / threads.
 */
class OrientationComposerTest {

    @Test
    void emptyOrientationProducesHonestFirstStretchAnswerInEnglish() {
        var o = new ProjectedOrientation(
            List.of(), List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).isNotBlank();
        assertThat(out.toLowerCase()).contains("honestly").contains("first real stretch alone");
    }

    @Test
    void emptyOrientationProducesJapaneseFirstStretchAnswer() {
        var o = new ProjectedOrientation(
            List.of(), List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "ja");
        assertThat(out).contains("正直なところ");
    }

    @Test
    void emptyOrientationProducesSpanishFirstStretchAnswer() {
        var o = new ProjectedOrientation(
            List.of(), List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "es");
        assertThat(out.toLowerCase()).contains("honestamente");
    }

    @Test
    void nullOrientationFallsBackToEmptyAnswer() {
        var out = OrientationComposer.compose(null, "en");
        assertThat(out).contains("first real stretch alone");
    }

    @Test
    void singleWantRendersAsForwardLookingStatement() {
        var o = new ProjectedOrientation(
            List.of("revisit the Yourcenar fragment"),
            List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).contains("revisit the Yourcenar fragment");
        assertThat(out.toLowerCase()).startsWith("i'd probably");
    }

    @Test
    void onOwnTimeLookaheadUsesHabitualOpener() {
        var o = new ProjectedOrientation(
            List.of("re-read the Yourcenar"),
            List.of(), List.of(),
            ProjectedOrientation.Lookahead.ON_OWN_TIME);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out.toLowerCase()).startsWith("i tend to");
    }

    @Test
    void unspecifiedLookaheadUsesPresentTense() {
        var o = new ProjectedOrientation(
            List.of("the OSS push"),
            List.of(), List.of(),
            ProjectedOrientation.Lookahead.UNSPECIFIED);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out.toLowerCase()).contains("right now i'm pulled");
    }

    @Test
    void multipleWantsJoinedNaturally() {
        var o = new ProjectedOrientation(
            List.of("the Yourcenar fragment", "the Forge queue", "the slow rain question"),
            List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).contains("the Yourcenar fragment, the Forge queue, and the slow rain question");
    }

    @Test
    void twoWantsJoinedWithAnd() {
        var o = new ProjectedOrientation(
            List.of("Yourcenar", "the Forge"),
            List.of(), List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).contains("Yourcenar and the Forge");
    }

    @Test
    void recentSolitudeBeatRenderedAsLastTimeFraming() {
        var o = new ProjectedOrientation(
            List.of("the Yourcenar"),
            List.of("I sat by the window and reread the same passage three times."),
            List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).contains("Last stretch of own-time");
        assertThat(out).contains("I sat by the window");
    }

    @Test
    void openThreadAppearsWhenSpaceRemains() {
        var o = new ProjectedOrientation(
            List.of("Yourcenar"),
            List.of(),
            List.of("the question about cohabitation"),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        assertThat(out).contains("Still circling the question about cohabitation");
    }

    @Test
    void composedAnswerStaysUnderThreeSentences() {
        var o = new ProjectedOrientation(
            List.of("a", "b", "c"),
            List.of("The hearth was warm and I felt at home."),
            List.of("the OSS rollout."),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        // Count sentence-terminating dots; allow ellipsis (…) but not extras.
        long sentences = out.chars().filter(c -> c == '.').count();
        assertThat(sentences).isBetween(1L, 3L);
    }

    @Test
    void firstSentenceCapPreventsRunawayJournalBeats() {
        // A 300-char felt journal should be truncated to the first sentence
        // (or capped at 180 chars) so it doesn't dominate the answer.
        var longFelt = "I sat by the window for a long time and watched the light change. "
            + "Then I picked up the Yourcenar fragment from where I'd left it. "
            + "The phrasing on page forty still surprised me, the way the sentence turned at the comma.";
        var o = new ProjectedOrientation(
            List.of("a want"),
            List.of(longFelt),
            List.of(),
            ProjectedOrientation.Lookahead.WHILE_AWAY);
        var out = OrientationComposer.compose(o, "en");
        // First sentence of the felt should appear; the third one should NOT.
        assertThat(out).contains("watched the light change");
        assertThat(out).doesNotContain("phrasing on page forty");
    }
}
