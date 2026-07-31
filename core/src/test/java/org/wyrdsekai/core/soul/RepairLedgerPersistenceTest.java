package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 9a-Persist: round-trip + fail-clean tests for RepairLedger
 * persistence. The four-mode repair architecture leans on agents
 * remembering their prior acts — an unpersisted ledger means every
 * restart resets moral debt tracking.
 */
class RepairLedgerPersistenceTest {

    private static final String AGENT = "did:agent:alpha";
    private static final String OTHER = "did:bondholder:beta";
    private static final String OTHER2 = "did:bondholder:gamma";

    @BeforeEach
    void clean() { RepairLedger.get().clearForTests(); }

    @AfterEach
    void reset() { RepairLedger.get().clearForTests(); }

    @Test
    void persist_then_restore_recovers_single_entry(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "I named it");
        ledger.persist(file);

        ledger.clearForTests();
        assertThat(ledger.recent(AGENT, 10)).isEmpty();

        ledger.restore(file);
        var recovered = ledger.recent(AGENT, 10);
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0).kind())
            .isEqualTo(RepairLedger.Kind.ACKNOWLEDGE_HARM);
        assertThat(recovered.get(0).otherDid()).isEqualTo(OTHER);
        assertThat(recovered.get(0).detail()).isEqualTo("I named it");
    }

    @Test
    void persist_preserves_per_relationship_index(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "to beta");
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER2, "to gamma");
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "amends to beta");
        ledger.persist(file);

        ledger.clearForTests();
        ledger.restore(file);

        var withBeta = ledger.recentWith(AGENT, OTHER, 10);
        var withGamma = ledger.recentWith(AGENT, OTHER2, 10);
        assertThat(withBeta).hasSize(2);
        assertThat(withGamma).hasSize(1);
        assertThat(ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER)).isTrue();
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER)).isTrue();
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER2)).isFalse();
    }

    @Test
    void persist_preserves_newest_first_ordering(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "first");
        Thread.sleep(5);  // ensure distinct timestamps
        ledger.record(AGENT, RepairLedger.Kind.MAKE_AMENDS, OTHER, "second");
        Thread.sleep(5);
        ledger.record(AGENT, RepairLedger.Kind.RELEASE, OTHER, "third");
        ledger.persist(file);

        ledger.clearForTests();
        ledger.restore(file);

        var recovered = ledger.recent(AGENT, 10);
        assertThat(recovered).hasSize(3);
        // Newest-first
        assertThat(recovered.get(0).detail()).isEqualTo("third");
        assertThat(recovered.get(1).detail()).isEqualTo("second");
        assertThat(recovered.get(2).detail()).isEqualTo("first");
    }

    @Test
    void persist_preserves_multi_agent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        var ledger = RepairLedger.get();
        var agentB = "did:agent:bravo";
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "alpha->beta");
        ledger.record(agentB, RepairLedger.Kind.MAKE_AMENDS, OTHER, "bravo->beta");
        ledger.persist(file);

        ledger.clearForTests();
        ledger.restore(file);

        assertThat(ledger.recent(AGENT, 10)).hasSize(1);
        assertThat(ledger.recent(agentB, 10)).hasSize(1);
        assertThat(ledger.recent(AGENT, 10).get(0).detail()).isEqualTo("alpha->beta");
        assertThat(ledger.recent(agentB, 10).get(0).detail()).isEqualTo("bravo->beta");
    }

    @Test
    void restore_of_missing_file_is_silent_noop() {
        var ledger = RepairLedger.get();
        var missing = Path.of("/nonexistent/ledger.json");
        ledger.restore(missing);  // must not throw
        assertThat(ledger.recent(AGENT, 10)).isEmpty();
    }

    @Test
    void restore_of_null_path_is_silent_noop() {
        var ledger = RepairLedger.get();
        ledger.restore(null);
        assertThat(ledger.recent(AGENT, 10)).isEmpty();
    }

    @Test
    void restore_of_corrupt_file_fails_clean_with_empty_ledger(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("corrupt.json");
        Files.writeString(file, "{this is not json at all}}}");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "pre-corrupt");
        ledger.restore(file);
        // Corrupt restore must clear state, not preserve pre-corrupt data
        // (otherwise we'd have a half-restored phantom state).
        assertThat(ledger.recent(AGENT, 10))
            .as("corrupt restore must fail-clean to empty, not half-restore")
            .isEmpty();
    }

    @Test
    void persist_with_null_path_throws() {
        var ledger = RepairLedger.get();
        assertThatThrownBy(() -> ledger.persist(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persist_creates_parent_directory(@TempDir Path tmp) throws Exception {
        var nested = tmp.resolve("deep").resolve("nested").resolve("ledger.json");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "x");
        ledger.persist(nested);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void round_trip_preserves_predicate_queries(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        var ledger = RepairLedger.get();
        ledger.record(AGENT, RepairLedger.Kind.ACKNOWLEDGE_HARM, OTHER, "named harm");
        // Specifically NO make_amends — predicate should return false on both sides of restore.
        boolean preAck = ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER);
        boolean preAmends = ledger.hasMadeAmendsToward(AGENT, OTHER);

        ledger.persist(file);
        ledger.clearForTests();
        ledger.restore(file);

        assertThat(ledger.hasAcknowledgedHarmAgainst(AGENT, OTHER)).isEqualTo(preAck).isTrue();
        assertThat(ledger.hasMadeAmendsToward(AGENT, OTHER)).isEqualTo(preAmends).isFalse();
    }
}
