package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W5 Family Locker sync (2026-07-11): local store → hub → publisher, and
 * remote item/tombstone → hub → merge into every registered locker of the
 * family. Uses unique family ids per test — the hub is a process singleton.
 */
class LockerSyncHubTest {

    private record Captured(String familyId, LockerSyncHub.RemoteItem item) {}

    private FamilyLocker newLocker(String familyId, String did) {
        var locker = new FamilyLocker(familyId, "local");
        locker.authorize(SoulBud.original(did, "local", familyId, "local", "local", "8b"));
        return locker;
    }

    @Test
    void local_store_publishes_and_remote_item_merges() {
        var familyId = "fam-" + UUID.randomUUID();
        var published = new CopyOnWriteArrayList<Captured>();
        var subscribed = ConcurrentHashMap.<String>newKeySet();

        var hub = LockerSyncHub.get();
        hub.setTransport(
            (fid, item) -> published.add(new Captured(fid, item)),
            (fid, ts) -> { },
            subscribed::add);

        // Registration (via authorize) subscribes the family
        var locker = newLocker(familyId, "did:wyrd:a");
        assertTrue(subscribed.contains(familyId),
            "registering a locker must subscribe its family's channel");

        // Local store publishes the item on the family channel
        var item = SoulItem.create("memory", "label", "the text of a memory",
            "did:wyrd:a", 0.7);
        locker.store(item, "did:wyrd:a");
        assertEquals(1, published.stream().filter(c -> c.familyId().equals(familyId)).count());
        var wire = published.stream().filter(c -> c.familyId().equals(familyId))
            .findFirst().orElseThrow().item();
        assertEquals(item.hash(), wire.hash());
        assertEquals("the text of a memory", wire.text());

        // A remote item for the same family merges into the locker
        var remoteSource = SoulItem.create("memory", "remote", "remote text",
            "did:wyrd:b", 0.5);
        hub.applyRemoteItem(familyId, new LockerSyncHub.RemoteItem(
            remoteSource.hash(), remoteSource.category(), remoteSource.label(),
            remoteSource.text(), remoteSource.creatorDid(),
            remoteSource.significance(), System.currentTimeMillis()));
        assertTrue(locker.get(remoteSource.hash(), "did:wyrd:a").isPresent(),
            "remote item must merge into the registered locker");

        // Remote merge must NOT re-publish (no gossip loop)
        assertEquals(1, published.stream().filter(c -> c.familyId().equals(familyId)).count(),
            "merging a remote item must not publish it again");
    }

    @Test
    void remote_tombstone_hides_item_in_registered_locker() {
        var familyId = "fam-" + UUID.randomUUID();
        var tombstonesOut = new CopyOnWriteArrayList<LockerSyncHub.RemoteTombstone>();
        var hub = LockerSyncHub.get();
        hub.setTransport(
            (fid, item) -> { },
            (fid, ts) -> { if (fid.equals(familyId)) tombstonesOut.add(ts); },
            fid -> { });

        var locker = newLocker(familyId, "did:wyrd:a");
        var item = SoulItem.create("memory", "doomed", "to be deleted",
            "did:wyrd:a", 0.4);
        locker.store(item, "did:wyrd:a");

        // Local tombstone publishes
        locker.tombstone(item.hash(), "did:wyrd:a", "cleanup");
        assertEquals(1, tombstonesOut.size());
        assertEquals(item.hash(), tombstonesOut.get(0).itemHash());

        // A remote tombstone hides an item in a second locker of the family
        var locker2 = newLocker(familyId, "did:wyrd:a");
        var item2 = SoulItem.create("memory", "elsewhere", "deleted remotely",
            "did:wyrd:a", 0.4);
        locker2.store(item2, "did:wyrd:a");
        hub.applyRemoteTombstone(familyId, new LockerSyncHub.RemoteTombstone(
            item2.hash(), "did:wyrd:b", "remote cleanup", System.currentTimeMillis()));
        assertTrue(locker2.get(item2.hash(), "did:wyrd:a").isEmpty(),
            "remote tombstone must hide the item");
    }

    @Test
    void unwired_hub_is_a_silent_no_op() {
        // No transport (or a fresh family after transport reset) — store must not throw.
        var familyId = "fam-" + UUID.randomUUID();
        var hub = LockerSyncHub.get();
        hub.setTransport(null, null, null);
        var locker = newLocker(familyId, "did:wyrd:a");
        var item = SoulItem.create("memory", "quiet", "single-node text",
            "did:wyrd:a", 0.2);
        assertDoesNotThrow(() -> locker.store(item, "did:wyrd:a"));
        assertDoesNotThrow(() -> hub.applyRemoteItem(familyId,
            new LockerSyncHub.RemoteItem(item.hash(), "memory", "quiet",
                "single-node text", "did:wyrd:a", 0.2, System.currentTimeMillis())));
        assertNotNull(locker.get(item.hash(), "did:wyrd:a").orElse(null));
    }
}
