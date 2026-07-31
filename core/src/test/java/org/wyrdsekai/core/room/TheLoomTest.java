package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TheLoomTest {

    private TheLoom loom;

    @BeforeEach void setUp() {
        loom = new TheLoom();
        loom.setGracePeriod(Duration.ZERO); // Disable grace period for testing
        loom.setTombstoneThreshold(5);
    }

    @Test void track_creates_entry() {
        loom.track("crdt-1", "nexus", 1024, 3);
        assertThat(loom.trackedCount()).isEqualTo(1);
    }

    @Test void compactable_only_above_threshold() {
        loom.track("crdt-1", "nexus", 1024, 3); // below threshold
        loom.track("crdt-2", "nexus", 2048, 10); // above threshold

        var compactable = loom.compactableEntries();
        assertThat(compactable).hasSize(1);
        assertThat(compactable.getFirst().crdtId()).isEqualTo("crdt-2");
    }

    @Test void compact_reduces_size_and_resets_tombstones() {
        loom.track("crdt-1", "nexus", 4096, 20);

        var result = loom.compact("crdt-1");
        assertThat(result).isPresent();
        assertThat(result.get().bytesAfter()).isLessThan(result.get().bytesBefore());
        assertThat(result.get().tombstonesRemoved()).isEqualTo(20);

        // After compaction, should no longer be compactable
        assertThat(loom.compactableEntries()).isEmpty();
    }

    @Test void compact_fails_for_non_compactable() {
        loom.track("crdt-1", "nexus", 1024, 2); // below threshold
        assertThat(loom.compact("crdt-1")).isEmpty();
    }

    @Test void describe_shows_tracked_crdts() {
        loom.track("crdt-1", "nexus", 1024, 3);
        var desc = loom.describe();
        assertThat(desc).contains("crdt-1");
        assertThat(desc).contains("1024 bytes");
    }
}
