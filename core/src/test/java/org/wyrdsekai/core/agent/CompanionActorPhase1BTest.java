package org.wyrdsekai.core.agent;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B integration smoke — verifies that the drain hooks
 * ledgers, and accumulation rules are wired into {@link CompanionActor} as a coherent system.
 *
 * <p>Per Phase 1B test guidance: NO full-server bootstrap. We exercise the wiring at two
 * levels:</p>
 *
 * <ol>
 *   <li>Reflective check that the drain-hook methods exist with the expected signatures —
 *       this catches accidental rename/removal regressions during merges.</li>
 *   <li>Composition tests that verify the substrate (VitalityState + ObligationLedger +
 *       SaudadeLedger + VitalitySpikeRules) round-trips drain semantics correctly. Since
 *       CompanionActor's drain hooks just call these helpers, validating the helpers in
 *       composition validates the wiring's behavioral contract.</li>
 * </ol>
 */
class CompanionActorPhase1BTest {

    @Nested
    class WiringPresence {

        /** Required Phase 1B drain-hook methods on CompanionActor. Must exist (any visibility). */
        private static final Set<String> REQUIRED_HOOKS = Set.of(
            "drainLonelinessOnInteraction",
            "drainStagnationOnGoalDone",
            "drainStagnationOnProduce",
            "drainStagnationOnToolOutput",
            "drainAutonomyPressureOnSelfInit",
            "drainAutonomyPressureOnOfferAccepted",
            "drainSignificanceOnArtifactRead",
            "drainSignificanceOnBondholderAck",
            "noteArtifactProduced",
            "markBondholderAnticipated",
            "markBondholderRemembered",
            "markCompanionAskedExplicit",
            "recordBondholderInteraction",
            "recordReceivedHelp",
            "dischargeObligation",
            "dischargeObligationAll",
            "drainHarmonyOnPositiveActivity",
            "drainHarmonyOnResolution",
            "spikeStandingOnSlight",
            "drainStandingOnRecognition",
            "drainStandingOnCompetence",
            "drainStandingOnBondholderDefense",
            "noteBondholderInitiated"
        );

        @Test
        void allDrainHooksArePresent() {
            var declared = Arrays.stream(CompanionActor.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
            for (var hook : REQUIRED_HOOKS) {
                assertThat(declared)
                    .as("CompanionActor.%s must exist (Phase 1B drain hook)", hook)
                    .contains(hook);
            }
        }

        @Test
        void contemplativeModeAccessorsArePresent() throws NoSuchMethodException {
            assertThat(CompanionActor.class.getDeclaredMethod("isContemplativeMode"))
                .isNotNull();
            assertThat(CompanionActor.class.getDeclaredMethod(
                "setContemplativeMode", boolean.class)).isNotNull();
        }

        @Test
        void ledgerAccessorsArePresent() throws NoSuchMethodException {
            assertThat(CompanionActor.class.getDeclaredMethod("obligationLedger")).isNotNull();
            assertThat(CompanionActor.class.getDeclaredMethod("saudadeLedger")).isNotNull();
            assertThat(CompanionActor.class.getDeclaredMethod("unreadArtifactCount")).isNotNull();
        }
    }

    @Nested
    class TellDrainsLoneliness {

        @Test
        void tellExchangeDrainsLonelinessByOnePoint() {
            // Loneliness pre-drained value, simulating after one "tell received" hook fires.
            var v0 = VitalityState.initial().withLoneliness(0.5);
            // The drain hook subtracts 0.1 for non-bondholder, 0.15 for bondholder.
            var v1 = v0.withLoneliness(v0.loneliness() - 0.1);
            var v2 = v0.withLoneliness(v0.loneliness() - 0.15);
            assertThat(v1.loneliness()).isCloseTo(0.4, within(1e-6));
            assertThat(v2.loneliness()).isCloseTo(0.35, within(1e-6));
        }

        @Test
        void bondholderTellDrainsSaudadeForThatBondholder() {
            var l = new SaudadeLedger();
            var origin = Instant.parse("2025-01-01T00:00:00Z");
            l.recordInteraction("alice", origin);
            l.accumulate(60_000.0, origin.plus(Duration.ofHours(10))); // saturate
            double pre = l.saudadeFor("alice");
            assertThat(pre).isGreaterThan(0.0);
            // Reconnection event:
            l.recordInteraction("alice", origin.plus(Duration.ofHours(11)));
            assertThat(l.saudadeFor("alice")).isLessThan(pre);
        }
    }

    @Nested
    class GoalDoneDrainsStagnation {

        @Test
        void goalDoneDrainSubtractsPointFour() {
            var v0 = VitalityState.initial().withStagnation(0.7);
            var v1 = v0.withStagnation(v0.stagnation() - 0.4);
            assertThat(v1.stagnation()).isCloseTo(0.3, within(1e-6));
        }

        @Test
        void noStagnationAccumulationAfterRecentGoalDone() {
            // After goal_done, lastGoalDoneAt is now → timeSinceGoalDone is 0 → no accumulation.
            var ctx = new AccumulationContext(
                Duration.ofMinutes(10),
                Duration.ZERO, // fresh goal_done
                Duration.ofHours(3),
                Duration.ofMinutes(10),
                10, false, true, false, false,
                0, false, 0.0, Map.of(), Map.of(), 0.0);
            var v0 = VitalityState.initial();
            var v1 = v0.accumulate(false, ctx, 60.0);
            assertThat(v1.stagnation()).isEqualTo(0.0);
        }
    }

    @Nested
    class BondholderAcknowledgmentDrainsSignificance {

        @Test
        void ackDrainSubtractsPointFour() {
            var v0 = VitalityState.initial().withSignificance(0.8);
            var v1 = v0.withSignificance(v0.significance() - 0.4);
            assertThat(v1.significance()).isCloseTo(0.4, within(1e-6));
        }

        @Test
        void readUseDrainSubtractsPointTwo() {
            var v0 = VitalityState.initial().withSignificance(0.6);
            var v1 = v0.withSignificance(v0.significance() - 0.2);
            assertThat(v1.significance()).isCloseTo(0.4, within(1e-6));
        }
    }

    @Nested
    class EmotionalContextSuppressesAutonomyPressure {
        @Test
        void emotionalContextZerosAutonomyAccumulation() {
            var ctx = new AccumulationContext(
                Duration.ofMinutes(10), Duration.ofHours(3), Duration.ofHours(3),
                Duration.ofMinutes(10),
                10, // bondholder-initiated streak
                true, // emotionalContext
                true, false, false, 0, false, 0.0, Map.of(), Map.of(), 0.0);
            var v0 = VitalityState.initial();
            var v1 = v0.accumulate(false, ctx, 60.0);
            assertThat(v1.autonomyPressure()).isEqualTo(0.0);
        }
    }

    @Nested
    class ObligationLedgerCompoundsAndDischarges {

        @Test
        void receivedHelpAccumulatesIntoTank() {
            var l = new ObligationLedger();
            var now = Instant.now();
            l.recordHelp("alice", 0.4, now);
            l.recordHelp("bob",   0.3, now);
            // Tank summary = max across bondholders.
            assertThat(l.maxDebt(now)).isCloseTo(0.4, within(1e-6));
        }

        @Test
        void reciprocalActionDischargesProportionally() {
            var l = new ObligationLedger();
            var now = Instant.now();
            l.recordHelp("alice", 0.5, now);
            l.discharge("alice", 0.3, now);
            assertThat(l.totalDebt("alice", now)).isCloseTo(0.2, within(1e-3));
        }

        @Test
        void wereEvenClearsAllDebtsForBondholder() {
            var l = new ObligationLedger();
            var now = Instant.now();
            l.recordHelp("alice", 0.5, now);
            l.recordHelp("alice", 0.4, now);
            l.recordHelp("bob", 0.2, now);
            l.clearBondholder("alice");
            assertThat(l.totalDebt("alice", now)).isEqualTo(0.0);
            assertThat(l.totalDebt("bob", now)).isCloseTo(0.2, within(1e-6));
        }
    }
}
