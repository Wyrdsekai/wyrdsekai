package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.empathy.CoupledVitalitySystem;
import org.wyrdsekai.core.empathy.TankGenome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A line heard does not hand her back the energy she had a moment ago.
 *
 * <p>Hearing speech fills rapport, focus and alignment through the coupled tanks, and the coupled
 * values are then written back over hers, energy included. The coupled copy of her energy was
 * refreshed only on the tick, so a line heard inside that second undid whatever had been written
 * since: a forced 0.30 read 0.99 again until she slept (the sleep test, 2026-10-05), and a turn's
 * drain or a sleep's recovery would go the same way.
 */
class HearingALineKeepsHerEnergyTest {

    private static CoupledVitalitySystem coupledAt(double energy) {
        var coupled = new CoupledVitalitySystem(TankGenome.defaultGenome("did:key:z6MkTest"));
        coupled.set("energy", energy);
        return coupled;
    }

    @Test
    void energy_written_since_the_last_tick_survives_a_line_heard() {
        var coupled = coupledAt(0.99);                          // the copy, as the last tick left it
        var her = VitalityState.initial().withEnergy(0.30);     // set since then
        var after = CompanionActor.hearingSpeech(her, coupled);
        assertThat(after.energy()).as("not the stale 0.99").isCloseTo(0.30, within(0.03));
        assertThat(coupled.value("energy")).as("the copy now reads hers").isCloseTo(after.energy(), within(0.001));
    }

    @Test
    void a_turns_drain_is_not_refunded_by_the_next_line() {
        var coupled = coupledAt(0.60);
        var her = VitalityState.initial().withEnergy(0.60);
        her = her.withEnergy(her.energy() - 0.06);              // a tool used for someone, between ticks
        var after = CompanionActor.hearingSpeech(her, coupled);
        assertThat(after.energy()).isLessThan(0.58);
    }

    @Test
    void the_line_still_fills_rapport_focus_and_alignment() {
        var coupled = coupledAt(0.50);
        var her = VitalityState.initial().withEnergy(0.50);
        var after = CompanionActor.hearingSpeech(her, coupled);
        assertThat(after.rapport()).isGreaterThan(her.rapport());
        assertThat(after.focus()).isGreaterThan(her.focus());
    }
}
