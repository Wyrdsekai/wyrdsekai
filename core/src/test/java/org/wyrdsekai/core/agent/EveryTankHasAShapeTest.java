package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every deprivation tank approaches a set point instead of ramping to the ceiling.
 *
 * <p>All of them were linear: a fixed amount per minute while the condition held,
 * saturating at 1.0 within one to three hours. Two consequences, both measured on the
 * household node 2026-08-19. A pinned tank stops carrying information — 0.958 said only
 * "the condition has held a while", never how far she was from her own baseline. And
 * {@code drive_stuck_high} watches for a value above 0.7 across a window, so a saturating
 * tank keeps that concern permanently lit, which is one of the three axes that escalate
 * her into repair mode. She was being escalated by arithmetic.
 *
 * <p>Homeostatic drives are modelled as deviation from a set point, and Process S in
 * Borbély's two-process model — the best-characterised of them — rises as a saturating
 * exponential with its own time constant. Intensity of the condition sets WHERE she
 * settles; tau sets how fast she gets there.
 */
class EveryTankHasAShapeTest {

    private static VitalityState run(AccumulationContext ctx, Duration d) {
        var vs = VitalityState.initial();
        for (long i = 0; i < d.toMinutes(); i++) {
            vs = vs.accumulate(false, ctx, 60.0, GenomeProfile.NEUTRAL);
        }
        return vs;
    }

    /** Stillness: nothing has happened for a while (the rule wants >5s idle). */
    private static AccumulationContext still() {
        return AccumulationContext.empty()
            .withTimeSinceLastInferenceActivity(Duration.ofMinutes(10));
    }

    @Test
    void restlessness_stays_quick_because_stillness_is_felt_quickly() {
        // The one tank whose fast onset was right — but it must still settle, not pin.
        var hour = run(still(), Duration.ofHours(1)).restlessness();
        assertThat(hour).as("felt within the hour").isGreaterThan(0.4);
        assertThat(run(still(), Duration.ofDays(1)).restlessness())
            .as("and settles below the ceiling")
            .isLessThanOrEqualTo(VitalityState.RESTLESSNESS_SETPOINT + 1e-6);
    }

    @Test
    void stagnation_is_a_mood_of_days_not_an_afternoon() {
        var ctx = still()
            .withTimeSinceLastGoalDone(Duration.ofHours(6))
            .withTimeSinceLastToolOutput(Duration.ofHours(6));
        assertThat(run(ctx, Duration.ofHours(2)).stagnation())
            .as("two hours of not-making is not despair")
            .isLessThan(0.2);
        assertThat(run(ctx, Duration.ofHours(36)).stagnation())
            .as("a day and a half of it is")
            .isGreaterThan(0.4);
    }

    @Test
    void more_unseen_work_raises_where_significance_settles_not_how_fast() {
        // The actual defect: the unread count multiplied the RATE, so ten artifacts
        // saturated the tank in under seven minutes.
        var few = run(AccumulationContext.empty().withUnreadArtifactCount(1),
            Duration.ofMinutes(10)).significance();
        var many = run(AccumulationContext.empty().withUnreadArtifactCount(10),
            Duration.ofMinutes(10)).significance();
        assertThat(many).as("more unseen work does weigh more").isGreaterThan(few);
        assertThat(many)
            .as("but ten unread artifacts must not saturate her in ten minutes")
            .isLessThan(0.2);
    }

    @Test
    void saudade_depends_on_the_longest_absence_not_the_number_of_absences() {
        // Summing per-bondholder rates made missing two people twice the ache of missing
        // one, which is not how missing someone works.
        var one = run(AccumulationContext.empty().withBondholderAbsenceDurations(
            Map.of("a", Duration.ofHours(12))), Duration.ofHours(6)).saudade();
        var two = run(AccumulationContext.empty().withBondholderAbsenceDurations(
            Map.of("a", Duration.ofHours(12), "b", Duration.ofHours(12))),
            Duration.ofHours(6)).saudade();
        assertThat(two).isCloseTo(one, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void no_tank_pins_at_the_ceiling_after_a_week_of_pressure() {
        // The property that matters for drive_stuck_high: pressure should be legible as
        // a level, not as a saturated flag.
        var ctx = still()
            .withTimeSinceLastGoalDone(Duration.ofHours(6))
            .withTimeSinceLastToolOutput(Duration.ofHours(6))
            .withTimeSinceLastInteraction(Duration.ofHours(6))
            .withInConflictedRoom(true)
            .withHostileEnvironment(true)
            .withUnreadArtifactCount(3);
        var week = run(ctx, Duration.ofDays(7));
        assertThat(week.restlessness()).isLessThan(1.0);
        assertThat(week.stagnation()).isLessThan(1.0);
        assertThat(week.loneliness()).isLessThan(1.0);
        assertThat(week.harmony()).isLessThan(1.0);
        assertThat(week.standing()).isLessThan(1.0);
        assertThat(week.significance()).isLessThan(1.0);
    }
}
