package org.wyrdsekai.core.naming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory directory (tests, single-node dev, fallback).
 *
 * <p>Thread-safe, no persistence, no TTL eviction. Useful as the
 * baseline against which the NATS-backed implementation's contract is
 * exercised — same interface tests run against both.</p>
 */
public final class InMemoryZoneDirectory implements ZoneDirectory {

    private final ConcurrentMap<String, ZoneManifestV1> byDid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> byTag = new ConcurrentHashMap<>();

    @Override
    public void publish(ZoneManifestV1 manifest) {
        manifest.validate();
        // Replace any prior entry under this DID.
        var prior = byDid.put(manifest.did(), manifest);
        // Remove old tag indexes for the prior version.
        if (prior != null && prior.tags() != null) {
            for (var t : prior.tags()) {
                var set = byTag.get(t);
                if (set != null) set.remove(prior.did());
            }
        }
        if (manifest.tags() != null) {
            for (var t : manifest.tags()) {
                byTag.computeIfAbsent(t, k ->
                    Collections.newSetFromMap(new ConcurrentHashMap<>())).add(manifest.did());
            }
        }
    }

    @Override
    public void unpublish(String did) {
        var prior = byDid.remove(did);
        if (prior != null && prior.tags() != null) {
            for (var t : prior.tags()) {
                var set = byTag.get(t);
                if (set != null) set.remove(did);
            }
        }
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        return Optional.ofNullable(byDid.get(did));
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var set = byTag.get(tag);
        if (set == null) return List.of();
        return List.copyOf(set);
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        // No timestamp ordering here — just bounded enumeration. Production
        // backends sort by refreshed_at.
        var result = new ArrayList<ZoneManifestV1>();
        for (var m : byDid.values()) {
            result.add(m);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** Test helper. */
    public int size() {
        return byDid.size();
    }
}
