package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class AccountStoreTest {

    private AccountStore store;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        store = new AccountStore(jdbcUrl);
    }

    @Test void save_and_find_by_did() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        var found = store.findByDid(account.did());
        assertThat(found).isPresent();
        assertThat(found.get().did()).isEqualTo(account.did());
        assertThat(found.get().displayName()).isEqualTo("Masumi");
    }

    @Test void find_by_name() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        var found = store.findByName("Masumi");
        assertThat(found).isPresent();
        assertThat(found.get().did()).isEqualTo(account.did());
    }

    @Test void find_by_name_case_insensitive() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        var found = store.findByName("operator");
        assertThat(found).isPresent();
        assertThat(found.get().did()).isEqualTo(account.did());
    }

    @Test void list_all() {
        store.save(PlayerAccount.create("Alice"));
        store.save(PlayerAccount.create("Bob"));
        store.save(PlayerAccount.create("Charlie"));

        var all = store.listAll();
        assertThat(all).hasSize(3);
    }

    @Test void update_last_seen() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        var later = Instant.now().plusSeconds(3600);
        store.updateLastSeen(account.did(), later);

        var found = store.findByDid(account.did());
        assertThat(found).isPresent();
        assertThat(found.get().lastSeen().getEpochSecond()).isEqualTo(later.getEpochSecond());
    }

    @Test void device_auto_login_mapping() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        store.registerDevice(account.did(), "device-home-server");

        var found = store.findAccountForDevice("device-home-server");
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(account.did());
    }

    @Test void unknown_did_returns_empty() {
        assertThat(store.findByDid("did:key:z6MkNonexistent")).isEmpty();
    }

    @Test void unknown_device_returns_empty() {
        assertThat(store.findAccountForDevice("device-unknown")).isEmpty();
    }

    @Test void save_preserves_device_ids() {
        var account = PlayerAccount.create("Masumi")
            .withDevice("device-home-server")
            .withDevice("device-phone");
        store.save(account);

        var found = store.findByDid(account.did());
        assertThat(found).isPresent();
        assertThat(found.get().deviceIds()).containsExactlyInAnyOrder("device-home-server", "device-phone");
    }

    @Test void multiple_devices_per_account() {
        var account = PlayerAccount.create("Masumi");
        store.save(account);

        store.registerDevice(account.did(), "device-1");
        store.registerDevice(account.did(), "device-2");
        store.registerDevice(account.did(), "device-3");

        // All three devices should resolve to the same account
        assertThat(store.findAccountForDevice("device-1")).hasValue(account.did());
        assertThat(store.findAccountForDevice("device-2")).hasValue(account.did());
        assertThat(store.findAccountForDevice("device-3")).hasValue(account.did());
    }

    @Test void re_registering_device_updates_account() {
        var alice = PlayerAccount.create("Alice");
        var bob = PlayerAccount.create("Bob");
        store.save(alice);
        store.save(bob);

        // Register device for Alice first
        store.registerDevice(alice.did(), "shared-tablet");
        assertThat(store.findAccountForDevice("shared-tablet")).hasValue(alice.did());

        // Re-register same device for Bob
        store.registerDevice(bob.did(), "shared-tablet");
        assertThat(store.findAccountForDevice("shared-tablet")).hasValue(bob.did());
    }

    @Test void save_updates_existing_account() {
        var account = PlayerAccount.create("Original");
        store.save(account);

        // Save again with updated display name (via manual construction)
        var updated = new PlayerAccount(
            account.did(), "Updated", account.createdAt(),
            account.lastSeen(), "node-1", account.deviceIds());
        store.save(updated);

        var found = store.findByDid(account.did());
        assertThat(found).isPresent();
        assertThat(found.get().displayName()).isEqualTo("Updated");
        assertThat(found.get().primaryNodeId()).isEqualTo("node-1");
    }

    // --- Zone bank ---

    @Test void zonebank_get_empty_then_put_then_get() {
        assertThat(store.getZoneBank("acct-1")).isEmpty();
        store.putZoneBank("acct-1", "[{\"zoneId\":\"home-server\"}]", 1000L);
        var rec = store.getZoneBank("acct-1");
        assertThat(rec).isPresent();
        assertThat(rec.get().bankJson()).contains("home-server");
        assertThat(rec.get().updatedAt()).isEqualTo(1000L);
    }

    @Test void zonebank_put_upserts_last_write() {
        store.putZoneBank("acct-1", "[{\"zoneId\":\"home-server\"}]", 1000L);
        store.putZoneBank("acct-1", "[{\"zoneId\":\"home-server\"},{\"zoneId\":\"relay-b\"}]", 2000L);
        var rec = store.getZoneBank("acct-1").orElseThrow();
        assertThat(rec.bankJson()).contains("relay-b");
        assertThat(rec.updatedAt()).isEqualTo(2000L);
    }

    @Test void zonebank_is_per_account() {
        store.putZoneBank("acct-1", "[{\"zoneId\":\"home-server\"}]", 1L);
        assertThat(store.getZoneBank("acct-2")).isEmpty();
    }

    // --- Access requests / steward knock ---

    @Test void accessRequest_record_list_and_approve() {
        store.addAccessRequest("req-1", "home-server", "Ada", "ada@example.com", "I read your zone", 100L);
        store.addAccessRequest("req-2", "home-server", "Bea", null, null, 200L);

        var pending = store.listAccessRequests("home-server", "pending", 20);
        assertThat(pending).hasSize(2);
        // Newest first.
        assertThat(pending.get(0).id()).isEqualTo("req-2");
        assertThat(pending.get(0).requesterName()).isEqualTo("Bea");
        assertThat(pending.get(1).requesterContact()).isEqualTo("ada@example.com");

        assertThat(store.setAccessRequestStatus("req-1", "approved")).isTrue();
        assertThat(store.listAccessRequests("home-server", "pending", 20)).hasSize(1);
        assertThat(store.listAccessRequests("home-server", "approved", 20)).hasSize(1);
    }

    @Test void accessRequest_is_scoped_to_zone() {
        store.addAccessRequest("req-1", "home-server", "Ada", null, null, 1L);
        assertThat(store.listAccessRequests("relay-b", null, 20)).isEmpty();
        assertThat(store.listAccessRequests("home-server", null, 20)).hasSize(1);
    }

    @Test void accessRequest_setStatus_unknownId_isFalse() {
        assertThat(store.setAccessRequestStatus("nope", "approved")).isFalse();
    }
}
