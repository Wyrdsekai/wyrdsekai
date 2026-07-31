package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * companions table is canonical
 * for companion DIDs.
 */
class CompanionRegistryTest {

    private String jdbcUrl;
    private CompanionRegistry registry;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "cr-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAlive.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS companions(
                  did             TEXT PRIMARY KEY,
                  entity_id       TEXT NOT NULL,
                  name            TEXT,
                  home_zone       TEXT,
                  born_at         INTEGER NOT NULL,
                  last_seen_at    INTEGER,
                  archived        INTEGER NOT NULL DEFAULT 0
                )
                """);
        }
        registry = new CompanionRegistry(jdbcUrl);
    }

    @Test
    void registerPersistsAllFields() {
        registry.register("did:key:z6Mkfoo", "wyrd", "Wyrd", "alpha");
        var loaded = registry.get("did:key:z6Mkfoo").orElseThrow();
        assertThat(loaded.entityId()).isEqualTo("wyrd");
        assertThat(loaded.name()).isEqualTo("Wyrd");
        assertThat(loaded.homeZone()).isEqualTo("alpha");
        assertThat(loaded.archived()).isFalse();
        assertThat(loaded.bornAt()).isNotNull();
        assertThat(loaded.lastSeenAt()).isNotNull();
    }

    @Test
    void registerOnConflictRefreshes() {
        registry.register("did:key:z6Mkrename", "agent1", "Old Name", "alpha");
        registry.register("did:key:z6Mkrename", "agent1", "New Name", "beta");

        var loaded = registry.get("did:key:z6Mkrename").orElseThrow();
        assertThat(loaded.name()).isEqualTo("New Name");
        assertThat(loaded.homeZone()).isEqualTo("beta");
        assertThat(registry.count()).isEqualTo(1);
    }

    @Test
    void findByEntityIdLooksUpRow() {
        registry.register("did:key:z6Mka", "ember", "Ember", "alpha");
        registry.register("did:key:z6Mkb", "ash", "Ash", "alpha");

        var found = registry.findByEntityId("ember").orElseThrow();
        assertThat(found.did()).isEqualTo("did:key:z6Mka");
        assertThat(registry.findByEntityId("missing")).isEmpty();
    }

    @Test
    void allReturnsActiveAndArchivedRows() {
        registry.register("did:key:z6Mka", "a", "A", "alpha");
        registry.register("did:key:z6Mkb", "b", "B", "alpha");
        registry.archive("did:key:z6Mkb");

        assertThat(registry.all()).hasSize(2);
        assertThat(registry.count()).isEqualTo(1);  // archived not counted
    }

    @Test
    void findByEntityIdSkipsArchived() {
        registry.register("did:key:z6Mka", "scout", "Scout", "alpha");
        registry.archive("did:key:z6Mka");
        assertThat(registry.findByEntityId("scout")).isEmpty();
    }

    @Test
    void touchUpdatesLastSeenWithoutMutatingOtherFields() throws InterruptedException {
        registry.register("did:key:z6Mkt", "tio", "Tio", "alpha");
        var before = registry.get("did:key:z6Mkt").orElseThrow();
        Thread.sleep(1100);  // ensure epoch second tick
        registry.touch("did:key:z6Mkt");
        var after = registry.get("did:key:z6Mkt").orElseThrow();

        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.entityId()).isEqualTo(before.entityId());
        assertThat(after.lastSeenAt()).isAfterOrEqualTo(before.lastSeenAt());
    }

    @Test
    void blankInputsAreRejected() {
        registry.register("", "ent", "name", "z");
        registry.register(null, "ent", "name", "z");
        registry.register("did:key:z6Mk", "", "name", "z");
        registry.register("did:key:z6Mk", null, "name", "z");
        assertThat(registry.count()).isZero();
    }

    @Test
    void getReturnsEmptyForUnknownDid() {
        assertThat(registry.get("did:key:nope")).isEmpty();
    }

    @Test
    void homeZoneCanBeNull() {
        // localZoneId may not be known yet at register time.
        registry.register("did:key:z6Mkearly", "early", "Early", null);
        var loaded = registry.get("did:key:z6Mkearly").orElseThrow();
        assertThat(loaded.homeZone()).isNull();
    }
}
