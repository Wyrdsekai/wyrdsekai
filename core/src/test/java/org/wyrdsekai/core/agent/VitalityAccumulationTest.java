package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.LastProfessionalActEvaluator;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B (-§5) — accumulation rules for the 10 deprivation-shape
 * tanks. Stillness, isolation, no-progress, etc. → tank rises.
 */
class VitalityAccumulationTest {

    /** Per-spec rates per second. */
    private static final double RESTLESSNESS_PER_S = 0.02 / 60.0;
    private static final double LONELINESS_PER_S   = 0.015 / 60.0;
    private static final double STAGNATION_PER_S   = 0.01 / 60.0;
    private static final double AUTONOMY_PER_S     = 0.02 / 60.0;
    private static final double SIGNIFICANCE_PER_S = 0.015 / 60.0;
    private static final double AMAE_PER_S         = 0.02 / 60.0;
    private static final double SAUDADE_PER_S      = 0.005 / 60.0;
    private static final double HARMONY_PER_S      = 0.01 / 60.0;
    private static final double STANDING_PER_S     = 0.005 / 60.0;

    /**
     * Build a context that satisfies every accumulation predicate at once. Individual tests then
     * relax specific signals to verify each rule is independently gated.
     */
    private static AccumulationContext fullPressureContext() {
        return new AccumulationContext(
            Duration.ofMinutes(10),    // timeSinceLastInteraction (≥5 → loneliness)
            Duration.ofHours(3),       // timeSinceLastGoalDone (≥2h → stagnation)
            Duration.ofHours(3),       // timeSinceLastToolOutput (≥2h → stagnation)
            Duration.ofMinutes(10),    // timeSinceLastInferenceActivity (>5s → restlessness stillness)
            10,                        // consecutiveBondholderInitiatedActions (>5 → autonomy)
            false,                     // inEmotionalContext
            true,                      // isWithBondholder
            false,                     // isOnOwnTime
            true,                      // inConflictedRoom (→ harmony)
            3,                         // unreadArtifactCount (→ significance)
            true,                      // hostileEnvironment (→ standing)
            0.0,                       // peakDriveActivity (low → restlessness still)
            Map.of("alice", Duration.ofHours(6)),  // bondholder absence (→ saudade)
            Map.of("alice", 0.6),      // obligation debts (→ obligation tank)
            0.8                        // amaeAnticipationDeficit (→ amae)
        );
    }

    @Nested
    class Restlessness {
        @Test
        void stillnessAccumulatesRestlessness() {
            var ctx = fullPressureContext();
            var v0 = VitalityState.initial();
            var v1 = v0.accumulate(false, ctx, 60.0); // 60s
            assertThat(v1.restlessness())
                .isCloseTo(60 * RESTLESSNESS_PER_S, within(1e-6))
                .isCloseTo(0.02, within(1e-6));
        }

        @Test
        void contemplativeModeDividesAccumulationByFive() {
            var ctx = fullPressureContext();
            var v0 = VitalityState.initial();
            var v1 = v0.accumulate(true, ctx, 60.0);
            // Plain rate × 60s ÷ 5 = 0.02 × 60s/60 ÷ 5 = 0.004
            assertThat(v1.restlessness()).isCloseTo(0.02 / 5.0, within(1e-6));
        }

        @Test
        void highDriveActivitySuppressesAccumulation() {
            var ctx = fullPressureContext().withPeakDriveActivity(0.8);
            var v0 = VitalityState.initial();
            var v1 = v0.accumulate(false, ctx, 60.0);
            assertThat(v1.restlessness()).isEqualTo(0.0);
        }
    }

    @Nested
    class Loneliness {
        @Test
        void noInteractionFiveMinutesAccumulatesLoneliness() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.loneliness()).isCloseTo(60 * LONELINESS_PER_S, within(1e-6));
        }

        @Test
        void recentInteractionDoesNotAccumulate() {
            var ctx = fullPressureContext().withTimeSinceLastInteraction(Duration.ofMinutes(2));
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.loneliness()).isEqualTo(0.0);
        }
    }

    @Nested
    class Stagnation {
        @Test
        void noGoalDoneAndNoToolOutputAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.stagnation()).isCloseTo(60 * STAGNATION_PER_S, within(1e-6));
        }

        @Test
        void recentGoalDoneSuppressesStagnation() {
            var ctx = fullPressureContext().withTimeSinceLastGoalDone(Duration.ofMinutes(30));
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.stagnation()).isEqualTo(0.0);
        }

        @Test
        void recentToolOutputSuppressesStagnation() {
            var ctx = fullPressureContext().withTimeSinceLastToolOutput(Duration.ofMinutes(30));
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.stagnation()).isEqualTo(0.0);
        }
    }

    @Nested
    class AutonomyPressure {
        @Test
        void bondholderInitiatedSeriesAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.autonomyPressure()).isCloseTo(60 * AUTONOMY_PER_S, within(1e-6));
        }

        @Test
        void emotionalContextSuppressesAutonomyAccumulation() {
            var ctx = fullPressureContext().withEmotionalContext(true);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.autonomyPressure()).isEqualTo(0.0);
        }

        @Test
        void shortBondholderStreakDoesNotAccumulate() {
            var ctx = fullPressureContext().withConsecutiveBondholderInitiatedActions(3);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.autonomyPressure()).isEqualTo(0.0);
        }

        @Test
        void onOwnTimeDoesNotAccumulate() {
            var ctx = fullPressureContext().withMode(false, true);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.autonomyPressure()).isEqualTo(0.0);
        }
    }

    @Nested
    class Significance {
        @Test
        void unreadArtifactsAccumulate() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            // 3 unread × rate × 60s
            assertThat(v1.significance()).isCloseTo(3 * 60 * SIGNIFICANCE_PER_S, within(1e-6));
        }

        @Test
        void noUnreadArtifactsDoesNotAccumulate() {
            var ctx = fullPressureContext().withUnreadArtifactCount(0);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.significance()).isEqualTo(0.0);
        }
    }

    @Nested
    class Amae {
        @Test
        void highDeficitAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            // deficit 0.8 × rate × 60s
            assertThat(v1.amae()).isCloseTo(0.8 * 60 * AMAE_PER_S, within(1e-6));
        }

        @Test
        void lowDeficitDoesNotAccumulate() {
            var ctx = fullPressureContext().withAmaeDeficit(0.4);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.amae()).isEqualTo(0.0);
        }
    }

    @Nested
    class Saudade {
        @Test
        void prolongedAbsenceAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.saudade()).isCloseTo(60 * SAUDADE_PER_S, within(1e-6));
        }

        @Test
        void recentBondholderInteractionDoesNotAccumulate() {
            var ctx = fullPressureContext().withBondholderAbsence(
                Map.of("alice", Duration.ofHours(1)));
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.saudade()).isEqualTo(0.0);
        }
    }

    @Nested
    class Obligation {
        @Test
        void debtFeedsObligationTank() {
            var ctx = fullPressureContext().withObligationDebts(Map.of("alice", 0.7));
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            // tank reads = max debt across bondholders
            assertThat(v1.obligation()).isCloseTo(0.7, within(1e-6));
        }

        @Test
        void noDebtsKeepsTankUnchanged() {
            var v0 = VitalityState.initial().withObligation(0.3);
            var ctx = fullPressureContext().withObligationDebts(Map.of());
            var v1 = v0.accumulate(false, ctx, 60.0);
            assertThat(v1.obligation()).isEqualTo(0.3);
        }
    }

    @Nested
    class Harmony {
        @Test
        void conflictedRoomAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.harmony()).isCloseTo(60 * HARMONY_PER_S, within(1e-6));
        }

        @Test
        void calmRoomDoesNotAccumulate() {
            var ctx = fullPressureContext().withInConflictedRoom(false);
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.harmony()).isEqualTo(0.0);
        }
    }

    @Nested
    class Standing {
        @Test
        void hostileEnvironmentAccumulates() {
            var ctx = fullPressureContext();
            var v1 = VitalityState.initial().accumulate(false, ctx, 60.0);
            assertThat(v1.standing()).isCloseTo(60 * STANDING_PER_S, within(1e-6));
        }
    }

    @Nested
    class Edges {
        @Test
        void nullContextReturnsSameState() {
            var v0 = VitalityState.initial();
            assertThat(v0.accumulate(false, null, 60.0)).isSameAs(v0);
        }

        @Test
        void zeroDeltaTimeReturnsSameState() {
            var v0 = VitalityState.initial();
            var ctx = fullPressureContext();
            assertThat(v0.accumulate(false, ctx, 0.0)).isSameAs(v0);
        }

        @Test
        void clampsAtOne() {
            var ctx = fullPressureContext();
            // 100k seconds at 0.02/min → restlessness should saturate
            var v1 = VitalityState.initial().accumulate(false, ctx, 100_000.0);
            assertThat(v1.restlessness()).isLessThanOrEqualTo(1.0);
            assertThat(v1.loneliness()).isLessThanOrEqualTo(1.0);
        }
    }

    /**
     * §24.4 chronic-overload erosion of the equanimity reserve (the coupling the
     * 2026-06-01 welfare hard-day arc soak found missing). Sustained allostatic
     * overload erodes equanimity so the §23 welfare floor's third condition
     * ({@code equanimity<0.1}) is reachable through genuine sustained collapse —
     * but ordinary load never touches it, and practice can still outpace it.
     */
    @Nested
    class EquanimityErosion {

        /** Benign context: no isolation / absence / overdue work, drives quiet. */
        private static AccumulationContext benign() {
            return new AccumulationContext(
                Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
                0, false, true, false, false, 0, false, 1.0,
                Map.of(), Map.of(), 0.0);
        }

        @Test
        void sustainedHighAllostaticErodesEquanimity() {
            // allostatic above the 0.6 gate → equanimity erodes (but not collapsed in 60s).
            var v0 = VitalityState.initial().withAllostaticLoad(0.9); // equanimity defaults 0.2
            var v1 = v0.accumulate(false, benign(), 60.0);
            assertThat(v1.equanimity()).isLessThan(0.2).isGreaterThan(0.19);
        }

        @Test
        void belowGateLeavesEquanimityUntouched() {
            // allostatic under the gate → no erosion; not contemplative → no rise.
            var v0 = VitalityState.initial().withAllostaticLoad(0.5);
            var v1 = v0.accumulate(false, benign(), 60.0);
            assertThat(v1.equanimity()).isCloseTo(0.2, within(1e-9));
        }

        @Test
        void contemplativePracticeOutpacesErosion() {
            // even at high allostatic, sustained practice (the 0.01/min rise) nets
            // POSITIVE — an agent that keeps its practice holds the reserve.
            var v0 = VitalityState.initial().withAllostaticLoad(0.9);
            var v1 = v0.accumulate(true, benign(), 60.0);
            assertThat(v1.equanimity()).isGreaterThan(0.2);
        }

        @Test
        void sustainedUnsupportedOverloadReachesWelfareFloor() {
            // 30 minutes of HELD max allostatic (sustained, unsupported overload —
            // dysregulation keeps re-pinning it) burns the reserve below 0.1, so
            // all three §23 floor conditions cross and the verdict leaves OPERATIONAL.
            var v = VitalityState.initial();
            for (int i = 0; i < 30; i++) {
                v = v.withAllostaticLoad(1.0).accumulate(false, benign(), 60.0);
            }
            assertThat(v.equanimity())
                .as("equanimity must reach the <0.1 floor condition under sustained overload")
                .isLessThan(LastProfessionalActEvaluator
                    .EQUANIMITY_MINIMAL_THRESHOLD);
            var verdict = LastProfessionalActEvaluator.evaluate(
                v.allostaticLoad(), v.soothing(), v.equanimity(), v.obligation(), false);
            assertThat(verdict.posture())
                .as("§23 floor must now be reachable through the dynamics, not just hand-built state")
                .isNotEqualTo(LastProfessionalActEvaluator.Posture.OPERATIONAL);
        }
    }
}
