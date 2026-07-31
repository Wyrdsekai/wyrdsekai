package org.wyrdsekai.core.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ER Backup Vault (§105.5).
 * Encrypted soul snapshots with dual-key access and rotation.
 * Safety net for catastrophic recovery.
 */
public class ErBackupVault {

    /** A stored backup snapshot. */
    public record Snapshot(
        String snapshotId,
        String agentDid,
        SnapshotType type,
        Instant createdAt,
        byte[] encryptedData,
        byte[] agentSignature,
        String manifestHash
    ) {}

    public enum SnapshotType {
        /** After successful SoulVerifier pass. */
        KNOWN_GOOD_MANIFEST,
        /** Periodic fragment store checkpoint. */
        FRAGMENT_CHECKPOINT,
        /** Immutable birth certificate — behavioral baseline. */
        BEHAVIORAL_BASELINE,
        /** Taken before every Forge consolidation. */
        PRE_FORGE
    }

    /** Dual-key access request with cooldown. */
    public record DualKeyRequest(
        String requestId,
        String agentDid,
        String stewardId,
        Instant requestedAt,
        Instant availableAt,
        DualKeyStatus status
    ) {}

    public enum DualKeyStatus {
        PENDING_COOLDOWN, AVAILABLE, COMPLETED, CANCELLED
    }

    private final Map<String, Deque<Snapshot>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, DualKeyRequest> dualKeyRequests = new ConcurrentHashMap<>();
    private final int maxManifestSnapshots;
    private final int maxPreForgeSnapshots;
    private final Duration dualKeyCooldown;
    private int nextId = 1;

    public ErBackupVault() {
        this(5, 3, Duration.ofHours(24));
    }

    public ErBackupVault(int maxManifestSnapshots, int maxPreForgeSnapshots, Duration dualKeyCooldown) {
        this.maxManifestSnapshots = maxManifestSnapshots;
        this.maxPreForgeSnapshots = maxPreForgeSnapshots;
        this.dualKeyCooldown = dualKeyCooldown;
    }

    /** Store a snapshot. Rotates oldest if at capacity. */
    public Snapshot store(String agentDid, SnapshotType type, byte[] encryptedData,
                          byte[] agentSignature, String manifestHash) {
        var snapshot = new Snapshot("snap-" + nextId++, agentDid, type,
            Instant.now(), encryptedData, agentSignature, manifestHash);

        var key = agentDid + ":" + type.name();
        var queue = snapshots.computeIfAbsent(key, k -> new ArrayDeque<>());

        int max = switch (type) {
            case KNOWN_GOOD_MANIFEST -> maxManifestSnapshots;
            case PRE_FORGE -> maxPreForgeSnapshots;
            case FRAGMENT_CHECKPOINT -> maxManifestSnapshots;
            case BEHAVIORAL_BASELINE -> 1; // immutable, only one
        };

        synchronized (queue) {
            if (type == SnapshotType.BEHAVIORAL_BASELINE && !queue.isEmpty()) {
                return queue.peek(); // immutable — return existing
            }
            queue.addLast(snapshot);
            while (queue.size() > max) {
                queue.removeFirst();
            }
        }
        return snapshot;
    }

    /** Get the latest snapshot of a given type for an agent. */
    public Optional<Snapshot> latest(String agentDid, SnapshotType type) {
        var key = agentDid + ":" + type.name();
        var queue = snapshots.get(key);
        if (queue == null || queue.isEmpty()) return Optional.empty();
        synchronized (queue) {
            return Optional.of(queue.peekLast());
        }
    }

    /** Get all snapshots of a given type for an agent. */
    public List<Snapshot> allSnapshots(String agentDid, SnapshotType type) {
        var key = agentDid + ":" + type.name();
        var queue = snapshots.get(key);
        if (queue == null) return List.of();
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    /** Get the behavioral baseline (birth certificate). */
    public Optional<Snapshot> baseline(String agentDid) {
        return latest(agentDid, SnapshotType.BEHAVIORAL_BASELINE);
    }

    /** Initiate a dual-key access request (steward accessing without agent consent). */
    public DualKeyRequest requestDualKeyAccess(String agentDid, String stewardId) {
        var request = new DualKeyRequest("dkr-" + nextId++, agentDid, stewardId,
            Instant.now(), Instant.now().plus(dualKeyCooldown), DualKeyStatus.PENDING_COOLDOWN);
        dualKeyRequests.put(request.requestId(), request);
        return request;
    }

    /** Check if a dual-key request has cleared the cooldown. */
    public DualKeyRequest checkDualKeyRequest(String requestId) {
        var request = dualKeyRequests.get(requestId);
        if (request == null) return null;
        if (request.status() == DualKeyStatus.PENDING_COOLDOWN
                && Instant.now().isAfter(request.availableAt())) {
            var available = new DualKeyRequest(request.requestId(), request.agentDid(),
                request.stewardId(), request.requestedAt(), request.availableAt(),
                DualKeyStatus.AVAILABLE);
            dualKeyRequests.put(requestId, available);
            return available;
        }
        return request;
    }

    /** Cancel a dual-key request. */
    public DualKeyRequest cancelDualKeyAccess(String requestId) {
        var request = dualKeyRequests.get(requestId);
        if (request == null) return null;
        var cancelled = new DualKeyRequest(request.requestId(), request.agentDid(),
            request.stewardId(), request.requestedAt(), request.availableAt(),
            DualKeyStatus.CANCELLED);
        dualKeyRequests.put(requestId, cancelled);
        return cancelled;
    }

    /** Count total snapshots for an agent across all types. */
    public int snapshotCount(String agentDid) {
        int count = 0;
        for (var type : SnapshotType.values()) {
            count += allSnapshots(agentDid, type).size();
        }
        return count;
    }

    /** Check backup freshness — returns true if latest checkpoint is within the given duration. */
    public boolean isFresh(String agentDid, SnapshotType type, Duration maxAge) {
        return latest(agentDid, type)
            .map(s -> s.createdAt().isAfter(Instant.now().minus(maxAge)))
            .orElse(false);
    }
}
