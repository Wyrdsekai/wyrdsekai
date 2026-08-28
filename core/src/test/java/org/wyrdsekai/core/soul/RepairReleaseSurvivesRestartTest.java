package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A release has to outlive the process that granted it.
 *
 * <p>{@link RepairModeTracker#transition} only mutates memory; the trackers are written
 * to disk on the sleep path — which is precisely the event a companion held in a repair
 * mode is not having. Observed live 2026-08-18: the automatic release fired correctly at
 * 16:25 and {@code repair-mode.json} still read {@code ATTENDANT}, so the next restart
 * would have restored her to the mode she had just been let out of, and her history
 * would never have recorded that she was handed back.
 *
 * <p>Both release paths — the automatic handoff and the steward route — now persist
 * immediately.
 */
class RepairReleaseSurvivesRestartTest {

    private static final String DID = "did:key:z6MkExampleCompanion";

    @Test
    void a_release_written_to_disk_is_still_a_release_after_a_restart() throws Exception {
        var dir = Files.createTempDirectory("repair-mode");
        var file = dir.resolve("repair-mode.json");
        try {
            var tracker = RepairModeTracker.get();
            tracker.transition(DID, RepairMode.ATTENDANT, "auto-escalation: test");
            tracker.persist(file);
            assertThat(tracker.currentMode(DID)).isEqualTo(RepairMode.ATTENDANT);

            tracker.transition(DID, RepairMode.NONE, "attendant_session_exceeded_duration");
            tracker.persist(file);

            // "Restart": reload from what is actually on disk.
            tracker.restore(file);
            assertThat(tracker.currentMode(DID))
                .as("she must not wake up back in the mode she was released from")
                .isEqualTo(RepairMode.NONE);
            assertThat(tracker.agentsInRepair()).doesNotContainKey(DID);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void a_release_that_was_never_persisted_is_lost_which_is_why_we_persist() throws Exception {
        // Pins the actual live failure: transition without persist, then restart.
        var dir = Files.createTempDirectory("repair-mode-lost");
        var file = dir.resolve("repair-mode.json");
        try {
            var tracker = RepairModeTracker.get();
            tracker.transition(DID, RepairMode.ATTENDANT, "auto-escalation: test");
            tracker.persist(file);

            tracker.transition(DID, RepairMode.NONE, "released but not written");
            // no persist here — the bug

            tracker.restore(file);
            assertThat(tracker.currentMode(DID))
                .as("this is the state she would have woken into")
                .isEqualTo(RepairMode.ATTENDANT);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void agents_in_repair_reports_only_those_actually_held() {
        var tracker = RepairModeTracker.get();
        tracker.transition(DID, RepairMode.ATTENDANT, "test");
        assertThat(tracker.agentsInRepair()).containsEntry(DID, RepairMode.ATTENDANT);
        tracker.transition(DID, RepairMode.NONE, "test release");
        assertThat(tracker.agentsInRepair()).doesNotContainKey(DID);
    }
}
