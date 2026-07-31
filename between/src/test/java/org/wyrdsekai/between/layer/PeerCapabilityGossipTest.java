package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PeerCapabilityGossip — capability discovery through
 * Between federation (section 91.2).
 */
class PeerCapabilityGossipTest {

    /**
     * In-memory transport shared between gossip instances.
     * Delivers to all subscribers synchronously.
     */
    static class InMemoryTransport implements BetweenLockerBridge.MessageTransport {
        private final Map<String, List<BetweenLockerBridge.MessageHandler>> subscriptions =
            new ConcurrentHashMap<>();

        @Override
        public void publish(String subject, String json) {
            var handlers = subscriptions.getOrDefault(subject, List.of());
            for (var handler : handlers) {
                handler.onMessage(json);
            }
        }

        @Override
        public void subscribe(String subject, BetweenLockerBridge.MessageHandler handler) {
            subscriptions.computeIfAbsent(subject, _ -> new ArrayList<>()).add(handler);
        }
    }

    private InMemoryTransport transport;
    private PeerCapabilityGossip gossip;

    @BeforeEach
    void setup() {
        transport = new InMemoryTransport();
        gossip = new PeerCapabilityGossip(transport, "node-1");
    }

    @Nested
    class AnnounceAndDiscover {

        @Test
        void announce_stores_locally() {
            gossip.announceCapabilities(Set.of("mcp.weather", "mcp.search"));

            assertThat(gossip.knownNodeCount()).isEqualTo(1);
            assertThat(gossip.allServiceIds()).containsExactlyInAnyOrder("mcp.weather", "mcp.search");
        }

        @Test
        void find_service_returns_announcing_node() {
            gossip.announceCapabilities(Set.of("mcp.weather", "mcp.calendar"));

            assertThat(gossip.findService("mcp.weather")).containsExactly("node-1");
            assertThat(gossip.findService("mcp.calendar")).containsExactly("node-1");
        }

        @Test
        void find_unknown_service_returns_empty() {
            gossip.announceCapabilities(Set.of("mcp.weather"));

            assertThat(gossip.findService("mcp.nonexistent")).isEmpty();
        }

        @Test
        void known_capabilities_map() {
            gossip.announceCapabilities(Set.of("mcp.weather", "mcp.search"));

            var caps = gossip.knownCapabilities();
            assertThat(caps).hasSize(2);
            assertThat(caps.get("mcp.weather")).containsExactly("node-1");
            assertThat(caps.get("mcp.search")).containsExactly("node-1");
        }

        @Test
        void empty_when_no_announcements() {
            assertThat(gossip.knownNodeCount()).isZero();
            assertThat(gossip.allServiceIds()).isEmpty();
            assertThat(gossip.knownCapabilities()).isEmpty();
        }
    }

    @Nested
    class PeerDiscovery {

        @Test
        void subscribe_receives_peer_announcements() {
            var received = new ArrayList<PeerCapabilityGossip.CapabilityAnnouncement>();

            // gossip subscribes
            gossip.subscribeCapabilities(received::add);

            // Simulate a peer announcement arriving via transport
            var peer = new PeerCapabilityGossip(transport, "node-2");
            peer.announceCapabilities(Set.of("mcp.email", "mcp.slack"));

            // gossip should have received the announcement
            assertThat(received).hasSize(1);
            assertThat(received.getFirst().nodeId()).isEqualTo("node-2");
            assertThat(received.getFirst().serviceIds())
                .containsExactlyInAnyOrder("mcp.email", "mcp.slack");
        }

        @Test
        void multiple_peers_aggregate() {
            gossip.subscribeCapabilities();

            var peer1 = new PeerCapabilityGossip(transport, "node-2");
            peer1.announceCapabilities(Set.of("mcp.weather"));

            var peer2 = new PeerCapabilityGossip(transport, "node-3");
            peer2.announceCapabilities(Set.of("mcp.weather", "mcp.search"));

            // gossip itself also announces
            gossip.announceCapabilities(Set.of("mcp.calendar"));

            // findService should aggregate all nodes offering weather
            var weatherNodes = gossip.findService("mcp.weather");
            assertThat(weatherNodes).containsExactlyInAnyOrder("node-2", "node-3");

            // search only on node-3
            assertThat(gossip.findService("mcp.search")).containsExactly("node-3");

            // calendar only on node-1
            assertThat(gossip.findService("mcp.calendar")).containsExactly("node-1");
        }

        @Test
        void newer_announcement_overwrites_older() {
            gossip.subscribeCapabilities();

            var peer = new PeerCapabilityGossip(transport, "node-2");
            peer.announceCapabilities(Set.of("mcp.old-service"));

            assertThat(gossip.findService("mcp.old-service")).contains("node-2");

            // Peer re-announces with different capabilities
            peer.announceCapabilities(Set.of("mcp.new-service"));

            assertThat(gossip.findService("mcp.old-service")).isEmpty();
            assertThat(gossip.findService("mcp.new-service")).contains("node-2");
        }

        @Test
        void subscribe_without_listener_still_stores() {
            gossip.subscribeCapabilities();

            var peer = new PeerCapabilityGossip(transport, "node-2");
            peer.announceCapabilities(Set.of("mcp.weather"));

            assertThat(gossip.findService("mcp.weather")).contains("node-2");
            assertThat(gossip.knownNodeCount()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    class StalePruning {

        @Test
        void stale_announcements_are_pruned() {
            // Manually inject a stale announcement with old timestamp
            gossip.subscribeCapabilities();

            // Simulate receiving a very old announcement by publishing raw JSON
            long staleTime = Instant.now().getEpochSecond() - PeerCapabilityGossip.EXPIRY_SECONDS - 10;
            String staleJson = """
                {"nodeId":"node-stale","serviceIds":["mcp.old"],"timestamp":%d}
                """.formatted(staleTime);
            transport.publish("wyrd.discovery.capabilities", staleJson);

            // The stale announcement was stored because it was "newer" than nothing
            // But knownCapabilities prunes before returning
            assertThat(gossip.findService("mcp.old")).isEmpty();
            assertThat(gossip.knownNodeCount()).isZero();
        }

        @Test
        void fresh_announcements_survive_pruning() {
            gossip.announceCapabilities(Set.of("mcp.fresh"));

            assertThat(gossip.findService("mcp.fresh")).contains("node-1");
            assertThat(gossip.knownNodeCount()).isEqualTo(1);
        }
    }

    @Nested
    class ServiceDiscoveryEdgeCases {

        @Test
        void empty_service_set_announcement() {
            gossip.announceCapabilities(Set.of());

            assertThat(gossip.knownNodeCount()).isEqualTo(1);
            assertThat(gossip.allServiceIds()).isEmpty();
            assertThat(gossip.knownCapabilities()).isEmpty();
        }

        @Test
        void same_service_on_many_nodes() {
            gossip.subscribeCapabilities();

            gossip.announceCapabilities(Set.of("mcp.common"));

            for (int i = 2; i <= 5; i++) {
                var peer = new PeerCapabilityGossip(transport, "node-" + i);
                peer.announceCapabilities(Set.of("mcp.common"));
            }

            var nodes = gossip.findService("mcp.common");
            assertThat(nodes).hasSize(5);
            assertThat(nodes).contains("node-1", "node-2", "node-3", "node-4", "node-5");
        }
    }
}
