package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide hub connecting in-memory {@link FamilyLocker} instances to the
 * Between's Family Locker replication channel (W5, ).
 *
 * <p>Core cannot depend on the between module, so the hub speaks only core
 * types: the between side (BetweenActor) installs the transport callbacks via
 * {@link #setTransport} and adapts them to {@code BetweenLockerBridge}, whose
 * NATS subjects are {@code wyrd.locker.<familyId>.items} /
 * {@code .tombstones}.</p>
 *
 * <p>Flow:</p>
 * <ul>
 *   <li>local {@link FamilyLocker#store} → {@link #onLocalStore} → publisher →
 *       NATS (no-op until the transport is wired — single-node stays silent)</li>
 *   <li>NATS → {@link #applyRemoteItem} → {@link FamilyLocker#mergeItems} on
 *       every registered locker of that family (merge bypasses store(), so a
 *       received item is never re-published — no gossip loop)</li>
 * </ul>
 *
 * <p>Lockers register themselves on {@link FamilyLocker#authorize}; the map
 * holds them weakly so respawned companions' abandoned lockers don't leak.</p>
 */
public final class LockerSyncHub {

    private static final Logger log = LoggerFactory.getLogger(LockerSyncHub.class);
    private static final LockerSyncHub INSTANCE = new LockerSyncHub();

    public static LockerSyncHub get() { return INSTANCE; }

    private LockerSyncHub() {}

    /** Wire shape of a replicated soul item (mirrors BetweenLockerBridge.ItemMessage). */
    public record RemoteItem(String hash, String category, String label, String text,
                             String creatorDid, double significance, long timestamp) {}

    /** Wire shape of a replicated tombstone (mirrors BetweenLockerBridge.TombstoneMessage). */
    public record RemoteTombstone(String itemHash, String deletedBy, String reason,
                                  long timestamp) {}

    @FunctionalInterface
    public interface ItemPublisher { void publish(String familyId, RemoteItem item); }

    @FunctionalInterface
    public interface TombstonePublisher { void publish(String familyId, RemoteTombstone tombstone); }

    /** Called once per family so the transport can subscribe its NATS subjects. */
    @FunctionalInterface
    public interface FamilySubscriber { void subscribe(String familyId); }

    // familyId → (locker → authorized local DID used for merges). Weak keys:
    // respawned companions abandon their old locker instances.
    private final Map<String, Map<FamilyLocker, String>> lockersByFamily = new ConcurrentHashMap<>();

    private volatile ItemPublisher itemPublisher;
    private volatile TombstonePublisher tombstonePublisher;
    private volatile FamilySubscriber familySubscriber;

    /**
     * Install the Between transport. Families registered before the transport
     * arrives get their subscriptions immediately; later registrations
     * subscribe as they appear.
     */
    public synchronized void setTransport(ItemPublisher items, TombstonePublisher tombstones,
                                          FamilySubscriber subscriber) {
        this.itemPublisher = items;
        this.tombstonePublisher = tombstones;
        this.familySubscriber = subscriber;
        if (subscriber != null) {
            for (var familyId : lockersByFamily.keySet()) {
                trySubscribe(familyId);
            }
        }
        log.info("LockerSyncHub: transport installed ({} families registered)",
            lockersByFamily.size());
    }

    /**
     * Register a locker for replication. Idempotent. {@code localDid} is the
     * authorized DID the hub uses when merging remote items into this locker.
     */
    public void registerLocker(FamilyLocker locker, String localDid) {
        if (locker == null || localDid == null) return;
        var familyId = locker.familyId();
        var family = lockersByFamily.computeIfAbsent(familyId,
            _ -> Collections.synchronizedMap(new WeakHashMap<>()));
        boolean firstForFamily = family.isEmpty();
        family.put(locker, localDid);
        if (firstForFamily) {
            trySubscribe(familyId);
        }
    }

    /** Called by {@link FamilyLocker#store} after a local write. */
    public void onLocalStore(String familyId, SoulItem item) {
        var publisher = itemPublisher;
        if (publisher == null || item == null) return;
        try {
            publisher.publish(familyId, new RemoteItem(
                item.hash(), item.category(), item.label(), item.text(),
                item.creatorDid(), item.significance(),
                item.created() != null ? item.created().toEpochMilli()
                                       : System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("LockerSyncHub: item publish failed for family {}: {}",
                familyId, e.getMessage());
        }
    }

    /** Called by {@link FamilyLocker#tombstone} after a local delete. */
    public void onLocalTombstone(String familyId, FamilyLocker.Tombstone tombstone) {
        var publisher = tombstonePublisher;
        if (publisher == null || tombstone == null) return;
        try {
            publisher.publish(familyId, new RemoteTombstone(
                tombstone.itemHash(), tombstone.deletedBy(), tombstone.reason(),
                tombstone.deletedAt() != null ? tombstone.deletedAt().toEpochMilli()
                                              : System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("LockerSyncHub: tombstone publish failed for family {}: {}",
                familyId, e.getMessage());
        }
    }

    /** Apply an item received from a peer node to every local locker of the family. */
    public void applyRemoteItem(String familyId, RemoteItem remote) {
        var family = lockersByFamily.get(familyId);
        if (family == null || remote == null) return;
        var item = new SoulItem(remote.hash(), remote.category(), remote.label(),
            remote.text(), null, remote.creatorDid(), null,
            Instant.ofEpochMilli(remote.timestamp()), Instant.now(),
            remote.significance(), new String[0]);
        synchronized (family) {
            for (var entry : family.entrySet()) {
                try {
                    // mergeItems is idempotent (content-addressed) and does NOT
                    // route through store() — received items are never re-published.
                    entry.getKey().mergeItems(List.of(item), entry.getValue());
                } catch (Exception e) {
                    log.debug("LockerSyncHub: merge failed for family {}: {}",
                        familyId, e.getMessage());
                }
            }
        }
    }

    /** Apply a tombstone received from a peer node. */
    public void applyRemoteTombstone(String familyId, RemoteTombstone remote) {
        var family = lockersByFamily.get(familyId);
        if (family == null || remote == null) return;
        var tombstone = new FamilyLocker.Tombstone(remote.itemHash(), remote.deletedBy(),
            Instant.ofEpochMilli(remote.timestamp()), remote.reason());
        synchronized (family) {
            for (var locker : family.keySet()) {
                try {
                    locker.applyTombstones(List.of(tombstone));
                } catch (Exception e) {
                    log.debug("LockerSyncHub: tombstone apply failed for family {}: {}",
                        familyId, e.getMessage());
                }
            }
        }
    }

    /** Number of families currently registered (diagnostics/tests). */
    public int registeredFamilyCount() {
        return lockersByFamily.size();
    }

    private void trySubscribe(String familyId) {
        var subscriber = familySubscriber;
        if (subscriber == null) return;
        try {
            subscriber.subscribe(familyId);
        } catch (Exception e) {
            log.warn("LockerSyncHub: subscribe failed for family {}: {}",
                familyId, e.getMessage());
        }
    }
}
