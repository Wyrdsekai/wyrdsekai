package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HearthJournalTest {

    @Test
    void write_persists_to_disk_and_round_trips(@TempDir Path tmp) {
        var j = new HearthJournal("did:key:test1", tmp);
        var entry = j.write("contemplative", "First reflection — feeling the room settle.");
        assertThat(entry.id()).isNotBlank();
        assertThat(entry.mood()).isEqualTo("contemplative");

        var reloaded = new HearthJournal("did:key:test1", tmp);
        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(reloaded.recent(10).getFirst().text()).contains("First reflection");
    }

    @Test
    void recent_returns_newest_first(@TempDir Path tmp) throws Exception {
        var j = new HearthJournal("did:key:test2", tmp);
        j.write("morning", "first");
        Thread.sleep(5);
        j.write("evening", "second");
        var recent = j.recent(5);
        assertThat(recent).hasSize(2);
        assertThat(recent.getFirst().text()).isEqualTo("second");
    }

    @Test
    void blank_text_is_rejected(@TempDir Path tmp) {
        var j = new HearthJournal("did:key:test3", tmp);
        assertThatThrownBy(() -> j.write("", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ring_buffer_drops_oldest(@TempDir Path tmp) {
        var j = new HearthJournal("did:key:test4", tmp, 3);
        j.write("", "a");
        j.write("", "b");
        j.write("", "c");
        j.write("", "d"); // pushes 'a' off
        assertThat(j.size()).isEqualTo(3);
        assertThat(j.recent(10)).extracting(HearthJournal.Entry::text)
            .containsExactly("d", "c", "b");
    }
}
