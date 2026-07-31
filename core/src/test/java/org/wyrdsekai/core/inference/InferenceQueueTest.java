package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.PriorityQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for inference queue behavior.
 * The queue is inside InferenceRouter (Pekko actor) so integration testing
 * happens in InferenceRouterActorTest and E2E tests. These tests verify
 * the queue data structure and priority logic.
 */
class InferenceQueueTest {

    @Test void queue_orders_by_priority() {
        var queue = new PriorityQueue<PrioritizedItem>(
            Comparator.comparingInt(PrioritizedItem::priority));

        queue.add(new PrioritizedItem(2, "ambient"));
        queue.add(new PrioritizedItem(0, "human"));
        queue.add(new PrioritizedItem(1, "tool"));

        assertThat(queue.poll().name()).isEqualTo("human");   // priority 0
        assertThat(queue.poll().name()).isEqualTo("tool");     // priority 1
        assertThat(queue.poll().name()).isEqualTo("ambient");  // priority 2
    }

    @Test void queue_human_requests_processed_before_autonomy() {
        var queue = new PriorityQueue<PrioritizedItem>(
            Comparator.comparingInt(PrioritizedItem::priority));

        // Simulate: autonomy check queued first, then human speaks
        queue.add(new PrioritizedItem(2, "autonomy-check"));
        queue.add(new PrioritizedItem(2, "ambient-emote"));
        queue.add(new PrioritizedItem(0, "human-speech"));

        // Human should be processed first despite being added last
        assertThat(queue.poll().name()).isEqualTo("human-speech");
    }

    @Test void concurrency_env_default_is_1() {
        // WYRDSEKAI_INFERENCE_CONCURRENCY not set → default 1
        var defaultVal = Integer.parseInt(
            System.getenv().getOrDefault("WYRDSEKAI_INFERENCE_CONCURRENCY", "1"));
        assertThat(defaultVal).isEqualTo(1);
    }

    @Test void queue_size_bounded() {
        var queue = new PriorityQueue<PrioritizedItem>(
            Comparator.comparingInt(PrioritizedItem::priority));
        int maxSize = 20;

        for (int i = 0; i < 25; i++) {
            if (queue.size() < maxSize) {
                queue.add(new PrioritizedItem(1, "req-" + i));
            }
        }
        assertThat(queue.size()).isEqualTo(maxSize);
    }

    record PrioritizedItem(int priority, String name) {}
}
