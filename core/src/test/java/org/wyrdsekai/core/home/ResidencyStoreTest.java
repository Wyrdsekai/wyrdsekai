package org.wyrdsekai.core.home;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ResidencyStoreTest {

    @TempDir Path workspace;

    private ResidencyStore newStore() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("residency.db"));
        return new ResidencyStore(jdbc);
    }

    private String newStoreAndReturnJdbc() {
        return SchemaInitializer.initialize(workspace.resolve("residency.db"));
    }

    @Test void grant_and_get_round_trip() {
        var store = newStore();
        var r = new Residency("did:wyrd:z6MkAlice", "alpha",
            Residency.ROLE_STEWARD, Instant.now(), "did:wyrd:z6MkBob", "study-alice");
        store.grant(r);

        var fetched = store.get("did:wyrd:z6MkAlice", "alpha").orElseThrow();
        assertThat(fetched.did()).isEqualTo("did:wyrd:z6MkAlice");
        assertThat(fetched.zoneId()).isEqualTo("alpha");
        assertThat(fetched.role()).isEqualTo(Residency.ROLE_STEWARD);
        assertThat(fetched.grantor()).isEqualTo("did:wyrd:z6MkBob");
        assertThat(fetched.studyRoomId()).isEqualTo("study-alice");
    }

    @Test void grant_upserts_on_conflict() {
        var store = newStore();
        var alice = "did:wyrd:z6MkAlice";
        store.grant(new Residency(alice, "alpha", Residency.ROLE_MEMBER,
            Instant.now(), "steward-1", null));
        store.grant(new Residency(alice, "alpha", Residency.ROLE_STEWARD,
            Instant.now(), "steward-1", "study-a"));

        var fetched = store.get(alice, "alpha").orElseThrow();
        assertThat(fetched.role()).isEqualTo(Residency.ROLE_STEWARD);
        assertThat(fetched.studyRoomId()).isEqualTo("study-a");
    }

    @Test void isResident_true_only_for_granted_zone() {
        var store = newStore();
        var alice = "did:wyrd:z6MkAlice";
        store.grant(new Residency(alice, "alpha", Residency.ROLE_MEMBER,
            Instant.now(), "steward", null));

        assertThat(store.isResident(alice, "alpha")).isTrue();
        assertThat(store.isResident(alice, "beta")).isFalse();
        assertThat(store.isResident("did:wyrd:z6MkUnknown", "alpha")).isFalse();
    }

    @Test void revoke_removes_the_row() {
        var store = newStore();
        var alice = "did:wyrd:z6MkAlice";
        store.grant(new Residency(alice, "alpha", Residency.ROLE_MEMBER,
            Instant.now(), "steward", null));
        assertThat(store.isResident(alice, "alpha")).isTrue();

        boolean removed = store.revoke(alice, "alpha");
        assertThat(removed).isTrue();
        assertThat(store.isResident(alice, "alpha")).isFalse();
        assertThat(store.revoke(alice, "alpha")).isFalse();  // idempotent
    }

    @Test void listByZone_scoped_to_zone() {
        var store = newStore();
        store.grant(new Residency("did:wyrd:z6MkAlice", "alpha",
            Residency.ROLE_MEMBER, Instant.now(), "steward", null));
        store.grant(new Residency("did:wyrd:z6MkBob", "alpha",
            Residency.ROLE_MEMBER, Instant.now(), "steward", null));
        store.grant(new Residency("did:wyrd:z6MkCarol", "beta",
            Residency.ROLE_MEMBER, Instant.now(), "steward", null));

        assertThat(store.listByZone("alpha")).hasSize(2);
        assertThat(store.listByZone("beta")).hasSize(1);
        assertThat(store.listByZone("gamma")).isEmpty();
    }

    @Test void setStudyRoomId_updates_existing() {
        var store = newStore();
        var alice = "did:wyrd:z6MkAlice";
        store.grant(new Residency(alice, "alpha", Residency.ROLE_MEMBER,
            Instant.now(), "steward", null));

        store.setStudyRoomId(alice, "alpha", "study-alice-42");
        assertThat(store.get(alice, "alpha").orElseThrow().studyRoomId())
            .isEqualTo("study-alice-42");
    }

    @Test void backfillFromUsers_populates_rows_once() throws Exception {
        var jdbc = newStoreAndReturnJdbc();
        // Seed users table (schema is initialised by SchemaInitializer).
        try (var conn = DriverManager.getConnection(jdbc);
             var st = conn.prepareStatement(
                "INSERT INTO users (id, username, password_hash, role, created_at) "
                + "VALUES (?, ?, 'x', ?, ?)")) {
            st.setString(1, "u-alice"); st.setString(2, "alice");
            st.setString(3, "steward"); st.setLong(4, Instant.now().getEpochSecond());
            st.executeUpdate();
            st.setString(1, "u-bob"); st.setString(2, "bob");
            st.setString(3, "member"); st.setLong(4, Instant.now().getEpochSecond());
            st.executeUpdate();
        }

        var store = new ResidencyStore(jdbc);
        int first = store.backfillFromUsers("alpha");
        assertThat(first).isEqualTo(2);
        assertThat(store.isResident("u-alice", "alpha")).isTrue();
        assertThat(store.isResident("u-bob", "alpha")).isTrue();
        assertThat(store.get("u-alice", "alpha").orElseThrow().role()).isEqualTo("steward");
        assertThat(store.get("u-alice", "alpha").orElseThrow().grantor())
            .isEqualTo(Residency.GRANTOR_MIGRATION);

        // Second call is idempotent — no duplicate inserts.
        int second = store.backfillFromUsers("alpha");
        assertThat(second).isEqualTo(0);
    }
}
