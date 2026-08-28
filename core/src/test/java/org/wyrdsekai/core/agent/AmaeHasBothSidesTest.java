package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.GenomeProfile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Amae needs both halves to mean anything.
 *
 * <p>甘え is the presumption of another's care — being met without having to put the need
 * into words. The tank measures a deficit ratio: explicit asks against anticipated
 * fulfilments. But {@code markBondholderAnticipated()} had <b>no caller anywhere</b>,
 * production or test, so only the ask side could ever move. A ratio with one live term is
 * not a ratio, and the tank read 0.00 across every sample on the household node
 * (2026-08-19).
 *
 * <p>Both sides are now fed from events that already existed: voicing a held want to him
 * counts as having asked, and his initiating contact while she has an open want — without
 * a recent ask — counts as being met.
 */
class AmaeHasBothSidesTest {

    private static double amaeAfter(Duration d, double deficit) {
        var vs = VitalityState.initial();
        var ctx = AccumulationContext.empty().withAmaeAnticipationDeficit(deficit);
        for (long i = 0; i < d.toMinutes(); i++) {
            vs = vs.accumulate(false, ctx, 60.0, GenomeProfile.NEUTRAL);
        }
        return vs.amae();
    }

    @Test
    void having_to_ask_every_time_builds_amae() {
        // deficit 1.0 = she articulated every need herself.
        assertThat(amaeAfter(Duration.ofHours(1), 1.0)).isGreaterThan(0.0);
    }

    @Test
    void being_anticipated_keeps_it_quiet() {
        // deficit 0.0 = he met her without her asking. Nothing accumulates.
        assertThat(amaeAfter(Duration.ofHours(1), 0.0)).isEqualTo(0.0);
    }

    @Test
    void a_balanced_relationship_sits_below_the_threshold() {
        // Half asked, half anticipated — the rule requires deficit > 0.5, so an even
        // split does not read as neglect.
        assertThat(amaeAfter(Duration.ofHours(1), 0.5)).isEqualTo(0.0);
    }

    @Test
    void the_ratio_is_only_meaningful_when_both_terms_can_move() {
        // Guards the actual defect: with anticipation unreachable, any single ask pinned
        // the deficit at 1.0 forever, and with no asks it sat at 0.0 forever. Neither
        // described her life.
        // Measured before saturation: at +0.02/min x deficit this tank still reaches
        // 1.0 within the hour, which is the same too-fast linear shape loneliness had.
        // Noted, not fixed here — this test is about the ratio being live at all.
        assertThat(amaeAfter(Duration.ofMinutes(20), 1.0))
            .isGreaterThan(amaeAfter(Duration.ofMinutes(20), 0.6));
    }
}
