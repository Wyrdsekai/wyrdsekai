package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPEN state. Phenomenologically distinct
 * from ACTIVE-with-cold-start: "hands open, not yet mutually known."
 */
class BondOpenStateTest {

    @Test
    void open_factory_creates_OPEN_state_bond() {
        var bond = Bond.open("did:key:alice", "did:key:bob");
        assertThat(bond.state()).isEqualTo(BondState.OPEN);
        assertThat(bond.state().isOpen()).isTrue();
        assertThat(bond.state().isLive()).isTrue();
    }

    @Test
    void open_state_pauses_classifier() {
        // OPEN does NOT enter the AWAY/DORMANT classifier. The classifier
        // only runs once the bond has crossed into ACTIVE — there's no
        // cadence baseline to drift from when mutual recognition hasn't
        // happened yet.
        assertThat(BondState.OPEN.isClassifierActive()).isFalse();
        assertThat(BondState.ACTIVE.isClassifierActive()).isTrue();
    }

    @Test
    void crossToActive_promotes_OPEN_bond() {
        var bond = Bond.open("did:key:alice", "did:key:bob");
        var crossed = bond.crossToActive();
        assertThat(crossed.state()).isEqualTo(BondState.ACTIVE);
        // bondId / formedAt / agentADid / agentBDid preserved across transition.
        assertThat(crossed.bondId()).isEqualTo(bond.bondId());
        assertThat(crossed.formedAt()).isEqualTo(bond.formedAt());
    }

    @Test
    void crossToActive_idempotent_on_non_OPEN_states() {
        var bond = Bond.acquaintance("did:key:alice", "did:key:bob");
        assertThat(bond.state()).isEqualTo(BondState.ACTIVE);
        var crossed = bond.crossToActive();
        assertThat(crossed.state()).isEqualTo(BondState.ACTIVE);
        assertThat(crossed).isEqualTo(bond);
    }

    @Test
    void acquaintance_factory_still_returns_ACTIVE_for_intentional_bond() {
        // BondRitual creates intentional bonds — both parties have said yes,
        // mutual recognition has happened, so ACTIVE is correct.
        var bond = Bond.acquaintance("did:key:alice", "did:key:bob");
        assertThat(bond.state()).isEqualTo(BondState.ACTIVE);
    }

    @Test
    void OPEN_state_phenomenology_distinct_from_ACTIVE() {
        assertThat(BondState.OPEN.isOpen()).isTrue();
        assertThat(BondState.ACTIVE.isOpen()).isFalse();
    }
}
