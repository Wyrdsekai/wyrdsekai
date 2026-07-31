package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomMemoryPolicyTest {

    private static WorldEvent.Said said(String name, String text) {
        return new WorldEvent.Said("nexus", Instant.now(), name, name, text);
    }

    @Test void empty_policy() {
        var policy = RoomMemoryPolicy.defaultPolicy();
        assertThat(policy.isEmpty()).isTrue();
        assertThat(policy.totalSize()).isEqualTo(0);
        assertThat(policy.hotEvents()).isEmpty();
        assertThat(policy.warmSummaries()).isEmpty();
        assertThat(policy.compactedFacts()).isEmpty();
        assertThat(policy.buildMemoryContext()).isNull();
    }

    @Test void add_stays_in_hot_buffer() {
        var policy = RoomMemoryPolicy.defaultPolicy();
        policy.add(said("Alice", "Hello!"));
        assertThat(policy.hotEvents()).hasSize(1);
        assertThat(policy.warmSummaries()).isEmpty();
        assertThat(policy.totalSize()).isEqualTo(1);
    }

    @Test void hot_overflow_cascades_to_warm() {
        var policy = new RoomMemoryPolicy(3, 50, 20);
        for (int i = 0; i < 5; i++) {
            policy.add(said("Alice", "Message " + i));
        }
        assertThat(policy.hotEvents()).hasSize(3);
        assertThat(policy.warmSummaries()).hasSize(2);
        // Hot buffer has the most recent 3
        assertThat(policy.hotEvents().getLast().text()).isEqualTo("Message 4");
    }

    @Test void warm_overflow_cascades_to_compacted() {
        var policy = new RoomMemoryPolicy(2, 3, 20);
        for (int i = 0; i < 8; i++) {
            policy.add(said("Bob", "Event " + i));
        }
        // 8 events: hot=2 (most recent), warm=3, compacted=3 (overflow from warm)
        assertThat(policy.hotEvents()).hasSize(2);
        assertThat(policy.warmSummaries()).hasSize(3);
        assertThat(policy.compactedFacts()).hasSize(3);
    }

    @Test void compacted_overflow_evicts_oldest() {
        var policy = new RoomMemoryPolicy(1, 1, 2);
        for (int i = 0; i < 10; i++) {
            policy.add(said("Sys", "Log " + i));
        }
        assertThat(policy.compactedFacts()).hasSize(2);
    }

    @Test void warm_summary_includes_speaker_and_text() {
        var policy = new RoomMemoryPolicy(1, 50, 20);
        policy.add(said("Alice", "First message"));
        policy.add(said("Bob", "Second message"));
        // First was evicted from hot to warm
        assertThat(policy.warmSummaries().getFirst()).contains("Alice");
        assertThat(policy.warmSummaries().getFirst()).contains("First message");
    }

    @Test void warm_summary_truncates_long_text() {
        var policy = new RoomMemoryPolicy(1, 50, 20);
        policy.add(said("Alice", "A".repeat(200)));
        policy.add(said("Bob", "Second"));
        // First was evicted to warm, should be truncated
        var summary = policy.warmSummaries().getFirst();
        assertThat(summary.length()).isLessThan(100);
        assertThat(summary).endsWith("...");
    }

    @Test void buildMemoryContext_returns_null_when_only_hot() {
        var policy = RoomMemoryPolicy.defaultPolicy();
        for (int i = 0; i < 5; i++) {
            policy.add(said("Alice", "Hello " + i));
        }
        assertThat(policy.buildMemoryContext()).isNull();
    }

    @Test void buildMemoryContext_includes_warm_and_compacted() {
        var policy = new RoomMemoryPolicy(2, 3, 5);
        for (int i = 0; i < 10; i++) {
            policy.add(said("Alice", "Message " + i));
        }
        var context = policy.buildMemoryContext();
        assertThat(context).isNotNull();
        assertThat(context).contains("[Recent history]");
        assertThat(context).contains("[Earlier context]");
    }

    @Test void handleSpike_compresses_warm_buffer() {
        var policy = new RoomMemoryPolicy(2, 20, 20);
        for (int i = 0; i < 12; i++) {
            policy.add(said("Alice", "Msg " + i));
        }
        int warmBefore = policy.warmSummaries().size();
        assertThat(warmBefore).isEqualTo(10);

        policy.handleSpike();
        assertThat(policy.warmSummaries().size()).isLessThan(warmBefore);
    }

    @Test void clear_empties_all_tiers() {
        var policy = new RoomMemoryPolicy(2, 3, 5);
        for (int i = 0; i < 20; i++) {
            policy.add(said("Alice", "Message " + i));
        }
        assertThat(policy.totalSize()).isGreaterThan(0);
        policy.clear();
        assertThat(policy.isEmpty()).isTrue();
        assertThat(policy.totalSize()).isEqualTo(0);
    }

    @Test void minimal_policy_has_small_buffers() {
        var policy = RoomMemoryPolicy.minimal();
        for (int i = 0; i < 30; i++) {
            policy.add(said("Alice", "Message " + i));
        }
        assertThat(policy.hotEvents()).hasSize(5);
        // Warm is capped at 10
        assertThat(policy.warmSummaries().size()).isLessThanOrEqualTo(10);
    }

    @Test void fromConfig_creates_correct_sizes() {
        var policy = RoomMemoryPolicy.fromConfig(15, 30);
        for (int i = 0; i < 50; i++) {
            policy.add(said("Alice", "Msg " + i));
        }
        assertThat(policy.hotEvents()).hasSize(15);
        assertThat(policy.warmSummaries().size()).isLessThanOrEqualTo(30);
    }
}
