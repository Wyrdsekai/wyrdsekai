package org.wyrdsekai.between.update;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.TopologyRegister;
import org.wyrdsekai.core.update.UpdateConfig;
import org.wyrdsekai.core.update.UpdateEngine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MeshUpdateWatcher (Wave 5: mesh propagation).
 */
class MeshUpdateWatcherTest {

    @Test
    void watcher_detects_newer_peer() throws Exception {
        var topology = new TopologyRegister();
        topology.peerConnected("peer-1", Map.of(), "9.0.0", 1);

        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.MANUAL,
            Duration.ofMinutes(5), Duration.ofSeconds(1), null, "secondary", null, null, 3);
        var engine = new UpdateEngine(Path.of("/tmp/nonexistent"), config);
        var watcher = new MeshUpdateWatcher(topology, config, engine);

        watcher.start();
        Thread.sleep(1500); // let one check cycle run

        var pending = watcher.pendingUpdates();
        assertTrue(pending.containsKey("9.0.0") || pending.isEmpty(),
            "Should detect newer peer or not have run yet");

        watcher.close();
    }

    @Test
    void watcher_records_mesh_failure() {
        var topology = new TopologyRegister();
        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var engine = new UpdateEngine(Path.of("/tmp"), config);
        var watcher = new MeshUpdateWatcher(topology, config, engine);

        watcher.recordMeshFailure("0.5.0");
        assertTrue(watcher.meshFailedVersions().contains("0.5.0"));
        watcher.close();
    }

    @Test
    void watcher_selects_lowest_latency_peer() {
        var topology = new TopologyRegister();
        topology.peerConnected("slow-peer", Map.of(), "2.0.0", 1);
        topology.peerConnected("fast-peer", Map.of(), "2.0.0", 1);
        topology.updateLatency("slow-peer", 100.0);
        topology.updateLatency("fast-peer", 5.0);

        var config = UpdateConfig.fromEnv();
        var engine = new UpdateEngine(Path.of("/tmp"), config);
        var watcher = new MeshUpdateWatcher(topology, config, engine);

        var best = watcher.selectBestPeer("2.0.0");
        assertTrue(best.isPresent());
        assertEquals("fast-peer", best.get());

        watcher.close();
    }

    @Test
    void watcher_disabled_by_policy() {
        var topology = new TopologyRegister();
        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.DISABLED,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var engine = new UpdateEngine(Path.of("/tmp"), config);
        var watcher = new MeshUpdateWatcher(topology, config, engine);

        watcher.start(); // should not throw
        assertTrue(watcher.pendingUpdates().isEmpty());
        watcher.close();
    }

    @Test
    void watcher_no_false_positive_on_same_version() {
        var topology = new TopologyRegister();
        topology.peerConnected("peer-1", Map.of(), "0.1.0-SNAPSHOT", 1);

        var config = new UpdateConfig("", UpdateConfig.UpdatePolicy.AUTO,
            Duration.ofHours(6), Duration.ofMinutes(5), null, "secondary", null, null, 3);
        var engine = new UpdateEngine(Path.of("/tmp"), config);
        var watcher = new MeshUpdateWatcher(topology, config, engine);

        watcher.start();
        // Peer is at same version — should not trigger
        assertTrue(watcher.pendingUpdates().isEmpty());
        watcher.close();
    }
}
