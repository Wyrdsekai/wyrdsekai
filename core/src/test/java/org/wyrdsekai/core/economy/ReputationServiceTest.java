package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReputationServiceTest {

    private MutualCreditLedger ledger;
    private ReputationService service;

    @BeforeEach void setUp() {
        ledger = new MutualCreditLedger();
        service = new ReputationService(ledger);
    }

    @Test void unknown_entity_gets_neutral_reputation() {
        var rep = service.computeReputation("unknown");
        assertThat(rep.entityId()).isEqualTo("unknown");
        // Inactive entity gets lower uptime
        assertThat(rep.uptime()).isLessThanOrEqualTo(0.5);
        assertThat(rep.contribution()).isEqualTo(0.0);
    }

    @Test void active_trader_has_higher_reputation() {
        ledger.grant("alice", 100, "seed");
        ledger.transfer("alice", "bob", 50, "trade");

        var aliceRep = service.computeReputation("alice");
        var unknownRep = service.computeReputation("unknown");

        assertThat(aliceRep.composite()).isGreaterThan(unknownRep.composite());
        assertThat(aliceRep.contribution()).isGreaterThan(0.0);
    }

    @Test void balanced_trader_has_high_quality() {
        ledger.grant("alice", 100, "seed");
        ledger.grant("bob", 100, "seed");
        ledger.transfer("alice", "bob", 50, "trade1");
        ledger.transfer("bob", "alice", 50, "trade2");

        var rep = service.computeReputation("alice");
        // Balanced earned/spent should yield good quality
        assertThat(rep.quality()).isGreaterThanOrEqualTo(0.5);
    }

    @Test void positive_balance_improves_consistency() {
        ledger.grant("alice", 100, "seed");

        var rep = service.computeReputation("alice");
        // Positive balance relative to credit limit = good consistency
        assertThat(rep.consistency()).isGreaterThan(0.5);
    }

    @Test void negative_balance_reduces_consistency() {
        ledger.transfer("alice", "bob", 80, "big spend");

        var rep = service.computeReputation("alice");
        // Negative balance = lower consistency
        assertThat(rep.consistency()).isLessThan(0.5);
    }

    @Test void computeAll_returns_all_entities() {
        ledger.grant("alice", 100, "seed");
        ledger.transfer("alice", "bob", 30, "trade");

        var all = service.computeAll();
        assertThat(all).containsKeys("alice", "bob");
    }

    @Test void describe_with_no_entities_shows_message() {
        assertThat(service.describe()).contains("No reputation data");
    }

    @Test void describe_with_entities_shows_scores() {
        ledger.grant("alice", 100, "seed");
        var desc = service.describe();
        assertThat(desc).contains("Reputation Summary");
        assertThat(desc).contains("alice");
    }
}
