package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDelegationTest {

    private AgentDelegation delegation;

    @BeforeEach
    void setUp() {
        delegation = new AgentDelegation();
    }

    @Test void delegate_and_check_permission() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent",
            Set.of("speak", "move", "look"), null);

        assertThat(delegation.hasPermission("did:wyrd:z:agent", "speak")).isTrue();
        assertThat(delegation.hasPermission("did:wyrd:z:agent", "trade")).isFalse();
    }

    @Test void wildcard_permission() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent",
            Set.of("*"), null);

        assertThat(delegation.hasPermission("did:wyrd:z:agent", "anything")).isTrue();
    }

    @Test void get_principal() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent",
            Set.of("speak"), null);

        var principal = delegation.getPrincipal("did:wyrd:z:agent");
        assertThat(principal).isPresent();
        assertThat(principal.get()).isEqualTo("did:wyrd:z:human");
    }

    @Test void no_principal_for_unknown() {
        assertThat(delegation.getPrincipal("did:wyrd:z:unknown")).isEmpty();
    }

    @Test void delegations_for_principal() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent1",
            Set.of("speak"), null);
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent2",
            Set.of("move"), null);

        assertThat(delegation.delegationsFor("did:wyrd:z:human")).hasSize(2);
    }

    @Test void revoke_delegation() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent",
            Set.of("speak"), null);

        int revoked = delegation.revoke("did:wyrd:z:agent");
        assertThat(revoked).isEqualTo(1);
        assertThat(delegation.hasPermission("did:wyrd:z:agent", "speak")).isFalse();
    }

    @Test void expired_delegation_invalid() {
        delegation.delegate("did:wyrd:z:human", "did:wyrd:z:agent",
            Set.of("speak"), Instant.now().minusSeconds(3600)); // expired

        assertThat(delegation.hasPermission("did:wyrd:z:agent", "speak")).isFalse();
    }
}
