package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded-size eviction for {@link RoomSnapshotCache} — when sustained
 * cross-zone churn pushes the entry count past the cap, the oldest entries
 * (by cached time) get pruned to bring the cache back under cap. Strategy
 * is FIFO (insertion-order), matching the existing pattern used in
 * {@code HearthJournal} and {@code McpAuditLog}.
 */
class RoomSnapshotCacheEvictionTest {

    @Test void exceeding_cap_evicts_back_to_cap() {
        var cache = new RoomSnapshotCache(Duration.ofMinutes(10), /* cap */ 50);
        for (int i = 0; i < 250; i++) {
            cache.put("room-" + i, Map.of("name", "Room " + i));
        }
        // Eviction is applied on every put-over-cap; size never exceeds cap.
        assertThat(cache.size()).isLessThanOrEqualTo(50);
        assertThat(cache.maxEntries()).isEqualTo(50);
    }

    @Test void eviction_drops_oldest_first_keeps_newest() throws InterruptedException {
        var cache = new RoomSnapshotCache(Duration.ofMinutes(10), /* cap */ 5);
        // Insert 5 entries, then add 5 more — original 5 should be gone,
        // newest 5 retained. Sleep between batches to make the cachedAt
        // ordering unambiguous on systems with coarse clock resolution.
        cache.put("a", Map.of("v", "a"));
        cache.put("b", Map.of("v", "b"));
        cache.put("c", Map.of("v", "c"));
        cache.put("d", Map.of("v", "d"));
        cache.put("e", Map.of("v", "e"));
        Thread.sleep(5);
        cache.put("f", Map.of("v", "f"));
        cache.put("g", Map.of("v", "g"));
        cache.put("h", Map.of("v", "h"));
        cache.put("i", Map.of("v", "i"));
        cache.put("j", Map.of("v", "j"));

        assertThat(cache.size()).isLessThanOrEqualTo(5);
        // Newest-five must all still be reachable.
        assertThat(cache.get("f")).isNotNull();
        assertThat(cache.get("g")).isNotNull();
        assertThat(cache.get("h")).isNotNull();
        assertThat(cache.get("i")).isNotNull();
        assertThat(cache.get("j")).isNotNull();
        // Original-five evicted.
        assertThat(cache.get("a")).isNull();
        assertThat(cache.get("b")).isNull();
        assertThat(cache.get("c")).isNull();
        assertThat(cache.get("d")).isNull();
        assertThat(cache.get("e")).isNull();
    }

    @Test void default_cap_is_household_scale() {
        // Sanity: the default cap is 200 — comfortably absorbs household
        // churn without growing without bound.
        var cache = new RoomSnapshotCache();
        assertThat(cache.maxEntries()).isEqualTo(RoomSnapshotCache.DEFAULT_MAX_ENTRIES);
        assertThat(RoomSnapshotCache.DEFAULT_MAX_ENTRIES).isEqualTo(200);
    }

    @Test void puts_under_cap_do_not_evict_anything() {
        var cache = new RoomSnapshotCache(Duration.ofMinutes(10), /* cap */ 100);
        for (int i = 0; i < 80; i++) {
            cache.put("k" + i, Map.of("v", i));
        }
        assertThat(cache.size()).isEqualTo(80);
        // Every entry must still be reachable — no premature eviction.
        for (int i = 0; i < 80; i++) {
            assertThat(cache.get("k" + i)).as("k" + i).isNotNull();
        }
    }

    @Test void overwrite_does_not_count_as_new_eviction_pressure() {
        var cache = new RoomSnapshotCache(Duration.ofMinutes(10), /* cap */ 10);
        // Fill exactly to cap.
        for (int i = 0; i < 10; i++) {
            cache.put("k" + i, Map.of("v", i));
        }
        assertThat(cache.size()).isEqualTo(10);
        // Overwrite an existing key — size stays at cap, no eviction needed.
        cache.put("k5", Map.of("v", "updated"));
        assertThat(cache.size()).isEqualTo(10);
        assertThat(cache.get("k5")).isEqualTo(Map.of("v", "updated"));
    }
}
