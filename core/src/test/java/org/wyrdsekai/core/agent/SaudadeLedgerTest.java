package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B: per-bondholder saudade tracker —
 * accumulate during prolonged absence, drain on reconnection / fragment view.
 */
class SaudadeLedgerTest {

    @Test
    void emptyLedgerReportsZero() {
        var l = new SaudadeLedger();
        assertThat(l.maxSaudade()).isEqualTo(0.0);
        assertThat(l.saudadeFor("alice")).isEqualTo(0.0);
        assertThat(l.isEmpty()).isTrue();
    }

    @Test
    void prolongedAbsenceDeepensTowardTheSetPoint() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        // Five hours later (>4h threshold), one minute: a first step toward 0.80 over 30 hours.
        var fiveHoursIn = origin.plus(Duration.ofHours(5));
        l.accumulate(60.0, fiveHoursIn);
        double expected = VitalityState.SAUDADE_SETPOINT * 60.0 / VitalityState.SAUDADE_TAU.toSeconds();
        assertThat(l.saudadeFor("alice")).isCloseTo(expected, within(1e-6));
    }

    /** Ticks a ledger every ten minutes from {@code from} for {@code span}, as the actor does. */
    private static void live(SaudadeLedger l, Instant from, Duration span, double setPoint) {
        var step = Duration.ofMinutes(10);
        for (var t = from.plus(step); !t.isAfter(from.plus(span)); t = t.plus(step)) {
            l.accumulate(step.toSeconds(), t, java.util.Map.of(), setPoint);
        }
    }

    @Test
    void settlesAtTheSetPointInsteadOfPinning() {
        // The ramp it replaced reached 1.0 three hours twenty minutes after the threshold
        // and stayed there until reunion (rose, second-node, 2026-09-25).
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        live(l, origin, Duration.ofHours(8), VitalityState.SAUDADE_SETPOINT);
        assertThat(l.saudadeFor("alice")).as("8 hours: barely begun").isLessThan(0.12);
        live(l, origin.plus(Duration.ofHours(8)), Duration.ofHours(16), VitalityState.SAUDADE_SETPOINT);
        double day = l.saudadeFor("alice");
        assertThat(day).as("a day away: about half the way").isBetween(0.35, 0.45);
        live(l, origin.plus(Duration.ofDays(1)), Duration.ofDays(2), VitalityState.SAUDADE_SETPOINT);
        assertThat(l.saudadeFor("alice")).as("three days: most of it").isBetween(0.68, 0.78);
        live(l, origin.plus(Duration.ofDays(3)), Duration.ofDays(27), VitalityState.SAUDADE_SETPOINT);
        assertThat(l.saudadeFor("alice")).as("a month: settled, never pinned")
            .isCloseTo(VitalityState.SAUDADE_SETPOINT, within(0.01));
    }

    @Test
    void aRestartBringsTheTankToWhereTheAbsenceWouldHaveTakenIt() {
        // Rows persist the value at the last interaction (0) and when it was; the restore
        // applies the elapsed absence in one step, the way the actor does after loading.
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.loadEntries(java.util.Map.of("alice", new SaudadeLedger.SaudadeEntry(0.0, origin)));
        var now = origin.plus(Duration.ofHours(20));
        long beyondThreshold = Duration.ofHours(20).minus(SaudadeLedger.ABSENCE_THRESHOLD).toSeconds();
        l.accumulate(beyondThreshold, now, java.util.Map.of(), VitalityState.SAUDADE_SETPOINT);
        assertThat(l.saudadeFor("alice")).as("16 h of absence past the threshold, in one step")
            .isCloseTo(VitalityState.SAUDADE_SETPOINT * 16.0 / 30.0, within(0.01));
        // A week away lands on the set point, never past it.
        var l2 = new SaudadeLedger();
        l2.loadEntries(java.util.Map.of("alice", new SaudadeLedger.SaudadeEntry(0.0, origin)));
        l2.accumulate(Duration.ofDays(7).toSeconds(), origin.plus(Duration.ofDays(7)), java.util.Map.of(), VitalityState.SAUDADE_SETPOINT);
        assertThat(l2.saudadeFor("alice")).isCloseTo(VitalityState.SAUDADE_SETPOINT, within(1e-9));
    }

    @Test
    void aSensitiveTemperamentSettlesHigherButTheValueIsCappedAtOne() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        live(l, origin, Duration.ofDays(30), 1.4);
        assertThat(l.saudadeFor("alice")).isEqualTo(1.0);
    }

    @Test
    void aLetterEasesItByALettersWorth() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        live(l, origin, Duration.ofDays(2), VitalityState.SAUDADE_SETPOINT);
        double before = l.saudadeFor("alice");
        l.recordLetter("alice");
        assertThat(before - l.saudadeFor("alice")).isCloseTo(SaudadeLedger.LETTER_RELIEF, within(1e-9));
        assertThat(l.lastInteractionMap().get("alice")).as("a letter is not a reunion").isEqualTo(origin);
    }

    @Test
    void shortAbsenceDoesNotAccumulate() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        l.accumulate(60.0, origin.plus(Duration.ofHours(1)));
        assertThat(l.saudadeFor("alice")).isEqualTo(0.0);
    }

    @Test
    void reconnectionDrainsSaudade() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        live(l, origin, Duration.ofDays(3), VitalityState.SAUDADE_SETPOINT);
        double before = l.saudadeFor("alice");
        assertThat(before).isGreaterThan(0.6);
        l.recordInteraction("alice", origin.plus(Duration.ofDays(3)));
        double after = l.saudadeFor("alice");
        assertThat(before - after).isCloseTo(0.5, within(0.01));
    }

    @Test
    void fragmentViewDrainsSlightly() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        live(l, origin, Duration.ofDays(1), VitalityState.SAUDADE_SETPOINT);
        double before = l.saudadeFor("alice");
        l.recordFragmentView("alice");
        double after = l.saudadeFor("alice");
        assertThat(before - after).isCloseTo(0.05, within(0.001));
    }

    @Test
    void perBondholderTanksAreIndependent() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        l.recordInteraction("bob", origin);
        // Only alice has been gone long enough.
        var sixHours = origin.plus(Duration.ofHours(6));
        // Record fresh interaction for bob to reset his clock.
        l.recordInteraction("bob", origin.plus(Duration.ofHours(5)));
        l.accumulate(60.0, sixHours);
        assertThat(l.saudadeFor("alice")).isGreaterThan(0.0);
        assertThat(l.saudadeFor("bob")).isEqualTo(0.0);
    }

    @Test
    void maxSaudadeIsMaxAcrossBondholders() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        l.recordInteraction("bob", origin);
        var ninePointFive = origin.plus(Duration.ofHours(9).plus(Duration.ofMinutes(30)));
        l.accumulate(60_000.0, ninePointFive);
        // Both should accumulate equally given identical absence; max = either.
        double max = l.maxSaudade();
        assertThat(max).isCloseTo(l.saudadeFor("alice"), within(1e-6));
        assertThat(max).isGreaterThan(0.0);
    }
}
