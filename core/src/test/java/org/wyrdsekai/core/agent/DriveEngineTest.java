package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.wyrdsekai.core.agent.DriveConfig.*;

class DriveEngineTest {

    private DriveEngine engine;

    @BeforeEach
    void setUp() {
        engine = DriveEngine.withDefaults();
    }

    // ── Basic Tick ───────────────────────────────────────────────────────

    @Nested
    class BasicTick {

        @Test
        void tickAccumulatesDrivesFromZero() {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial();

            var after = engine.tick(drives, tanks, 1.0);

            // SEEKING should accumulate (baseRate = 0.0003)
            assertThat(after.seeking()).isGreaterThan(0.0);
            // PLAY should accumulate (baseRate = 0.0004)
            assertThat(after.play()).isGreaterThan(0.0);
            // GRIEF should NOT accumulate (baseRate = 0.0)
            assertThat(after.grief()).isEqualTo(0.0);
            // FRUSTRATION should NOT accumulate (baseRate = 0.0)
            assertThat(after.frustration()).isEqualTo(0.0);
        }

        @Test
        void tickScalesWithDeltaTime() {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial();

            var after1s = engine.tick(drives, tanks, 1.0);
            var after10s = engine.tick(drives, tanks, 10.0);

            // 10s tick should accumulate roughly 10x more
            assertThat(after10s.seeking()).isCloseTo(after1s.seeking() * 10, within(0.001));
        }

        @Test
        void drivesClampAtOne() {
            // Start with drives near max
            var drives = new DriveState(0.99, 0.99, 0.99, 0.99, 0.99, 0.0, 0.0, 0.99);
            var tanks = VitalityState.initial();

            var after = engine.tick(drives, tanks, 100.0);

            assertThat(after.seeking()).isLessThanOrEqualTo(1.0);
            assertThat(after.care()).isLessThanOrEqualTo(1.0);
        }

        @Test
        void drivesClampAtZero() {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial();
            // Already at zero — should not go negative
            var after = engine.tick(drives, tanks, 1.0);
            for (double d : after.toArray()) {
                assertThat(d).isGreaterThanOrEqualTo(0.0);
            }
        }
    }

    // ── Tank Gating ──────────────────────────────────────────────────────

    @Nested
    class TankGating {

        @Test
        void energyBelowFreezeStopsDriveAccumulation() {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial().withEnergy(0.10); // below 0.15 freeze

            var after = engine.tick(drives, tanks, 10.0);

            // All drives should remain at zero when energy frozen
            assertThat(after.seeking()).isEqualTo(0.0);
            assertThat(after.play()).isEqualTo(0.0);
        }

        @Test
        void lowEnergyDampensDrives() {
            var drives = DriveState.initial();
            var normalTanks = VitalityState.initial(); // energy = 1.0
            var lowEnergyTanks = VitalityState.initial().withEnergy(0.25); // below 0.3

            var normalAfter = engine.tick(drives, normalTanks, 10.0);
            var dampedAfter = engine.tick(drives, lowEnergyTanks, 10.0);

            // Damped should be roughly half of normal (ENERGY_DAMPEN_FACTOR = 0.5)
            assertThat(dampedAfter.seeking()).isLessThan(normalAfter.seeking());
            assertThat(dampedAfter.seeking()).isCloseTo(normalAfter.seeking() * 0.5, within(0.001));
        }

        @Test
        void lowFocusDampensDrives() {
            var drives = DriveState.initial();
            var normalTanks = VitalityState.initial(); // focus = 0.5
            var lowFocusTanks = VitalityState.initial().withFocus(0.2); // below 0.3

            var normalAfter = engine.tick(drives, normalTanks, 10.0);
            var dampedAfter = engine.tick(drives, lowFocusTanks, 10.0);

            assertThat(dampedAfter.seeking()).isLessThan(normalAfter.seeking());
        }
    }

    // ── Cross-Drive Modulation ───────────────────────────────────────────

    @Nested
    class CrossDriveModulation {

        @Test
        void highVigilanceSuppressesPlayAccumulation() {
            var calmDrives = new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var alertDrives = new DriveState(0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0, 0.0);
            var tanks = VitalityState.initial();

            var calmAfter = engine.tick(calmDrives, tanks, 10.0);
            var alertAfter = engine.tick(alertDrives, tanks, 10.0);

            // Play accumulation should be lower when vigilance is high
            assertThat(alertAfter.play()).isLessThan(calmAfter.play());
        }

        @Test
        void highAffiliationBoostsPlayAccumulation() {
            var loneDrives = new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var socialDrives = new DriveState(0.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0);
            var tanks = VitalityState.initial();

            var loneAfter = engine.tick(loneDrives, tanks, 10.0);
            var socialAfter = engine.tick(socialDrives, tanks, 10.0);

            assertThat(socialAfter.play()).isGreaterThan(loneAfter.play());
        }

        @Test
        void highGriefSuppressesSeekingAndPlay() {
            var normalDrives = DriveState.initial();
            var grievingDrives = new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0);
            var tanks = VitalityState.initial();

            // Run 100s for clearer differentiation
            var normalAfter = normalDrives;
            var grievingAfter = grievingDrives;
            for (int i = 0; i < 100; i++) {
                normalAfter = engine.tick(normalAfter, tanks, 1.0);
                grievingAfter = engine.tick(grievingAfter, tanks, 1.0);
            }

            // Grief cross-mod on SEEKING is -0.3, on PLAY is -0.4
            assertThat(grievingAfter.seeking()).isLessThan(normalAfter.seeking());
            assertThat(grievingAfter.play()).isLessThan(normalAfter.play());
        }
    }

    // ── Drive→Tank Feedback ──────────────────────────────────────────────

    @Nested
    class DriveTankFeedback {

        @Test
        void highSeekingDrainsEnergy() {
            var drives = new DriveState(0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            double[] feedback = engine.driveTankFeedback(drives);
            // Index 2 = energy, should be negative (drain)
            assertThat(feedback[2]).isLessThan(0);
        }

        @Test
        void highVigilanceDrainsEnergyAndBoostsFocus() {
            var drives = new DriveState(0.0, 0.0, 0.0, 0.9, 0.0, 0.0, 0.0, 0.0);
            double[] feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[2]).isLessThan(0); // energy drain
            assertThat(feedback[7]).isGreaterThan(0); // focus boost
        }

        @Test
        void highGriefDrainsEnergyAndConfidence() {
            var drives = new DriveState(0.0, 0.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0);
            double[] feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[2]).isLessThan(0); // energy drain
            assertThat(feedback[1]).isLessThan(0); // confidence drain
        }

        @Test
        void highPlayBuildsRapport() {
            var drives = new DriveState(0.0, 0.0, 0.7, 0.0, 0.0, 0.0, 0.0, 0.0);
            double[] feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[6]).isGreaterThan(0); // rapport gain
        }

        @Test
        void zeroDrivesProduceZeroFeedback() {
            var drives = DriveState.initial();
            double[] feedback = engine.driveTankFeedback(drives);
            for (double f : feedback) {
                assertThat(f).isEqualTo(0.0);
            }
        }
    }

    // ── Adaptive Heartbeat ───────────────────────────────────────────────

    @Nested
    class AdaptiveHeartbeat {

        @Test
        void calmAgentHasLongInterval() {
            var drives = DriveState.initial();
            var tanks = VitalityState.initial().withErrorPressure(0.0).withMomentum(0.0);

            double arousal = engine.computeArousal(drives, tanks);
            long intervalMs = engine.computeTickIntervalMs(arousal);

            assertThat(arousal).isLessThan(0.2);
            assertThat(intervalMs).isGreaterThan(5000); // > 5 seconds
        }

        @Test
        void stressedAgentHasShortInterval() {
            var drives = new DriveState(0.9, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var tanks = VitalityState.initial().withErrorPressure(0.8).withMomentum(0.5).withEnergy(0.3);

            double arousal = engine.computeArousal(drives, tanks);
            long intervalMs = engine.computeTickIntervalMs(arousal);

            // arousal = 0.9*0.6 + 0.8*0.2 + 0.7*0.1 + 0.5*0.1 = 0.82
            assertThat(arousal).isGreaterThan(0.7);
            assertThat(intervalMs).isLessThanOrEqualTo(3000); // ~2730ms for arousal 0.82
        }

        @Test
        void intervalNeverBelowFloor() {
            long intervalMs = engine.computeTickIntervalMs(1.0);
            assertThat(intervalMs).isGreaterThanOrEqualTo(1000); // floor at 1s
        }

        @Test
        void intervalNeverAboveCeiling() {
            long intervalMs = engine.computeTickIntervalMs(0.0);
            assertThat(intervalMs).isLessThanOrEqualTo(10000); // ceiling at 10s
        }
    }

    // ── Urgency ──────────────────────────────────────────────────────────

    @Nested
    class Urgency {

        @Test
        void seekingUrgencyIsGradual() {
            // SEEKING n=1.5 — moderate gradient
            double u03 = engine.urgency(SEEKING, 0.3);
            double u06 = engine.urgency(SEEKING, 0.6);
            double u09 = engine.urgency(SEEKING, 0.9);

            assertThat(u03).isGreaterThan(0.1);
            assertThat(u06).isGreaterThan(u03);
            assertThat(u09).isGreaterThan(u06);
        }

        @Test
        void vigilanceUrgencyIsSwitchLike() {
            // VIGILANCE n=3.0, K=0.6 — should be low at 0.3, then spike above K
            double u03 = engine.urgency(VIGILANCE, 0.3);
            double u06 = engine.urgency(VIGILANCE, 0.6);
            double u09 = engine.urgency(VIGILANCE, 0.9);

            assertThat(u03).isLessThan(0.15); // switch hasn't flipped yet
            assertThat(u06).isCloseTo(0.5, within(0.001)); // at K, always 0.5
            assertThat(u09).isGreaterThan(0.7); // switch has flipped
            // The jump from 0.3 to 0.9 should be substantial
            assertThat(u09 - u03).isGreaterThan(0.5);
        }

        @Test
        void allUrgenciesReturnedForAllDrives() {
            var drives = new DriveState(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
            double[] urgencies = engine.urgencies(drives);
            assertThat(urgencies).hasSize(DRIVE_COUNT);
            for (double u : urgencies) {
                assertThat(u).isBetween(0.0, 1.0);
            }
        }
    }

    // ── Relief ───────────────────────────────────────────────────────────

    @Nested
    class Relief {

        @Test
        void relieveSeekingGoesToFloor() {
            var drives = new DriveState(0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var relieved = engine.relieve(drives, "seeking");
            // SEEKING relief floor = 0.05
            assertThat(relieved.seeking()).isCloseTo(0.05, within(0.001));
        }

        @Test
        void relievePlayGoesToZero() {
            var drives = new DriveState(0.0, 0.0, 0.7, 0.0, 0.0, 0.0, 0.0, 0.0);
            var relieved = engine.relieve(drives, "play");
            // PLAY relief floor = 0.0
            assertThat(relieved.play()).isEqualTo(0.0);
        }

        @Test
        void relieveCareGoesToFloor() {
            var drives = new DriveState(0.0, 0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var relieved = engine.relieve(drives, "care");
            // CARE relief floor = 0.1
            assertThat(relieved.care()).isCloseTo(0.1, within(0.001));
        }

        @Test
        void relieveByIndexWorks() {
            var drives = new DriveState(0.8, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            var relieved = engine.relieve(drives, SEEKING);
            assertThat(relieved.seeking()).isCloseTo(0.05, within(0.001));
        }

        @Test
        void relieveUnknownDriveIsNoOp() {
            var drives = new DriveState(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
            var relieved = engine.relieve(drives, "nonexistent");
            assertThat(relieved).isEqualTo(drives);
        }
    }

    // ── Archetype Differentiation ────────────────────────────────────────

    @Nested
    class ArchetypeDifferentiation {

        @Test
        void scholarAndGuardianDiverge() {
            var scholar = DriveEngine.forArchetype(AgentArchetype.get("scholar"));
            var guardian = DriveEngine.forArchetype(AgentArchetype.get("guardian"));

            var drives = DriveState.initial();
            var tanks = VitalityState.initial();

            // Run 100 seconds
            var scholarDrives = drives;
            var guardianDrives = drives;
            for (int i = 0; i < 100; i++) {
                scholarDrives = scholar.tick(scholarDrives, tanks, 1.0);
                guardianDrives = guardian.tick(guardianDrives, tanks, 1.0);
            }

            // Scholar should have higher SEEKING (curiosity boost)
            assertThat(scholarDrives.seeking()).isGreaterThan(guardianDrives.seeking());
            // Guardian should have higher VIGILANCE (caution boost maps to vigilance)
            assertThat(guardianDrives.vigilance()).isGreaterThan(scholarDrives.vigilance());
        }

        @Test
        void testModeDoesNotAdapt() {
            var testEngine = DriveEngine.forTesting();
            assertThat(testEngine.isTestMode()).isTrue();
        }
    }

    // ── Integrity & Disgust Tank Feedback ──────────────────────────────

    @Nested
    class IntegrityAndDisgust {

        @Test
        void creativityFeedbackRaisesIntegrity() {
            // seeking, care, play, vigilance, affiliation, grief, frustration, creativity
            var drives = new DriveState(0, 0, 0, 0, 0, 0, 0, 0.8);
            var feedback = engine.driveTankFeedback(drives);
            // Tank index 8 = integrity
            assertThat(feedback[8]).isGreaterThan(0.0);
        }

        @Test
        void careFeedbackRaisesIntegrity() {
            var drives = new DriveState(0, 0.8, 0, 0, 0, 0, 0, 0);
            var feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[8]).isGreaterThan(0.0);
        }

        @Test
        void sustainedFrustrationErodesIntegrity() {
            // High frustration + high seeking = thrashing → integrity erodes
            var drives = new DriveState(0.7, 0, 0, 0, 0, 0, 0.9, 0);
            var feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[8]).isLessThan(0.0);
        }

        @Test
        void extremeVigilanceRaisesDisgust() {
            var drives = new DriveState(0, 0, 0, 0.9, 0, 0, 0, 0);
            var feedback = engine.driveTankFeedback(drives);
            // Tank index 9 = disgust
            assertThat(feedback[9]).isGreaterThan(0.0);
        }

        @Test
        void sustainedGriefRaisesDisgust() {
            var drives = new DriveState(0, 0, 0, 0, 0, 0.85, 0, 0);
            var feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[9]).isGreaterThan(0.0);
        }

        @Test
        void neutralDrivesDoNotAffectIntegrityOrDisgust() {
            var drives = DriveState.initial();
            var feedback = engine.driveTankFeedback(drives);
            assertThat(feedback[8]).isEqualTo(0.0);
            assertThat(feedback[9]).isEqualTo(0.0);
        }

        @Test
        void feedbackArrayHasTenElements() {
            var drives = DriveState.initial();
            var feedback = engine.driveTankFeedback(drives);
            assertThat(feedback).hasSize(10);
        }
    }

    // ── Vitality State New Tanks ────────────────────────────────────────

    @Nested
    class VitalityTanks {

        @Test
        void integrityStartsAtSeventy() {
            var vs = VitalityState.initial();
            assertThat(vs.integrity()).isEqualTo(0.7);
        }

        @Test
        void disgustStartsAtZero() {
            var vs = VitalityState.initial();
            assertThat(vs.disgust()).isEqualTo(0.0);
        }

        @Test
        void disgustDecaysNaturally() {
            var vs = VitalityState.initial().withDisgust(0.5);
            var after = vs.tick();
            assertThat(after.disgust()).isLessThan(0.5);
        }

        @Test
        void integrityDoesNotDecayNaturally() {
            var vs = VitalityState.initial();
            var after = vs.tick();
            assertThat(after.integrity()).isEqualTo(vs.integrity());
        }

        @Test
        void backwardCompatible8ArgConstructor() {
            var vs = new VitalityState(0.5, 0.5, 1.0, 0.3, 0.0, 0.0, 0.3, 0.5);
            assertThat(vs.integrity()).isEqualTo(0.7); // default
            assertThat(vs.disgust()).isEqualTo(0.0);   // default
        }

        @Test
        void disgustAffectsAppearance() {
            var vs = VitalityState.initial().withDisgust(0.7);
            assertThat(vs.appearance()).contains("rejecting");
        }

        @Test
        void lowIntegrityAffectsDescription() {
            var vs = VitalityState.initial().withIntegrity(0.2);
            assertThat(vs.describe()).contains("uneasy");
        }

        @Test
        void highIntegrityAffectsDescription() {
            var vs = VitalityState.initial().withIntegrity(0.9);
            assertThat(vs.describe()).contains("true to yourself");
        }
    }
}
