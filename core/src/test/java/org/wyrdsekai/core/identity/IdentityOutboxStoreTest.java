package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IdentityOutboxStore} — insert / replace / stale logic.
 */
class IdentityOutboxStoreTest {

    private IdentityOutboxStore store;
    private DidKey.DidKeyPair kp;

    @BeforeEach void setup() throws Exception {
        var jdbcUrl = TestDb.createInMemory();
        store = new IdentityOutboxStore(jdbcUrl);
        kp = DidKey.generate();
    }

    @Test void insert_then_get_returns_record() throws Exception {
        var record = mk("alice", 1000L);
        var r = store.upsertIfNewer(record);
        assertThat(r).isEqualTo(IdentityOutboxStore.UpsertResult.INSERTED);

        var fetched = store.get(kp.did());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().displayName()).isEqualTo("alice");
        assertThat(fetched.get().verify()).isTrue();
    }

    @Test void replace_when_updatedAt_newer() throws Exception {
        store.upsertIfNewer(mk("alice", 1000L));
        var second = mk("alice-renamed", 2000L);
        var r = store.upsertIfNewer(second);
        assertThat(r).isEqualTo(IdentityOutboxStore.UpsertResult.REPLACED);

        var fetched = store.get(kp.did()).orElseThrow();
        assertThat(fetched.displayName()).isEqualTo("alice-renamed");
        assertThat(fetched.updatedAt()).isEqualTo(2000L);
    }

    @Test void stale_when_updatedAt_older_or_equal() throws Exception {
        store.upsertIfNewer(mk("alice", 2000L));
        // Same timestamp → stale (we require strictly newer)
        var equal = mk("alice-different", 2000L);
        assertThat(store.upsertIfNewer(equal)).isEqualTo(IdentityOutboxStore.UpsertResult.STALE);

        var earlier = mk("alice-rewind", 1000L);
        assertThat(store.upsertIfNewer(earlier)).isEqualTo(IdentityOutboxStore.UpsertResult.STALE);

        // Original still wins
        var fetched = store.get(kp.did()).orElseThrow();
        assertThat(fetched.displayName()).isEqualTo("alice");
    }

    @Test void get_unknown_did_returns_empty() {
        assertThat(store.get("did:key:znotreal")).isEmpty();
    }

    @Test void delete_removes_row() throws Exception {
        store.upsertIfNewer(mk("alice", 1000L));
        assertThat(store.delete(kp.did())).isTrue();
        assertThat(store.get(kp.did())).isEmpty();
        assertThat(store.delete(kp.did())).isFalse();  // idempotent
    }

    @Test void listAll_returns_records() throws Exception {
        var kp2 = DidKey.generate();
        store.upsertIfNewer(mk("alice", 1000L));
        store.upsertIfNewer(IdentityOutboxRecord.sign(
            kp2.did(), "bob", "beta",
            List.of("beta"), List.of("beta"), List.of(),
            1500L, kp2.keyPair().getPrivate()));
        var all = store.listAll();
        assertThat(all).hasSize(2);
        assertThat(all).extracting(IdentityOutboxRecord::displayName)
            .containsExactlyInAnyOrder("alice", "bob");
    }

    private IdentityOutboxRecord mk(String displayName, long updatedAt) {
        return IdentityOutboxRecord.sign(
            kp.did(), displayName, "alpha",
            List.of("alpha"), List.of("alpha"), List.of(),
            updatedAt, kp.keyPair().getPrivate());
    }
}
