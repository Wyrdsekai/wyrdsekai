package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Phase 1B: per-bondholder obligation ledger — record
 * help, compound by 1.05×/week (capped at 2×), discharge proportionally or wholesale.
 */
class ObligationLedgerTest {

    @Test
    void emptyLedgerReportsZero() {
        var l = new ObligationLedger();
        assertThat(l.totalDebt("alice", Instant.now())).isEqualTo(0.0);
        assertThat(l.maxDebt(Instant.now())).isEqualTo(0.0);
        assertThat(l.isEmpty()).isTrue();
    }

    // ── giri credit direction + net balance (2026-06-02) ──────────────────────

    @Test
    void creditRecordsAndNetsAgainstDebt() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.4, now);    // I owe alice
        l.recordCredit("alice", 0.3, now);  // alice owes me
        // net = debt − credit = +0.1 (still slightly debt-heavy)
        assertThat(l.netBalance("alice", now)).isCloseTo(0.1, within(1e-6));
        assertThat(l.totalCredit("alice", now)).isCloseTo(0.3, within(1e-6));
    }

    @Test
    void creditHeavyGivesNegativeNetAndImbalanceIsAbsolute() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordCredit("alex", 0.5, now);
        l.recordCredit("alex", 0.4, now);   // alex heavily owes me, I owe nothing
        assertThat(l.netBalance("alex", now)).isCloseTo(-0.9, within(1e-6));
        // The tank reads the MAGNITUDE of imbalance — credit-heavy registers as pressure too.
        assertThat(l.maxImbalance(now)).isCloseTo(0.9, within(1e-6));
    }

    @Test
    void balancedFlowReadsNearZeroImbalance() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("bo", 0.3, now);
        l.recordCredit("bo", 0.3, now);     // even give-and-take
        assertThat(l.netBalance("bo", now)).isCloseTo(0.0, within(1e-6));
        assertThat(l.maxImbalance(now)).isCloseTo(0.0, within(1e-6));
        assertThat(l.isEmpty()).isFalse();  // there IS reciprocal flow — not "empty"
    }

    @Test
    void maxImbalanceTakesLargestAcrossCounterparties() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("a", 0.2, now);        // |net| 0.2 (debt)
        l.recordCredit("b", 0.5, now);      // |net| 0.5 (credit)
        assertThat(l.maxImbalance(now)).isCloseTo(0.5, within(1e-6));
    }

    @Test
    void releaseCreditClearsItToBalance() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordCredit("alex", 0.5, now);
        l.clearCreditBondholder("alex");    // gracious "let it go"
        assertThat(l.totalCredit("alex", now)).isEqualTo(0.0);
        assertThat(l.maxImbalance(now)).isEqualTo(0.0);
    }

    @Test
    void creditSurvivesSnapshotRoundTrip() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.4, now);
        l.recordCredit("alex", 0.3, now);
        var restored = new ObligationLedger();
        restored.loadEntries(l.snapshotEntries());
        // Both directions round-trip through the prefixed-key persistence with no schema change.
        assertThat(restored.totalDebt("alice", now)).isCloseTo(0.4, within(1e-6));
        assertThat(restored.totalCredit("alex", now)).isCloseTo(0.3, within(1e-6));
        assertThat(restored.netBalance("alex", now)).isCloseTo(-0.3, within(1e-6));
    }

    @Test
    void recordHelpClampsMagnitudeToValidRange() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.05, now);  // below floor → clamps to 0.1
        l.recordHelp("alice", 0.8, now);   // above ceiling → clamps to 0.5
        assertThat(l.totalDebt("alice", now)).isCloseTo(0.6, within(1e-6));
    }

    @Test
    void debtCompoundsOverWeeks() {
        var l = new ObligationLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordHelp("alice", 0.4, origin);
        // After 1 week: 0.4 × 1.05 = 0.42
        var oneWeek = origin.plus(Duration.ofDays(7));
        assertThat(l.totalDebt("alice", oneWeek)).isCloseTo(0.42, within(1e-3));
    }

    @Test
    void compoundCapsAtTwoX() {
        var l = new ObligationLedger();
        var origin = Instant.parse("2025-01-01T00:00:00Z");
        l.recordHelp("alice", 0.3, origin);
        // After many years, the cap means total ≤ 2× original = 0.6.
        var farFuture = origin.plus(Duration.ofDays(365 * 5));
        assertThat(l.totalDebt("alice", farFuture)).isCloseTo(0.6, within(1e-6));
    }

    @Test
    void dischargeRemovesOldestDebtFirst() {
        var l = new ObligationLedger();
        var t0 = Instant.parse("2025-01-01T00:00:00Z");
        l.recordHelp("alice", 0.2, t0);
        l.recordHelp("alice", 0.3, t0.plus(Duration.ofMinutes(1)));
        // Discharge 0.2 — should remove first debt entirely.
        var actuallyDischarged = l.discharge("alice", 0.2, t0.plus(Duration.ofMinutes(2)));
        assertThat(actuallyDischarged).isCloseTo(0.2, within(1e-3));
        assertThat(l.totalDebt("alice", t0.plus(Duration.ofMinutes(2)))).isCloseTo(0.3, within(1e-3));
        assertThat(l.debtCount("alice")).isEqualTo(1);
    }

    @Test
    void clearBondholderRemovesAllDebt() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.5, now);
        l.recordHelp("alice", 0.4, now);
        l.clearBondholder("alice");
        assertThat(l.totalDebt("alice", now)).isEqualTo(0.0);
    }

    @Test
    void perBondholderTanksAreIndependent() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.4, now);
        l.recordHelp("bob", 0.3, now);
        assertThat(l.totalDebt("alice", now)).isCloseTo(0.4, within(1e-6));
        assertThat(l.totalDebt("bob", now)).isCloseTo(0.3, within(1e-6));
        assertThat(l.maxDebt(now)).isCloseTo(0.4, within(1e-6));
    }

    @Test
    void snapshotReturnsAllBondholders() {
        var l = new ObligationLedger();
        var now = Instant.now();
        l.recordHelp("alice", 0.4, now);
        l.recordHelp("bob", 0.2, now);
        var snap = l.snapshot(now);
        assertThat(snap).hasSize(2);
        assertThat(snap.get("alice")).isCloseTo(0.4, within(1e-6));
        assertThat(snap.get("bob")).isCloseTo(0.2, within(1e-6));
    }

    @Test
    void nullBondholderRecordIsNoOp() {
        var l = new ObligationLedger();
        l.recordHelp(null, 0.5, Instant.now());
        l.recordHelp("", 0.5, Instant.now());
        assertThat(l.isEmpty()).isTrue();
    }
}
