package org.wyrdsekai.core.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TheSafeTest {

    private TheSafe safe;

    @BeforeEach void setUp() {
        safe = new TheSafe();
    }

    @Test void store_and_retrieve_secret() {
        var secret = "my secret password".getBytes(StandardCharsets.UTF_8);
        safe.store("s1", secret, "alice", 2, 3, 0.5, 0.5);

        var retrieved = safe.retrieve("s1", List.of(0, 1), 0.7, 0.7);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(secret);
    }

    @Test void retrieve_fails_with_insufficient_shares() {
        safe.store("s1", "secret".getBytes(), "alice", 3, 5, 0.5, 0.5);

        var retrieved = safe.retrieve("s1", List.of(0, 1), 0.7, 0.7);
        assertThat(retrieved).isEmpty();
    }

    @Test void retrieve_fails_with_low_confidence() {
        safe.store("s1", "secret".getBytes(), "alice", 2, 3, 0.8, 0.5);

        var retrieved = safe.retrieve("s1", List.of(0, 1), 0.3, 0.7); // confidence too low
        assertThat(retrieved).isEmpty();
    }

    @Test void retrieve_fails_with_low_alignment() {
        safe.store("s1", "secret".getBytes(), "alice", 2, 3, 0.5, 0.8);

        var retrieved = safe.retrieve("s1", List.of(0, 1), 0.7, 0.3); // alignment too low
        assertThat(retrieved).isEmpty();
    }

    @Test void retrieve_nonexistent_secret() {
        assertThat(safe.retrieve("nonexistent", List.of(0), 1.0, 1.0)).isEmpty();
    }

    @Test void describeSecret_shows_metadata() {
        safe.store("s1", "secret".getBytes(), "alice", 2, 3, 0.6, 0.7);
        var desc = safe.describeSecret("s1");
        assertThat(desc).isPresent();
        assertThat(desc.get()).contains("alice");
        assertThat(desc.get()).contains("2/3");
    }

    // --- Topology-gated access tests (§74.11) ---

    @Test void retrieve_fails_when_share_node_has_high_latency() {
        var secret = "topology-secret".getBytes(StandardCharsets.UTF_8);
        // Store with topology gate: max 50ms latency, shares on nodes A, B, C
        safe.store("topo1", secret, "alice", 2, 3, 0.5, 0.5,
            List.of("node-A", "node-B", "node-C"), 50.0);

        // Node-A has 120ms latency (way above 50ms threshold)
        var peerLatencies = Map.of("node-A", 120.0, "node-B", 5.0, "node-C", 3.0);
        var retrieved = safe.retrieve("topo1", List.of(0, 1), 0.7, 0.7, peerLatencies);
        assertThat(retrieved).isEmpty(); // Rejected — node-A latency too high
    }

    @Test void retrieve_fails_when_share_node_disconnected() {
        var secret = "topology-secret".getBytes(StandardCharsets.UTF_8);
        safe.store("topo2", secret, "alice", 2, 3, 0.5, 0.5,
            List.of("node-A", "node-B", "node-C"), 50.0);

        // Only node-C is connected — node-A and node-B are missing (offline)
        var peerLatencies = Map.of("node-C", 3.0);
        var retrieved = safe.retrieve("topo2", List.of(0, 1), 0.7, 0.7, peerLatencies);
        assertThat(retrieved).isEmpty(); // Rejected — nodes A and B not connected
    }

    @Test void retrieve_succeeds_with_acceptable_latency() {
        var secret = "topology-secret".getBytes(StandardCharsets.UTF_8);
        safe.store("topo3", secret, "alice", 2, 3, 0.5, 0.5,
            List.of("node-A", "node-B", "node-C"), 50.0);

        // All nodes within threshold (LAN-level latency)
        var peerLatencies = Map.of("node-A", 2.0, "node-B", 5.0, "node-C", 3.0);
        var retrieved = safe.retrieve("topo3", List.of(0, 1), 0.7, 0.7, peerLatencies);
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(secret);
    }

    @Test void retrieve_without_topology_gate_ignores_latency() {
        var secret = "plain-secret".getBytes(StandardCharsets.UTF_8);
        // Store WITHOUT topology gate (maxLatencyMs=0, no node assignments)
        safe.store("plain1", secret, "alice", 2, 3, 0.5, 0.5);

        // Retrieve with empty latency map — should still work
        var retrieved = safe.retrieve("plain1", List.of(0, 1), 0.7, 0.7, Map.of());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get()).isEqualTo(secret);
    }

    @Test void describeSecret_shows_topology_requirements() {
        safe.store("topo4", "secret".getBytes(), "alice", 2, 3, 0.6, 0.7,
            List.of("node-A", "node-B", "node-C"), 50.0);
        var desc = safe.describeSecret("topo4");
        assertThat(desc).isPresent();
        assertThat(desc.get()).contains("latency≤50ms");
    }
}
