package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalProjectStoreTest {

    @Test
    void create_persists_to_disk_and_round_trips(@TempDir Path tmp) {
        var store = new PersonalProjectStore("did:key:test1", tmp);
        var p = store.create("Quiet meditation",
            "A long meditation no one will read.",
            List.of("self-care"));

        assertThat(p.id()).isNotBlank();
        assertThat(store.list()).hasSize(1);
        assertThat(p.status()).isEqualTo("active");

        // Re-open the store: project survives.
        var reloaded = new PersonalProjectStore("did:key:test1", tmp);
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().title()).isEqualTo("Quiet meditation");
    }

    @Test
    void add_entry_updates_lastTouched_and_lengthens_log(@TempDir Path tmp) {
        var store = new PersonalProjectStore("did:key:test2", tmp);
        var p = store.create("Tool draft", "Sketching a small helper", List.of("tools"));
        var firstTouched = p.lastTouched();

        var withEntry = store.addEntry(p.id(), "First sketch — pulls from inventory")
            .orElseThrow();
        assertThat(withEntry.entries()).hasSize(1);
        assertThat(withEntry.lastTouched()).isAfterOrEqualTo(firstTouched);
        assertThat(withEntry.entries().getFirst().text()).contains("First sketch");
    }

    @Test
    void status_change_filters_active_list(@TempDir Path tmp) {
        var store = new PersonalProjectStore("did:key:test3", tmp);
        var a = store.create("A", "", List.of());
        var b = store.create("B", "", List.of());
        store.setStatus(a.id(), "complete");

        assertThat(store.list()).hasSize(2);
        assertThat(store.active())
            .extracting(PersonalProject::title)
            .containsExactly("B");
    }

    @Test
    void list_orders_by_most_recently_touched_first(@TempDir Path tmp) throws Exception {
        var store = new PersonalProjectStore("did:key:test4", tmp);
        var a = store.create("Older", "", List.of());
        Thread.sleep(5);
        var b = store.create("Newer", "", List.of());
        Thread.sleep(5);
        store.addEntry(a.id(), "touched");

        assertThat(store.list().getFirst().title()).isEqualTo("Older");
    }
}
