package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Doom-loop → behavior (2026-06-02): the Decide step discounts a verb the agent has
 * been repeating so it diversifies instead of grinding one action. Pins the penalty
 * curve: fresh = full weight, strictly decreasing as repeats climb, floored.
 */
class RecencyPenaltyTest {

    @Test
    void freshVerbKeepsFullWeight() {
        assertThat(CompanionActor.recencyPenalty(0)).isEqualTo(1.0);
    }

    @Test
    void penaltyStrictlyDecreasesThenFloors() {
        double p0 = CompanionActor.recencyPenalty(0);
        double p1 = CompanionActor.recencyPenalty(1);
        double p2 = CompanionActor.recencyPenalty(2);
        double p3 = CompanionActor.recencyPenalty(3);
        double p4 = CompanionActor.recencyPenalty(4);
        assertThat(p0).isGreaterThan(p1);
        assertThat(p1).isGreaterThan(p2);
        assertThat(p2).isGreaterThan(p3);
        assertThat(p3).isGreaterThan(p4);
        // Floors so a fresh alternative (full weight) reliably outscores a 4+×-repeated
        // verb even when the repeated one started heavier.
        assertThat(p4).isLessThanOrEqualTo(0.25);
        assertThat(CompanionActor.recencyPenalty(9)).isEqualTo(p4); // clamps, no underflow
    }

    @Test
    void negativeRepeatsTreatedAsFresh() {
        assertThat(CompanionActor.recencyPenalty(-3)).isEqualTo(1.0);
    }
}
