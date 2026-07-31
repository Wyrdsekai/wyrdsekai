package org.wyrdsekai.between.layer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry of all node resources in the household.
 * Updated on every peer heartbeat. All routing decisions (inference, search,
 * storage, scripts) consult this registry to find the best node for a request.
 *
 * Singleton — one per JVM. The Between layer owns the zone, this owns the resource map.
 */
public final class ResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ResourceRegistry.class);
    private static final ResourceRegistry INSTANCE = new ResourceRegistry();
    // Snapshot stale threshold. Heartbeat interval is 10s for same-zone peers.
    // Cross-zone capability announcements arrive via RelayBridge forwarding,
    // which can batch under load or across WiFi-wired network transitions.
    // 120s = 12 missed announcements before we declare a peer dead — errs
    // strongly toward keeping cross-zone inference backends alive through
    // transient relay jitter. Real peer death is caught by TopologyRegister
    // heartbeat timeout (30s) + BetweenActor's PeerTimedOut → removePeer,
    // which is an explicit signal, not a stale-timestamp inference.
    private static final Duration STALE_THRESHOLD = Duration.ofSeconds(120);

    /** Peer snapshots keyed by nodeId. Includes local node. */
    private final ConcurrentHashMap<String, NodeCapabilities.Snapshot> snapshots = new ConcurrentHashMap<>();

    /** Peer latency (ms) from TopologyRegister, keyed by nodeId. */
    private final ConcurrentHashMap<String, Double> peerLatency = new ConcurrentHashMap<>();

    private volatile String localNodeId;

    private ResourceRegistry() {}

    public static ResourceRegistry get() { return INSTANCE; }

    public void setLocalNodeId(String nodeId) { this.localNodeId = nodeId; }

    // ── Updates ──

    /** Called on every capability announce (heartbeat). */
    public void updateSnapshot(NodeCapabilities.Snapshot snapshot) {
        snapshots.put(snapshot.nodeId(), snapshot);
    }

    /** Called when peer latency is measured. */
    public void updateLatency(String nodeId, double latencyMs) {
        peerLatency.put(nodeId, latencyMs);
    }

    /** Called when a peer disconnects or times out. */
    public void removePeer(String nodeId) {
        snapshots.remove(nodeId);
        peerLatency.remove(nodeId);
    }

    // ── Queries ──

    /** Get snapshot for a specific node. */
    public Optional<NodeCapabilities.Snapshot> getSnapshot(String nodeId) {
        return Optional.ofNullable(snapshots.get(nodeId));
    }

    /** All current snapshots (including local). */
    public Map<String, NodeCapabilities.Snapshot> allSnapshots() {
        return Map.copyOf(snapshots);
    }

    /** All nodes that have GPU. */
    public List<String> nodesWithGpu() {
        return snapshots.values().stream()
            .filter(s -> s.hasCapability(NodeCapabilities.CAP_GPU) && isAlive(s))
            .map(NodeCapabilities.Snapshot::nodeId)
            .toList();
    }

    /** All nodes that have inference endpoints. */
    public List<NodeCapabilities.Snapshot> nodesWithInference() {
        return snapshots.values().stream()
            .filter(s -> s.hasInference() && isAlive(s))
            .toList();
    }

    /** Find the best node for inference, scored by: local preference, GPU, load, latency. */
    public Optional<NodeCapabilities.Snapshot> bestNodeForInference() {
        return snapshots.values().stream()
            .filter(s -> s.hasInference() && isAlive(s))
            .min(Comparator.comparingDouble(this::inferenceScore));
    }

    /** All nodes with a search engine (Searxng). */
    public List<NodeCapabilities.Snapshot> nodesWithSearch() {
        return snapshots.values().stream()
            .filter(s -> s.hasSearchEngine() && isAlive(s))
            .toList();
    }

    /** All nodes with Oracle prediction engine. */
    public List<NodeCapabilities.Snapshot> nodesWithOracle() {
        return snapshots.values().stream()
            .filter(s -> s.hasOracleEngine() && isAlive(s))
            .toList();
    }

    /** Find all inference endpoints across all nodes. */
    public List<RemoteInferenceEndpoint> allInferenceEndpoints() {
        var result = new ArrayList<RemoteInferenceEndpoint>();
        for (var snap : snapshots.values()) {
            if (!isAlive(snap) || snap.inferenceEndpoints() == null) continue;
            for (var ep : snap.inferenceEndpoints()) {
                var baseUrl = buildBaseUrl(snap, ep);
                if (baseUrl != null) {
                    result.add(new RemoteInferenceEndpoint(
                        snap.nodeId(), ep, baseUrl,
                        peerLatency.getOrDefault(snap.nodeId(), 0.0),
                        isLocal(snap.nodeId())
                    ));
                }
            }
        }
        return result;
    }

    /** Convenience: get the local node's snapshot. */
    public Optional<NodeCapabilities.Snapshot> localSnapshot() {
        return localNodeId != null ? getSnapshot(localNodeId) : Optional.empty();
    }

    /**
     * Does the given peer node advertise a usable GPU? Used by household inference
     * auto-share to only prefer borrowing from a household peer that actually has a
     * GPU (not another CPU box).
     */
    public boolean peerHasGpu(String nodeId) {
        var snap = snapshots.get(nodeId);
        return snap != null && snap.gpuVramMb() > 0;
    }

    public int peerCount() {
        return (int) snapshots.values().stream()
            .filter(s -> !isLocal(s.nodeId()) && isAlive(s))
            .count();
    }

    // ── Scoring ──

    /** Lower score = better for inference. Local nodes get a bonus. */
    private double inferenceScore(NodeCapabilities.Snapshot snap) {
        double score = 0;

        // Local preference: remote nodes penalized by latency
        if (!isLocal(snap.nodeId())) {
            score += 10.0 + peerLatency.getOrDefault(snap.nodeId(), 50.0) / 10.0;
        }

        // GPU bonus (lower score = better)
        if (snap.gpuVramMb() > 0) {
            score -= snap.gpuVramMb() / 1024.0 * 3.0;
        }

        // Load penalty
        if (snap.cpuIdlePct() < 20) {
            score += 15.0; // heavily loaded
        }

        // Companion count penalty (more companions = more inference contention)
        if (snap.companionHosting() != null) {
            score += snap.companionHosting().size() * 2.0;
        }

        return score;
    }

    // ── Helpers ──

    private boolean isLocal(String nodeId) {
        return localNodeId != null && localNodeId.equals(nodeId);
    }

    private boolean isAlive(NodeCapabilities.Snapshot snap) {
        if (snap.timestamp() == null) return true; // backward compat
        return Duration.between(snap.timestamp(), Instant.now()).compareTo(STALE_THRESHOLD) < 0;
    }

    private String buildBaseUrl(NodeCapabilities.Snapshot snap, NodeCapabilities.InferenceEndpoint ep) {
        // If the endpoint has a full URL, use it directly
        if (ep.url() != null && !ep.url().isEmpty()) {
            // For local node, the URL is already correct
            if (isLocal(snap.nodeId())) return ep.url();
            // For remote nodes, replace localhost with the node's LAN IP
            var url = ep.url();
            if (url.contains("localhost") || url.contains("127.0.0.1")) {
                if (snap.lanIp() != null && !snap.lanIp().isEmpty()) {
                    url = url.replace("localhost", snap.lanIp())
                             .replace("127.0.0.1", snap.lanIp());
                } else {
                    return null; // can't reach remote localhost
                }
            }
            return url;
        }
        return null;
    }

    /** A resolved inference endpoint with network info. */
    public record RemoteInferenceEndpoint(
        String nodeId,
        NodeCapabilities.InferenceEndpoint endpoint,
        String resolvedUrl,       // full URL reachable from this node
        double latencyMs,
        boolean isLocal
    ) {}
}
