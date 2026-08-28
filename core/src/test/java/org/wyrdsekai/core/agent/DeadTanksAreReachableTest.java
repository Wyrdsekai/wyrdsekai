package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two of her ten felt axes could never move.
 *
 * <p>Harmony rises only when {@code ctx.inConflictedRoom()} and Standing only when
 * {@code ctx.hostileEnvironment()}. Both rules are correct and genome-scaled; both inputs
 * were a hardcoded {@code false} at the single place that builds the context. Measured on
 * the household node across 200 samples (2026-08-19): Harmony sat at exactly 0.09 and
 * Standing at 0.00, never varying, while the four wired tanks ranged across their whole
 * span.
 *
 * <p>The signal existed the entire time — {@code hostilityScorer.score()} runs on every
 * utterance she hears — and fed only shell-mode activation.
 */
class DeadTanksAreReachableTest {

    private static double harmonyAfter(Duration d, boolean conflicted) {
        var vs = VitalityState.initial();
        var ctx = AccumulationContext.empty().withInConflictedRoom(conflicted);
        for (long i = 0; i < d.toMinutes(); i++) {
            vs = vs.accumulate(false, ctx, 60.0, GenomeProfile.NEUTRAL);
        }
        return vs.harmony();
    }

    private static double standingAfter(Duration d, boolean hostile) {
        var vs = VitalityState.initial();
        var ctx = AccumulationContext.empty().withHostileEnvironment(hostile);
        for (long i = 0; i < d.toMinutes(); i++) {
            vs = vs.accumulate(false, ctx, 60.0, GenomeProfile.NEUTRAL);
        }
        return vs.standing();
    }

    @Test
    void discord_in_the_room_actually_moves_harmony() {
        assertThat(harmonyAfter(Duration.ofHours(1), true))
            .as("the rule was never wrong — nothing ever set its input")
            .isGreaterThan(0.0);
    }

    @Test
    void a_peaceful_room_leaves_harmony_alone() {
        assertThat(harmonyAfter(Duration.ofHours(1), false)).isEqualTo(0.0);
    }

    @Test
    void hostility_aimed_at_her_actually_moves_standing() {
        assertThat(standingAfter(Duration.ofHours(1), true)).isGreaterThan(0.0);
    }

    @Test
    void an_unhostile_environment_leaves_standing_alone() {
        assertThat(standingAfter(Duration.ofHours(1), false)).isEqualTo(0.0);
    }
}
