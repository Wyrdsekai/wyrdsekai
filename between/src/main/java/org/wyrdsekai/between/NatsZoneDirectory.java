package org.wyrdsekai.between;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneDirectory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * NATS-backed {@link ZoneDirectory} (, Phase-1 impl).
 *
 * <p>Uses the existing relay as a KV / pub-sub substrate. Not a proper
 * DHT — single-relay reach, no global peer addressability — but ships
 * working discovery today on infrastructure we already run. Migrate to
 * libp2p Kademlia when scale demands.</p>
 *
 * <h2>Subject layout</h2>
 *
 * <ul>
 *   <li>{@code directory.manifest.{did-encoded}} — put/get for a zone's
 *       full manifest. DID is URL-encoded (replace {@code :} with {@code _})
 *       to stay NATS-safe.</li>
 *   <li>{@code directory.tag.{tag}} — append-only tag index. Each publish
 *       sends the DID on this subject; subscribers cache the DID set.</li>
 *   <li>{@code directory.tombstone} — signed "unpublish" broadcasts.</li>
 * </ul>
 *
 * <h2>Local cache</h2>
 *
 * <p>The directory maintains an in-memory cache populated by subscriptions.
 * {@link #lookup} reads from the cache — NATS doesn't give us request/reply
 * for free on abstract subjects, and caching avoids round-trips. Cache
 * hydrates as peers publish; a cold-start node sees an empty directory
 * until other zones refresh their manifests (spec §5.2 "TTL ~24h, refresh
 * periodically").</p>
 */
public final class NatsZoneDirectory implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(NatsZoneDirectory.class);

    public static final String MANIFEST_PREFIX = "directory.manifest.";
    public static final String TAG_PREFIX = "directory.tag.";
    public static final String TOMBSTONE_SUBJECT = "directory.tombstone";
    public static final String MANIFEST_WILDCARD = "directory.manifest.>";
    public static final String TAG_WILDCARD = "directory.tag.>";

    private final NatsBridge nats;
    private final ConcurrentMap<String, ZoneManifestV1> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();

    public NatsZoneDirectory(NatsBridge nats) {
        this.nats = nats;
        subscribe();
    }

    /** Encode a DID into a NATS-subject-safe token. DIDs use {@code :},
     *  NATS uses {@code .} as subject separator — we map {@code :} to
     *  {@code _} and back at read time. */
    static String encodeDid(String did) {
        return did.replace(':', '_');
    }

    static String decodeDid(String encoded) {
        return encoded.replace('_', ':');
    }

    private void subscribe() {
        nats.subscribeRaw(MANIFEST_WILDCARD, data -> {
            try {
                var m = ZoneManifestV1.fromJsonBytes(data);
                cache.put(m.did(), m);
                // Rebuild tag index for this DID.
                if (m.tags() != null) {
                    for (var t : m.tags()) {
                        tagIndex.computeIfAbsent(t,
                            k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(m.did());
                    }
                }
                log.debug("Directory: cached manifest for {}", m.did());
            } catch (Exception e) {
                log.warn("Directory: malformed manifest on wire ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            }
        });

        nats.subscribeRaw(TOMBSTONE_SUBJECT, data -> {
            // Tombstone payload is just the DID bytes. Signature verification
            // lives at the caller's envelope layer (§5.2 "Owner-signed tombstones");
            // this subscription trusts the source because the relay only
            // forwards federation-signed traffic (§3.1 NATS account ACL).
            try {
                var did = new String(data, StandardCharsets.UTF_8);
                var removed = cache.remove(did);
                if (removed != null && removed.tags() != null) {
                    for (var t : removed.tags()) {
                        var set = tagIndex.get(t);
                        if (set != null) set.remove(did);
                    }
                }
                log.info("Directory: tombstoned {}", did);
            } catch (Exception e) {
                log.warn("Directory: malformed tombstone: {}", e.getMessage());
            }
        });
    }

    @Override
    public void publish(ZoneManifestV1 manifest) {
        manifest.validate();
        var bytes = manifest.toJsonBytes();
        nats.publishRaw(MANIFEST_PREFIX + encodeDid(manifest.did()), bytes);
        // Populate local cache immediately — we don't want to round-trip
        // to read our own publish.
        cache.put(manifest.did(), manifest);
        if (manifest.tags() != null) {
            for (var t : manifest.tags()) {
                tagIndex.computeIfAbsent(t,
                    k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(manifest.did());
                // Secondary tag-index publish. Body is the DID bytes.
                nats.publishRaw(TAG_PREFIX + t,
                    manifest.did().getBytes(StandardCharsets.UTF_8));
            }
        }
        log.info("Directory: published manifest for {} ({} tags)",
            manifest.did(), manifest.tags() != null ? manifest.tags().size() : 0);
    }

    @Override
    public void unpublish(String did) {
        var removed = cache.remove(did);
        if (removed != null && removed.tags() != null) {
            for (var t : removed.tags()) {
                var set = tagIndex.get(t);
                if (set != null) set.remove(did);
            }
        }
        nats.publishRaw(TOMBSTONE_SUBJECT,
            did.getBytes(StandardCharsets.UTF_8));
        log.info("Directory: unpublished {}", did);
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        return Optional.ofNullable(cache.get(did));
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var set = tagIndex.get(tag);
        if (set == null) return List.of();
        return List.copyOf(set);
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        // Sort by refreshed_at descending when present. Null-safe: manifests
        // without refreshed_at go last.
        var all = new ArrayList<>(cache.values());
        all.sort((a, b) -> {
            var ta = a.refreshedAt() == null ? "" : a.refreshedAt();
            var tb = b.refreshedAt() == null ? "" : b.refreshedAt();
            return tb.compareTo(ta);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** Test / diagnostic — current cache size. */
    public int cacheSize() {
        return cache.size();
    }
}
