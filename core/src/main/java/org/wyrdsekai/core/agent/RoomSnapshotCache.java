package org.wyrdsekai.core.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * / Phase 2b — short TTL cache for {@code world.peek}
 * snapshots.
 *
 * <p>A script that loops over many rooms ({@code rooms.forEach(r =>
 * world.peek(r))}) would otherwise hit the same {@code RoomActor} repeatedly
 * via Pekko ask, hammering the actor's mailbox. The 30s TTL is short enough
 * that "now" feels live, long enough to absorb a tight script loop.</p>
 *
 * <p>Pure in-memory; instance-scoped so each {@link CompanionActor} owns its
 * own cache (they have different perception scopes — cache invalidation is
 * naturally per-actor). Thread-safe via {@link ConcurrentHashMap}; reads use
 * a {@link java.lang.System#nanoTime()} comparison so concurrent writes
 * don't matter.</p>
 *
 * <p>Eviction is two-layered: (a) lazy on read — TTL-expired entries are
 * removed when looked up; (b) bounded-size on write — when the entry count
 * exceeds {@link #DEFAULT_MAX_ENTRIES}, the oldest entries (by cached time)
 * are pruned to bring the cache back under cap. Same shape as the FIFO-style
 * eviction used in {@code HearthJournal} and {@code McpAuditLog}: insertion
 * order, drop oldest first. Household-scale 200 entries comfortably absorbs
 * a tight room-walk loop without growing without bound under sustained
 * cross-zone churn.</p>
 */
public final class RoomSnapshotCache {

    /** Spec §8 task §D: 30s TTL — script-loop friendly, perception-fresh. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    /** Bounded-size cap. Household-scale (1-20 nodes × few rooms each). */
    public static final int DEFAULT_MAX_ENTRIES = 200;

    private final Duration ttl;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public RoomSnapshotCache() { this(DEFAULT_TTL, DEFAULT_MAX_ENTRIES); }
    public RoomSnapshotCache(Duration ttl) { this(ttl, DEFAULT_MAX_ENTRIES); }
    public RoomSnapshotCache(Duration ttl, int maxEntries) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
    }

    /**
     * Lookup the cached snapshot for {@code key} (typically {@code "zone.room"}
     * for cross-zone or {@code "room"} for same-zone remote). Returns null
     * when there's no entry or the entry has aged past the TTL.
     */
    public Map<String, Object> get(String key) {
        if (key == null) return null;
        var e = entries.get(key);
        if (e == null) return null;
        if (Instant.now().isAfter(e.expiresAt)) {
            entries.remove(key, e);
            return null;
        }
        return e.snapshot;
    }

    /** Store a snapshot under {@code key}. Null snapshots are cached as null
     *  (so a known-not-there room doesn't trigger fresh asks every loop iter).
     *  When the cache exceeds {@link #DEFAULT_MAX_ENTRIES}, the oldest
     *  entries (by cached time) are pruned. */
    public void put(String key, Map<String, Object> snapshot) {
        if (key == null) return;
        var now = Instant.now();
        entries.put(key, new Entry(snapshot, now, now.plus(ttl)));
        if (entries.size() > maxEntries) {
            evictOldest();
        }
    }

    /**
     * Drop oldest-by-cached-time entries until size is back at the cap.
     * Synchronized so concurrent puts don't double-evict; cheap because
     * we only run when over cap.
     */
    private synchronized void evictOldest() {
        // Re-check inside the lock — another thread may have evicted already.
        int over = entries.size() - maxEntries;
        if (over <= 0) return;
        // Grab keys sorted oldest-first by cachedAt.
        var oldest = entries.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getValue().cachedAt))
            .limit(over)
            .toList();
        for (var e : oldest) {
            entries.remove(e.getKey(), e.getValue());
        }
    }

    /** Drop everything — used for tests and forced refresh. */
    public void clear() { entries.clear(); }

    public int size() { return entries.size(); }

    public int maxEntries() { return maxEntries; }

    private record Entry(Map<String, Object> snapshot, Instant cachedAt, Instant expiresAt) {}
}
