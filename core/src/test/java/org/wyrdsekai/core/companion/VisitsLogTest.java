package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VisitsLogTest {

    @Test
    void records_and_persists(@TempDir Path tmp) {
        var log = new VisitsLog("did:key:visit1", tmp);
        log.record("alpha", "nexus", "spawn");
        log.record("alpha", "garden", "follow");

        var reloaded = new VisitsLog("did:key:visit1", tmp);
        assertThat(reloaded.size()).isEqualTo(2);
        var recent = reloaded.recent(5);
        assertThat(recent.getFirst().roomId()).isEqualTo("garden");
        assertThat(recent.get(1).roomId()).isEqualTo("nexus");
    }

    @Test
    void dedupes_adjacent_same_room(@TempDir Path tmp) {
        var log = new VisitsLog("did:key:visit2", tmp);
        log.record("alpha", "nexus", "spawn");
        log.record("alpha", "nexus", "spawn"); // duplicate
        log.record("alpha", "garden", "follow");
        assertThat(log.size()).isEqualTo(2);
    }

    @Test
    void blank_room_id_is_ignored(@TempDir Path tmp) {
        var log = new VisitsLog("did:key:visit3", tmp);
        log.record("alpha", "", "follow");
        log.record("alpha", null, "follow");
        assertThat(log.size()).isZero();
    }

    @Test
    void ring_buffer_drops_oldest(@TempDir Path tmp) {
        var log = new VisitsLog("did:key:visit4", tmp, 3);
        log.record("alpha", "a", "");
        log.record("alpha", "b", "");
        log.record("alpha", "c", "");
        log.record("alpha", "d", ""); // pushes 'a' off
        assertThat(log.size()).isEqualTo(3);
        assertThat(log.recent(10)).extracting(VisitsLog.Visit::roomId)
            .containsExactly("d", "c", "b");
    }
}
