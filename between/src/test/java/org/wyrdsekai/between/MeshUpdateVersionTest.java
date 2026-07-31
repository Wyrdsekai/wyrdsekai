package org.wyrdsekai.between;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.AppVersion;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for mesh update protocol Wave 1: version advertisement.
 */
class MeshUpdateVersionTest {

    // --- AppVersion ---

    @Test
    void appVersion_loads_defaults() {
        var v = AppVersion.get();
        assertNotNull(v.version());
        assertNotNull(v.buildHash());
        assertNotNull(v.buildTimestamp());
        assertTrue(v.wireProtocol() >= 1);
    }

    @Test
    void appVersion_toString_contains_version() {
        var v = AppVersion.get();
        assertTrue(v.toString().contains(v.version()));
        assertTrue(v.toString().contains(v.buildHash()));
    }

    @Test
    void appVersion_singleton() {
        assertSame(AppVersion.get(), AppVersion.get());
    }

    // --- TopologyRegister version tracking ---

    @Test
    void peerConnected_stores_version() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of("cpuCount", "8"), "0.2.0", 1);

        var conn = topo.get("node-1");
        assertTrue(conn.isPresent());
        assertEquals("0.2.0", conn.get().appVersion());
        assertEquals(1, conn.get().wireProtocol());
        assertNotNull(conn.get().versionSince());
    }

    @Test
    void peerConnected_without_version_backward_compatible() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of("cpuCount", "4"));

        var conn = topo.get("node-1");
        assertTrue(conn.isPresent());
        assertNull(conn.get().appVersion());
        assertEquals(0, conn.get().wireProtocol());
    }

    @Test
    void heartbeat_updates_version() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of(), "0.1.0", 1);

        // Heartbeat with same version
        topo.updateHeartbeat("node-1", "0.1.0", 1);
        assertEquals("0.1.0", topo.get("node-1").orElseThrow().appVersion());

        // Heartbeat with new version (node updated)
        topo.updateHeartbeat("node-1", "0.2.0", 1);
        assertEquals("0.2.0", topo.get("node-1").orElseThrow().appVersion());
    }

    @Test
    void heartbeat_without_version_preserves_existing() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of(), "0.1.0", 1);

        // Legacy heartbeat (no version)
        topo.updateHeartbeat("node-1");
        assertEquals("0.1.0", topo.get("node-1").orElseThrow().appVersion());
    }

    @Test
    void version_change_resets_versionSince() throws InterruptedException {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of(), "0.1.0", 1);
        var originalSince = topo.get("node-1").orElseThrow().versionSince();

        Thread.sleep(50);

        // Version changes — versionSince should update
        topo.updateHeartbeat("node-1", "0.2.0", 1);
        var newSince = topo.get("node-1").orElseThrow().versionSince();
        assertTrue(newSince.isAfter(originalSince),
            "versionSince should be updated when version changes");
    }

    @Test
    void disconnection_preserves_version() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of(), "0.3.0", 1);
        topo.peerDisconnected("node-1");

        var conn = topo.get("node-1").orElseThrow();
        assertFalse(conn.connected());
        assertEquals("0.3.0", conn.appVersion());
        assertEquals(1, conn.wireProtocol());
    }

    @Test
    void describe_includes_version() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-abc", Map.of("cpuCount", "8"), "0.2.0", 1);

        var desc = topo.describe();
        assertTrue(desc.contains("v0.2.0"), "describe() should include version: " + desc);
        assertTrue(desc.contains("1 peer(s)"));
    }

    @Test
    void latency_update_preserves_version() {
        var topo = new TopologyRegister();
        topo.peerConnected("node-1", Map.of(), "0.5.0", 2);

        topo.updateLatency("node-1", 15.5);
        var conn = topo.get("node-1").orElseThrow();
        assertEquals("0.5.0", conn.appVersion());
        assertEquals(2, conn.wireProtocol());
        assertTrue(conn.latencyMs() > 0);
    }
}
