package org.wyrdsekai.core.naming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Composition backend. Fans out writes to all
 * configured backends and merges reads by {@code refreshed_at} (newest
 * wins; tiebreak on signature presence).
 *
 * <p>The rationale is resilience plus progressive deployment: ship
 * {@code .well-known} + NATS today, add federated + rendezvous later
 * without breaking callers. Any single backend can fail without taking
 * down discovery — the remaining backends still serve queries.</p>
 *
 * <h2>Merge semantics</h2>
 *
 * <ul>
 *   <li>{@link #publish(ZoneManifestV1)} — fire-and-forget to every
 *       backend. A backend that throws logs a WARN but does not block
 *       the others.</li>
 *   <li>{@link #unpublish(String)} — same fan-out.</li>
 *   <li>{@link #lookup(String)} — query each backend; choose the
 *       manifest with the newest {@code refreshed_at}. If all tie,
 *       prefer the one that carries a signature.</li>
 *   <li>{@link #discoverByTag(String)} — union of DIDs across backends.</li>
 *   <li>{@link #recent(int)} — merge all manifests, dedupe by DID
 *       (keep newest), sort by {@code refreshed_at} desc, trim to
 *       limit.</li>
 * </ul>
 *
 * <p>Backend order matters only for one thing: the first listed is the
 * "primary" for publish retries and logs. Reads are driven by
 * {@code refreshed_at}, not order.</p>
 */
public final class CompositeZoneDirectory implements ZoneDirectory {

    private static final Logger log = LoggerFactory.getLogger(CompositeZoneDirectory.class);

    private final List<ZoneDirectory> backends;

    public CompositeZoneDirectory(List<ZoneDirectory> backends) {
        if (backends == null || backends.isEmpty()) {
            throw new IllegalArgumentException("CompositeZoneDirectory requires >=1 backend");
        }
        this.backends = List.copyOf(backends);
    }

    @Override
    public void publish(ZoneManifestV1 manifest) {
        for (var backend : backends) {
            try {
                backend.publish(manifest);
            } catch (Exception e) {
                log.warn("publish failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public void unpublish(String did) {
        for (var backend : backends) {
            try {
                backend.unpublish(did);
            } catch (Exception e) {
                log.warn("unpublish failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    @Override
    public Optional<ZoneManifestV1> lookup(String did) {
        ZoneManifestV1 best = null;
        for (var backend : backends) {
            try {
                var r = backend.lookup(did);
                if (r.isEmpty()) continue;
                if (best == null || preferNewer(r.get(), best)) {
                    best = r.get();
                }
            } catch (Exception e) {
                log.debug("lookup failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
        return Optional.ofNullable(best);
    }

    @Override
    public List<String> discoverByTag(String tag) {
        var seen = new LinkedHashSet<String>();
        for (var backend : backends) {
            try {
                seen.addAll(backend.discoverByTag(tag));
            } catch (Exception e) {
                log.debug("discoverByTag failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
        return List.copyOf(seen);
    }

    @Override
    public List<String> discoverByCapability(String capability) {
        var seen = new LinkedHashSet<String>();
        for (var backend : backends) {
            try {
                seen.addAll(backend.discoverByCapability(capability));
            } catch (Exception e) {
                log.debug("discoverByCapability failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
        return List.copyOf(seen);
    }

    @Override
    public List<ZoneManifestV1> recent(int limit) {
        // Dedupe by DID, keep newest across backends.
        var merged = new HashMap<String, ZoneManifestV1>();
        for (var backend : backends) {
            try {
                for (var m : backend.recent(Math.max(limit, 50))) {
                    var prev = merged.get(m.did());
                    if (prev == null || preferNewer(m, prev)) {
                        merged.put(m.did(), m);
                    }
                }
            } catch (Exception e) {
                log.debug("recent failed for backend {}: {}",
                    backend.getClass().getSimpleName(), e.getMessage());
            }
        }
        var all = new ArrayList<>(merged.values());
        all.sort((a, b) -> {
            var ta = a.refreshedAt() == null ? "" : a.refreshedAt();
            var tb = b.refreshedAt() == null ? "" : b.refreshedAt();
            return tb.compareTo(ta);
        });
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /**
     * Choose which of two manifests for the same DID should win.
     *
     * <p>Primary criterion: newer {@code refreshed_at}. Tiebreaker:
     * whichever has a non-null {@code signature} (defense-in-depth —
     * prefer the verifiable copy when timestamps are equal).</p>
     *
     * @return true if {@code candidate} should replace {@code incumbent}
     */
    private static boolean preferNewer(ZoneManifestV1 candidate, ZoneManifestV1 incumbent) {
        var ca = candidate.refreshedAt() == null ? "" : candidate.refreshedAt();
        var ia = incumbent.refreshedAt() == null ? "" : incumbent.refreshedAt();
        int cmp = ca.compareTo(ia);
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        // Tie — prefer signed.
        return candidate.signature() != null && incumbent.signature() == null;
    }

    /** Diagnostic — ordered list of configured backend class names. */
    public List<String> backendNames() {
        var out = new LinkedHashMap<String, Boolean>();
        for (var b : backends) out.put(b.getClass().getSimpleName(), true);
        return List.copyOf(out.keySet());
    }

    /** Diagnostic — number of backends configured. */
    public int backendCount() {
        return backends.size();
    }
}
