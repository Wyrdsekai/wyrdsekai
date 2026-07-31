package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for BetweenLockerBridge — content-addressed item distribution
 * over the Between network.
 *
 * Uses an in-memory MessageTransport so no NATS server is required.
 */
class BetweenLockerBridgeTest {

    /**
     * Simple in-memory transport for testing. Subscriptions are delivered
     * synchronously when publish is called (simulates NATS within one JVM).
     */
    static class InMemoryTransport implements BetweenLockerBridge.MessageTransport {
        private final Map<String, List<BetweenLockerBridge.MessageHandler>> subscriptions =
            new ConcurrentHashMap<>();
        private final List<PublishedMessage> published = new ArrayList<>();

        record PublishedMessage(String subject, String json) {}

        @Override
        public void publish(String subject, String json) {
            published.add(new PublishedMessage(subject, json));
            var handlers = subscriptions.getOrDefault(subject, List.of());
            for (var handler : handlers) {
                handler.onMessage(json);
            }
        }

        @Override
        public void subscribe(String subject, BetweenLockerBridge.MessageHandler handler) {
            subscriptions.computeIfAbsent(subject, _ -> new ArrayList<>()).add(handler);
        }

        int publishCount() { return published.size(); }
        List<PublishedMessage> messages() { return published; }
    }

    private InMemoryTransport transport;
    private BetweenLockerBridge bridge;

    @BeforeEach
    void setup() {
        transport = new InMemoryTransport();
        bridge = new BetweenLockerBridge(transport);
    }

    @Nested
    class ItemPublishSubscribe {

        @Test
        void publish_item_sends_to_transport() {
            var item = new BetweenLockerBridge.ItemMessage(
                "abc123", "memory", "First meeting",
                "We met in the garden", "did:key:home-server", 0.8,
                Instant.now().getEpochSecond());

            bridge.publishItem(item, "family-1");

            assertThat(transport.publishCount()).isEqualTo(1);
            assertThat(transport.messages().getFirst().subject())
                .isEqualTo("wyrd.locker.family-1.items");
        }

        @Test
        void subscribe_receives_published_item() {
            var received = new ArrayList<BetweenLockerBridge.ItemMessage>();

            // Use a separate receiver bridge to avoid self-dedup:
            // the publisher marks the hash known before transport delivers it,
            // so a single bridge would dedup its own publishes.
            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.subscribeItems("family-1", received::add);

            var item = new BetweenLockerBridge.ItemMessage(
                "hash1", "identity-core", "Name", "Lain",
                "did:key:home-server", 1.0, Instant.now().getEpochSecond());
            receiverTransport.publish("wyrd.locker.family-1.items", toJson(item));

            assertThat(received).hasSize(1);
            assertThat(received.getFirst().hash()).isEqualTo("hash1");
            assertThat(received.getFirst().text()).isEqualTo("Lain");
        }

        @Test
        void duplicate_item_is_deduplicated() {
            var received = new ArrayList<BetweenLockerBridge.ItemMessage>();
            bridge.subscribeItems("family-1", received::add);

            var item = new BetweenLockerBridge.ItemMessage(
                "same-hash", "memory", "label", "text",
                "did:key:home-server", 0.5, Instant.now().getEpochSecond());

            // Publish same hash twice
            bridge.publishItem(item, "family-1");
            bridge.publishItem(item, "family-1");

            // Subscriber should only see one (the first is self-published and known,
            // the second is also known already)
            // Note: publishItem adds to knownItemHashes, so the subscriber's dedup
            // will catch it when received back
            assertThat(received).isEmpty(); // Both were already known when subscription received them
        }

        @Test
        void different_items_both_received() {
            var received = new ArrayList<BetweenLockerBridge.ItemMessage>();

            // Subscribe on a fresh bridge that hasn't published
            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.subscribeItems("family-1", received::add);

            // Simulate incoming items via the transport directly
            var item1 = new BetweenLockerBridge.ItemMessage(
                "hash-a", "memory", "M1", "text-a",
                "did:key:home-server", 0.5, Instant.now().getEpochSecond());
            var item2 = new BetweenLockerBridge.ItemMessage(
                "hash-b", "memory", "M2", "text-b",
                "did:key:home-server", 0.6, Instant.now().getEpochSecond());

            // Publish via the receiver's transport to trigger subscription
            receiverTransport.publish("wyrd.locker.family-1.items", toJson(item1));
            receiverTransport.publish("wyrd.locker.family-1.items", toJson(item2));

            assertThat(received).hasSize(2);
            assertThat(received.get(0).hash()).isEqualTo("hash-a");
            assertThat(received.get(1).hash()).isEqualTo("hash-b");
        }

        @Test
        void pre_registered_hash_is_deduplicated() {
            var received = new ArrayList<BetweenLockerBridge.ItemMessage>();

            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.registerKnownHash("existing-hash");
            receiver.subscribeItems("family-1", received::add);

            var item = new BetweenLockerBridge.ItemMessage(
                "existing-hash", "memory", "Old", "text",
                "did:key:home-server", 0.5, Instant.now().getEpochSecond());
            receiverTransport.publish("wyrd.locker.family-1.items", toJson(item));

            assertThat(received).isEmpty();
        }

        @Test
        void known_item_count_tracks_items() {
            assertThat(bridge.knownItemCount()).isZero();

            bridge.publishItem(new BetweenLockerBridge.ItemMessage(
                "h1", "c", "l", "t", "d", 0.5, 0), "f1");
            assertThat(bridge.knownItemCount()).isEqualTo(1);

            bridge.registerKnownHash("h2");
            assertThat(bridge.knownItemCount()).isEqualTo(2);
        }
    }

    @Nested
    class TombstonePublishSubscribe {

        @Test
        void publish_tombstone_sends_to_transport() {
            var tombstone = new BetweenLockerBridge.TombstoneMessage(
                "hash-to-delete", "did:key:home-server", "Forge pruned",
                Instant.now().getEpochSecond());

            bridge.publishTombstone(tombstone, "family-1");

            assertThat(transport.publishCount()).isEqualTo(1);
            assertThat(transport.messages().getFirst().subject())
                .isEqualTo("wyrd.locker.family-1.tombstones");
        }

        @Test
        void subscribe_receives_published_tombstone() {
            var received = new ArrayList<BetweenLockerBridge.TombstoneMessage>();

            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.subscribeTombstones("family-1", received::add);

            var tombstone = new BetweenLockerBridge.TombstoneMessage(
                "dead-hash", "did:key:home-server", "memory faded",
                Instant.now().getEpochSecond());
            receiverTransport.publish("wyrd.locker.family-1.tombstones", toJson(tombstone));

            assertThat(received).hasSize(1);
            assertThat(received.getFirst().itemHash()).isEqualTo("dead-hash");
            assertThat(received.getFirst().reason()).isEqualTo("memory faded");
        }

        @Test
        void duplicate_tombstone_is_deduplicated() {
            var received = new ArrayList<BetweenLockerBridge.TombstoneMessage>();

            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.subscribeTombstones("family-1", received::add);

            var tombstone = new BetweenLockerBridge.TombstoneMessage(
                "dead-hash", "did:key:home-server", "pruned",
                Instant.now().getEpochSecond());

            receiverTransport.publish("wyrd.locker.family-1.tombstones", toJson(tombstone));
            receiverTransport.publish("wyrd.locker.family-1.tombstones", toJson(tombstone));

            assertThat(received).hasSize(1); // Second delivery deduplicated
        }

        @Test
        void pre_registered_tombstone_is_deduplicated() {
            var received = new ArrayList<BetweenLockerBridge.TombstoneMessage>();

            var receiverTransport = new InMemoryTransport();
            var receiver = new BetweenLockerBridge(receiverTransport);
            receiver.registerKnownTombstone("already-dead");
            receiver.subscribeTombstones("family-1", received::add);

            var tombstone = new BetweenLockerBridge.TombstoneMessage(
                "already-dead", "did:key:home-server", "old deletion",
                Instant.now().getEpochSecond());
            receiverTransport.publish("wyrd.locker.family-1.tombstones", toJson(tombstone));

            assertThat(received).isEmpty();
        }

        @Test
        void known_tombstone_count_tracks() {
            assertThat(bridge.knownTombstoneCount()).isZero();

            bridge.publishTombstone(new BetweenLockerBridge.TombstoneMessage(
                "h1", "d", "r", 0), "f1");
            assertThat(bridge.knownTombstoneCount()).isEqualTo(1);

            bridge.registerKnownTombstone("h2");
            assertThat(bridge.knownTombstoneCount()).isEqualTo(2);
        }
    }

    @Nested
    class CrossFamilyIsolation {

        @Test
        void items_from_different_families_do_not_cross() {
            var receivedF1 = new ArrayList<BetweenLockerBridge.ItemMessage>();
            var receivedF2 = new ArrayList<BetweenLockerBridge.ItemMessage>();

            var sharedTransport = new InMemoryTransport();
            var bridge1 = new BetweenLockerBridge(sharedTransport);
            bridge1.subscribeItems("family-1", receivedF1::add);
            bridge1.subscribeItems("family-2", receivedF2::add);

            var item = new BetweenLockerBridge.ItemMessage(
                "h1", "c", "l", "t", "d", 0.5,
                Instant.now().getEpochSecond());
            sharedTransport.publish("wyrd.locker.family-1.items", toJson(item));

            assertThat(receivedF1).hasSize(1);
            assertThat(receivedF2).isEmpty();
        }
    }

    // --- Helper ---

    private static String toJson(Object obj) {
        try {
            return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
