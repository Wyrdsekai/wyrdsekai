package org.wyrdsekai.between.layer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourceRegistryTest {

    @BeforeEach
    void reset() {
        // Clear singleton state between tests
        var reg = ResourceRegistry.get();
        reg.setLocalNodeId("local-node");
        for (var snap : reg.allSnapshots().values()) {
            reg.removePeer(snap.nodeId());
        }
    }

    private NodeCapabilities.Snapshot makeSnapshot(String nodeId, boolean hasInference,
                                                    String lanIp, String gpuName, long vramMb) {
        var endpoints = hasInference
            ? List.of(new NodeCapabilities.InferenceEndpoint(
                "llama-server", "wyrdsekai-3.5-4b", "http://localhost:8200",
                1, 8192, true, true))
            : List.<NodeCapabilities.InferenceEndpoint>of();

        return new NodeCapabilities.Snapshot(
            nodeId, Set.of("inference", "gpu"), 16, 32768, 16384,
            gpuName, vramMb, 500000, 80.0,
            hasInference ? "llama-server" : null, hasInference,
            endpoints, List.of(), List.of(), -1, "HEALTHY",
            lanIp, 7070, false, false, Instant.now());
    }

    @Test
    void peerHasGpu_reflects_advertised_vram() {
        // household borrow only prefers a peer that
        // actually advertises a GPU (vram > 0). A CPU box (vram 0) or an unknown
        // node must read false.
        var reg = ResourceRegistry.get();
        reg.updateSnapshot(makeSnapshot("gpu-node", true, "198.51.100.10", "RTX 4090", 24000));
        reg.updateSnapshot(makeSnapshot("cpu-node", true, "198.51.100.11", null, 0));
        assertTrue(reg.peerHasGpu("gpu-node"));
        assertFalse(reg.peerHasGpu("cpu-node"));
        assertFalse(reg.peerHasGpu("unknown-node"));
    }

    @Test
    void testUpdateAndRetrieve() {
        var reg = ResourceRegistry.get();
        var snap = makeSnapshot("node-1", true, "198.51.100.10", "RTX 4090", 24000);
        reg.updateSnapshot(snap);

        var retrieved = reg.getSnapshot("node-1");
        assertTrue(retrieved.isPresent());
        assertEquals("node-1", retrieved.get().nodeId());
        assertEquals("RTX 4090", retrieved.get().gpuName());
    }

    @Test
    void testNodesWithInference() {
        var reg = ResourceRegistry.get();
        reg.updateSnapshot(makeSnapshot("node-gpu", true, "198.51.100.10", "RTX 4090", 24000));
        reg.updateSnapshot(makeSnapshot("node-cpu", false, "198.51.100.11", null, 0));

        var nodes = reg.nodesWithInference();
        assertEquals(1, nodes.size());
        assertEquals("node-gpu", nodes.getFirst().nodeId());
    }

    @Test
    void testAllInferenceEndpoints() {
        var reg = ResourceRegistry.get();
        reg.updateSnapshot(makeSnapshot("home-server", true, "192.0.2.105", "RTX 4090", 24000));
        reg.updateSnapshot(makeSnapshot("local-node", true, "192.0.2.100", "RTX 3060", 12000));

        var endpoints = reg.allInferenceEndpoints();
        assertEquals(2, endpoints.size());

        // Local endpoint should use localhost URL as-is
        var localEp = endpoints.stream()
            .filter(ResourceRegistry.RemoteInferenceEndpoint::isLocal).findFirst();
        assertTrue(localEp.isPresent());
        assertEquals("http://localhost:8200", localEp.get().resolvedUrl());

        // Remote endpoint should replace localhost with LAN IP
        var remoteEp = endpoints.stream()
            .filter(e -> !e.isLocal()).findFirst();
        assertTrue(remoteEp.isPresent());
        assertEquals("http://192.0.2.105:8200", remoteEp.get().resolvedUrl());
    }

    @Test
    void testRemovePeer() {
        var reg = ResourceRegistry.get();
        reg.updateSnapshot(makeSnapshot("node-1", true, "198.51.100.10", "RTX 4090", 24000));
        assertEquals(1, reg.peerCount());

        reg.removePeer("node-1");
        assertEquals(0, reg.peerCount());
        assertTrue(reg.getSnapshot("node-1").isEmpty());
    }

    @Test
    void testBestNodeForInference_localWinsWhenSimilarGpu() {
        var reg = ResourceRegistry.get();
        // Local node with same-ish GPU
        reg.updateSnapshot(makeSnapshot("local-node", true, "192.0.2.100", "RTX 4060", 16000));
        // Remote node with slightly bigger GPU
        reg.updateSnapshot(makeSnapshot("remote", true, "192.0.2.105", "RTX 4070", 16000));
        reg.updateLatency("remote", 15.0);

        var best = reg.bestNodeForInference();
        assertTrue(best.isPresent());
        // Local should win — same GPU class, local preference tips the balance
        assertEquals("local-node", best.get().nodeId());
    }

    @Test
    void testBestNodeForInference_remoteWinsWithMuchBiggerGpu() {
        var reg = ResourceRegistry.get();
        // Local node with no GPU
        reg.updateSnapshot(makeSnapshot("local-node", true, "192.0.2.100", null, 0));
        // Remote node with big GPU
        reg.updateSnapshot(makeSnapshot("home-server", true, "192.0.2.105", "RTX 4090", 24000));
        reg.updateLatency("home-server", 15.0);

        var best = reg.bestNodeForInference();
        assertTrue(best.isPresent());
        // Remote should win — it has a real GPU
        assertEquals("home-server", best.get().nodeId());
    }

    @Test
    void testRemoteUrlResolution_noLanIp() {
        var reg = ResourceRegistry.get();
        // Remote node with localhost URL but no LAN IP — should be unreachable
        var snap = makeSnapshot("remote-no-ip", true, null, "RTX 4090", 24000);
        reg.updateSnapshot(snap);

        var endpoints = reg.allInferenceEndpoints();
        var remote = endpoints.stream()
            .filter(e -> e.nodeId().equals("remote-no-ip")).findFirst();
        // Should not appear because URL can't be resolved
        assertTrue(remote.isEmpty());
    }

    @Test
    void testNodesWithGpu() {
        var reg = ResourceRegistry.get();
        reg.updateSnapshot(makeSnapshot("gpu-node", true, "198.51.100.10", "RTX 4090", 24000));
        reg.updateSnapshot(makeSnapshot("cpu-node", false, "198.51.100.11", null, 0));

        var gpuNodes = reg.nodesWithGpu();
        // Both snapshots have "gpu" in capabilities set (from makeSnapshot)
        // but cpu-node has no GPU name — capability set is what matters
        assertEquals(2, gpuNodes.size());
    }
}
