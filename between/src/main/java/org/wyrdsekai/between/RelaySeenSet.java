package org.wyrdsekai.between;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU of recently-seen message signatures, shared across a zone's relay
 * legs ( — inbound dedup).
 *
 * <p>When a zone is multi-homed and a peer is reachable over more than one
 * shared relay, the same signed envelope can arrive via each leg. This drops the
 * duplicates so the local bus sees a single copy. The Ed25519 {@code sig} (over
 * {@code src:dst:ts:payload}) is a stable per-message id: the same envelope has
 * the same sig on every leg, while distinct messages differ by {@code ts}.</p>
 *
 * <p>Single-leg zones pass {@code null} for the dedup set (no overhead, behavior
 * identical to before multi-homing).</p>
 */
final class RelaySeenSet {

    private final int capacity;
    private final LinkedHashMap<String, Boolean> seen;

    RelaySeenSet(int capacity) {
        this.capacity = Math.max(64, capacity);
        this.seen = new LinkedHashMap<>(256, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > RelaySeenSet.this.capacity;
            }
        };
    }

    /**
     * Returns true if this signature has not been seen before (and records it);
     * false if it is a duplicate that should be dropped. A null/blank sig is
     * always "first sight" (never deduped) so unsigned/legacy traffic is
     * unaffected.
     */
    synchronized boolean firstSight(String sig) {
        if (sig == null || sig.isBlank()) return true;
        if (seen.containsKey(sig)) return false;
        seen.put(sig, Boolean.TRUE);
        return true;
    }
}
