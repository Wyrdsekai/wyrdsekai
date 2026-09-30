package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The register dial: her state decides how far the styled adapter is raised over the plain one.
 * Rested sits a little above the floor; play, creativity and seeking lift her; grief and low energy
 * bring her down to plain.
 */
class RegisterDialTest {

    private static final DriveState D0 = DriveState.initial();
    private static final VitalityState V0 = VitalityState.initial().withEnergy(1.0);

    @Test
    @DisplayName("rested and even, she sits a little above the floor")
    void rested() {
        assertThat(RegisterDial.scale(D0, V0, 0.5, 1.0)).isCloseTo(RegisterDial.REST, within(1e-9));
        assertThat(RegisterDial.scale(D0, V0, 0.5, 0.5)).as("the top scales it").isCloseTo(0.5 * RegisterDial.REST, within(1e-9));
    }

    @Test
    @DisplayName("play, creativity and seeking lift her toward the top")
    void lifted() {
        assertThat(RegisterDial.scale(D0.spikePlay(0.9), V0, 0.5, 1.0)).isGreaterThan(0.8);
        assertThat(RegisterDial.scale(D0.spikeSeeking(0.9).spikeCreativity(0.5), V0.withEnergy(0.9), 0.5, 1.0)).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("grief and exhaustion bring her down to plain")
    void lowered() {
        assertThat(RegisterDial.scale(D0.spikeGrief(0.9), V0.withEnergy(0.6), 0.5, 1.0)).isZero();
        assertThat(RegisterDial.scale(D0, V0.withEnergy(0.1), 0.5, 1.0)).isZero();
        assertThat(RegisterDial.scale(D0.spikeFrustration(0.9), V0, 0.5, 1.0)).isLessThan(RegisterDial.REST);
    }

    @Test
    @DisplayName("the jitter moves a turn a little either way and never past the ends")
    void jitter() {
        double low = RegisterDial.scale(D0, V0, 0.0, 1.0), mid = RegisterDial.scale(D0, V0, 0.5, 1.0), high = RegisterDial.scale(D0, V0, 1.0, 1.0);
        assertThat(low).isLessThan(mid); assertThat(high).isGreaterThan(mid);
        assertThat(high - low).isCloseTo(2 * RegisterDial.JITTER, within(1e-9));
        assertThat(RegisterDial.scale(D0.spikePlay(1.0).spikeCreativity(1.0).spikeSeeking(1.0), V0, 1.0, 0.7)).isEqualTo(0.7);
        assertThat(RegisterDial.scale(D0.spikeGrief(1.0), V0.withEnergy(0.0), 0.0, 0.7)).isZero();
        assertThat(RegisterDial.scale(D0.spikePlay(1.0), V0, 0.5, 0.0)).as("a dial with no top is off").isZero();
    }
}
