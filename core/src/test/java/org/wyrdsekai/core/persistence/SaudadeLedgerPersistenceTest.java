package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.SaudadeLedger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1C: persistence round-trip tests for
 * {@link SaudadeLedgerPersistence}.
 */
class SaudadeLedgerPersistenceTest {

    private SaudadeLedgerPersistence persistence;
    private String jdbcUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new SaudadeLedgerPersistence(jdbcUrl);
    }

    @Test void empty_load_for_unknown_companion() {
        assertThat(persistence.loadAll("ghost")).isEmpty();
    }

    @Test void save_and_load_single_bondholder() {
        var ledger = new SaudadeLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordInteraction("alice", t);
        // Force tank to a non-zero value via accumulation.
        ledger.accumulate(60.0 * 60.0 * 24, t.plus(Duration.ofHours(28))); // ~24h after 4h threshold
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).containsOnlyKeys("alice");
        assertThat(loaded.get("alice").currentValue()).isGreaterThan(0.0);
        assertThat(loaded.get("alice").lastInteractionAt()).isEqualTo(t);
    }

    @Test void roundtrip_close_reopen_preserves_state() {
        var ledger = new SaudadeLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordInteraction("alice", t);
        ledger.accumulate(3600 * 10, t.plus(Duration.ofHours(10)));
        double expectedValue = ledger.saudadeFor("alice");
        persistence.saveAll("c-1", ledger.snapshotEntries());

        // Restart simulation
        var fresh = new SaudadeLedger();
        fresh.loadEntries(new SaudadeLedgerPersistence(jdbcUrl).loadAll("c-1"));

        assertThat(fresh.saudadeFor("alice")).isEqualTo(expectedValue);
        assertThat(fresh.lastInteractionMap()).containsKey("alice");
        assertThat(fresh.lastInteractionMap().get("alice")).isEqualTo(t);
    }

    @Test void multiple_bondholders_independent() {
        var ledger = new SaudadeLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordInteraction("alice", t);
        ledger.recordInteraction("bob", t.plusSeconds(60));
        ledger.accumulate(3600 * 10, t.plus(Duration.ofHours(10)));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).containsKeys("alice", "bob");
    }

    @Test void interaction_drains_persists() {
        var ledger = new SaudadeLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordInteraction("alice", t);
        ledger.accumulate(3600 * 24, t.plus(Duration.ofHours(24)));
        double afterAccum = ledger.saudadeFor("alice");
        assertThat(afterAccum).isGreaterThan(0.0);

        // Reconnection drains -0.5
        ledger.recordInteraction("alice", t.plus(Duration.ofHours(25)));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var fresh = new SaudadeLedger();
        fresh.loadEntries(new SaudadeLedgerPersistence(jdbcUrl).loadAll("c-1"));
        // Drain of -0.5 should bring tank to 0 if afterAccum < 0.5.
        assertThat(fresh.saudadeFor("alice")).isLessThanOrEqualTo(afterAccum);
    }

    @Test void multiple_companions_isolated() {
        var l1 = new SaudadeLedger();
        var l2 = new SaudadeLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        l1.recordInteraction("alice", t);
        l2.recordInteraction("alice", t.plusSeconds(3600));
        persistence.saveAll("c-1", l1.snapshotEntries());
        persistence.saveAll("c-2", l2.snapshotEntries());

        var loaded1 = persistence.loadAll("c-1");
        var loaded2 = persistence.loadAll("c-2");
        assertThat(loaded1.get("alice").lastInteractionAt()).isEqualTo(t);
        assertThat(loaded2.get("alice").lastInteractionAt()).isEqualTo(t.plusSeconds(3600));
    }

    @Test void empty_save_clears_companion() {
        var ledger = new SaudadeLedger();
        ledger.recordInteraction("alice", Instant.ofEpochSecond(1_700_000_000L));
        persistence.saveAll("c-1", ledger.snapshotEntries());
        assertThat(persistence.count("c-1")).isEqualTo(1);

        var empty = new SaudadeLedger();
        persistence.saveAll("c-1", empty.snapshotEntries());
        assertThat(persistence.count("c-1")).isEqualTo(0);
    }
}
