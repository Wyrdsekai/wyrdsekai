package org.wyrdsekai.server.inference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-side inference dedup (spec/tla/InferenceRedelivery.tla, P2).
 */
class ServedRequestDedupTest {

    @Test void first_sight_serves_then_a_redelivery_is_dropped() {
        var dedup = new ServedRequestDedup(1024);
        assertThat(dedup.firstSight("stream-1")).isTrue();    // serve
        assertThat(dedup.firstSight("stream-1")).isFalse();   // redelivery — drop, no re-run
        assertThat(dedup.firstSight("stream-1")).isFalse();
        assertThat(dedup.firstSight("stream-2")).isTrue();    // a different request is served
    }

    @Test void null_or_blank_streamId_cannot_be_deduped_and_is_served() {
        var dedup = new ServedRequestDedup(1024);
        assertThat(dedup.firstSight(null)).isTrue();
        assertThat(dedup.firstSight("")).isTrue();
        assertThat(dedup.firstSight("  ")).isTrue();
    }

    @Test void bounded_lru_evicts_oldest_so_memory_does_not_grow_without_limit() {
        var dedup = new ServedRequestDedup(2);   // tiny cap to force eviction
        assertThat(dedup.firstSight("a")).isTrue();
        assertThat(dedup.firstSight("b")).isTrue();
        assertThat(dedup.firstSight("c")).isTrue();   // evicts the eldest ("a")
        // "a" was evicted, so it now reads as first-sight again (bounded best-effort dedup).
        assertThat(dedup.firstSight("a")).isTrue();
        // "c" (most-recent) is still remembered.
        assertThat(dedup.firstSight("c")).isFalse();
    }
}
