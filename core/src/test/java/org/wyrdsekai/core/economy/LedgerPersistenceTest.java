package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPersistenceTest {

    private LedgerPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new LedgerPersistence(jdbcUrl);
    }

    @Test void save_and_load_transaction() {
        var tx = new MutualCreditLedger.Transaction("tx-1", "alice", "bob",
            50, "payment", Instant.ofEpochSecond(1700000000));
        persistence.saveTransaction(tx);

        var loaded = persistence.loadTransaction("tx-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().fromEntity()).isEqualTo("alice");
        assertThat(loaded.get().toEntity()).isEqualTo("bob");
        assertThat(loaded.get().amount()).isEqualTo(50);
    }

    @Test void transaction_not_found() {
        assertThat(persistence.loadTransaction("ghost")).isEmpty();
    }

    @Test void transactions_for_entity() {
        persistence.saveTransaction(new MutualCreditLedger.Transaction("tx-1", "alice", "bob",
            50, "p1", Instant.ofEpochSecond(1700000000)));
        persistence.saveTransaction(new MutualCreditLedger.Transaction("tx-2", "bob", "carol",
            30, "p2", Instant.ofEpochSecond(1700001000)));
        persistence.saveTransaction(new MutualCreditLedger.Transaction("tx-3", "carol", "alice",
            20, "p3", Instant.ofEpochSecond(1700002000)));

        assertThat(persistence.transactionsForEntity("alice")).hasSize(2); // tx-1 + tx-3
        assertThat(persistence.transactionsForEntity("bob")).hasSize(2);   // tx-1 + tx-2
    }

    @Test void transaction_count() {
        assertThat(persistence.transactionCount()).isEqualTo(0);
        persistence.saveTransaction(new MutualCreditLedger.Transaction("tx-1", "a", "b",
            10, "", Instant.now()));
        assertThat(persistence.transactionCount()).isEqualTo(1);
    }

    @Test void save_and_load_balance() {
        var balance = new CreditBalance("alice", 500, 1000, 800, 300);
        persistence.saveBalance("alice", balance);

        var loaded = persistence.loadBalance("alice");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().balance()).isEqualTo(500);
        assertThat(loaded.get().creditLimit()).isEqualTo(1000);
        assertThat(loaded.get().totalEarned()).isEqualTo(800);
        assertThat(loaded.get().totalSpent()).isEqualTo(300);
    }

    @Test void balance_not_found() {
        assertThat(persistence.loadBalance("ghost")).isEmpty();
    }

    @Test void balance_upsert() {
        persistence.saveBalance("alice", new CreditBalance("alice", 100, 500, 100, 0));
        persistence.saveBalance("alice", new CreditBalance("alice", 200, 500, 300, 100));

        var loaded = persistence.loadBalance("alice");
        assertThat(loaded.get().balance()).isEqualTo(200);
        assertThat(loaded.get().totalEarned()).isEqualTo(300);
    }
}
