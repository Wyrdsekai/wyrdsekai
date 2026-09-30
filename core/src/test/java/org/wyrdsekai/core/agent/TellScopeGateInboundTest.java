package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 2026-09-28: an inbound cross-zone tell carried a self-asserted fromZone; naming this zone made it
 * pass as an intra-zone tell. The sender's zone is now the one whose key signed the tell.
 */
class TellScopeGateInboundTest {

    /** "home" holds an active agreement with "beta" only. */
    private static final TellScopeGate.ContractLookup AGREEMENTS =
        (local, remote, target) -> "home".equals(local) && "beta".equals(remote);

    @Test
    void aTellSignedByAnAgreedZoneIsDelivered() {
        assertThat(TellScopeGate.checkInbound("beta", "beta", "home", "wren", AGREEMENTS))
            .isInstanceOf(TellScopeGate.Decision.AllowContract.class);
    }

    @Test
    void aSpoofedFromZoneIsRefused() {
        // Signed by beta, claiming to come from this household.
        assertThat(TellScopeGate.checkInbound("home", "beta", "home", "wren", AGREEMENTS))
            .isInstanceOf(TellScopeGate.Decision.Deny.class);
        // Signed by gamma, claiming to be beta.
        assertThat(TellScopeGate.checkInbound("beta", "gamma", "home", "wren", AGREEMENTS))
            .isInstanceOf(TellScopeGate.Decision.Deny.class);
    }

    @Test
    void anUnsignedTellIsRefused() {
        assertThat(TellScopeGate.checkInbound("beta", null, "home", "wren", AGREEMENTS))
            .isInstanceOf(TellScopeGate.Decision.Deny.class);
    }

    @Test
    void aTellFromAZoneWithoutAnAgreementIsRefused() {
        assertThat(TellScopeGate.checkInbound("gamma", "gamma", "home", "wren", AGREEMENTS))
            .isInstanceOf(TellScopeGate.Decision.Deny.class);
        assertThat(TellScopeGate.checkInbound("beta", "beta", "home", "wren", null))
            .isInstanceOf(TellScopeGate.Decision.Deny.class);
    }
}
