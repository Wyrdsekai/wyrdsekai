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
    void prolongedAbsenceAccumulates() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        // Five hours later (>4h threshold), accumulate for 60s.
        var fiveHoursIn = origin.plus(Duration.ofHours(5));
        l.accumulate(60.0, fiveHoursIn);
        // Per-second rate = 0.005/60; 60s × that = 0.005.
        assertThat(l.saudadeFor("alice")).isCloseTo(0.005, within(1e-6));
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
        l.accumulate(60_000.0, origin.plus(Duration.ofHours(10))); // ~5 (clamps to 1.0 ideally)
        // Force tank to 0.6 by recording another interaction first does not reset; we just
        // saturate then trigger reconnection: -0.5.
        // Re-interact:
        var fivePointFive = origin.plus(Duration.ofHours(15));
        // Read pre-drain
        double before = l.saudadeFor("alice");
        l.recordInteraction("alice", fivePointFive);
        double after = l.saudadeFor("alice");
        assertThat(after).isLessThan(before);
        assertThat(before - after).isCloseTo(0.5, within(0.01));
    }

    @Test
    void fragmentViewDrainsSlightly() {
        var l = new SaudadeLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordInteraction("alice", origin);
        l.accumulate(60_000.0, origin.plus(Duration.ofHours(10)));
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
