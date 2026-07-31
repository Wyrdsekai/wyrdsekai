package org.wyrdsekai.core.naming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Zone discovery surface.
 *
 * <p>Publishes and looks up {@link ZoneManifestV1}s. Phase-1 implementation
 * uses the existing NATS relay as transport ({@code NatsZoneDirectory});
 * Phase-2 migrates to libp2p DHT for global addressability. The interface
 * is the migration seam — callers code against this, implementations
 * swap.</p>
 *
 * <p>Lookup semantics:</p>
 * <ul>
 *   <li>{@link #lookup(String)} — exact DID → manifest. Returns empty when
 *       the zone hasn't published or has been tombstoned.</li>
 *   <li>{@link #discoverByTag(String)} — tag query → DIDs. Manifests keyed
 *       by tag via secondary publish (§5.2 "Tag-keyed secondary entries").
 *       Caller resolves each DID to a full manifest with a follow-up lookup.</li>
 *   <li>{@link #publish(ZoneManifestV1)} — put. Must be called at least
 *       once; re-publish refreshes TTL. Rejects if manifest fails validation.</li>
 *   <li>{@link #unpublish(String)} — signed tombstone. Immediate removal
 *       across replicas.</li>
 * </ul>
 */
public interface ZoneDirectory {

    /** Publish a manifest. Fails if the manifest is invalid or over size cap. */
    void publish(ZoneManifestV1 manifest);

    /** Explicit tombstone — removes {@code did}'s manifest from the directory. */
    void unpublish(String did);

    /** @return the manifest for {@code did}, or empty if not published. */
    Optional<ZoneManifestV1> lookup(String did);

    /** @return DIDs tagged with {@code tag}. Does not fetch manifests. */
    List<String> discoverByTag(String tag);

    /** @return all published manifests (bounded — implementations may cap). */
    List<ZoneManifestV1> recent(int limit);

    /**
     * @return DIDs advertising {@code capability} — a room label, agent
     *     label, agent skill, or zone-level capability key. Default
     *     implementation scans {@link #recent} and filters in-memory;
     *     backends with an indexed path (e.g. a rendezvous with a
     *     capability-index) should override for performance.
     *
     * <p>Matching is case-insensitive and matches against:</p>
     * <ul>
     *   <li>{@code capabilities.rooms[].label}</li>
     *   <li>{@code capabilities.agents[].label}</li>
     *   <li>{@code capabilities.agents[].skills[]} (when populated)</li>
     * </ul>
     */
    default List<String> discoverByCapability(String capability) {
        if (capability == null || capability.isBlank()) return List.of();
        var needle = capability.trim().toLowerCase(Locale.ROOT);
        var out = new ArrayList<String>();
        for (var m : recent(1000)) {
            if (matchesCapability(m, needle)) out.add(m.did());
        }
        return out;
    }

    /** Helper used by the default {@link #discoverByCapability} implementation. */
    static boolean matchesCapability(ZoneManifestV1 m, String needleLower) {
        if (m.capabilities() == null) return false;
        var caps = m.capabilities();
        if (caps.rooms() != null) {
            for (var r : caps.rooms()) {
                if (r.label() != null && r.label().toLowerCase().contains(needleLower)) return true;
            }
        }
        if (caps.agents() != null) {
            for (var a : caps.agents()) {
                if (a.label() != null && a.label().toLowerCase().contains(needleLower)) return true;
                if (a.role() != null && a.role().toLowerCase().contains(needleLower)) return true;
                if (a.skills() != null) {
                    for (var s : a.skills()) {
                        if (s != null && s.toLowerCase().contains(needleLower)) return true;
                    }
                }
            }
        }
        return false;
    }
}
