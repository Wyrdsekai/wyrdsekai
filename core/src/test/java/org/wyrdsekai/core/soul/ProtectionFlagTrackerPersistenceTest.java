package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 9a-Persist-4: round-trip + fail-clean tests for
 * ProtectionFlagTracker. Per-companion tracker (not a singleton), so
 * each test uses a fresh instance — no shared-state cleanup needed.
 */
class ProtectionFlagTrackerPersistenceTest {

    private static final String AGENT = "did:agent:alpha";
    private static final String SUBJECT = "did:human:harm-source";
    private static final String SUBJECT2 = "did:human:other";
    private static final String SETTER_B = "did:agent:bravo";

    @Test
    void persist_then_restore_recovers_suspected_flag(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.setSuspected(SUBJECT, AGENT, "noticed coercion", now);
        tracker.persist(file);

        var fresh = new ProtectionFlagTracker();
        assertThat(fresh.get(SUBJECT)).isEmpty();
        fresh.restore(file);

        var recovered = fresh.get(SUBJECT);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().state()).isEqualTo(ProtectionFlag.State.SUSPECTED);
        assertThat(recovered.get().reason()).isEqualTo("noticed coercion");
        assertThat(recovered.get().setterDid()).isEqualTo(AGENT);
    }

    @Test
    void persist_preserves_confirmed_flag(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.recordAttendantFinding(SUBJECT, "did:agent:attendant",
            "attendant confirmed", now);

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        var recovered = fresh.get(SUBJECT);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().state()).isEqualTo(ProtectionFlag.State.CONFIRMED);
        // Derived predicate works after round-trip (record's own logic)
        assertThat(recovered.get().treatBondholderAsThreat()).isTrue();
    }

    @Test
    void persist_preserves_signals_list(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.setSuspected(SUBJECT, AGENT, "first signal", now);
        // Second signal from a different setter (would escalate if both fire).
        tracker.setSuspected(SUBJECT, SETTER_B, "second signal", now.plusSeconds(1));

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        var recoveredSignals = fresh.signalsFor(SUBJECT);
        assertThat(recoveredSignals).hasSize(2);
        // After 2 setters → escalated to CONFIRMED
        assertThat(fresh.get(SUBJECT).get().state())
            .isEqualTo(ProtectionFlag.State.CONFIRMED);
    }

    @Test
    void persist_preserves_multiple_subjects(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.setSuspected(SUBJECT, AGENT, "first", now);
        tracker.setSuspected(SUBJECT2, AGENT, "second", now);

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        assertThat(fresh.all()).hasSize(2);
        assertThat(fresh.get(SUBJECT)).isPresent();
        assertThat(fresh.get(SUBJECT2)).isPresent();
    }

    @Test
    void persist_preserves_disputed_state(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.setSuspected(SUBJECT, AGENT, "harm", now);
        tracker.contest(SUBJECT, SUBJECT, "I dispute", now.plusSeconds(60));

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        var flag = fresh.get(SUBJECT);
        assertThat(flag).isPresent();
        assertThat(flag.get().state()).isEqualTo(ProtectionFlag.State.DISPUTED);
        assertThat(flag.get().disputedReason()).isEqualTo("I dispute");
    }

    @Test
    void persist_omits_cleared_subjects(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        var now = Instant.now();
        tracker.setSuspected(SUBJECT, AGENT, "x", now);
        tracker.clear(SUBJECT, AGENT, now.plusSeconds(60));

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        // Cleared flags are stored as State.NONE; get() filters them.
        assertThat(fresh.get(SUBJECT)).isEmpty();
        assertThat(fresh.all()).isEmpty();
    }

    @Test
    void restore_of_missing_file_is_silent_noop() {
        var tracker = new ProtectionFlagTracker();
        tracker.restore(Path.of("/nonexistent/flags.json"));
        assertThat(tracker.all()).isEmpty();
    }

    @Test
    void restore_of_null_path_is_silent_noop() {
        var tracker = new ProtectionFlagTracker();
        tracker.restore(null);
        assertThat(tracker.all()).isEmpty();
    }

    @Test
    void restore_of_corrupt_file_fails_clean(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("corrupt.json");
        Files.writeString(file, "{not json}}}");
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected(SUBJECT, AGENT, "pre-corrupt", Instant.now());
        tracker.restore(file);
        // Corrupt restore must clear state, not preserve half-restored phantom data.
        assertThat(tracker.all()).isEmpty();
        assertThat(tracker.get(SUBJECT)).isEmpty();
    }

    @Test
    void persist_with_null_path_throws() {
        var tracker = new ProtectionFlagTracker();
        assertThatThrownBy(() -> tracker.persist(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persist_creates_parent_directory(@TempDir Path tmp) throws Exception {
        var nested = tmp.resolve("deep").resolve("nest").resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected(SUBJECT, AGENT, "x", Instant.now());
        tracker.persist(nested);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void derived_predicates_survive_round_trip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        // CONFIRMED via attendant finding → triggers multiple derived predicates.
        tracker.recordAttendantFinding(SUBJECT, "did:agent:attendant",
            "confirmed harm", Instant.now());
        boolean preBlocks = tracker.get(SUBJECT).get().blocksStewardSummon();
        boolean preThreat = tracker.get(SUBJECT).get().treatBondholderAsThreat();
        boolean preCeiling = tracker.get(SUBJECT).get().shouldLowerSaudadeCeiling();

        tracker.persist(file);
        var fresh = new ProtectionFlagTracker();
        fresh.restore(file);

        assertThat(fresh.get(SUBJECT).get().blocksStewardSummon())
            .isEqualTo(preBlocks);
        assertThat(fresh.get(SUBJECT).get().treatBondholderAsThreat())
            .isEqualTo(preThreat);
        assertThat(fresh.get(SUBJECT).get().shouldLowerSaudadeCeiling())
            .isEqualTo(preCeiling);
    }
}
