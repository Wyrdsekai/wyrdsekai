package org.wyrdsekai.server.inference;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, thread-safe set of already-served inference {@code streamId}s — the
 * provider-side dedup proven by {@code spec/tla/InferenceRedelivery.tla} (P2).
 *
 * <p>The cross-zone inference request/reply protocol correlates a request to its
 * reply by {@code streamId} only, with no message-id / dedup header. On core NATS
 * (at-most-once) the provider serves each request exactly once. But if the
 * transport ever becomes at-least-once (a JetStream consumer, or any re-publish),
 * a redelivered request would re-enter {@code onRequest}, re-run inference, and
 * publish a <em>second</em> reply stream on the same {@code streamId} — the
 * requestor then accumulates two overlapping replies (garbled output, double GPU
 * cost). The model shows that a provider-side dedup keyed on {@code streamId}
 * restores at-most-once-served even under redelivery.</p>
 *
 * <p>Bounded via an access-ordered LRU so memory can't grow without limit; a
 * redelivery arrives close in time to the original, so a few thousand recent
 * streamIds is ample. A null streamId can't be deduped and is always treated as
 * first-sight (serve).</p>
 */
final class ServedRequestDedup {

    private final Set<String> seen;

    ServedRequestDedup(int maxEntries) {
        this.seen = Collections.synchronizedSet(Collections.newSetFromMap(
            new LinkedHashMap<>(Math.min(maxEntries, 512), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > maxEntries;
                }
            }));
    }

    /**
     * Record {@code streamId} as served and report whether this is the FIRST time
     * we've seen it.
     *
     * @return {@code true} if this is a new request the caller should serve;
     *         {@code false} if it is a duplicate/redelivery the caller should drop.
     */
    boolean firstSight(String streamId) {
        if (streamId == null || streamId.isBlank()) {
            return true;   // can't dedup an unidentified request — serve it
        }
        return seen.add(streamId);
    }
}
