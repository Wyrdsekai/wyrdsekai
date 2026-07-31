package org.wyrdsekai.between.layer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placement engine for distributed resource placement (Wave 2: Node Coordination).
 * Sprite-style process placement: score-based, capability-matched, with migration inertia.
 *
 * Responsibilities:
 * - Score nodes based on hardware resources + current load
 * - Match room/service requirements to node capabilities
 * - Decide companion placement, room primary ownership
 * - Trigger migration when a better node joins or host becomes busy
 * - Handle failover when a node goes down
 */
public final class PlacementEngine {

    private static final Logger log = LoggerFactory.getLogger(PlacementEngine.class);

    // ── Tuning parameters ──

    private final double migrationCost;      // inertia penalty (default 3.0)
    private final double busyThresholdPct;   // CPU idle below this = busy (default 20%)
    private final Duration busyDuration;     // how long before eviction triggers (default 5m)
    private final Duration heartbeatTimeout; // companion heartbeat timeout (default 30s)

    // ── State ──

    /** All known node capability snapshots, keyed by nodeId. */
    private final ConcurrentHashMap<String, NodeCapabilities.Snapshot> nodeSnapshots = new ConcurrentHashMap<>();

    /** Room requirements: roomId → set of required capabilities. */
    private final Map<String, Set<String>> roomRequirements = new ConcurrentHashMap<>();

    /** Current companion claims: entityId → nodeId. */
    private final ConcurrentHashMap<String, ClaimRecord> companionClaims = new ConcurrentHashMap<>();

    /** Current room primary claims: roomId → nodeId. */
    private final ConcurrentHashMap<String, ClaimRecord> roomPrimaryClaims = new ConcurrentHashMap<>();

    /** When a node first reported busy (for busyDuration threshold). */
    private final ConcurrentHashMap<String, Instant> busySince = new ConcurrentHashMap<>();

    public record ClaimRecord(String nodeId, Instant claimedAt, double score) {}

    /**
     * Result of a placement evaluation.
     */
    public record PlacementDecision(
        String entityId,
        String currentNodeId,  // null if unclaimed
        String targetNodeId,   // null if no placement possible
        double currentScore,
        double targetScore,
        Action action
    ) {
        public enum Action {
            KEEP,       // current placement is good enough
            CLAIM,      // unclaimed — target should claim
            MIGRATE,    // target is significantly better
            FAILOVER,   // current host is down
            EVICT       // current host is too busy
        }

        public boolean requiresAction() {
            return action != Action.KEEP;
        }
    }

    // ── Constructors ──

    public PlacementEngine() {
        this(3.0, 20.0, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    public PlacementEngine(double migrationCost, double busyThresholdPct,
                           Duration busyDuration, Duration heartbeatTimeout) {
        this.migrationCost = migrationCost;
        this.busyThresholdPct = busyThresholdPct;
        this.busyDuration = busyDuration;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    // ── Snapshot management ──

    /** Update the capability snapshot for a node. Called when gossip is received. */
    public void updateNodeSnapshot(NodeCapabilities.Snapshot snapshot) {
        nodeSnapshots.put(snapshot.nodeId(), snapshot);
        // Track busy state
        if (snapshot.cpuIdlePct() < busyThresholdPct) {
            busySince.putIfAbsent(snapshot.nodeId(), Instant.now());
        } else {
            busySince.remove(snapshot.nodeId());
        }
    }

    /** Mark a node as down (heartbeat timeout). */
    public void markNodeDown(String nodeId) {
        nodeSnapshots.remove(nodeId);
        busySince.remove(nodeId);
    }

    /** Register room capability requirements. */
    public void setRoomRequirements(String roomId, Set<String> requirements) {
        if (requirements != null && !requirements.isEmpty()) {
            roomRequirements.put(roomId, Set.copyOf(requirements));
        }
    }

    /** Get all known node snapshots. */
    public Map<String, NodeCapabilities.Snapshot> getNodeSnapshots() {
        return Map.copyOf(nodeSnapshots);
    }

    // ── Scoring ──

    /**
     * Calculate placement score for a node.
     * Higher is better. Matches spec formula:
     *   (gpu_vram_mb / 1024) * 3.0 + (model_loaded ? 5.0 : 0) + ram_free_gb
     *   + (cpu_idle_pct * 0.1) - (companion_count * 2.0) - (battery_penalty)
     */
    public double score(NodeCapabilities.Snapshot snap) {
        if (snap == null) return -1;

        var stateStr = snap.nodeState();
        if ("DOWN".equals(stateStr) || "MAINTENANCE".equals(stateStr) || "DRAINING".equals(stateStr)) {
            return -1; // not eligible for placement
        }

        double s = 0;

        // GPU VRAM strongly preferred
        s += (snap.gpuVramMb() / 1024.0) * 3.0;

        // Model already warm in memory
        if (snap.inferenceModelLoaded()) s += 5.0;

        // Available RAM
        s += snap.ramFreeMb() / 1024.0; // convert to GB

        // CPU idle preferred
        s += snap.cpuIdlePct() * 0.1;

        // Penalty for already hosting companions
        s -= (snap.companionHosting() != null ? snap.companionHosting().size() : 0) * 2.0;

        // Battery penalty — low battery devices should avoid hosting
        if (snap.batteryPct() >= 0 && snap.batteryPct() < 20) {
            s -= 10.0; // significant penalty for low battery
        } else if (snap.batteryPct() >= 0 && snap.batteryPct() < 50) {
            s -= 3.0; // moderate penalty for half battery
        }

        // Degraded state penalty
        if ("DEGRADED".equals(stateStr)) {
            s -= 5.0;
        }

        return s;
    }

    // ── Companion Placement ──

    /**
     * Evaluate placement for a companion entity.
     * Returns a decision: keep, claim, migrate, failover, or evict.
     */
    public PlacementDecision evaluateCompanionPlacement(String entityId) {
        var currentClaim = companionClaims.get(entityId);

        if (currentClaim == null) {
            // Unclaimed — find best node
            var best = findBestNode(Set.of(NodeCapabilities.CAP_INFERENCE));
            if (best == null) {
                return new PlacementDecision(entityId, null, null, 0, 0,
                    PlacementDecision.Action.KEEP);
            }
            return new PlacementDecision(entityId, null, best.nodeId(), 0,
                score(best), PlacementDecision.Action.CLAIM);
        }

        var currentSnap = nodeSnapshots.get(currentClaim.nodeId());

        // Check if current host is down
        if (currentSnap == null) {
            var best = findBestNode(Set.of(NodeCapabilities.CAP_INFERENCE));
            return new PlacementDecision(entityId, currentClaim.nodeId(),
                best != null ? best.nodeId() : null,
                0, best != null ? score(best) : 0,
                PlacementDecision.Action.FAILOVER);
        }

        var currentScore = score(currentSnap);

        // Check if current host is busy for too long
        var busyStart = busySince.get(currentClaim.nodeId());
        if (busyStart != null && Duration.between(busyStart, Instant.now()).compareTo(busyDuration) > 0) {
            var best = findBestNodeExcluding(Set.of(NodeCapabilities.CAP_INFERENCE), currentClaim.nodeId());
            if (best != null) {
                var bestScore = score(best);
                if (bestScore > currentScore + migrationCost) {
                    return new PlacementDecision(entityId, currentClaim.nodeId(),
                        best.nodeId(), currentScore, bestScore,
                        PlacementDecision.Action.EVICT);
                }
            }
        }

        // Check if a better node exists (with migration cost inertia)
        var best = findBestNodeExcluding(Set.of(NodeCapabilities.CAP_INFERENCE), currentClaim.nodeId());
        if (best != null) {
            var bestScore = score(best);
            if (bestScore > currentScore + migrationCost) {
                return new PlacementDecision(entityId, currentClaim.nodeId(),
                    best.nodeId(), currentScore, bestScore,
                    PlacementDecision.Action.MIGRATE);
            }
        }

        return new PlacementDecision(entityId, currentClaim.nodeId(), currentClaim.nodeId(),
            currentScore, currentScore, PlacementDecision.Action.KEEP);
    }

    /**
     * Record that a node has claimed a companion.
     */
    public void recordCompanionClaim(String entityId, String nodeId) {
        var snap = nodeSnapshots.get(nodeId);
        var s = snap != null ? score(snap) : 0;
        companionClaims.put(entityId, new ClaimRecord(nodeId, Instant.now(), s));
        log.info("Companion {} claimed by node {} (score={})", entityId, nodeId, s);
    }

    /**
     * Release a companion claim (host shutting down or migrating away).
     */
    public void releaseCompanionClaim(String entityId) {
        var removed = companionClaims.remove(entityId);
        if (removed != null) {
            log.info("Companion {} released by node {}", entityId, removed.nodeId());
        }
    }

    // ── Room Primary Placement ──

    /**
     * Evaluate placement for a room primary.
     */
    public PlacementDecision evaluateRoomPrimary(String roomId) {
        var requirements = roomRequirements.getOrDefault(roomId, Set.of());
        var currentClaim = roomPrimaryClaims.get(roomId);

        if (currentClaim == null) {
            var best = findBestNode(requirements);
            if (best == null) {
                return new PlacementDecision(roomId, null, null, 0, 0,
                    PlacementDecision.Action.KEEP);
            }
            return new PlacementDecision(roomId, null, best.nodeId(), 0,
                score(best), PlacementDecision.Action.CLAIM);
        }

        var currentSnap = nodeSnapshots.get(currentClaim.nodeId());
        if (currentSnap == null) {
            // Current host down — failover
            var best = findBestNode(requirements);
            return new PlacementDecision(roomId, currentClaim.nodeId(),
                best != null ? best.nodeId() : null,
                0, best != null ? score(best) : 0,
                PlacementDecision.Action.FAILOVER);
        }

        // Rooms don't migrate as aggressively — only on failover or capability loss
        if (!currentSnap.satisfiesRequirements(requirements)) {
            var best = findBestNode(requirements);
            if (best != null) {
                return new PlacementDecision(roomId, currentClaim.nodeId(),
                    best.nodeId(), score(currentSnap), score(best),
                    PlacementDecision.Action.MIGRATE);
            }
        }

        return new PlacementDecision(roomId, currentClaim.nodeId(), currentClaim.nodeId(),
            score(currentSnap), score(currentSnap), PlacementDecision.Action.KEEP);
    }

    /**
     * Record that a node has claimed room primary.
     */
    public void recordRoomPrimaryClaim(String roomId, String nodeId) {
        var snap = nodeSnapshots.get(nodeId);
        var s = snap != null ? score(snap) : 0;
        roomPrimaryClaims.put(roomId, new ClaimRecord(nodeId, Instant.now(), s));
    }

    /**
     * Release a room primary claim.
     */
    public void releaseRoomPrimaryClaim(String roomId) {
        roomPrimaryClaims.remove(roomId);
    }

    /** Get the current primary node for a room. */
    public Optional<String> getRoomPrimaryNode(String roomId) {
        var claim = roomPrimaryClaims.get(roomId);
        return claim != null ? Optional.of(claim.nodeId()) : Optional.empty();
    }

    /** Get all companion claims. */
    public Map<String, ClaimRecord> getCompanionClaims() { return Map.copyOf(companionClaims); }

    /** Get all room primary claims. */
    public Map<String, ClaimRecord> getRoomPrimaryClaims() { return Map.copyOf(roomPrimaryClaims); }

    // ── Conflict resolution ──

    /**
     * Resolve a claim conflict between two nodes.
     * Spec: earliest timestamp wins → highest score → lexicographic nodeId.
     * @return the winning nodeId
     */
    public String resolveClaimConflict(String nodeA, Instant claimTimeA,
                                       String nodeB, Instant claimTimeB) {
        // Within 1 second = tied
        var timeDiff = Duration.between(claimTimeA, claimTimeB).abs();
        if (timeDiff.toMillis() > 1000) {
            return claimTimeA.isBefore(claimTimeB) ? nodeA : nodeB;
        }
        // Tied — compare scores
        var scoreA = score(nodeSnapshots.get(nodeA));
        var scoreB = score(nodeSnapshots.get(nodeB));
        if (Math.abs(scoreA - scoreB) > 0.1) {
            return scoreA > scoreB ? nodeA : nodeB;
        }
        // Still tied — lexicographic
        return nodeA.compareTo(nodeB) <= 0 ? nodeA : nodeB;
    }

    // ── Helpers ──

    private NodeCapabilities.Snapshot findBestNode(Set<String> requiredCapabilities) {
        return nodeSnapshots.values().stream()
            .filter(s -> s.satisfiesRequirements(requiredCapabilities))
            .filter(s -> !"DOWN".equals(s.nodeState()) && !"MAINTENANCE".equals(s.nodeState())
                && !"DRAINING".equals(s.nodeState()))
            .max(Comparator.comparingDouble(this::score))
            .orElse(null);
    }

    private NodeCapabilities.Snapshot findBestNodeExcluding(Set<String> requiredCapabilities,
                                                            String excludeNodeId) {
        return nodeSnapshots.values().stream()
            .filter(s -> !s.nodeId().equals(excludeNodeId))
            .filter(s -> s.satisfiesRequirements(requiredCapabilities))
            .filter(s -> !"DOWN".equals(s.nodeState()) && !"MAINTENANCE".equals(s.nodeState())
                && !"DRAINING".equals(s.nodeState()))
            .max(Comparator.comparingDouble(this::score))
            .orElse(null);
    }
}
