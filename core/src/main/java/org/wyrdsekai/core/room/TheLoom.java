package org.wyrdsekai.core.room;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Loom — CRDT garbage collection / compaction room (§59).
 * Grace period + checkpoint hybrid for safe GC of distributed state.
 */
public class TheLoom {

    /** A tracked CRDT state with GC metadata. */
    public record CrdtEntry(
        String crdtId,
        String roomId,
        long sizeBytes,
        Instant lastModified,
        Instant lastCheckpoint,
        int tombstoneCount,
        boolean compactable
    ) {}

    /** Result of a compaction operation. */
    public record CompactionResult(
        String crdtId,
        long bytesBefore,
        long bytesAfter,
        int tombstonesRemoved,
        Instant compactedAt
    ) {}

    private final Map<String, CrdtEntry> entries = new ConcurrentHashMap<>();
    private final List<CompactionResult> compactionHistory = Collections.synchronizedList(new ArrayList<>());
    private Duration gracePeriod = Duration.ofMinutes(30);
    private int tombstoneThreshold = 100;

    /** Set the grace period before tombstones can be compacted. */
    public void setGracePeriod(Duration period) {
        this.gracePeriod = period;
    }

    /** Set the tombstone count threshold that triggers compaction eligibility. */
    public void setTombstoneThreshold(int threshold) {
        this.tombstoneThreshold = threshold;
    }

    /** Register or update a CRDT entry. */
    public void track(String crdtId, String roomId, long sizeBytes, int tombstoneCount) {
        var now = Instant.now();
        var existing = entries.get(crdtId);
        var lastCheckpoint = existing != null ? existing.lastCheckpoint() : now;
        var compactable = tombstoneCount >= tombstoneThreshold
            && (existing == null || Duration.between(existing.lastModified(), now).compareTo(gracePeriod) > 0);

        entries.put(crdtId, new CrdtEntry(crdtId, roomId, sizeBytes, now,
            lastCheckpoint, tombstoneCount, compactable));
    }

    /** Get compactable entries (eligible for GC). */
    public List<CrdtEntry> compactableEntries() {
        return entries.values().stream()
            .filter(CrdtEntry::compactable)
            .sorted(Comparator.comparingInt(CrdtEntry::tombstoneCount).reversed())
            .toList();
    }

    /**
     * Simulate compaction of a CRDT entry.
     * In a real implementation, this would trigger actual CRDT compaction.
     */
    public Optional<CompactionResult> compact(String crdtId) {
        var entry = entries.get(crdtId);
        if (entry == null || !entry.compactable()) return Optional.empty();

        // Estimate compacted size (remove tombstones)
        long estimatedReduction = entry.tombstoneCount() * 64L; // ~64 bytes per tombstone
        long bytesAfter = Math.max(0, entry.sizeBytes() - estimatedReduction);

        var result = new CompactionResult(crdtId, entry.sizeBytes(), bytesAfter,
            entry.tombstoneCount(), Instant.now());
        compactionHistory.add(result);

        // Update entry: reset tombstones, update checkpoint
        entries.put(crdtId, new CrdtEntry(crdtId, entry.roomId(), bytesAfter,
            entry.lastModified(), Instant.now(), 0, false));

        return Optional.of(result);
    }

    /** Checkpoint a CRDT (mark current state as saved). */
    public void checkpoint(String crdtId) {
        var entry = entries.get(crdtId);
        if (entry != null) {
            entries.put(crdtId, new CrdtEntry(entry.crdtId(), entry.roomId(),
                entry.sizeBytes(), entry.lastModified(), Instant.now(),
                entry.tombstoneCount(), entry.compactable()));
        }
    }

    /** Get all tracked entries. */
    public List<CrdtEntry> allEntries() {
        return new ArrayList<>(entries.values());
    }

    /** Recent compaction results. */
    public List<CompactionResult> recentCompactions(int limit) {
        synchronized (compactionHistory) {
            int start = Math.max(0, compactionHistory.size() - limit);
            var subList = new ArrayList<>(compactionHistory.subList(start, compactionHistory.size()));
            Collections.reverse(subList);
            return subList;
        }
    }

    /** Total tracked CRDTs. */
    public int trackedCount() {
        return entries.size();
    }

    /** Human-readable summary. */
    public String describe() {
        if (entries.isEmpty()) return I18n.get("loom.idle");
        var sb = new StringBuilder("=== ").append(I18n.get("loom.title")).append(" ===\n\n");
        sb.append(I18n.get("loom.tracked")).append(": ").append(trackedCount()).append("\n");
        sb.append(I18n.get("loom.compactable")).append(": ").append(compactableEntries().size()).append("\n\n");

        for (var e : entries.values()) {
            sb.append("  ").append(e.crdtId())
                .append(" — ").append(e.sizeBytes()).append(" ").append(I18n.get("loom.bytes"))
                .append(", ").append(e.tombstoneCount()).append(" ").append(I18n.get("loom.tombstones"))
                .append(e.compactable() ? " " + I18n.get("loom.ready") : "")
                .append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
