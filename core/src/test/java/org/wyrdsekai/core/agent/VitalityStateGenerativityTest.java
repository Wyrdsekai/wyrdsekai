package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — the generativity tank's coupling, in isolation.
 * Pure math on {@link VitalityState#accumulateGenerativity} + {@link
 * VitalityState#withGenerativity}; no actor, no inference.
 */
class VitalityStateGenerativityTest {

    private static final double DT = 600.0; // 10 minutes of ticks

    private static VitalityState base() {
        return VitalityState.initial();
    }

    @Test
    void rises_when_gaps_and_means_both_present() {
        var after = base().accumulateGenerativity(2, true, false, DT);
        assertThat(after.generativity()).isGreaterThan(base().generativity());
    }

    @Test
    void zero_gaps_means_no_pressure() {
        var after = base().accumulateGenerativity(0, true, false, DT);
        assertThat(after.generativity()).isEqualTo(base().generativity());
    }

    @Test
    void no_means_means_no_pressure() {
        var after = base().accumulateGenerativity(3, false, false, DT);
        assertThat(after.generativity()).isEqualTo(base().generativity());
    }

    @Test
    void repair_mode_suppresses_entirely() {
        var after = base().accumulateGenerativity(3, true, true, DT);
        assertThat(after.generativity()).isEqualTo(base().generativity());
    }

    @Test
    void rate_saturates_at_the_gap_cap() {
        // 3 gaps and 9 gaps must accumulate the SAME (cap is 3).
        var atCap = base().accumulateGenerativity(3, true, false, DT).generativity();
        var wayOver = base().accumulateGenerativity(9, true, false, DT).generativity();
        assertThat(wayOver).isEqualTo(atCap);
        // ...and more than a single gap.
        var oneGap = base().accumulateGenerativity(1, true, false, DT).generativity();
        assertThat(atCap).isGreaterThan(oneGap);
    }

    @Test
    void a_self_authored_act_drains_it() {
        var pressured = base().accumulateGenerativity(3, true, false, DT * 6); // ~1h
        assertThat(pressured.generativity()).isGreaterThan(0.0);
        // The act-relief: CompanionActor calls withGenerativity(current - drain).
        var relieved = pressured.withGenerativity(pressured.generativity() - 0.5);
        assertThat(relieved.generativity()).isLessThan(pressured.generativity());
    }

    @Test
    void generativity_survives_other_tank_updates() {
        // Regression for the silent-drop footgun: updating ANY other tank must
        // not reset generativity (the with-methods must pass it through).
        var g = base().accumulateGenerativity(3, true, false, DT * 6);
        double before = g.generativity();
        assertThat(before).isGreaterThan(0.0);
        var afterOtherUpdates = g.withEnergy(0.9).withSoothing(0.5).withEquanimity(0.4)
            .withStanding(0.2).withAllostaticLoad(0.1);
        assertThat(afterOtherUpdates.generativity()).isEqualTo(before);
    }

    @Test
    void round_trips_through_to_map_from_map() {
        var g = base().accumulateGenerativity(3, true, false, DT * 6);
        var restored = VitalityState.fromMap(g.toMap());
        assertThat(restored.generativity()).isEqualTo(g.generativity());
    }

    @Test
    void legacy_map_without_generativity_defaults_to_zero() {
        var m = base().toMap();
        m.remove("generativity");
        assertThat(VitalityState.fromMap(m).generativity()).isEqualTo(0.0);
    }
}
