package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * households table mirrors
 * node-identity.json's public key for queries.
 */
class HouseholdStoreTest {

    private String jdbcUrl;
    private HouseholdStore store;

    @BeforeEach
    void setUp() throws SQLException {
        var dbName = "hh-test-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcUrl = "jdbc:sqlite:file:" + dbName + "?mode=memory&cache=shared";
        @SuppressWarnings("resource")
        var keepAlive = DriverManager.getConnection(jdbcUrl);
        try (var stmt = keepAlive.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS households(
                  household_id    TEXT PRIMARY KEY,
                  public_key      BLOB NOT NULL,
                  fingerprint     TEXT NOT NULL,
                  did_key         TEXT,
                  x25519_public_key BLOB,
                  registered_at   INTEGER NOT NULL,
                  updated_at      INTEGER NOT NULL DEFAULT (unixepoch())
                )
                """);
        }
        store = new HouseholdStore(jdbcUrl);
    }

    @Test
    void upsertPersistsAllFields() {
        var pk = new byte[]{1, 2, 3, 4, 5};
        store.upsert("node-1", pk, "aa:bb:cc", "did:key:z6Mkalpha");
        var loaded = store.get("node-1").orElseThrow();
        assertThat(loaded.householdId()).isEqualTo("node-1");
        assertThat(loaded.publicKey()).containsExactly(pk);
        assertThat(loaded.fingerprint()).isEqualTo("aa:bb:cc");
        assertThat(loaded.didKey()).isEqualTo("did:key:z6Mkalpha");
        assertThat(loaded.registeredAt()).isNotNull();
    }

    @Test
    void upsertOnConflictRefreshes() {
        var pk1 = new byte[]{1};
        var pk2 = new byte[]{2};
        store.upsert("node-rotate", pk1, "fp1", "did:key:z6Mk1");
        store.upsert("node-rotate", pk2, "fp2", "did:key:z6Mk2");

        var loaded = store.get("node-rotate").orElseThrow();
        assertThat(loaded.publicKey()).containsExactly(pk2);
        assertThat(loaded.fingerprint()).isEqualTo("fp2");
        assertThat(loaded.didKey()).isEqualTo("did:key:z6Mk2");
        assertThat(store.count()).isEqualTo(1);
    }

    @Test
    void findByDidLooksUpRow() {
        store.upsert("node-a", new byte[]{1}, "fp-a", "did:key:z6MkA");
        store.upsert("node-b", new byte[]{2}, "fp-b", "did:key:z6MkB");

        var found = store.findByDid("did:key:z6MkA").orElseThrow();
        assertThat(found.householdId()).isEqualTo("node-a");
        assertThat(store.findByDid("did:key:z6MkMissing")).isEmpty();
    }

    @Test
    void allReturnsRowsInRegistrationOrder() {
        store.upsert("node-1", new byte[]{1}, "f1", "d1");
        store.upsert("node-2", new byte[]{2}, "f2", "d2");
        store.upsert("node-3", new byte[]{3}, "f3", "d3");
        var all = store.all();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).householdId()).isEqualTo("node-1");
        assertThat(all.get(2).householdId()).isEqualTo("node-3");
    }

    @Test
    void blankInputsAreRejected() {
        store.upsert("", new byte[]{1}, "fp", "did");
        store.upsert(null, new byte[]{1}, "fp", "did");
        store.upsert("node-empty-key", new byte[0], "fp", "did");
        store.upsert("node-null-key", null, "fp", "did");
        assertThat(store.count()).isZero();
    }

    @Test
    void getReturnsEmptyForUnknownId() {
        assertThat(store.get("does-not-exist")).isEmpty();
    }

    @Test
    void x25519GrantKeyRoundTripsAndIsPreservedOnNullUpsert() {
        // #1184: the X25519 grant key mirrors alongside the signing key.
        var grantKey = new byte[]{9, 8, 7, 6};
        store.upsert("node-grant", new byte[]{1}, "fp", "did:key:zG", grantKey);
        assertThat(store.get("node-grant").orElseThrow().x25519PublicKey()).containsExactly(grantKey);
        assertThat(store.x25519PublicKey("node-grant")).get().asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.BYTE_ARRAY).containsExactly(grantKey);

        // A later legacy upsert (no grant key) must NOT wipe the stored one.
        store.upsert("node-grant", new byte[]{2}, "fp2", "did:key:zG2");
        assertThat(store.get("node-grant").orElseThrow().x25519PublicKey()).containsExactly(grantKey);
        assertThat(store.get("node-grant").orElseThrow().publicKey()).containsExactly(new byte[]{2});

        // A node mirrored without a grant key reports none.
        store.upsert("node-nogrant", new byte[]{1}, "fp", "did:key:zN");
        assertThat(store.x25519PublicKey("node-nogrant")).isEmpty();
    }

    @Test
    void findByDidHandlesNullDidKey() {
        // Upsert with null DID — findByDid should not match.
        store.upsert("node-no-did", new byte[]{1}, "fp", null);
        assertThat(store.findByDid("did:key:anything")).isEmpty();
        // But get() still works on the household_id key.
        var loaded = store.get("node-no-did").orElseThrow();
        assertThat(loaded.didKey()).isNull();
    }
}
