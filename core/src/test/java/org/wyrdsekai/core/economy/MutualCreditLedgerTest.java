package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutualCreditLedgerTest {

    private MutualCreditLedger ledger;

    @BeforeEach void setUp() {
        ledger = new MutualCreditLedger();
    }

    @Test void initial_balance_is_zero() {
        var balance = ledger.getBalance("alice");
        assertThat(balance.balance()).isEqualTo(0);
        assertThat(balance.creditLimit()).isEqualTo(100);
    }

    @Test void grant_credits() {
        ledger.grant("alice", 50, "welcome bonus");
        assertThat(ledger.getBalance("alice").balance()).isEqualTo(50);
        assertThat(ledger.getBalance("alice").totalEarned()).isEqualTo(50);
    }

    @Test void transfer_succeeds_with_positive_balance() {
        ledger.grant("alice", 100, "seed");
        var tx = ledger.transfer("alice", "bob", 30, "payment");

        assertThat(tx).isPresent();
        assertThat(ledger.getBalance("alice").balance()).isEqualTo(70);
        assertThat(ledger.getBalance("bob").balance()).isEqualTo(30);
    }

    @Test void transfer_succeeds_with_credit_limit() {
        // Alice has 0 balance but 100 credit limit
        var tx = ledger.transfer("alice", "bob", 50, "on credit");

        assertThat(tx).isPresent();
        assertThat(ledger.getBalance("alice").balance()).isEqualTo(-50);
        assertThat(ledger.getBalance("bob").balance()).isEqualTo(50);
    }

    @Test void transfer_fails_beyond_credit_limit() {
        var tx = ledger.transfer("alice", "bob", 200, "too much");

        assertThat(tx).isEmpty();
        assertThat(ledger.getBalance("alice").balance()).isEqualTo(0);
        assertThat(ledger.getBalance("bob").balance()).isEqualTo(0);
    }

    @Test void transfer_fails_for_zero_amount() {
        assertThat(ledger.transfer("alice", "bob", 0, "nothing")).isEmpty();
    }

    @Test void transfer_fails_for_self_transfer() {
        assertThat(ledger.transfer("alice", "alice", 10, "self")).isEmpty();
    }

    @Test void setCreditLimit_updates_limit() {
        ledger.setCreditLimit("alice", 500);
        assertThat(ledger.getBalance("alice").creditLimit()).isEqualTo(500);

        // Now alice can spend up to 500
        var tx = ledger.transfer("alice", "bob", 400, "big purchase");
        assertThat(tx).isPresent();
    }

    @Test void recentTransactions_returns_most_recent_first() {
        ledger.grant("alice", 100, "seed");
        ledger.transfer("alice", "bob", 20, "first");
        ledger.transfer("alice", "bob", 30, "second");

        var recent = ledger.recentTransactions(2);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).description()).isEqualTo("second");
        assertThat(recent.get(1).description()).isEqualTo("first");
    }

    @Test void transactionCount_tracks_all() {
        ledger.grant("alice", 100, "seed");
        ledger.transfer("alice", "bob", 10, "tx1");
        assertThat(ledger.transactionCount()).isEqualTo(2);
    }

    @Test void describe_empty_ledger() {
        assertThat(ledger.describe()).contains("No credit accounts");
    }

    @Test void describe_with_accounts() {
        ledger.grant("alice", 50, "seed");
        ledger.transfer("alice", "bob", 20, "payment");

        var desc = ledger.describe();
        assertThat(desc).contains("Mutual Credit Ledger");
        assertThat(desc).contains("alice");
        assertThat(desc).contains("bob");
    }
}
