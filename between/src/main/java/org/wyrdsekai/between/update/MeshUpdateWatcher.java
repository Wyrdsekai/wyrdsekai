package org.wyrdsekai.between.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.TopologyRegister;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.update.ReleaseManifest;
import org.wyrdsekai.core.update.UpdateConfig;
import org.wyrdsekai.core.update.UpdateEngine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * Watches mesh peers for version changes and manages update propagation.
 *
 * Flow:
 * 1. Observes peer heartbeats via TopologyRegister
 * 2. When a peer is at a newer version, starts stability timer
 * 3. After stability delay (default 5m), if peer is still healthy, triggers update
 * 4. Primary node waits until all secondaries are at new version
 * 5. Failed updates are broadcast to prevent cascade
 */
public final class MeshUpdateWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MeshUpdateWatcher.class);

    private final TopologyRegister topology;
    private final UpdateConfig config;
    private final UpdateEngine engine;
    private final ScheduledExecutorService scheduler;

    // Track pending updates: version → first seen timestamp
    private final ConcurrentHashMap<String, PendingUpdate> pendingUpdates = new ConcurrentHashMap<>();
    // Track failed versions across the mesh
    private final Set<String> meshFailedVersions = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;

    public record PendingUpdate(
        String version,
        String sourceNodeId,
        Instant firstSeen,
        int consecutiveHealthyChecks
    ) {}

    public MeshUpdateWatcher(TopologyRegister topology, UpdateConfig config, UpdateEngine engine) {
        this.topology = topology;
        this.config = config;
        this.engine = engine;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "mesh-update-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start watching mesh peers for version changes.
     * Checks every 30s (aligned with heartbeat interval).
     */
    public void start() {
        if (config.policy() == UpdateConfig.UpdatePolicy.DISABLED) {
            log.info("[MeshUpdate] Disabled by policy");
            return;
        }
        running = true;
        scheduler.scheduleAtFixedRate(this::check, 30, 30, TimeUnit.SECONDS);
        log.info("[MeshUpdate] Watcher started (policy={}, delay={}, role={})",
            config.policy(), config.stabilityDelay(), config.nodeRole());
    }

    /**
     * Record a failed version from mesh (peer's heartbeat with failedUpdate field).
     */
    public void recordMeshFailure(String version) {
        meshFailedVersions.add(version);
        pendingUpdates.remove(version);
        log.warn("[MeshUpdate] Version {} marked as failed by mesh peer", version);
    }

    /**
     * Get pending updates being tracked.
     */
    public Map<String, PendingUpdate> pendingUpdates() {
        return Collections.unmodifiableMap(pendingUpdates);
    }

    /**
     * Get versions that failed on mesh peers.
     */
    public Set<String> meshFailedVersions() {
        return Collections.unmodifiableSet(meshFailedVersions);
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
    }

    // --- Internal ---

    private void check() {
        if (!running) return;

        try {
            var myVersion = AppVersion.get().version();
            var connections = topology.allConnections();

            for (var conn : connections) {
                if (!conn.connected()) continue;
                if (conn.appVersion() == null) continue;

                // Skip if peer is at same or older version
                if (ReleaseManifest.compareVersions(conn.appVersion(), myVersion) <= 0) continue;

                var peerVersion = conn.appVersion();

                // Skip versions known to have failed
                if (meshFailedVersions.contains(peerVersion)) continue;
                if (peerVersion.equals(engine.failedVersion())) continue;

                // Track or update pending
                pendingUpdates.compute(peerVersion, (v, existing) -> {
                    if (existing == null) {
                        log.info("[MeshUpdate] Peer {} at v{} (newer than our v{}). Starting stability timer.",
                            conn.remoteNodeId(), peerVersion, myVersion);
                        return new PendingUpdate(peerVersion, conn.remoteNodeId(), Instant.now(), 1);
                    } else {
                        return new PendingUpdate(peerVersion, existing.sourceNodeId(),
                            existing.firstSeen(), existing.consecutiveHealthyChecks() + 1);
                    }
                });
            }

            // Check if any pending updates have passed the stability delay
            var now = Instant.now();
            for (var entry : pendingUpdates.entrySet()) {
                var pending = entry.getValue();
                var elapsed = Duration.between(pending.firstSeen(), now);

                if (elapsed.compareTo(config.stabilityDelay()) < 0) continue;

                // Stability delay passed — check if source peer is still healthy
                var sourcePeer = topology.get(pending.sourceNodeId());
                if (sourcePeer.isEmpty() || !sourcePeer.get().connected()) {
                    log.warn("[MeshUpdate] Source peer {} went down during stability delay. Canceling v{}.",
                        pending.sourceNodeId(), pending.version());
                    pendingUpdates.remove(entry.getKey());
                    continue;
                }

                // Primary node: wait until all secondaries are at new version
                if (config.isPrimary()) {
                    if (!allSecondariesAtVersion(pending.version(), connections)) {
                        log.debug("[MeshUpdate] Primary node waiting — not all secondaries at v{}",
                            pending.version());
                        continue;
                    }
                }

                // Check policy
                var manifest = new ReleaseManifest(pending.version(),
                    sourcePeer.get().wireProtocol(), null, null, null, null, null, false, null);
                var effectivePolicy = config.effectivePolicy(myVersion, manifest);

                switch (effectivePolicy) {
                    case AUTO -> {
                        // Check maintenance window
                        if (config.maintenanceWindow() != null && !inMaintenanceWindow()) {
                            log.debug("[MeshUpdate] Auto update for v{} waiting for maintenance window",
                                pending.version());
                            continue;
                        }
                        log.info("[MeshUpdate] Auto-applying v{} from peer {} (stable for {})",
                            pending.version(), pending.sourceNodeId(), elapsed);
                        pendingUpdates.remove(entry.getKey());
                        triggerUpdate(manifest, pending.sourceNodeId());
                    }
                    case PROMPT -> {
                        log.info("[MeshUpdate] v{} available (stable for {}). Steward approval required.",
                            pending.version(), elapsed);
                        // TODO: notify steward via companion message
                        pendingUpdates.remove(entry.getKey());
                    }
                    case MANUAL -> {
                        log.debug("[MeshUpdate] v{} available but policy=manual. Use: wyrdsekai update",
                            pending.version());
                        pendingUpdates.remove(entry.getKey());
                    }
                    case DISABLED -> pendingUpdates.remove(entry.getKey());
                }
            }

            // Clean up pending updates for versions no longer seen in mesh
            pendingUpdates.entrySet().removeIf(entry -> {
                var version = entry.getKey();
                return connections.stream().noneMatch(c ->
                    c.connected() && version.equals(c.appVersion()));
            });

        } catch (Exception e) {
            log.warn("[MeshUpdate] Check failed: {}", e.getMessage());
        }
    }

    private boolean allSecondariesAtVersion(String version,
                                             Collection<TopologyRegister.ConnectionState> connections) {
        // All connected peers should be at the target version (or newer)
        return connections.stream()
            .filter(TopologyRegister.ConnectionState::connected)
            .filter(c -> c.appVersion() != null)
            .allMatch(c -> ReleaseManifest.compareVersions(c.appVersion(), version) >= 0);
    }

    private void triggerUpdate(ReleaseManifest manifest, String sourceNodeId) {
        // Build download URL from peer
        var peer = topology.get(sourceNodeId);
        if (peer.isEmpty()) {
            log.warn("[MeshUpdate] Source peer {} no longer available", sourceNodeId);
            return;
        }
        // Construct URL from peer's artery host (Between knows the peer's IP)
        var caps = peer.get().capabilities();
        // Use http port (default 7070) on the peer's host
        // Note: in production, this would use the peer's advertised HTTP port
        var peerHost = caps != null ? caps.getOrDefault("arteryHost", "127.0.0.1") : "127.0.0.1";
        var downloadUrl = "http://" + peerHost + ":7070/api/update/package";

        var result = engine.apply(manifest, downloadUrl);
        if (result.success()) {
            log.info("[MeshUpdate] Update staged successfully. Server restart needed.");
        } else {
            log.warn("[MeshUpdate] Update failed: {}", result.message());
        }
    }

    private boolean inMaintenanceWindow() {
        if (config.maintenanceWindow() == null) return true;
        try {
            var parts = config.maintenanceWindow().split("-");
            if (parts.length != 2) return true;
            var now = LocalTime.now();
            var start = LocalTime.parse(parts[0].trim());
            var end = LocalTime.parse(parts[1].trim());
            if (start.isBefore(end)) {
                return now.isAfter(start) && now.isBefore(end);
            } else {
                // Wraps midnight (e.g., 22:00-06:00)
                return now.isAfter(start) || now.isBefore(end);
            }
        } catch (Exception e) {
            return true; // If we can't parse, allow updates
        }
    }

    /**
     * Select the best peer to download from: lowest latency, then longest uptime at target version.
     */
    public Optional<String> selectBestPeer(String targetVersion) {
        return topology.allConnections().stream()
            .filter(TopologyRegister.ConnectionState::connected)
            .filter(c -> targetVersion.equals(c.appVersion()))
            .min(Comparator.comparingDouble(TopologyRegister.ConnectionState::latencyMs)
                .thenComparing(c -> -c.connectionAgeMs())) // prefer longer uptime
            .map(TopologyRegister.ConnectionState::remoteNodeId);
    }
}
