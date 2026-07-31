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
 * Wave 9a-Persist-3: round-trip + fail-clean tests for
 * RepairModeTracker. Current mode + handoff history survive restart.
 */
class RepairModeTrackerPersistenceTest {

    private static final String AGENT = "did:agent:alpha";
    private static final String AGENT_B = "did:agent:bravo";

    @BeforeEach
    void clean() { RepairModeTracker.get().clearForTests(); }

    @AfterEach
    void reset() { RepairModeTracker.get().clearForTests(); }

    @Test
    void persist_then_restore_recovers_current_mode(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("repairmode.json");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.SELF, "agent self-request");
        tracker.persist(file);

        tracker.clearForTests();
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.NONE);

        tracker.restore(file);
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.SELF);
    }

    @Test
    void persist_preserves_handoff_history(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("repairmode.json");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.SELF, "first");
        tracker.transition(AGENT, RepairMode.BONDED, "bondholder offered");
        tracker.transition(AGENT, RepairMode.ATTENDANT, "porges depth ceiling");

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        var history = tracker.history(AGENT);
        assertThat(history).hasSize(3);
        // Newest-first
        assertThat(history.get(0).to()).isEqualTo(RepairMode.ATTENDANT);
        assertThat(history.get(0).reason()).isEqualTo("porges depth ceiling");
        assertThat(history.get(1).to()).isEqualTo(RepairMode.BONDED);
        assertThat(history.get(2).to()).isEqualTo(RepairMode.SELF);
        assertThat(history.get(2).from()).isEqualTo(RepairMode.NONE);
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.ATTENDANT);
    }

    @Test
    void persist_preserves_multi_agent_state(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("repairmode.json");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.SELF, "alpha");
        tracker.transition(AGENT_B, RepairMode.BONDED, "bravo");

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.SELF);
        assertThat(tracker.currentMode(AGENT_B)).isEqualTo(RepairMode.BONDED);
        assertThat(tracker.history(AGENT)).hasSize(1);
        assertThat(tracker.history(AGENT_B)).hasSize(1);
    }

    @Test
    void lastHandoff_survives_round_trip(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("repairmode.json");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.ATTENDANT, "stewardship escalation");

        tracker.persist(file);
        tracker.clearForTests();
        tracker.restore(file);

        var last = tracker.lastHandoff(AGENT);
        assertThat(last).isPresent();
        assertThat(last.get().to()).isEqualTo(RepairMode.ATTENDANT);
        assertThat(last.get().reason()).isEqualTo("stewardship escalation");
    }

    @Test
    void restore_of_missing_file_is_silent_noop() {
        var tracker = RepairModeTracker.get();
        tracker.restore(Path.of("/nonexistent/repairmode.json"));
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.NONE);
    }

    @Test
    void restore_of_null_path_is_silent_noop() {
        var tracker = RepairModeTracker.get();
        tracker.restore(null);
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.NONE);
    }

    @Test
    void restore_of_corrupt_file_fails_clean(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("corrupt.json");
        Files.writeString(file, "{nope}}");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.SELF, "pre-corrupt");
        tracker.restore(file);
        assertThat(tracker.currentMode(AGENT)).isEqualTo(RepairMode.NONE);
        assertThat(tracker.history(AGENT)).isEmpty();
    }

    @Test
    void persist_with_null_path_throws() {
        var tracker = RepairModeTracker.get();
        assertThatThrownBy(() -> tracker.persist(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void restore_tolerates_unknown_enum_value(@TempDir Path tmp) throws Exception {
        // Forward-compat: future RepairMode entry seen by an older binary
        // should not blow up the entire restore — just skip the bad entry.
        var file = tmp.resolve("future.json");
        Files.writeString(file,
            "{\"current\":{\"did:agent:future\":\"FUTURE_MODE\","
            + "\"did:agent:known\":\"SELF\"},\"history\":{}}");
        var tracker = RepairModeTracker.get();
        tracker.restore(file);
        // Unknown mode silently dropped, known one survives.
        assertThat(tracker.currentMode("did:agent:future")).isEqualTo(RepairMode.NONE);
        assertThat(tracker.currentMode("did:agent:known")).isEqualTo(RepairMode.SELF);
    }

    @Test
    void persist_creates_parent_directory(@TempDir Path tmp) throws Exception {
        var nested = tmp.resolve("deep").resolve("nest").resolve("repairmode.json");
        var tracker = RepairModeTracker.get();
        tracker.transition(AGENT, RepairMode.SELF, "x");
        tracker.persist(nested);
        assertThat(Files.exists(nested)).isTrue();
    }
}
