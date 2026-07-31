package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.BondState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B wiring.
 * Saudade ceiling per bond state: DORMANT/MOURNING/SEVERED cap the tank
 * so the agent isn't accumulating unbounded longing when the bond is
 * paused, in mourning, or closed.
 */
class SaudadeCeilingTest {

    @Test
    void ceilingForBondState_returns_canonical_values() {
        assertThat(SaudadeLedger.ceilingForBondState(BondState.OPEN)).isEqualTo(1.0);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.ACTIVE)).isEqualTo(1.0);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.AWAY)).isEqualTo(1.0);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.REACTIVATING)).isEqualTo(1.0);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.DORMANT)).isEqualTo(0.5);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.MOURNING)).isEqualTo(0.7);
        assertThat(SaudadeLedger.ceilingForBondState(BondState.SEVERED)).isEqualTo(0.3);
        assertThat(SaudadeLedger.ceilingForBondState(null)).isEqualTo(1.0);
    }

    @Test
    void accumulate_caps_at_provided_ceiling() {
        var ledger = new SaudadeLedger();
        var alice = "did:key:alice";
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        ledger.recordInteraction(alice, t0);

        // Advance past ABSENCE_THRESHOLD (4h) and accumulate a long time
        // with DORMANT ceiling of 0.5 — tank should cap at 0.5.
        var farFuture = t0.plus(Duration.ofDays(30));
        var ceilings = Map.of(alice, SaudadeLedger.ceilingForBondState(BondState.DORMANT));
        ledger.accumulate(Duration.ofDays(30).toSeconds(), farFuture, ceilings);

        assertThat(ledger.saudadeFor(alice))
            .as("DORMANT bond saudade must cap at 0.5")
            .isLessThanOrEqualTo(0.5);
        assertThat(ledger.saudadeFor(alice)).isGreaterThan(0.4); // accumulated a lot
    }

    @Test
    void accumulate_uncapped_for_ACTIVE_bond() {
        var ledger = new SaudadeLedger();
        var alice = "did:key:alice";
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        ledger.recordInteraction(alice, t0);
        var farFuture = t0.plus(Duration.ofDays(30));
        var ceilings = Map.of(alice, SaudadeLedger.ceilingForBondState(BondState.ACTIVE));
        ledger.accumulate(Duration.ofDays(30).toSeconds(), farFuture, ceilings);
        // 30 days * 0.005/min should saturate to 1.0
        assertThat(ledger.saudadeFor(alice)).isEqualTo(1.0);
    }

    @Test
    void transition_to_DORMANT_mid_tick_clamps_existing_tank() {
        var ledger = new SaudadeLedger();
        var alice = "did:key:alice";
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        ledger.recordInteraction(alice, t0);

        // First: 30 days at ACTIVE → tank goes to 1.0
        var phase1 = t0.plus(Duration.ofDays(30));
        ledger.accumulate(Duration.ofDays(30).toSeconds(), phase1,
            Map.of(alice, SaudadeLedger.ceilingForBondState(BondState.ACTIVE)));
        assertThat(ledger.saudadeFor(alice)).isEqualTo(1.0);

        // Now bond transitions to DORMANT. Even a zero-delta accumulate
        // call should clamp the stale tank down to 0.5.
        ledger.accumulate(0, phase1.plusSeconds(1),
            Map.of(alice, SaudadeLedger.ceilingForBondState(BondState.DORMANT)));
        assertThat(ledger.saudadeFor(alice))
            .as("DORMANT transition must clamp stale full tank")
            .isEqualTo(0.5);
    }

    @Test
    void back_compat_accumulate_without_ceilings_unchanged() {
        var ledger = new SaudadeLedger();
        var alice = "did:key:alice";
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        ledger.recordInteraction(alice, t0);
        var farFuture = t0.plus(Duration.ofDays(30));
        // Old 2-arg call — no ceilings — should saturate to 1.0 as before.
        ledger.accumulate(Duration.ofDays(30).toSeconds(), farFuture);
        assertThat(ledger.saudadeFor(alice)).isEqualTo(1.0);
    }

    @Test
    void SEVERED_bond_caps_at_minimal_residual() {
        var ledger = new SaudadeLedger();
        var alice = "did:key:alice";
        var t0 = Instant.parse("2026-05-17T10:00:00Z");
        ledger.recordInteraction(alice, t0);
        var farFuture = t0.plus(Duration.ofDays(30));
        ledger.accumulate(Duration.ofDays(30).toSeconds(), farFuture,
            Map.of(alice, SaudadeLedger.ceilingForBondState(BondState.SEVERED)));
        assertThat(ledger.saudadeFor(alice)).isLessThanOrEqualTo(0.3);
    }
}
