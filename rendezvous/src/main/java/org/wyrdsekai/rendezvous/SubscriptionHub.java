package org.wyrdsekai.rendezvous;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.naming.ZoneDirectory;
import org.wyrdsekai.core.naming.ZoneManifestV1;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE fan-out. Agents subscribe by {@code tag}
 * or {@code capability}; on each publish or tombstone, matching
 * subscribers receive one of:
 *
 * <ul>
 *   <li>{@code event: added} — new DID matching the filter</li>
 *   <li>{@code event: updated} — existing DID's manifest refreshed</li>
 *   <li>{@code event: removed} — DID tombstoned (data: {"did":"..."})</li>
 * </ul>
 *
 * <p>Data payload is the full manifest JSON (for added/updated) or
 * a {@code {"did":"…"}} envelope (for removed).</p>
 *
 * <h2>Testability</h2>
 *
 * <p>The hub talks to subscribers via {@link SseSink}, an abstraction
 * that production code wires to Javalin's {@code SseClient} and tests
 * supply a capturing implementation. This keeps the hub independent
 * of Javalin for unit tests.</p>
 */
public final class SubscriptionHub {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionHub.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Minimal sink surface — mirror of Javalin's SseClient. */
    public interface SseSink {
        /** @return a stable id so the hub can dedupe and cleanup. */
        long id();
        void send(String event, String jsonData);
        void close();
        boolean isClosed();
    }

    /** Tag or capability filter. Matched case-insensitively. */
    public record Filter(String tag, String capability) {
        public boolean matches(ZoneManifestV1 m) {
            if (tag != null && !tag.isBlank()) {
                if (m.tags() == null) return false;
                var needle = tag.toLowerCase();
                for (var t : m.tags()) {
                    if (t != null && t.toLowerCase().equals(needle)) return true;
                }
                return false;
            }
            if (capability != null && !capability.isBlank()) {
                return ZoneDirectory.matchesCapability(m, capability.toLowerCase());
            }
            return true; // empty filter = match all
        }

        /** Human-readable label used in logs + "key" maps. */
        public String label() {
            if (tag != null && !tag.isBlank()) return "tag=" + tag;
            if (capability != null && !capability.isBlank()) return "cap=" + capability;
            return "all";
        }
    }

    private static final AtomicLong NEXT_ID = new AtomicLong();

    /** Track every active subscription globally by sink id. */
    private final ConcurrentMap<Long, Subscription> subs = new ConcurrentHashMap<>();

    /**
     * Register a new subscription. Returns an id the caller can pass
     * to {@link #unsubscribe(long)} on client disconnect. The returned
     * id matches {@link SseSink#id()} — callers can use either.
     */
    public long subscribe(SseSink sink, Filter filter) {
        var id = sink.id();
        subs.put(id, new Subscription(sink, filter));
        log.debug("SSE subscriber {} added ({}), total={}", id, filter.label(), subs.size());
        return id;
    }

    public void unsubscribe(long id) {
        var s = subs.remove(id);
        if (s != null) {
            log.debug("SSE subscriber {} removed, total={}", id, subs.size());
        }
    }

    /** Notify subscribers whose filter matches of a publish/update. */
    public void notifyPublished(ZoneManifestV1 manifest, boolean isNew) {
        var event = isNew ? "added" : "updated";
        String payload;
        try {
            payload = new String(manifest.toJsonBytes());
        } catch (Exception e) {
            log.debug("manifest serialize failed for {}: {}", manifest.did(), e.getMessage());
            return;
        }
        dispatch(manifest, event, payload);
    }

    /** Notify subscribers whose filter matched the removed manifest. */
    public void notifyRemoved(ZoneManifestV1 manifest) {
        if (manifest == null) return;
        String payload;
        try {
            payload = MAPPER.writeValueAsString(Map.of("did", manifest.did()));
        } catch (Exception e) {
            payload = "{\"did\":\"" + manifest.did().replace("\"", "\\\"") + "\"}";
        }
        dispatch(manifest, "removed", payload);
    }

    private void dispatch(ZoneManifestV1 manifest, String event, String payload) {
        var toRemove = new HashSet<Long>();
        for (var e : subs.entrySet()) {
            var sub = e.getValue();
            if (sub.sink.isClosed()) {
                toRemove.add(e.getKey());
                continue;
            }
            if (!sub.filter.matches(manifest)) continue;
            try {
                sub.sink.send(event, payload);
            } catch (Exception ex) {
                log.debug("SSE send failed for {}: {}", e.getKey(), ex.getMessage());
                toRemove.add(e.getKey());
                try { sub.sink.close(); } catch (Exception ignored) {}
            }
        }
        for (var id : toRemove) subs.remove(id);
    }

    public int activeSubscriberCount() {
        return subs.size();
    }

    /** @return a fresh sink id for callers that need one (production SseClient wrappers). */
    public static long nextSinkId() {
        return NEXT_ID.incrementAndGet();
    }

    private record Subscription(SseSink sink, Filter filter) {}
}
