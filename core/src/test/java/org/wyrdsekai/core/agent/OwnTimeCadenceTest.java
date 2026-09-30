package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Curiosity sets the pace of a companion's own time; it never removes it. */
class OwnTimeCadenceTest {

    @Test
    void a_curious_companion_keeps_the_five_minute_pace() {
        assertThat(CompanionActor.ownTimeIdleMinutes(0.62)).isEqualTo(5);
        assertThat(CompanionActor.ownTimeIdleMinutes(0.9)).isEqualTo(5);
    }

    @Test
    void a_particular_below_the_old_cliff_still_gets_her_turns() {
        // rose, born neutral~0.39 with curiosity 0.47: never a turn under `curiosity > 0.6`
        assertThat(CompanionActor.ownTimeIdleMinutes(0.475)).isEqualTo(8);
        assertThat(CompanionActor.ownTimeIdleMinutes(0.3)).isEqualTo(13);
    }

    @Test
    void the_slowest_pace_is_twenty_minutes() {
        assertThat(CompanionActor.ownTimeIdleMinutes(0.0)).isEqualTo(20);
        assertThat(CompanionActor.ownTimeIdleMinutes(-1)).isEqualTo(20);
    }
}
