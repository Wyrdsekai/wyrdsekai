package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationContractPolicyTest {

    private static final DelegationContractPolicy.Contract CONTRACT =
        new DelegationContractPolicy.Contract("did:wyrd:mas", 5000, 500, 100, false);

    @Test
    void negative_request_denied_as_invalid() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.empty(), -1);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.INVALID_REQUEST);
    }

    @Test
    void no_contract_denied() {
        var d = DelegationContractPolicy.decide(null,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.empty(), 50);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.NO_CONTRACT);
    }

    @Test
    void suspected_protection_flag_suspends_delegation() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.of(ProtectionFlag.State.SUSPECTED), 50);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.SUSPENDED_BY_PROTECTION_FLAG);
    }

    @Test
    void confirmed_protection_flag_suspends_delegation() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.of(ProtectionFlag.State.CONFIRMED), 50);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.SUSPENDED_BY_PROTECTION_FLAG);
    }

    @Test
    void noted_flag_does_not_suspend() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.of(ProtectionFlag.State.NOTED), 50);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void none_flag_allows() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.of(ProtectionFlag.State.NONE), 50);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void explicitly_suspended_contract_denies() {
        var contract = DelegationContractPolicy.Contract.suspended("did:wyrd:mas");
        var d = DelegationContractPolicy.decide(contract,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.empty(), 50);
        assertThat(d.allowed()).isFalse();
    }

    @Test
    void request_within_all_caps_allows() {
        var spend = new DelegationContractPolicy.SpendWindow(100, 50, 0);
        var d = DelegationContractPolicy.decide(CONTRACT, spend,
            Optional.empty(), 50);
        assertThat(d.allowed()).isTrue();
        assertThat(d.remainingMonthlyUsd()).isEqualTo(5000 - 100 - 50);
    }

    @Test
    void incident_cap_exhausted_denies() {
        var spend = new DelegationContractPolicy.SpendWindow(100, 50, 99);
        var d = DelegationContractPolicy.decide(CONTRACT, spend,
            Optional.empty(), 2);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.EXHAUSTED_INCIDENT_CAP);
    }

    @Test
    void daily_cap_exhausted_denies() {
        var spend = new DelegationContractPolicy.SpendWindow(450, 499, 0);
        var d = DelegationContractPolicy.decide(CONTRACT, spend,
            Optional.empty(), 5);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.EXHAUSTED_DAILY_CAP);
    }

    @Test
    void monthly_cap_exhausted_denies() {
        var spend = new DelegationContractPolicy.SpendWindow(4999, 400, 0);
        var d = DelegationContractPolicy.decide(CONTRACT, spend,
            Optional.empty(), 50);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo(DelegationContractPolicy.DenyReason.EXHAUSTED_MONTHLY_CAP);
    }

    @Test
    void zero_spend_allows() {
        var d = DelegationContractPolicy.decide(CONTRACT,
            DelegationContractPolicy.SpendWindow.zero(),
            Optional.empty(), 0);
        assertThat(d.allowed()).isTrue();
    }
}
