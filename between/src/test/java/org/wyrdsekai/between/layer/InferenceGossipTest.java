package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class InferenceGossipTest {

    /** In-memory transport for testing. */
    static class MockTransport implements BetweenLockerBridge.MessageTransport {
        final Map<String, List<BetweenLockerBridge.MessageHandler>> subscribers = new HashMap<>();
        final List<String> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(String subject, String json) {
            published.add(json);
            var handlers = subscribers.getOrDefault(subject, List.of());
            for (var handler : handlers) {
                handler.onMessage(json);
            }
        }

        @Override
        public void subscribe(String subject, BetweenLockerBridge.MessageHandler handler) {
            subscribers.computeIfAbsent(subject, _ -> new ArrayList<>()).add(handler);
        }
    }

    private MockTransport transport;
    private InferenceGossip gossip;

    @BeforeEach
    void setup() {
        transport = new MockTransport();
        gossip = new InferenceGossip(transport, "node-a");
    }

    @Test
    void announce_publishes_to_transport() {
        var cap = new InferenceGossip.InferenceCapability(
            "node-a",
            List.of(new InferenceGossip.AvailableModel("qwen:7b", "large", "http://localhost:8080", 4, 0)),
            1, 20000, 4, 0, 100.0,
            Instant.now().getEpochSecond()
        );
        gossip.announceCapabilities(cap);
        assertEquals(1, transport.published.size());
        assertTrue(transport.published.get(0).contains("qwen:7b"));
    }

    @Test
    void subscribe_receives_announcements() {
        var received = new CopyOnWriteArrayList<InferenceGossip.InferenceCapability>();
        gossip.subscribeCapabilities(received::add);

        // Simulate another node announcing — the subscription handler
        // fires because announce publishes to the same transport
        var cap = new InferenceGossip.InferenceCapability(
            "node-b",
            List.of(new InferenceGossip.AvailableModel("llama:8b", "large", "http://node-b:8080", 2, 0)),
            2, 40000, 2, 0, 50.0,
            Instant.now().getEpochSecond()
        );
        gossip.announceCapabilities(cap);

        // The subscribe handler should fire because announce also publishes
        assertFalse(received.isEmpty());
    }

    @Test
    void find_nodes_with_tier() {
        // Subscribe first so that announced capabilities are stored under
        // the capability's own nodeId via the subscription handler
        gossip.subscribeCapabilities();

        // Announce a remote node's capability — the subscription handler
        // stores it under "node-b" (the capability's nodeId)
        var cap = new InferenceGossip.InferenceCapability(
            "node-b",
            List.of(new InferenceGossip.AvailableModel("model", "large", "http://b:8080", 2, 0)),
            1, 20000, 2, 0, 50.0,
            Instant.now().getEpochSecond()
        );
        // Publish directly to simulate a remote announcement
        transport.publish("wyrd.inference.capabilities",
            toJson(cap));

        // Also announce local so it is stored under "node-a"
        var localCap = new InferenceGossip.InferenceCapability(
            "node-a",
            List.of(new InferenceGossip.AvailableModel("local", "large", "http://a:8080", 4, 0)),
            1, 20000, 4, 0, 100.0,
            Instant.now().getEpochSecond()
        );
        gossip.announceCapabilities(localCap);

        var nodes = gossip.findNodesWithTier("large");
        assertEquals(1, nodes.size());
        assertEquals("node-b", nodes.get(0).nodeId());
    }

    @Test
    void find_best_remote() {
        gossip.subscribeCapabilities();

        var cap = new InferenceGossip.InferenceCapability(
            "node-b",
            List.of(new InferenceGossip.AvailableModel("model", "large", "http://b:8080", 2, 0)),
            1, 20000, 2, 0, 50.0,
            Instant.now().getEpochSecond()
        );
        transport.publish("wyrd.inference.capabilities", toJson(cap));

        var best = gossip.findBestRemote("large");
        assertTrue(best.isPresent());
        assertEquals("node-b", best.get().nodeId());
        assertEquals("model", best.get().modelId());
    }

    @Test
    void find_best_remote_empty_when_no_tier() {
        var best = gossip.findBestRemote("large");
        assertFalse(best.isPresent());
    }

    @Test
    void node_disconnected_removes_capability() {
        gossip.subscribeCapabilities();

        var cap = new InferenceGossip.InferenceCapability(
            "node-b",
            List.of(new InferenceGossip.AvailableModel("model", "large", "http://b:8080", 2, 0)),
            1, 20000, 2, 0, 50.0,
            Instant.now().getEpochSecond()
        );
        transport.publish("wyrd.inference.capabilities", toJson(cap));

        gossip.nodeDisconnected("node-b");
        assertFalse(gossip.getCapability("node-b").isPresent());
    }

    @Test
    void stale_announcements_pruned() {
        gossip.subscribeCapabilities();

        // Create announcement older than expiry
        var staleCap = new InferenceGossip.InferenceCapability(
            "stale-node",
            List.of(),
            0, 0, 0, 0, 0.0,
            Instant.now().getEpochSecond() - InferenceGossip.EXPIRY_SECONDS - 10
        );
        transport.publish("wyrd.inference.capabilities", toJson(staleCap));

        // After pruning (triggered by query), stale entry should be gone
        assertEquals(0, gossip.findNodesWithTier("large").size());
        assertFalse(gossip.getCapability("stale-node").isPresent());
    }

    @Test
    void known_node_count() {
        assertEquals(0, gossip.knownNodeCount());
        gossip.announceCapabilities(new InferenceGossip.InferenceCapability(
            "node-a", List.of(), 0, 0, 0, 0, 0.0, Instant.now().getEpochSecond()));
        assertEquals(1, gossip.knownNodeCount());
    }

    @Test
    void excludes_full_slots() {
        gossip.subscribeCapabilities();

        // Model with all slots in use
        var cap = new InferenceGossip.InferenceCapability(
            "node-b",
            List.of(new InferenceGossip.AvailableModel("model", "large", "http://b:8080", 2, 2)),
            1, 20000, 0, 3, 50.0,
            Instant.now().getEpochSecond()
        );
        transport.publish("wyrd.inference.capabilities", toJson(cap));

        var nodes = gossip.findNodesWithTier("large");
        assertTrue(nodes.isEmpty(), "Should not return nodes with full slots");
    }

    /** Helper to serialize a capability to JSON for direct transport publishing. */
    private static String toJson(InferenceGossip.InferenceCapability cap) {
        try {
            return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .writeValueAsString(cap);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
