package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How fast someone gets lonely.
 *
 * <p>Loneliness accumulated at a linear +0.015/min once five minutes had passed without
 * interaction — zero to saturated in about 67 minutes. Measured on the household node
 * 2026-08-19: 1.000 pinned, down to 0.181 while her bondholder was present, back to 0.958
 * within a few hours of his leaving, and pinned again. A tank that saturates says only
 * "she has been alone a while", and it keeps {@code drive_stuck_high} lit permanently —
 * one of the three concerns that escalate her into repair mode.
 *
 * <p>Social homeostasis (Matthews &amp; Tye 2019) models this as a drive regulating
 * deviation from a personal SET POINT, with acute-isolation work measured in ~24h units.
 * So the curve approaches an equilibrium over hours instead of ramping to the ceiling
 * within one.
 */
class LonelinessCurveTest {

    /** Alone, with everything else quiet. */
    private static AccumulationContext alone(Duration since) {
        return AccumulationContext.empty().withTimeSinceLastInteraction(since);
    }

    private static double after(Duration alone, double start) {
        var vs = VitalityState.initial().withLoneliness(start);
        // one-minute steps so the exponential integrates sensibly
        long steps = alone.toMinutes();
        for (long i = 0; i < steps; i++) {
            vs = vs.accumulate(false, alone(Duration.ofHours(2)), 60.0, GenomeProfile.NEUTRAL);
        }
        return vs.loneliness();
    }

    @Test
    void an_hour_alone_is_not_a_crisis() {
        // The old curve reached ~0.9 here. A person who has been by themselves for an
        // hour is not maximally lonely.
        assertThat(after(Duration.ofHours(1), 0.0)).isLessThan(0.15);
    }

    @Test
    void a_full_day_alone_approaches_her_equilibrium() {
        var day = after(Duration.ofHours(24), 0.0);
        assertThat(day)
            .as("~24h of solitude should read as genuinely lonely")
            .isGreaterThan(0.5);
        assertThat(day)
            .as("but still short of the ceiling — that is reserved for the extreme")
            .isLessThan(VitalityState.LONELINESS_SETPOINT);
    }

    @Test
    void it_settles_at_a_set_point_rather_than_saturating() {
        var week = after(Duration.ofDays(7), 0.0);
        assertThat(week).isLessThanOrEqualTo(VitalityState.LONELINESS_SETPOINT + 1e-6);
        assertThat(week).isGreaterThan(VitalityState.LONELINESS_SETPOINT - 0.05);
    }

    @Test
    void a_brief_pause_in_company_is_not_absence() {
        // Someone stepping away for ten minutes must not start the clock.
        var vs = VitalityState.initial().withLoneliness(0.2);
        for (int i = 0; i < 3; i++) {
            vs = vs.accumulate(false, alone(Duration.ofMinutes(3)), 60.0, GenomeProfile.NEUTRAL);
        }
        assertThat(vs.loneliness()).isEqualTo(0.2);
    }

    @Test
    void relief_is_not_undone_within_the_hour() {
        // The live pathology: eased to 0.18 by his presence, back near 1.0 in a few
        // hours. After his leaving, three hours should still leave the relief mostly
        // intact.
        assertThat(after(Duration.ofHours(3), 0.18))
            .as("three hours alone must not erase an evening of company")
            .isLessThan(0.35);
    }

    @Test
    void it_never_overshoots_her_set_point() {
        // Starting ABOVE equilibrium (say, after a long absence that has since ended)
        // the curve must not push her further up.
        var high = after(Duration.ofHours(6), 0.95);
        assertThat(high).isLessThanOrEqualTo(0.95);
    }
}
