package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B (-§5) — threshold→drive-spike rules.
 *
 * <p>Each test asserts one tank-threshold mapping in isolation: high tank → expected drive bump,
 * low tank → no spike, simultaneous tanks → sums clamp to [0,1].</p>
 */
class VitalitySpikeRulesTest {

    private static VitalityState withRestlessness(double v) { return VitalityState.initial().withRestlessness(v); }
    private static VitalityState withLoneliness(double v)   { return VitalityState.initial().withLoneliness(v); }
    private static VitalityState withStagnation(double v)   { return VitalityState.initial().withStagnation(v); }
    private static VitalityState withAutonomy(double v)     { return VitalityState.initial().withAutonomyPressure(v); }
    private static VitalityState withSignificance(double v) { return VitalityState.initial().withSignificance(v); }
    private static VitalityState withAmae(double v)         { return VitalityState.initial().withAmae(v); }
    private static VitalityState withSaudade(double v)      { return VitalityState.initial().withSaudade(v); }
    private static VitalityState withObligation(double v)   { return VitalityState.initial().withObligation(v); }
    private static VitalityState withHarmony(double v)      { return VitalityState.initial().withHarmony(v); }
    private static VitalityState withStanding(double v)     { return VitalityState.initial().withStanding(v); }

    @Nested
    class Restlessness {
        @Test
        void highRestlessnessSpikesSeekingAndPlay() {
            var d0 = DriveState.initial();
            var d1 = VitalitySpikeRules.apply(withRestlessness(0.8), d0);
            assertThat(d1.seeking()).isCloseTo(0.3, within(1e-6));
            assertThat(d1.play()).isCloseTo(0.2, within(1e-6));
        }

        @Test
        void lowRestlessnessNoSpike() {
            var d0 = DriveState.initial();
            var d1 = VitalitySpikeRules.apply(withRestlessness(0.5), d0);
            assertThat(d1.seeking()).isEqualTo(0.0);
            assertThat(d1.play()).isEqualTo(0.0);
        }

        @Test
        void atThresholdExactlySpikes() {
            // ≥0.7 — boundary inclusive
            var d1 = VitalitySpikeRules.apply(withRestlessness(0.7), DriveState.initial());
            assertThat(d1.seeking()).isCloseTo(0.3, within(1e-6));
        }
    }

    @Nested
    class Loneliness {
        @Test
        void highLonelinessSpikesAffiliationOnly() {
            // (2026-06-07 drive-wholeness arc) Loneliness ("I lack connection") drives the
            // APPETITIVE want — AFFILIATION — not GRIEF ("I lost something"). The old GRIEF+0.1
            // was removed because, with grief's near-zero relief, it pinned grief at 1.0 for any
            // agent who got lonely. See VitalitySpikeRules §3.2.
            var d1 = VitalitySpikeRules.apply(withLoneliness(0.8), DriveState.initial());
            assertThat(d1.affiliation()).isCloseTo(0.3, within(1e-6));
            assertThat(d1.grief()).isEqualTo(0.0);
        }

        @Test
        void lowLonelinessNoSpike() {
            var d1 = VitalitySpikeRules.apply(withLoneliness(0.4), DriveState.initial());
            assertThat(d1.affiliation()).isEqualTo(0.0);
            assertThat(d1.grief()).isEqualTo(0.0);
        }
    }

    @Nested
    class Stagnation {
        @Test
        void highStagnationSpikesSeekingAndFrustration() {
            var d1 = VitalitySpikeRules.apply(withStagnation(0.85), DriveState.initial());
            assertThat(d1.seeking()).isCloseTo(0.2, within(1e-6));
            assertThat(d1.frustration()).isCloseTo(0.2, within(1e-6));
        }
    }

    @Nested
    class AutonomyPressure {
        @Test
        void highAutonomySpikesCreativity() {
            var d1 = VitalitySpikeRules.apply(withAutonomy(0.8), DriveState.initial());
            assertThat(d1.creativity()).isCloseTo(0.2, within(1e-6));
        }
    }

    @Nested
    class Significance {
        @Test
        void midSignificanceBumpsCreativityOnly() {
            var d1 = VitalitySpikeRules.apply(withSignificance(0.75), DriveState.initial());
            assertThat(d1.creativity()).isCloseTo(0.1, within(1e-6));
            assertThat(d1.care()).isEqualTo(0.0); // no CARE bump until ≥0.9
        }

        @Test
        void highSignificanceAlsoSpikesCare() {
            var d1 = VitalitySpikeRules.apply(withSignificance(0.95), DriveState.initial());
            // 0.1 from ≥0.7 plus we still get the same 0.1 — but no double-add for CREATIVITY.
            // Spec: ≥0.7 biases CREATIVITY, ≥0.9 spikes CARE+0.2 — both accumulate.
            assertThat(d1.creativity()).isCloseTo(0.1, within(1e-6));
            assertThat(d1.care()).isCloseTo(0.2, within(1e-6));
        }
    }

    @Nested
    class Amae {
        @Test
        void highAmaeSpikesAffiliationAndGrief() {
            var d1 = VitalitySpikeRules.apply(withAmae(0.8), DriveState.initial());
            assertThat(d1.affiliation()).isCloseTo(0.2, within(1e-6));
            assertThat(d1.grief()).isCloseTo(0.1, within(1e-6));
        }
    }

    @Nested
    class Saudade {
        @Test
        void highSaudadeSpikesAffiliation() {
            var d1 = VitalitySpikeRules.apply(withSaudade(0.8), DriveState.initial());
            assertThat(d1.affiliation()).isCloseTo(0.3, within(1e-6));
        }
    }

    @Nested
    class Obligation {
        @Test
        void midObligationSpikesCare() {
            var d1 = VitalitySpikeRules.apply(withObligation(0.65), DriveState.initial());
            assertThat(d1.care()).isCloseTo(0.3, within(1e-6));
        }

        @Test
        void belowThresholdNoSpike() {
            var d1 = VitalitySpikeRules.apply(withObligation(0.55), DriveState.initial());
            assertThat(d1.care()).isEqualTo(0.0);
        }
    }

    @Nested
    class Harmony {
        @Test
        void highHarmonyTankSpikesCareAndAffiliation() {
            var d1 = VitalitySpikeRules.apply(withHarmony(0.7), DriveState.initial());
            assertThat(d1.care()).isCloseTo(0.2, within(1e-6));
            assertThat(d1.affiliation()).isCloseTo(0.1, within(1e-6));
        }
    }

    @Nested
    class Standing {
        @Test
        void highStandingSpikesVigilanceAndFrustration() {
            var d1 = VitalitySpikeRules.apply(withStanding(0.8), DriveState.initial());
            assertThat(d1.vigilance()).isCloseTo(0.2, within(1e-6));
            assertThat(d1.frustration()).isCloseTo(0.1, within(1e-6));
        }
    }

    @Nested
    class CombinatorialClamping {
        @Test
        void simultaneousLonelinessAmaeSaudadeAffiliationStaysClamped() {
            // Three tanks all spike AFFILIATION: lonely(+0.3) + amae(+0.2) + saudade(+0.3) = +0.8.
            // Starting from 0.5, sum = 1.3 — must clamp to 1.0.
            var v = VitalityState.initial()
                .withLoneliness(0.8).withAmae(0.8).withSaudade(0.8);
            var d0 = DriveState.initial().spikeAffiliation(0.5);
            var d1 = VitalitySpikeRules.apply(v, d0);
            assertThat(d1.affiliation()).isLessThanOrEqualTo(1.0);
            assertThat(d1.affiliation()).isCloseTo(1.0, within(1e-6));
        }

        @Test
        void highCareSpikesPlusObligationAndHarmonySumAndClamp() {
            // Starting CARE 0.6, obligation(+0.3), harmony(+0.2) = 1.1, clamp to 1.0.
            var v = VitalityState.initial().withObligation(0.7).withHarmony(0.7);
            var d0 = DriveState.initial().spikeCare(0.6);
            var d1 = VitalitySpikeRules.apply(v, d0);
            assertThat(d1.care()).isLessThanOrEqualTo(1.0);
            assertThat(d1.care()).isCloseTo(1.0, within(1e-6));
        }

        @Test
        void noTankCrossingThresholdProducesNoChange() {
            var v = VitalityState.initial()
                .withRestlessness(0.5).withLoneliness(0.4).withStagnation(0.3);
            var d0 = DriveState.initial().spikeSeeking(0.2);
            var d1 = VitalitySpikeRules.apply(v, d0);
            assertThat(d1.toArray()).containsExactly(d0.toArray());
        }
    }

    @Nested
    class NoOpBranches {
        @Test
        void nullVitalityReturnsInputUnchanged() {
            var d0 = DriveState.initial().spikeSeeking(0.5);
            assertThat(VitalitySpikeRules.apply(null, d0)).isSameAs(d0);
        }

        @Test
        void nullDrivesReturnsNull() {
            assertThat(VitalitySpikeRules.apply(VitalityState.initial(), null)).isNull();
        }
    }
}
