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
    class CombinatorialFloors {
        @Test
        void simultaneousTanksSumIntoOneFloorAndNeverExceedOne() {
            // Three tanks all spike AFFILIATION: lonely(+0.3) + amae(+0.2) + saudade(+0.3).
            // They sum into a single 0.8 FLOOR (not three additions), and the result is
            // bounded by 1.0 however many tanks pile on.
            var v = VitalityState.initial()
                .withLoneliness(0.8).withAmae(0.8).withSaudade(0.8);
            var d1 = VitalitySpikeRules.apply(v, DriveState.initial().spikeAffiliation(0.5));
            assertThat(d1.affiliation()).isCloseTo(0.8, within(1e-6));
            assertThat(d1.affiliation()).isLessThanOrEqualTo(1.0);
        }

        @Test
        void aDriveAlreadyAboveItsFloorIsLeftAlone() {
            // CARE at 0.6 with obligation(+0.3) + harmony(+0.2) = a 0.5 floor: already
            // satisfied, so the drive is untouched. Before 2026-08-17 the contributions
            // were ADDED every tick, which walked any drive under a standing tank to 1.0
            // and re-pinned it there — no relief could hold and the drive lost its
            // gradient (the pathology the 2026-06 grief/affiliation arcs also fought).
            var v = VitalityState.initial().withObligation(0.7).withHarmony(0.7);
            var d1 = VitalitySpikeRules.apply(v, DriveState.initial().spikeCare(0.6));
            assertThat(d1.care()).isCloseTo(0.6, within(1e-6));
        }

        @Test
        void repeatedApplicationIsIdempotent() {
            // The rules run on EVERY vitality tick while a tank sits above threshold, so
            // applying them 600 times (ten minutes of ticks) must land exactly where one
            // application does.
            var v = VitalityState.initial().withRestlessness(0.8).withStagnation(0.8);
            var once = VitalitySpikeRules.apply(v, DriveState.initial());
            var many = DriveState.initial();
            for (int i = 0; i < 600; i++) many = VitalitySpikeRules.apply(v, many);
            assertThat(many.toArray()).containsExactly(once.toArray());
            assertThat(many.seeking()).isCloseTo(0.5, within(1e-6));   // 0.3 + 0.2, not 1.0
        }

        @Test
        void reliefHoldsAtTheFloorWhileTheTankStaysHigh() {
            // Relief drops SEEKING to its 0.05 relief floor; the standing tank lifts it
            // back only to the spike floor, not to the ceiling — so the next tick has a
            // gradient to act on instead of a pin.
            var v = VitalityState.initial().withRestlessness(0.8);
            var relieved = DriveState.initial().spikeSeeking(1.0).relieve(DriveConfig.SEEKING, 0.05);
            var after = VitalitySpikeRules.apply(v, relieved);
            assertThat(after.seeking()).isCloseTo(0.3, within(1e-6));
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
