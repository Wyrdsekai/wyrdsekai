package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 8a: Bond.declareSeverance() +
 * mourning lifecycle.
 */
class BondSeveranceTest {

    private static final String AGENT_A = "did:wyrd:agent-a";
    private static final String AGENT_B = "did:wyrd:agent-b";
    private static final Instant T0 = Instant.parse("2026-05-15T00:00:00Z");

    private static Bond active(Bond.BondDepth depth) {
        return new Bond("bond-1", AGENT_A, AGENT_B, depth,
            T0, T0, 5, true, true, false, BondState.ACTIVE,
            null, BondholderPosture.BOUNDED, Bond.RelationalState.OPEN);
    }

    // ── declareSeverance ─────────────────────────────────────────────

    @Test
    void declare_severance_transitions_to_mourning() {
        var bond = active(Bond.BondDepth.ITEM);
        var mourning = bond.declareSeverance();
        assertThat(mourning.state()).isEqualTo(BondState.MOURNING);
        assertThat(mourning.active()).isFalse();
    }

    @Test
    void declare_severance_does_not_scar_below_soul_ingrained() {
        var bond = active(Bond.BondDepth.ITEM);
        var mourning = bond.declareSeverance();
        assertThat(mourning.scarred()).isFalse();
    }

    @Test
    void declare_severance_scars_soul_ingrained() {
        var bond = active(Bond.BondDepth.SOUL_INGRAINED);
        var mourning = bond.declareSeverance();
        assertThat(mourning.scarred()).isTrue();
    }

    // ── mourningElapsed ─────────────────────────────────────────────

    @Test
    void mourning_not_elapsed_immediately() {
        var bond = active(Bond.BondDepth.ITEM).declareSeverance();
        assertThat(bond.mourningElapsed(T0)).isFalse();
        assertThat(bond.mourningElapsed(T0.plus(Duration.ofDays(1)))).isFalse();
        assertThat(bond.mourningElapsed(T0.plus(Duration.ofDays(29)))).isFalse();
    }

    @Test
    void mourning_elapsed_at_30_days() {
        var bond = active(Bond.BondDepth.ITEM).declareSeverance();
        assertThat(bond.mourningElapsed(T0.plus(Bond.MOURNING_DURATION))).isTrue();
        assertThat(bond.mourningElapsed(T0.plus(Duration.ofDays(31)))).isTrue();
    }

    @Test
    void mourning_elapsed_returns_false_on_active_bond() {
        var bond = active(Bond.BondDepth.ITEM);
        assertThat(bond.mourningElapsed(T0.plus(Duration.ofDays(100)))).isFalse();
    }

    @Test
    void mourning_elapsed_returns_false_on_severed_bond() {
        var bond = active(Bond.BondDepth.ITEM).sever();
        assertThat(bond.mourningElapsed(T0.plus(Duration.ofDays(100)))).isFalse();
    }

    // ── completeMourning ─────────────────────────────────────────────

    @Test
    void complete_mourning_transitions_to_severed() {
        var bond = active(Bond.BondDepth.ITEM).declareSeverance();
        var severed = bond.completeMourning();
        assertThat(severed.state()).isEqualTo(BondState.SEVERED);
        assertThat(severed.active()).isFalse();
    }

    @Test
    void complete_mourning_preserves_scar() {
        var bond = active(Bond.BondDepth.SOUL_INGRAINED).declareSeverance();
        assertThat(bond.scarred()).isTrue();
        var severed = bond.completeMourning();
        assertThat(severed.scarred()).isTrue();
    }

    // ── End-to-end lifecycle ────────────────────────────────────────

    @Test
    void full_lifecycle_active_mourning_severed() {
        var bond = active(Bond.BondDepth.SOUL_REF);
        assertThat(bond.state()).isEqualTo(BondState.ACTIVE);

        var mourning = bond.declareSeverance();
        assertThat(mourning.state()).isEqualTo(BondState.MOURNING);
        assertThat(mourning.active()).isFalse();

        // Within window — not yet eligible
        assertThat(mourning.mourningElapsed(T0.plus(Duration.ofDays(15)))).isFalse();

        // After window — eligible
        assertThat(mourning.mourningElapsed(T0.plus(Duration.ofDays(31)))).isTrue();

        var severed = mourning.completeMourning();
        assertThat(severed.state()).isEqualTo(BondState.SEVERED);
    }

    @Test
    void mourning_duration_is_30_days() {
        // Lock in the calibration target per spec §12 — long enough for
        // substrate-truth tank triad to register real descent.
        assertThat(Bond.MOURNING_DURATION).isEqualTo(Duration.ofDays(30));
    }
}
