package org.wyrdsekai.between;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory register of connection state per peer node.
 * Volatile by design — rebuilt on each boot from live connections.
 * Not persisted (The Between is RAM, not Disk).
 */
public final class TopologyRegister {

    public record ConnectionState(
        String remoteNodeId,
        boolean connected,
        double latencyMs,             // exponential moving average (alpha=0.3)
        double jitterMs,              // rolling stddev of last N samples
        long bandwidthEstimateBps,    // from OS network interface stats
        long connectionAgeMs,         // millis since first connected this session
        int activeLayers,             // NATS subjects with traffic in last 30s
        Instant lastHeartbeat,
        Map<String, String> capabilities,  // static per session (cpu, memory, gpu, etc.)
        String appVersion,            // peer's running app version (mesh update protocol)
        int wireProtocol,             // peer's wire protocol version
        Instant versionSince          // when peer started running this version
    ) {
        /** Backward-compatible constructor without version fields. */
        public ConnectionState(String remoteNodeId, boolean connected,
                               double latencyMs, double jitterMs,
                               long bandwidthEstimateBps, long connectionAgeMs,
                               int activeLayers, Instant lastHeartbeat,
                               Map<String, String> capabilities) {
            this(remoteNodeId, connected, latencyMs, jitterMs,
                bandwidthEstimateBps, connectionAgeMs, activeLayers,
                lastHeartbeat, capabilities, null, 0, null);
        }
    }

    private static final double EMA_ALPHA = 0.3;
    private static final int JITTER_WINDOW = 20;

    private final ConcurrentHashMap<String, ConnectionState> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Double>> latencySamples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> firstConnected = new ConcurrentHashMap<>();

    /**
     * Record a new peer connection with its capabilities.
     */
    public void peerConnected(String remoteNodeId, Map<String, String> capabilities) {
        peerConnected(remoteNodeId, capabilities, null, 0);
    }

    /**
     * Record a new peer connection with capabilities and version info.
     */
    public void peerConnected(String remoteNodeId, Map<String, String> capabilities,
                              String appVersion, int wireProtocol) {
        firstConnected.putIfAbsent(remoteNodeId, Instant.now());
        latencySamples.putIfAbsent(remoteNodeId, new ArrayList<>());

        connections.put(remoteNodeId, new ConnectionState(
            remoteNodeId, true, 0.0, 0.0, 0L,
            connectionAge(remoteNodeId), 0,
            Instant.now(), capabilities,
            appVersion, wireProtocol, Instant.now()
        ));
    }

    /**
     * Record a peer disconnection.
     */
    public void peerDisconnected(String remoteNodeId) {
        var current = connections.get(remoteNodeId);
        if (current != null) {
            connections.put(remoteNodeId, new ConnectionState(
                remoteNodeId, false, current.latencyMs(), current.jitterMs(),
                0L, connectionAge(remoteNodeId), 0,
                current.lastHeartbeat(), current.capabilities(),
                current.appVersion(), current.wireProtocol(), current.versionSince()
            ));
        }
    }

    /**
     * Update latency measurement for a peer (from probe ping/pong).
     */
    public void updateLatency(String remoteNodeId, double latencyMs) {
        var samples = latencySamples.computeIfAbsent(remoteNodeId, _ -> new ArrayList<>());
        samples.add(latencyMs);
        while (samples.size() > JITTER_WINDOW) {
            samples.removeFirst();
        }

        var current = connections.get(remoteNodeId);
        if (current == null) return;

        // Exponential moving average
        var emaLatency = current.latencyMs() == 0.0
            ? latencyMs
            : EMA_ALPHA * latencyMs + (1 - EMA_ALPHA) * current.latencyMs();

        // Jitter = stddev of samples
        var jitter = calculateStdDev(samples);

        connections.put(remoteNodeId, new ConnectionState(
            remoteNodeId, true, emaLatency, jitter,
            current.bandwidthEstimateBps(), connectionAge(remoteNodeId),
            current.activeLayers(), Instant.now(), current.capabilities(),
            current.appVersion(), current.wireProtocol(), current.versionSince()
        ));
    }

    /**
     * Update heartbeat timestamp (legacy — no version info).
     */
    public void updateHeartbeat(String remoteNodeId) {
        updateHeartbeat(remoteNodeId, null, 0);
    }

    /**
     * Update heartbeat timestamp with version info from heartbeat payload.
     */
    public void updateHeartbeat(String remoteNodeId, String appVersion, int wireProtocol) {
        var current = connections.get(remoteNodeId);
        if (current != null) {
            // If version changed, update versionSince
            var versionSince = current.versionSince();
            var effectiveVersion = appVersion != null ? appVersion : current.appVersion();
            var effectiveWire = wireProtocol > 0 ? wireProtocol : current.wireProtocol();
            if (effectiveVersion != null && !effectiveVersion.equals(current.appVersion())) {
                versionSince = Instant.now(); // version changed — reset timer
            }

            connections.put(remoteNodeId, new ConnectionState(
                remoteNodeId, true, current.latencyMs(), current.jitterMs(),
                current.bandwidthEstimateBps(), connectionAge(remoteNodeId),
                current.activeLayers(), Instant.now(), current.capabilities(),
                effectiveVersion, effectiveWire, versionSince
            ));
        }
    }

    /** Check if a peer is known (has been through hello handshake). */
    public boolean isPeerConnected(String remoteNodeId) {
        var state = connections.get(remoteNodeId);
        return state != null && state.connected();
    }

    public Optional<ConnectionState> get(String remoteNodeId) {
        return Optional.ofNullable(connections.get(remoteNodeId));
    }

    public Collection<ConnectionState> allConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public int connectedNodeCount() {
        return (int) connections.values().stream()
            .filter(ConnectionState::connected)
            .count();
    }

    /**
     * Human-readable summary for room scripts and diagnostics.
     */
    public String describe() {
        var connected = connectedNodeCount();
        if (connected == 0) {
            return "Topology: standalone (no peers connected)";
        }

        var sb = new StringBuilder();
        sb.append("Topology: ").append(connected).append(" peer(s) connected\n");

        connections.values().stream()
            .filter(ConnectionState::connected)
            .sorted(Comparator.comparing(ConnectionState::remoteNodeId))
            .forEach(c -> {
                var shortId = c.remoteNodeId().length() > 8
                    ? c.remoteNodeId().substring(0, 8) : c.remoteNodeId();
                sb.append("  ").append(shortId).append(": ");
                if (c.appVersion() != null) {
                    sb.append("v").append(c.appVersion()).append(", ");
                }
                sb.append(String.format("latency %.1fms", c.latencyMs()));
                if (c.jitterMs() > 0) {
                    sb.append(String.format(", jitter %.1fms", c.jitterMs()));
                }
                var caps = c.capabilities();
                if (caps != null && caps.containsKey("cpuCount")) {
                    sb.append(", ").append(caps.get("cpuCount")).append(" CPUs");
                }
                if (caps != null && caps.containsKey("memoryMb")) {
                    sb.append(", ").append(caps.get("memoryMb")).append("MB RAM");
                }
                if (caps != null && caps.containsKey("gpu_count")) {
                    sb.append(", ").append(caps.get("gpu_count")).append(" GPU(s)");
                    if (caps.containsKey("gpu_free_vram_mb")) {
                        sb.append(" (").append(caps.get("gpu_free_vram_mb")).append("MB free)");
                    }
                }
                if (caps != null && caps.containsKey("inference_model")) {
                    sb.append(", model=").append(caps.get("inference_model"));
                }
                if (caps != null && caps.containsKey("inference_slots")) {
                    sb.append(", slots=").append(caps.get("inference_slots"));
                }
                sb.append("\n");
            });

        return sb.toString().stripTrailing();
    }

    // --- Internal ---

    private long connectionAge(String remoteNodeId) {
        var first = firstConnected.get(remoteNodeId);
        return first != null
            ? Duration.between(first, Instant.now()).toMillis()
            : 0L;
    }

    private static double calculateStdDev(List<Double> samples) {
        if (samples.size() < 2) return 0.0;
        var mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        var variance = samples.stream()
            .mapToDouble(s -> (s - mean) * (s - mean))
            .average().orElse(0.0);
        return Math.sqrt(variance);
    }
}
