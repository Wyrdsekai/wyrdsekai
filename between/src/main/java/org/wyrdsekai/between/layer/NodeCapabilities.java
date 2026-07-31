package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Node capability detection and advertisement (Wave 2: Node Coordination).
 * Detects local hardware + software capabilities and publishes them
 * via NATS for use by the PlacementEngine.
 *
 * Capabilities determine which rooms and services a node can host.
 * The placement engine uses capability matching + scoring to decide
 * room primary ownership and companion placement.
 */
public final class NodeCapabilities {

    private static final Logger log = LoggerFactory.getLogger(NodeCapabilities.class);

    // ── Capability names (match spec §Room Capability Requirements) ──

    public static final String CAP_GPU = "gpu";
    public static final String CAP_INFERENCE = "inference";
    public static final String CAP_INTERNET = "internet";
    public static final String CAP_STORAGE = "storage";
    public static final String CAP_PREDICTION = "prediction";
    public static final String CAP_SOULSTORE = "soulstore";

    // ── Inference endpoint record ──

    /**
     * Describes an inference endpoint available on this node.
     * Published in snapshots so peers can discover and route inference requests.
     */
    public record InferenceEndpoint(
        @JsonProperty("backendType") String backendType,      // "llama-server", "sglang", "ollama"
        @JsonProperty("modelName") String modelName,          // "wyrdsekai-3.5-4b-ssd-q4km"
        @JsonProperty("url") String url,                      // "http://192.0.2.105:8200"
        @JsonProperty("maxConcurrency") int maxConcurrency,
        @JsonProperty("ctxSize") int ctxSize,
        @JsonProperty("supportsTools") boolean supportsTools,
        @JsonProperty("supportsStreaming") boolean supportsStreaming
    ) {
        @JsonCreator
        public InferenceEndpoint {}
    }

    // ── Snapshot record ──

    /**
     * Complete snapshot of a node's capabilities and current resource state.
     * Published periodically via NATS for placement decisions.
     */
    public record Snapshot(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("capabilities") Set<String> capabilities,
        @JsonProperty("cpuCount") int cpuCount,
        @JsonProperty("ramTotalMb") long ramTotalMb,
        @JsonProperty("ramFreeMb") long ramFreeMb,
        @JsonProperty("gpuName") String gpuName,
        @JsonProperty("gpuVramMb") long gpuVramMb,
        @JsonProperty("diskFreeMb") long diskFreeMb,
        @JsonProperty("cpuIdlePct") double cpuIdlePct,
        @JsonProperty("inferenceBackend") String inferenceBackend,
        @JsonProperty("inferenceModelLoaded") boolean inferenceModelLoaded,
        @JsonProperty("inferenceEndpoints") List<InferenceEndpoint> inferenceEndpoints,
        @JsonProperty("companionHosting") List<String> companionHosting,
        @JsonProperty("roomPrimaries") List<String> roomPrimaries,
        @JsonProperty("batteryPct") int batteryPct, // -1 = plugged in / no battery
        @JsonProperty("nodeState") String nodeState, // HEALTHY, JOINING, DRAINING, MAINTENANCE, DEGRADED, DOWN
        @JsonProperty("lanIp") String lanIp,
        @JsonProperty("httpPort") int httpPort,
        @JsonProperty("hasSearchEngine") boolean hasSearchEngine,
        @JsonProperty("hasOracleEngine") boolean hasOracleEngine,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator
        public Snapshot {}

        /** Backward-compatible constructor without new fields. */
        public Snapshot(String nodeId, Set<String> capabilities, int cpuCount,
                       long ramTotalMb, long ramFreeMb, String gpuName, long gpuVramMb,
                       long diskFreeMb, double cpuIdlePct, String inferenceBackend,
                       boolean inferenceModelLoaded, List<String> companionHosting,
                       List<String> roomPrimaries, int batteryPct, String nodeState,
                       Instant timestamp) {
            this(nodeId, capabilities, cpuCount, ramTotalMb, ramFreeMb, gpuName, gpuVramMb,
                diskFreeMb, cpuIdlePct, inferenceBackend, inferenceModelLoaded,
                List.of(), companionHosting, roomPrimaries, batteryPct, nodeState,
                null, 0, false, false, timestamp);
        }

        /** Whether this node has a specific capability. */
        public boolean hasCapability(String cap) {
            return capabilities != null && capabilities.contains(cap);
        }

        /** Whether this node satisfies all required capabilities for a room. */
        public boolean satisfiesRequirements(Set<String> required) {
            if (required == null || required.isEmpty()) return true;
            return capabilities != null && capabilities.containsAll(required);
        }

        /** Whether this node has any inference endpoints available. */
        public boolean hasInference() {
            return inferenceEndpoints != null && !inferenceEndpoints.isEmpty();
        }
    }

    /** Node state signals (§: State Signals). */
    public enum NodeState {
        HEALTHY,      // Normal operation
        JOINING,      // Enrolling, syncing state
        DRAINING,     // Preparing for shutdown — migrate everything off
        MAINTENANCE,  // Admin-triggered, no new placements
        DEGRADED,     // High load or errors, reduce placements
        DOWN          // Unreachable (set by others, not self-reported)
    }

    // ── Detection ──

    private final String nodeId;
    private final Set<String> detectedCapabilities;
    private volatile String inferenceBackend;
    private volatile boolean inferenceModelLoaded;
    private volatile String gpuName;
    private volatile long gpuVramMb;
    private volatile NodeState state = NodeState.HEALTHY;
    private volatile String lanIp;
    private volatile int httpPort = 7070;
    private volatile boolean hasSearchEngine;
    private volatile boolean hasOracleEngine;
    private final List<String> companionHosting = Collections.synchronizedList(new ArrayList<>());
    private final List<String> roomPrimaries = Collections.synchronizedList(new ArrayList<>());
    private volatile List<InferenceEndpoint> inferenceEndpoints = List.of();

    public NodeCapabilities(String nodeId) {
        this.nodeId = nodeId;
        this.detectedCapabilities = detectCapabilities();
        log.info("Node {} capabilities detected: {}", nodeId, detectedCapabilities);
    }

    /**
     * Build a current snapshot of this node's capabilities and resources.
     * Called periodically to publish via NATS.
     */
    public Snapshot snapshot() {
        var os = ManagementFactory.getOperatingSystemMXBean();
        var runtime = Runtime.getRuntime();
        var cpuCount = os.getAvailableProcessors();
        var ramTotalMb = runtime.maxMemory() / (1024 * 1024);
        var ramFreeMb = runtime.freeMemory() / (1024 * 1024);
        var diskFreeMb = getDiskFreeMb();

        // CPU idle estimation — JMX system load average (1.0 = 100% all cores)
        var loadAvg = os.getSystemLoadAverage();
        var cpuIdlePct = loadAvg >= 0
            ? Math.max(0, 100.0 - (loadAvg / cpuCount) * 100.0)
            : 50.0; // unknown

        var batteryPct = detectBattery();

        return new Snapshot(
            nodeId,
            Set.copyOf(detectedCapabilities),
            cpuCount,
            ramTotalMb,
            ramFreeMb,
            gpuName,
            gpuVramMb,
            diskFreeMb,
            cpuIdlePct,
            inferenceBackend,
            inferenceModelLoaded,
            List.copyOf(inferenceEndpoints),
            List.copyOf(companionHosting),
            List.copyOf(roomPrimaries),
            batteryPct,
            state.name(),
            lanIp,
            httpPort,
            hasSearchEngine,
            hasOracleEngine,
            Instant.now()
        );
    }

    // ── Mutable state updates ──

    public void setInferenceBackend(String backend) { this.inferenceBackend = backend; }
    public void setInferenceModelLoaded(boolean loaded) { this.inferenceModelLoaded = loaded; }
    public void setGpu(String name, long vramMb) { this.gpuName = name; this.gpuVramMb = vramMb; }
    public void setState(NodeState newState) { this.state = newState; }
    public NodeState getState() { return state; }
    public void setLanIp(String ip) { this.lanIp = ip; }
    public void setHttpPort(int port) { this.httpPort = port; }
    public void setHasSearchEngine(boolean has) { this.hasSearchEngine = has; }
    public void setHasOracleEngine(boolean has) { this.hasOracleEngine = has; }
    public void setInferenceEndpoints(List<InferenceEndpoint> endpoints) {
        this.inferenceEndpoints = endpoints != null ? List.copyOf(endpoints) : List.of();
    }

    public void addCompanionHosting(String companionId) {
        if (!companionHosting.contains(companionId)) companionHosting.add(companionId);
    }
    public void removeCompanionHosting(String companionId) { companionHosting.remove(companionId); }

    public void addRoomPrimary(String roomId) {
        if (!roomPrimaries.contains(roomId)) roomPrimaries.add(roomId);
    }
    public void removeRoomPrimary(String roomId) { roomPrimaries.remove(roomId); }

    public Set<String> getCapabilities() { return Set.copyOf(detectedCapabilities); }
    public String getNodeId() { return nodeId; }

    // ── Detection logic ──

    private Set<String> detectCapabilities() {
        var caps = new HashSet<String>();
        var cfg = WyrdConfig.get();

        // Inference — check if llama-server, SGLang, or Ollama is configured
        if (cfg.inferenceUrl() != null
            || cfg.sglangUrl() != null
            || cfg.ollamaUrl() != null) {
            caps.add(CAP_INFERENCE);
        }

        // GPU — check nvidia-smi or env var
        if (detectGpu()) {
            caps.add(CAP_GPU);
        }

        // Internet — check if outbound connectivity is likely
        if (detectInternet()) {
            caps.add(CAP_INTERNET);
        }

        // Storage — node always has storage, but flag as "storage" if > 10GB free
        if (getDiskFreeMb() > 10_000) {
            caps.add(CAP_STORAGE);
        }

        // Prediction — check if Oracle/prediction engine is configured
        if (cfg.oracleEnabled() || cfg.predictionModel() != null) {
            caps.add(CAP_PREDICTION);
        }

        // SoulStore — check if Forge is configured
        if (cfg.forgeEnabled() || cfg.soulstoreDir() != null) {
            caps.add(CAP_SOULSTORE);
        }

        return caps;
    }

    private boolean detectGpu() {
        // Check NVIDIA
        try {
            var proc = new ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total",
                "--format=csv,noheader,nounits").start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.waitFor() == 0 && !output.isEmpty()) {
                var parts = output.split(",");
                gpuName = parts[0].trim();
                gpuVramMb = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0;
                log.info("GPU detected: {} ({}MB VRAM)", gpuName, gpuVramMb);
                return true;
            }
        } catch (Exception ignored) {}

        // Check config override (env > profile.toml)
        var cfg = WyrdConfig.get();
        var gpuEnv = cfg.gpuName();
        if (gpuEnv != null) {
            gpuName = gpuEnv;
            var vramEnv = cfg.gpuVramMb();
            gpuVramMb = vramEnv != null ? Long.parseLong(vramEnv) : 0;
            return true;
        }

        // Apple Silicon: Metal GPU on unified memory — nvidia-smi is absent here,
        // so detect by OS/arch and advertise a GPU so household peers can borrow it.
        var os = System.getProperty("os.name", "").toLowerCase();
        var arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            gpuName = "Apple Silicon (Metal)";
            // Unified memory — report a nominal ceiling so household peers see a GPU
            // (only the >0 "has GPU" signal is consumed by auto-share).
            if (gpuVramMb <= 0) gpuVramMb = 16384;
            log.info("GPU detected: Apple Silicon (Metal)");
            return true;
        }

        return false;
    }

    /**
     * Lightweight static "does this host have a usable GPU?" check, computed once
     * without mutating instance state. Used by household inference auto-share to
     * decide whether to prefer borrowing a household peer's GPU (a CPU-only node
     * borrows; a GPU node stays local-first).
     */
    public static boolean hostHasGpu() {
        // NVIDIA
        try {
            var proc = new ProcessBuilder("nvidia-smi", "--query-gpu=name",
                "--format=csv,noheader,nounits").start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            if (proc.waitFor() == 0 && !output.isEmpty()) return true;
        } catch (Exception ignored) {}
        // Apple Silicon (Metal)
        var os = System.getProperty("os.name", "").toLowerCase();
        var arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) return true;
        // Explicit config override (env > profile.toml)
        return WyrdConfig.get().gpuName() != null;
    }

    private boolean detectInternet() {
        // Honor explicit offline flag; otherwise assume internet is available.
        if (WyrdConfig.get().offline()) return false;
        return true;
    }

    private long getDiskFreeMb() {
        try {
            var dataDir = WyrdConfig.get().dataDir();
            var path = dataDir != null ? Path.of(dataDir) : Path.of(System.getProperty("user.home"), ".wyrdsekai");
            if (Files.exists(path)) {
                var store = Files.getFileStore(path);
                return store.getUsableSpace() / (1024 * 1024);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int detectBattery() {
        // Linux: /sys/class/power_supply/BAT0/capacity
        try {
            var batPath = Path.of("/sys/class/power_supply/BAT0/capacity");
            if (Files.exists(batPath)) {
                return Integer.parseInt(Files.readString(batPath).trim());
            }
        } catch (Exception ignored) {}
        // macOS: pmset -g batt
        try {
            var proc = new ProcessBuilder("pmset", "-g", "batt").start();
            var output = new String(proc.getInputStream().readAllBytes());
            if (proc.waitFor() == 0) {
                var matcher = Pattern.compile("(\\d+)%").matcher(output);
                if (matcher.find()) return Integer.parseInt(matcher.group(1));
            }
        } catch (Exception ignored) {}
        return -1; // plugged in / no battery
    }
}
