package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Phase 2b — short-TTL snapshot cache for
 * {@code world.peek}. Verifies put/get behaviour, TTL aging, and
 * null-snapshot caching (so known-missing peeks don't hammer the relay).
 */
class RoomSnapshotCacheTest {

    @Test void put_and_get_within_ttl_returns_snapshot() {
        var cache = new RoomSnapshotCache(Duration.ofSeconds(30));
        cache.put("foo", Map.of("name", "Foo"));
        assertThat(cache.get("foo")).isEqualTo(Map.of("name", "Foo"));
    }

    @Test void get_unknown_key_returns_null() {
        var cache = new RoomSnapshotCache();
        assertThat(cache.get("never-set")).isNull();
        assertThat(cache.get(null)).isNull();
    }

    @Test void entry_expires_after_ttl() throws InterruptedException {
        var cache = new RoomSnapshotCache(Duration.ofMillis(50));
        cache.put("foo", Map.of("name", "Foo"));
        assertThat(cache.get("foo")).isNotNull();

        Thread.sleep(80);
        assertThat(cache.get("foo")).isNull();
    }

    @Test void null_snapshot_is_cached_to_avoid_re_query() {
        // When a peek returned null (not authorized / no such room), we still
        // cache the null so repeated script calls don't hammer the actor.
        // get() returns null whether the entry is missing or null-cached;
        // size() distinguishes.
        var cache = new RoomSnapshotCache();
        cache.put("missing", null);
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("missing")).isNull();
    }

    @Test void clear_drops_all_entries() {
        var cache = new RoomSnapshotCache();
        cache.put("a", Map.of("name", "A"));
        cache.put("b", Map.of("name", "B"));
        assertThat(cache.size()).isEqualTo(2);

        cache.clear();
        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get("a")).isNull();
    }

    @Test void put_with_null_key_is_noop() {
        var cache = new RoomSnapshotCache();
        cache.put(null, Map.of("name", "X"));
        assertThat(cache.size()).isEqualTo(0);
    }

    @Test void overwrite_existing_entry_resets_ttl() throws InterruptedException {
        var cache = new RoomSnapshotCache(Duration.ofMillis(100));
        cache.put("foo", Map.of("v", 1));
        Thread.sleep(70);
        cache.put("foo", Map.of("v", 2));
        Thread.sleep(50);
        // Original entry would have expired (70+50=120 > 100), but we
        // overwrote at 70ms — second put has its own 100ms window starting
        // at 70ms, so at 120ms it's still 50ms into its TTL.
        var got = cache.get("foo");
        assertThat(got).isNotNull();
        assertThat(got.get("v")).isEqualTo(2);
    }
}
