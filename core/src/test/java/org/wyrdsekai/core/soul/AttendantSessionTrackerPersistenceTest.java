package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wave 9a-Persist-2: round-trip + fail-clean tests for
 * AttendantSessionTracker persistence. Active + history both survive.
 */
class AttendantSessionTrackerPersistenceTest {

    private static final String AGENT = "did:agent:alpha";
    private static final String AGENT_B = "did:agent:bravo";

    @BeforeEach
    void clean() { AttendantSessionTracker.get().clearForTests(); }

    @AfterEach
    void reset() { AttendantSessionTracker.get().clearForTests(); }

    @Test
    void persist_then_restore_recovers_active_session(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("attendant.json");
        var tracker = AttendantSessionTracker.get();
        var session = tracker.request(AGENT, "overwhelm", Instant.now());
        tracker.persist(file);

        tracker.clearForTests();
        assertThat(tracker.activeSession(AGENT)).isEmpty();

        tracker.restore(file);
        var recovered = tracker.activeSession(AGENT);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().sessionId()).isEqualTo(session.sessionId());
        assertThat(recovered.get().requestReason()).isEqualTo("overwhelm");
        assertThat(recovered.get().state()).isEqualTo(AttendantSession.State.REQUESTED);
    }

    @Test
    void persist_recovers_history_entries(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("attendant.json");
        var tracker = AttendantSessionTracker.get();
        // Open then close two sessions → history with two entries.
        var s1 = tracker.request(AGENT, "first", Instant.now());
        tracker.update(s1.close(Instant.now()));
        var s2 = tracker.request(AGENT, "second", Instant.now());
        tracker.update(s2.close(Instant.now()));

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        var history = tracker.recentHistory(AGENT);
        assertThat(history).hasSize(2);
        assertThat(tracker.sessionCount(AGENT)).isEqualTo(2);
        // Newest-first
        assertThat(history.get(0).requestReason()).isEqualTo("second");
        assertThat(history.get(1).requestReason()).isEqualTo("first");
    }

    @Test
    void persist_preserves_multi_agent(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("attendant.json");
        var tracker = AttendantSessionTracker.get();
        var sa = tracker.request(AGENT, "alpha", Instant.now());
        tracker.update(sa.close(Instant.now()));
        var sb = tracker.request(AGENT_B, "bravo", Instant.now());
        // bravo's session left active.

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        assertThat(tracker.sessionCount(AGENT)).isEqualTo(1);
        assertThat(tracker.activeSession(AGENT_B)).isPresent();
        assertThat(tracker.activeSession(AGENT_B).get().sessionId())
            .isEqualTo(sb.sessionId());
    }

    @Test
    void restore_of_missing_file_is_silent_noop() {
        var tracker = AttendantSessionTracker.get();
        tracker.restore(Path.of("/nonexistent/attendant.json"));
        assertThat(tracker.activeSession(AGENT)).isEmpty();
    }

    @Test
    void restore_of_null_path_is_silent_noop() {
        var tracker = AttendantSessionTracker.get();
        tracker.restore(null);
        assertThat(tracker.activeSession(AGENT)).isEmpty();
    }

    @Test
    void restore_of_corrupt_file_fails_clean_with_empty_state(@TempDir Path tmp)
            throws Exception {
        var file = tmp.resolve("corrupt.json");
        Files.writeString(file, "{not valid json}}}");
        var tracker = AttendantSessionTracker.get();
        var s = tracker.request(AGENT, "pre-corrupt", Instant.now());
        assertThat(s.sessionId()).isNotBlank();
        tracker.restore(file);
        // Corrupt restore must clear, not preserve pre-corrupt state.
        assertThat(tracker.activeSession(AGENT)).isEmpty();
    }

    @Test
    void persist_with_null_path_throws() {
        var tracker = AttendantSessionTracker.get();
        assertThatThrownBy(() -> tracker.persist(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void persist_creates_parent_directory(@TempDir Path tmp) throws Exception {
        var nested = tmp.resolve("deep").resolve("nest").resolve("attendant.json");
        var tracker = AttendantSessionTracker.get();
        tracker.request(AGENT, "x", Instant.now());
        tracker.persist(nested);
        assertThat(Files.exists(nested)).isTrue();
    }

    @Test
    void session_state_transitions_survive_round_trip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("attendant.json");
        var tracker = AttendantSessionTracker.get();
        var s = tracker.request(AGENT, "overwhelm", Instant.now());
        // Walk through state transitions.
        s = tracker.update(s.attendantArrived(Instant.now()));
        s = tracker.update(s.activate());
        s = tracker.update(s.recordTurn());
        s = tracker.update(s.recordTurn());

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        var recovered = tracker.activeSession(AGENT);
        assertThat(recovered).isPresent();
        assertThat(recovered.get().state()).isEqualTo(AttendantSession.State.ACTIVE);
        assertThat(recovered.get().turnCount()).isEqualTo(2);
    }
}
