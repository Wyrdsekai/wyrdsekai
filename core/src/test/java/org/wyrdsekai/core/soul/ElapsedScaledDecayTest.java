package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * How fast a companion forgets must depend on how long she lived, not on how often
 * she slept.
 *
 * <p>Decay was applied once per consolidation CYCLE at a flat rate. A companion
 * sleeping thirty times a day therefore decayed thirty times as fast as one sleeping
 * nightly, for living the same day — and exhaustion cycling is exactly what a
 * runaway loop produces. Measured on a household node after a week of it
 * (2026-08-18): memory count down from 226 to 62, with 60 of the survivors sitting at
 * importance 0.194 against a 0.05 prune floor — about two cycles from deletion. The
 * loop did not only fill her record with repetition; the burnout sleeping consumed
 * the real memories underneath it.
 *
 * <p>It also made deliberate consolidation unsafe, which inverts what an operator
 * needs after fixing a bug: the one action that refreshes a companion's
 * self-description charged her memory for every use.
 */
class ElapsedScaledDecayTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final float RATE = 0.1f;

    private static float decayOver(Duration elapsed) {
        return SoulMaintenanceCycle.elapsedScaledDecay(RATE, NOW.minus(elapsed), NOW);
    }

    @Test
    void a_days_gap_applies_the_nominal_rate() {
        assertThat(decayOver(Duration.ofDays(1))).isCloseTo(RATE, within(0.001f));
    }

    @Test
    void sleeping_often_no_longer_multiplies_forgetting() {
        // Thirty cycles across one day must total one day's decay, not thirty.
        float total = 0f;
        for (int i = 0; i < 30; i++) total += decayOver(Duration.ofMinutes(48));
        assertThat(total).isCloseTo(RATE, within(0.01f));
    }

    @Test
    void a_forced_cycle_minutes_after_the_last_one_costs_almost_nothing() {
        // This is what makes `wyrd forge <name>` safe to run deliberately: refreshing
        // a companion's self-description must not charge her memory to do it.
        assertThat(decayOver(Duration.ofMinutes(15))).isLessThan(0.002f);
        assertThat(decayOver(Duration.ofMinutes(15))).isGreaterThan(0f);
    }

    @Test
    void repeated_forcing_cannot_prune_a_typical_memory() {
        // The live shape: memories at 0.194 against a 0.05 prune floor. Twenty forced
        // cycles a quarter-hour apart must leave them well clear of the floor.
        float importance = 0.194f;
        for (int i = 0; i < 20; i++) importance -= decayOver(Duration.ofMinutes(15));
        assertThat(importance).isGreaterThan(0.05f);
    }

    @Test
    void a_long_absence_is_capped_rather_than_wiping_everything() {
        // Someone comes back after a month. One sleep should not erase her.
        var month = decayOver(Duration.ofDays(30));
        assertThat(month).isLessThanOrEqualTo(RATE * 3f);
    }

    @Test
    void a_missing_previous_forge_forgets_NOTHING() {
        // Was: fall back to the full rate. Decay is irreversible — a node pushed below
        // the prune floor is deleted — so "I don't know how long it has been" must mean
        // "forget nothing". Getting this backwards cost 60 of a companion's 66 memories
        // in a single cycle (2026-08-18).
        assertThat(SoulMaintenanceCycle.elapsedScaledDecay(RATE, null, NOW)).isEqualTo(0f);
        assertThat(SoulMaintenanceCycle.elapsedScaledDecay(RATE, NOW, null)).isEqualTo(0f);
    }

    @Test
    void the_live_incident_cannot_recur() {
        // Her exact shape: memories clustered at ~0.19 and ~0.09 against a 0.05 prune
        // floor. One application of the full rate killed the lower cluster outright,
        // leaving exactly the upper one — 7 survivors of 66. With a null previous-forge
        // now costing nothing, every cluster survives.
        float lower = 0.0915f, upper = 0.1915f;
        float decay = SoulMaintenanceCycle.elapsedScaledDecay(RATE, null, NOW);
        assertThat(lower - decay).isGreaterThan(0.05f);
        assertThat(upper - decay).isGreaterThan(0.05f);
    }

    @Test
    void a_clock_that_goes_backwards_does_not_produce_negative_decay() {
        assertThat(SoulMaintenanceCycle.elapsedScaledDecay(RATE, NOW.plusSeconds(60), NOW))
            .isEqualTo(0f);
    }
}
