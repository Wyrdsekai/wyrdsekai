package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where sleep brings her energy back to. The economy was tuned for a rested level of 0.65; the forge
 * wrote 0.5 for every tank it was not told about, and on that placeholder both household companions
 * lived under the "your energy is low" line nine hours in ten (2026-09-28).
 */
class RestedEnergyTest {

    @Test
    void aSilentGenomeRestsAtTheEconomysLevel() {
        assertThat(VitalityState.restedEnergy(null)).isEqualTo(VitalityState.RESTED_ENERGY);
        assertThat(VitalityState.restedEnergy(Map.of("focus", 0.5))).isEqualTo(VitalityState.RESTED_ENERGY);
    }

    @Test
    void theForgesPlaceholderIsNotAChoice() {
        // Exactly 0.5 is what the forge wrote when nobody said anything about energy.
        assertThat(VitalityState.restedEnergy(Map.of("energy", 0.5))).isEqualTo(0.65);
    }

    @Test
    void aChosenBaselineIsHers() {
        assertThat(VitalityState.restedEnergy(Map.of("energy", 0.58))).isEqualTo(0.58);
        assertThat(VitalityState.restedEnergy(Map.of("energy", 0.75))).isEqualTo(0.75);
        assertThat(VitalityState.restedEnergy(Map.of("energy", 0.49))).isEqualTo(0.49);
    }

    @Test
    void theEconomysArithmeticHoldsAtTheRestedLevel() {
        // A waking day of TICK_ENERGY_RATE alone, from a first sleep that fills 90% of the gap at
        // quality 0.8 (0.4 + 0.6 * 0.8 = 0.88), leaves room for a day's acts above the 0.15 threshold.
        double sleepThreshold = 0.15;
        double wake = sleepThreshold + (VitalityState.RESTED_ENERGY - sleepThreshold) * 0.90 * 0.88;
        double seventeenHours = 17 * 3600 * VitalityState.TICK_ENERGY_RATE; // negative
        assertThat(wake).isGreaterThan(0.54);
        assertThat(wake + seventeenHours - sleepThreshold).as("left for acts after 17 h awake")
            .isGreaterThan(0.15);
        // On the placeholder the same day left almost nothing.
        double placeholderWake = sleepThreshold + (0.5 - sleepThreshold) * 0.90 * 0.88;
        assertThat(placeholderWake + seventeenHours - sleepThreshold).isLessThan(0.05);
    }
}
