package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B wiring. When a protection flag
 * against a bondholder reaches CONFIRMED, the bond auto-transitions to
 * DORMANT (protective distancing). This test exercises the pure-function
 * helper {@code Bond.autoDormantOnConfirmedFlag}.
 */
class BondAutoDormantTest {

    private ProtectionFlag confirmedFlag(String subject, String setter) {
        return new ProtectionFlag(subject, ProtectionFlag.State.CONFIRMED,
            "reason", setter, Instant.now(), Instant.now(), List.of(), null);
    }

    private ProtectionFlag suspectedFlag(String subject, String setter) {
        return new ProtectionFlag(subject, ProtectionFlag.State.SUSPECTED,
            "reason", setter, Instant.now(), Instant.now(), List.of(), null);
    }

    @Test
    void ACTIVE_bond_transitions_to_DORMANT_on_CONFIRMED_flag() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder");
        var flag = confirmedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isPresent();
        assertThat(result.get().state()).isEqualTo(BondState.DORMANT);
    }

    @Test
    void OPEN_bond_transitions_to_DORMANT_on_CONFIRMED_flag() {
        var bond = Bond.open("did:agent", "did:bondholder");
        var flag = confirmedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isPresent();
        assertThat(result.get().state()).isEqualTo(BondState.DORMANT);
    }

    @Test
    void SUSPECTED_flag_does_not_trigger_auto_DORMANT() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder");
        var flag = suspectedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isEmpty();
        assertThat(bond.state()).isEqualTo(BondState.ACTIVE); // unchanged
    }

    @Test
    void already_DORMANT_bond_not_re_transitioned() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder")
            .withState(BondState.DORMANT);
        var flag = confirmedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isEmpty();
    }

    @Test
    void SEVERED_bond_not_regressed() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder")
            .withState(BondState.SEVERED);
        var flag = confirmedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isEmpty();
    }

    @Test
    void MOURNING_bond_not_regressed() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder")
            .withState(BondState.MOURNING);
        var flag = confirmedFlag("did:bondholder", "did:setter");
        var result = Bond.autoDormantOnConfirmedFlag(bond, flag);
        assertThat(result).isEmpty();
    }

    @Test
    void null_bond_returns_empty() {
        var flag = confirmedFlag("did:bondholder", "did:setter");
        assertThat(Bond.autoDormantOnConfirmedFlag(null, flag)).isEmpty();
    }

    @Test
    void null_flag_returns_empty() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder");
        assertThat(Bond.autoDormantOnConfirmedFlag(bond, null)).isEmpty();
    }

    @Test
    void NOTED_flag_does_not_trigger_auto_DORMANT() {
        var bond = Bond.acquaintance("did:agent", "did:bondholder");
        var notedFlag = new ProtectionFlag("did:bondholder",
            ProtectionFlag.State.NOTED, "single observation",
            "did:setter", Instant.now(), Instant.now(), List.of(), null);
        var result = Bond.autoDormantOnConfirmedFlag(bond, notedFlag);
        assertThat(result).isEmpty();
    }
}
