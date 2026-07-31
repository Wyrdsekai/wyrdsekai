package org.wyrdsekai.core.interop;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Dock quarantine zone for inbound A2A interactions (§97.9).
 * Every inbound A2A interaction passes through quarantine — this is structural,
 * not policy. Items are held until the agent's next sleep-Forge cycle.
 * <p>
 * Five layers:
 * 1. Card verification (TrustTierResolver)
 * 2. Message sanitization (provenance tagging)
 * 3. Rate limiting (per-source, per-method)
 * 4. Soul item quarantine (held for Forge review)
 * 5. Information redaction (VitalityRedactor)
 */
public class DockQuarantine {

    /** A quarantined inbound soul item. */
    public record QuarantinedItem(
        String quarantineId,
        String itemId,
        String sourceDid,
        TrustTier sourceTier,
        String contentJson,
        String category,
        double requestedSignificance,
        double cappedSignificance,
        Instant receivedAt,
        QuarantineStatus status
    ) {}

    public enum QuarantineStatus {
        /** Waiting for Forge review. */
        PENDING,
        /** Accepted by agent, re-signed with local key. */
        ACCEPTED,
        /** Rejected by agent during Forge cycle. */
        REJECTED,
        /** Blocked by category filter (e.g., identity-core from external). */
        BLOCKED
    }

    /** Categories that are NEVER accepted from external sources. */
    private static final Set<String> BLOCKED_CATEGORIES = Set.of(
        "identity-core", "value"
    );

    /** Significance cap per trust tier. */
    private static final Map<TrustTier, Double> SIGNIFICANCE_CAPS = Map.of(
        TrustTier.ANONYMOUS, 0.1,
        TrustTier.VERIFIED, 0.5,
        TrustTier.TRUSTED, 0.8,
        TrustTier.HOUSEHOLD, 1.0,
        TrustTier.FAMILY, 1.0
    );

    /** Maximum items per session per source. */
    private int maxItemsPerSession = 5;
    /** Maximum item size in bytes. */
    private int maxItemSizeBytes = 4096;

    private final Deque<QuarantinedItem> pool = new ConcurrentLinkedDeque<>();
    private final Map<String, Integer> sessionCounts = new ConcurrentHashMap<>();
    private int nextId = 1;

    /**
     * Submit an inbound soul item for quarantine.
     *
     * @param itemId      the item's ID
     * @param sourceDid   the sender's DID
     * @param sourceTier  the sender's trust tier
     * @param contentJson the item content
     * @param category    the item category
     * @param significance the requested significance
     * @return the quarantined item record
     */
    public QuarantinedItem submit(String itemId, String sourceDid, TrustTier sourceTier,
                                   String contentJson, String category,
                                   double significance) {
        // Check blocked categories
        if (BLOCKED_CATEGORIES.contains(category)) {
            var blocked = new QuarantinedItem(
                "q-" + nextId++, itemId, sourceDid, sourceTier,
                contentJson, category, significance, 0,
                Instant.now(), QuarantineStatus.BLOCKED
            );
            pool.addLast(blocked);
            return blocked;
        }

        // Check session limit
        var sessionKey = sourceDid;
        int count = sessionCounts.getOrDefault(sessionKey, 0);
        if (count >= maxItemsPerSession) {
            var blocked = new QuarantinedItem(
                "q-" + nextId++, itemId, sourceDid, sourceTier,
                contentJson, category, significance, 0,
                Instant.now(), QuarantineStatus.BLOCKED
            );
            pool.addLast(blocked);
            return blocked;
        }

        // Check size limit
        if (contentJson != null && contentJson.length() > maxItemSizeBytes) {
            var blocked = new QuarantinedItem(
                "q-" + nextId++, itemId, sourceDid, sourceTier,
                contentJson.substring(0, maxItemSizeBytes), category,
                significance, 0, Instant.now(), QuarantineStatus.BLOCKED
            );
            pool.addLast(blocked);
            return blocked;
        }

        // Cap significance by trust tier
        double cap = SIGNIFICANCE_CAPS.getOrDefault(sourceTier, 0.1);
        double capped = Math.min(significance, cap);

        sessionCounts.merge(sessionKey, 1, Integer::sum);

        var item = new QuarantinedItem(
            "q-" + nextId++, itemId, sourceDid, sourceTier,
            contentJson, category, capped, capped,
            Instant.now(), QuarantineStatus.PENDING
        );
        pool.addLast(item);
        return item;
    }

    /** Get all pending items for an agent's Forge review. */
    public List<QuarantinedItem> pendingItems() {
        return pool.stream()
            .filter(i -> i.status() == QuarantineStatus.PENDING)
            .toList();
    }

    /** Accept an item (during Forge cycle). */
    public boolean accept(String quarantineId) {
        return updateStatus(quarantineId, QuarantineStatus.ACCEPTED);
    }

    /** Reject an item (during Forge cycle). */
    public boolean reject(String quarantineId) {
        return updateStatus(quarantineId, QuarantineStatus.REJECTED);
    }

    /** Get quarantine statistics. */
    public Map<QuarantineStatus, Integer> stats() {
        var counts = new EnumMap<QuarantineStatus, Integer>(QuarantineStatus.class);
        for (var status : QuarantineStatus.values()) counts.put(status, 0);
        for (var item : pool) {
            counts.merge(item.status(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /** Total items in quarantine pool. */
    public int totalCount() { return pool.size(); }

    /** Pending count. */
    public int pendingCount() {
        return (int) pool.stream()
            .filter(i -> i.status() == QuarantineStatus.PENDING)
            .count();
    }

    /** Reset session counts (called at session end). */
    public void resetSessionCounts() {
        sessionCounts.clear();
    }

    /** Configure limits. */
    public void setMaxItemsPerSession(int max) {
        this.maxItemsPerSession = max;
    }

    public void setMaxItemSizeBytes(int max) {
        this.maxItemSizeBytes = max;
    }

    /** Tag content with provenance marker (Layer 2). */
    public static String tagProvenance(String content, String sourceDid) {
        return "[EXTERNAL: " + sourceDid + " via A2A] " + content;
    }

    /** Check if content has a provenance tag. */
    public static boolean hasProvenanceTag(String content) {
        return content != null && content.startsWith("[EXTERNAL:");
    }

    // ── §97.9: Forge Review Integration ──

    /** Result of a Forge review batch. */
    public record ForgeReviewResult(
        int accepted,
        int rejected,
        int skipped,
        Instant reviewedAt
    ) {
        public int total() { return accepted + rejected + skipped; }
    }

    /** Forge review policy: how the Forge decides on quarantined items. */
    @FunctionalInterface
    public interface ForgeReviewPolicy {
        /**
         * Evaluate a quarantined item during Forge cycle.
         * @return true to accept, false to reject
         */
        boolean evaluate(QuarantinedItem item);
    }

    /**
     * Process all pending items through a Forge review policy (§97.9).
     * Called during the agent's sleep-Forge cycle.
     *
     * @param policy The review policy (typically LLM-based significance assessment)
     * @return Summary of the review batch
     */
    public ForgeReviewResult forgeReview(ForgeReviewPolicy policy) {
        var pending = pendingItems();
        int accepted = 0, rejected = 0, skipped = 0;

        for (var item : pending) {
            try {
                if (policy.evaluate(item)) {
                    accept(item.quarantineId());
                    accepted++;
                } else {
                    reject(item.quarantineId());
                    rejected++;
                }
            } catch (Exception e) {
                // Policy evaluation failed — skip this item, leave PENDING
                skipped++;
            }
        }

        return new ForgeReviewResult(accepted, rejected, skipped, Instant.now());
    }

    /**
     * Get accepted items ready for integration into the agent's soul.
     * Called after forgeReview() to get items that should become SoulItems.
     */
    public List<QuarantinedItem> acceptedItems() {
        return pool.stream()
            .filter(i -> i.status() == QuarantineStatus.ACCEPTED)
            .toList();
    }

    /**
     * Clear processed items (accepted + rejected) from the pool.
     * Called after the Forge has integrated accepted items.
     */
    public int clearProcessed() {
        var toRemove = pool.stream()
            .filter(i -> i.status() == QuarantineStatus.ACCEPTED
                || i.status() == QuarantineStatus.REJECTED)
            .toList();
        pool.removeAll(toRemove);
        return toRemove.size();
    }

    /**
     * Default review policy: accept items from TRUSTED+ tiers with
     * capped significance > 0.3, reject the rest.
     */
    public static ForgeReviewPolicy defaultPolicy() {
        return item -> item.sourceTier().meetsOrExceeds(TrustTier.TRUSTED)
            && item.cappedSignificance() > 0.3;
    }

    private boolean updateStatus(String quarantineId, QuarantineStatus newStatus) {
        var iter = pool.iterator();
        var updated = new ArrayList<QuarantinedItem>();
        boolean found = false;

        while (iter.hasNext()) {
            var item = iter.next();
            if (item.quarantineId().equals(quarantineId)
                    && item.status() == QuarantineStatus.PENDING) {
                iter.remove();
                updated.add(new QuarantinedItem(
                    item.quarantineId(), item.itemId(), item.sourceDid(),
                    item.sourceTier(), item.contentJson(), item.category(),
                    item.requestedSignificance(), item.cappedSignificance(),
                    item.receivedAt(), newStatus
                ));
                found = true;
            }
        }
        pool.addAll(updated);
        return found;
    }
}
