package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.ObligationLedger;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1C: persistence round-trip tests for
 * {@link ObligationLedgerPersistence}. Mirror of VitalityPersistenceTest shape.
 */
class ObligationLedgerPersistenceTest {

    private ObligationLedgerPersistence persistence;
    private Path dbPath;
    private String jdbcUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dbPath = tempDir.resolve("test.db");
        jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new ObligationLedgerPersistence(jdbcUrl);
    }

    @Test void empty_load_for_unknown_companion() {
        var loaded = persistence.loadAll("ghost");
        assertThat(loaded).isEmpty();
    }

    @Test void save_and_load_single_debt() {
        var ledger = new ObligationLedger();
        ledger.recordHelp("alice", 0.3, Instant.ofEpochSecond(1_700_000_000L));
        persistence.saveAll("companion-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("companion-1");
        assertThat(loaded).containsOnlyKeys("alice");
        assertThat(loaded.get("alice")).hasSize(1);
        assertThat(loaded.get("alice").get(0).originalMagnitude()).isEqualTo(0.3);
    }

    @Test void roundtrip_close_reopen_preserves_debt() {
        // Initial save
        var ledger = new ObligationLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("bob", 0.5, t);
        persistence.saveAll("companion-1", ledger.snapshotEntries());

        // Simulate restart: drop in-memory ledger, build a fresh one,
        // open a brand-new persistence handle.
        var freshPersistence = new ObligationLedgerPersistence(jdbcUrl);
        var loaded = freshPersistence.loadAll("companion-1");

        var freshLedger = new ObligationLedger();
        freshLedger.loadEntries(loaded);

        assertThat(freshLedger.totalDebt("bob", t)).isEqualTo(0.5);
        assertThat(freshLedger.debtCount("bob")).isEqualTo(1);
    }

    @Test void compounding_survives_restart_cycle() {
        // Help received at t0 with magnitude 0.5; query 1 week later
        var ledger = new ObligationLedger();
        Instant t0 = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("ally", 0.5, t0);
        persistence.saveAll("c-1", ledger.snapshotEntries());

        // Restart
        var freshLedger = new ObligationLedger();
        freshLedger.loadEntries(new ObligationLedgerPersistence(jdbcUrl).loadAll("c-1"));

        // After 1 week → 1.05× original; before cap (2×).
        Instant oneWeekLater = t0.plus(Duration.ofDays(7));
        double expected = 0.5 * 1.05;
        assertThat(freshLedger.totalDebt("ally", oneWeekLater))
            .isCloseTo(expected, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test void multiple_debts_across_bondholders() {
        var ledger = new ObligationLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("alice", 0.2, t);
        ledger.recordHelp("alice", 0.3, t.plusSeconds(60));
        ledger.recordHelp("bob",   0.4, t.plusSeconds(120));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).containsOnlyKeys("alice", "bob");
        assertThat(loaded.get("alice")).hasSize(2);
        assertThat(loaded.get("bob")).hasSize(1);
    }

    @Test void clear_via_empty_save() {
        var ledger = new ObligationLedger();
        ledger.recordHelp("alice", 0.3, Instant.ofEpochSecond(1_700_000_000L));
        persistence.saveAll("c-1", ledger.snapshotEntries());
        assertThat(persistence.count("c-1")).isEqualTo(1);

        // Clear ledger and re-save
        var empty = new ObligationLedger();
        persistence.saveAll("c-1", empty.snapshotEntries());
        assertThat(persistence.count("c-1")).isEqualTo(0);
    }

    @Test void discharge_then_save_persists_remainder() {
        var ledger = new ObligationLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("alice", 0.5, t);
        ledger.recordHelp("alice", 0.3, t.plusSeconds(60));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        ledger.discharge("alice", 0.5, t.plusSeconds(120));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded.get("alice")).hasSize(1);
        // Remainder should be ~0.3
        assertThat(loaded.get("alice").get(0).originalMagnitude())
            .isCloseTo(0.3, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test void clear_bondholder_persists() {
        var ledger = new ObligationLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("alice", 0.3, t);
        ledger.recordHelp("bob",   0.4, t);
        persistence.saveAll("c-1", ledger.snapshotEntries());

        ledger.clearBondholder("alice");
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        assertThat(loaded).containsOnlyKeys("bob");
    }

    @Test void multiple_companions_isolated() {
        var l1 = new ObligationLedger();
        var l2 = new ObligationLedger();
        l1.recordHelp("alice", 0.3, Instant.ofEpochSecond(1_700_000_000L));
        l2.recordHelp("alice", 0.5, Instant.ofEpochSecond(1_700_000_000L));
        persistence.saveAll("c-1", l1.snapshotEntries());
        persistence.saveAll("c-2", l2.snapshotEntries());

        var loaded1 = persistence.loadAll("c-1");
        var loaded2 = persistence.loadAll("c-2");
        assertThat(loaded1.get("alice").get(0).originalMagnitude()).isEqualTo(0.3);
        assertThat(loaded2.get("alice").get(0).originalMagnitude()).isEqualTo(0.5);
    }

    @Test void load_preserves_ordering_by_created_at() {
        var ledger = new ObligationLedger();
        Instant t = Instant.ofEpochSecond(1_700_000_000L);
        ledger.recordHelp("alice", 0.1, t);
        ledger.recordHelp("alice", 0.2, t.plusSeconds(10));
        ledger.recordHelp("alice", 0.3, t.plusSeconds(20));
        persistence.saveAll("c-1", ledger.snapshotEntries());

        var loaded = persistence.loadAll("c-1");
        var debts = loaded.get("alice");
        assertThat(debts).hasSize(3);
        assertThat(debts.get(0).originalMagnitude()).isEqualTo(0.1);
        assertThat(debts.get(1).originalMagnitude()).isEqualTo(0.2);
        assertThat(debts.get(2).originalMagnitude()).isEqualTo(0.3);
    }

    @Test void empty_snapshot_save_is_safe() {
        // Saving a never-populated ledger shouldn't throw or create rows.
        persistence.saveAll("c-1", Map.<String, List<ObligationLedger.DebtEntry>>of());
        assertThat(persistence.count("c-1")).isEqualTo(0);
    }
}
