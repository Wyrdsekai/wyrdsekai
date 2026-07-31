package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditBalanceTest {

    @Test void initial_has_zero_balance_and_default_limit() {
        var b = CreditBalance.initial("alice");
        assertThat(b.entityId()).isEqualTo("alice");
        assertThat(b.balance()).isEqualTo(0);
        assertThat(b.creditLimit()).isEqualTo(100);
    }

    @Test void canSpend_within_limit() {
        var b = CreditBalance.initial("alice");
        assertThat(b.canSpend(100)).isTrue(); // 0 - 100 = -100 >= -100
        assertThat(b.canSpend(101)).isFalse(); // 0 - 101 = -101 < -100
    }

    @Test void credit_increases_balance() {
        var b = CreditBalance.initial("alice").credit(50);
        assertThat(b.balance()).isEqualTo(50);
        assertThat(b.totalEarned()).isEqualTo(50);
    }

    @Test void debit_decreases_balance() {
        var b = CreditBalance.initial("alice").credit(100).debit(30);
        assertThat(b.balance()).isEqualTo(70);
        assertThat(b.totalSpent()).isEqualTo(30);
    }
}
