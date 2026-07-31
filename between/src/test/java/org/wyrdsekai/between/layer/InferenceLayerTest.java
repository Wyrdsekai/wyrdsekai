package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InferenceLayerTest {

    private static ActorTestKit testKit;

    @BeforeAll
    static void setup() {
        testKit = ActorTestKit.create();
    }

    @AfterAll
    static void teardown() {
        testKit.shutdownTestKit();
    }

    @Test void route_to_local_when_no_peers() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingDecision.class);

        layer.tell(new InferenceLayer.RouteInference("node-1", "gemma-2b", 100, probe.getRef()));

        var decision = probe.receiveMessage();
        assertThat(decision.targetNodeId()).isEqualTo("node-1");
        assertThat(decision.local()).isTrue();
    }

    @Test void route_to_best_node_with_model() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingDecision.class);

        // Node-1 has high load
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-1", List.of("gemma-2b"), 1000, 0.9, 3, 4));
        // Node-2 has low load
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-2", List.of("gemma-2b", "llama-7b"), 2000, 0.1, 0, 4));

        layer.tell(new InferenceLayer.RouteInference("node-1", "gemma-2b", 100, probe.getRef()));

        var decision = probe.receiveMessage();
        assertThat(decision.targetNodeId()).isEqualTo("node-2"); // lower load
    }

    @Test void route_skips_node_without_model() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingDecision.class);

        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-2", List.of("llama-7b"), 2000, 0.1, 0, 4)); // no gemma

        layer.tell(new InferenceLayer.RouteInference("node-1", "gemma-2b", 100, probe.getRef()));

        var decision = probe.receiveMessage();
        assertThat(decision.targetNodeId()).isEqualTo("node-1"); // fallback to local
        assertThat(decision.local()).isTrue();
    }

    @Test void route_skips_full_node() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingDecision.class);

        // Node-2 is at max capacity
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-2", List.of("gemma-2b"), 2000, 0.1, 4, 4));
        // Node-3 has capacity
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-3", List.of("gemma-2b"), 1500, 0.3, 1, 4));

        layer.tell(new InferenceLayer.RouteInference("node-1", "gemma-2b", 100, probe.getRef()));

        var decision = probe.receiveMessage();
        assertThat(decision.targetNodeId()).isEqualTo("node-3");
    }

    @Test void get_routing_table_returns_all_advertised() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingTable.class);

        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-1", List.of("gemma-2b"), 1000, 0.5, 1, 4));
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-2", List.of("llama-7b"), 2000, 0.2, 0, 4));

        layer.tell(new InferenceLayer.GetRoutingTable(probe.getRef()));

        var table = probe.receiveMessage();
        assertThat(table.nodes()).hasSize(2);
        assertThat(table.nodes().get("node-1").models()).contains("gemma-2b");
        assertThat(table.nodes().get("node-2").freeMemoryMb()).isEqualTo(2000);
    }

    @Test void advertise_updates_existing_entry() {
        var layer = testKit.spawn(InferenceLayer.create("node-1"));
        var probe = testKit.createTestProbe(InferenceLayer.RoutingTable.class);

        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-1", List.of("gemma-2b"), 1000, 0.5, 1, 4));
        layer.tell(new InferenceLayer.AdvertiseCapacity(
            "node-1", List.of("gemma-2b", "llama-7b"), 800, 0.7, 2, 4));

        layer.tell(new InferenceLayer.GetRoutingTable(probe.getRef()));

        var table = probe.receiveMessage();
        assertThat(table.nodes()).hasSize(1); // same node, updated
        assertThat(table.nodes().get("node-1").models()).hasSize(2);
        assertThat(table.nodes().get("node-1").activeRequests()).isEqualTo(2);
    }
}
