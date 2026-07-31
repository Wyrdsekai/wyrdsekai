package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the go_to_room dead-loop guard (home-server two-companion live run 2026-07-18):
 * "I can't find a way to get to workshop from here right now" spoken three times
 * verbatim. The tracker is what lets the second failure go quiet and the ReAct
 * tool result escalate to "you already tried this".
 */
class NavFailureTrackerTest {

    @Test
    void firstFailureCountsOne_secondInsideWindowCountsTwo() {
        var t = new CompanionActor.NavFailureTracker();
        long now = 1_000_000L;
        assertThat(t.record("workshop", now)).isEqualTo(1);
        assertThat(t.record("workshop", now + 30_000)).isEqualTo(2);
        assertThat(t.record("workshop", now + 60_000)).isEqualTo(3);
    }

    @Test
    void windowExpiryResetsTheCount() {
        var t = new CompanionActor.NavFailureTracker();
        long now = 1_000_000L;
        t.record("workshop", now);
        assertThat(t.record("workshop",
            now + CompanionActor.NavFailureTracker.WINDOW_MS + 1)).isEqualTo(1);
    }

    @Test
    void countIsReadOnlyAndWindowAware() {
        var t = new CompanionActor.NavFailureTracker();
        long now = 1_000_000L;
        assertThat(t.count("workshop", now)).isZero();
        t.record("workshop", now);
        assertThat(t.count("workshop", now + 1_000)).isEqualTo(1);
        assertThat(t.count("workshop", now + 1_000)).isEqualTo(1); // no self-increment
        assertThat(t.count("workshop",
            now + CompanionActor.NavFailureTracker.WINDOW_MS + 1)).isZero();
    }

    @Test
    void targetsAreIndependentAndCaseInsensitive() {
        var t = new CompanionActor.NavFailureTracker();
        long now = 1_000_000L;
        t.record("Workshop", now);
        assertThat(t.count("workshop", now)).isEqualTo(1);   // same target, case folded
        assertThat(t.count("library", now)).isZero();        // different target untouched
        assertThat(t.record("  WORKSHOP ", now)).isEqualTo(2); // trims + folds
    }
}
