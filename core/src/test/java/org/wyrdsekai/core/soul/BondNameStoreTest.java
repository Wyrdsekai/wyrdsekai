package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name two parties gave their bond is kept in {@code world.db}, read after a restart, and
 * given once.
 */
@Tag("integration")
class BondNameStoreTest {

    private static final String MIA = "did:key:mia";
    private static final String PERSON = "did:person:ada";

    @Test
    void a_name_is_kept_in_world_db_and_read_after_a_restart(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        var store = new BondNameStore(jdbc);
        assertThat(store.find(MIA, PERSON)).isEmpty();
        assertThat(store.save(new BondNameStore.Named(MIA, PERSON, "lantern ✶", PERSON, Instant.parse("2026-09-30T13:00:00Z")))).isTrue();

        var afterRestart = new BondNameStore(jdbc).find(MIA, PERSON);
        assertThat(afterRestart).isPresent();
        assertThat(afterRestart.get().name()).isEqualTo("lantern ✶");
        assertThat(afterRestart.get().offeredBy()).isEqualTo(PERSON);
        assertThat(afterRestart.get().namedAt()).isEqualTo(Instant.parse("2026-09-30T13:00:00Z"));
    }

    @Test
    void a_name_is_given_once(@TempDir Path tmp) throws Exception {
        var store = new BondNameStore(SchemaInitializer.initialize(tmp.resolve("world.db")));
        assertThat(store.save(new BondNameStore.Named(MIA, PERSON, "lantern", PERSON, Instant.now()))).isTrue();
        assertThat(store.save(new BondNameStore.Named(MIA, PERSON, "another", PERSON, Instant.now())))
            .as("the bond already has its name").isFalse();
        assertThat(store.find(MIA, PERSON).get().name()).isEqualTo("lantern");
    }

    @Test
    void each_bond_has_its_own_name(@TempDir Path tmp) throws Exception {
        var store = new BondNameStore(SchemaInitializer.initialize(tmp.resolve("world.db")));
        store.save(new BondNameStore.Named(MIA, PERSON, "lantern", PERSON, Instant.now()));
        assertThat(store.find("did:key:rose", PERSON)).as("another companion's bond with the same person").isEmpty();
        assertThat(store.find(MIA, "did:person:kit")).as("the same companion's bond with another person").isEmpty();
    }

    @Test
    void without_a_database_names_are_held_in_memory() {
        var store = new BondNameStore(null);
        assertThat(store.save(new BondNameStore.Named(MIA, PERSON, "lantern", PERSON, Instant.now()))).isTrue();
        assertThat(store.save(new BondNameStore.Named(MIA, PERSON, "another", PERSON, Instant.now()))).isFalse();
        assertThat(store.find(MIA, PERSON).get().name()).isEqualTo("lantern");
    }
}
