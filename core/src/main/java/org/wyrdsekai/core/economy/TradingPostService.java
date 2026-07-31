package org.wyrdsekai.core.economy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trading Post service — manages posted items, provenance, and trust scores (§4.4, §68).
 * In-memory for M0; JDBC persistence deferred to M1.
 */
public class TradingPostService {

    private static volatile TradingPostService instance;
    public static void init() { instance = new TradingPostService(); }
    public static TradingPostService get() { return instance; }

    public enum ItemStatus { AVAILABLE, QUARANTINE, SOLD, WITHDRAWN }

    /** A posted item with provenance chain. */
    public record PostedItem(
        String itemId,
        String name,
        String description,
        long price,
        String sellerId,
        String sellerName,
        ItemStatus status,
        Instant postedAt,
        List<ProvenanceEntry> provenance
    ) {}

    /** A provenance chain entry tracking item history. */
    public record ProvenanceEntry(
        String action,
        String entityId,
        Instant timestamp,
        String details
    ) {}

    /** Trust score for a trading entity. */
    public record TrustScore(
        String entityId,
        int completedSales,
        int completedPurchases,
        int disputes,
        double score
    ) {
        /** Compute trust score from activity. */
        public static TrustScore compute(String entityId, int sales, int purchases, int disputes) {
            int total = sales + purchases;
            double score = total == 0 ? 0.5 :
                Math.max(0.0, Math.min(1.0, (total - disputes * 2.0) / (total + 1)));
            return new TrustScore(entityId, sales, purchases, disputes, score);
        }
    }

    private final Map<String, PostedItem> items = new ConcurrentHashMap<>();
    private final Map<String, TrustScore> trustScores = new ConcurrentHashMap<>();
    private int nextItemId = 1;

    /**
     * Post an item for sale.
     * @param name item name
     * @param description item description
     * @param price asking price in credits
     * @param sellerId entity posting the item
     * @param sellerName display name
     * @return the posted item
     */
    public synchronized PostedItem postItem(String name, String description, long price,
                                             String sellerId, String sellerName) {
        var itemId = "item-" + nextItemId++;
        var provenance = new ArrayList<ProvenanceEntry>();
        provenance.add(new ProvenanceEntry("posted", sellerId, Instant.now(),
            "Listed at " + price + " credits"));

        var item = new PostedItem(itemId, name, description, price, sellerId, sellerName,
            ItemStatus.AVAILABLE, Instant.now(), provenance);
        items.put(itemId, item);
        return item;
    }

    /** Browse available items. */
    public List<PostedItem> browseItems() {
        return items.values().stream()
            .filter(i -> i.status() == ItemStatus.AVAILABLE)
            .sorted(Comparator.comparing(PostedItem::postedAt).reversed())
            .toList();
    }

    /** Search items by name substring. */
    public List<PostedItem> searchItems(String query) {
        var lowerQuery = query.toLowerCase();
        return items.values().stream()
            .filter(i -> i.status() == ItemStatus.AVAILABLE)
            .filter(i -> i.name().toLowerCase().contains(lowerQuery)
                || i.description().toLowerCase().contains(lowerQuery))
            .toList();
    }

    /**
     * Acquire (buy) an item.
     * @return the updated item, or empty if not available
     */
    public synchronized Optional<PostedItem> acquireItem(String itemId, String buyerId) {
        var item = items.get(itemId);
        if (item == null || item.status() != ItemStatus.AVAILABLE) return Optional.empty();
        if (item.sellerId().equals(buyerId)) return Optional.empty(); // Can't buy own item

        var provenance = new ArrayList<>(item.provenance());
        provenance.add(new ProvenanceEntry("acquired", buyerId, Instant.now(),
            "Purchased for " + item.price() + " credits"));

        var updated = new PostedItem(item.itemId(), item.name(), item.description(),
            item.price(), item.sellerId(), item.sellerName(), ItemStatus.SOLD,
            item.postedAt(), provenance);
        items.put(itemId, updated);

        // Update trust scores
        updateTrustAfterSale(item.sellerId(), buyerId);

        return Optional.of(updated);
    }

    /** Withdraw an item (seller only). */
    public synchronized Optional<PostedItem> withdrawItem(String itemId, String sellerId) {
        var item = items.get(itemId);
        if (item == null || !item.sellerId().equals(sellerId)) return Optional.empty();
        if (item.status() != ItemStatus.AVAILABLE) return Optional.empty();

        var provenance = new ArrayList<>(item.provenance());
        provenance.add(new ProvenanceEntry("withdrawn", sellerId, Instant.now(), "Removed by seller"));

        var updated = new PostedItem(item.itemId(), item.name(), item.description(),
            item.price(), item.sellerId(), item.sellerName(), ItemStatus.WITHDRAWN,
            item.postedAt(), provenance);
        items.put(itemId, updated);
        return Optional.of(updated);
    }

    /** Quarantine an item (Warden action). */
    public synchronized Optional<PostedItem> quarantineItem(String itemId, String reason) {
        var item = items.get(itemId);
        if (item == null || item.status() != ItemStatus.AVAILABLE) return Optional.empty();

        var provenance = new ArrayList<>(item.provenance());
        provenance.add(new ProvenanceEntry("quarantined", "warden", Instant.now(), reason));

        var updated = new PostedItem(item.itemId(), item.name(), item.description(),
            item.price(), item.sellerId(), item.sellerName(), ItemStatus.QUARANTINE,
            item.postedAt(), provenance);
        items.put(itemId, updated);
        return Optional.of(updated);
    }

    /** Verify provenance chain for an item. */
    public Optional<List<ProvenanceEntry>> verifyProvenance(String itemId) {
        var item = items.get(itemId);
        if (item == null) return Optional.empty();
        return Optional.of(List.copyOf(item.provenance()));
    }

    /** Get trust score for an entity. */
    public TrustScore getTrustScore(String entityId) {
        return trustScores.getOrDefault(entityId,
            TrustScore.compute(entityId, 0, 0, 0));
    }

    /** Record a dispute against an entity. */
    public void recordDispute(String entityId) {
        var current = getTrustScore(entityId);
        trustScores.put(entityId, TrustScore.compute(entityId,
            current.completedSales(), current.completedPurchases(),
            current.disputes() + 1));
    }

    /** Get item by ID. */
    public Optional<PostedItem> getItem(String itemId) {
        return Optional.ofNullable(items.get(itemId));
    }

    /** Total posted items (all statuses). */
    public int totalItems() {
        return items.size();
    }

    /** Count of available items. */
    public int availableCount() {
        return (int) items.values().stream()
            .filter(i -> i.status() == ItemStatus.AVAILABLE).count();
    }

    /** Human-readable summary. */
    public String describe() {
        int available = availableCount();
        if (available == 0) return "The Trading Post is quiet — no items posted.";
        var sb = new StringBuilder("=== Trading Post ===\n\n");
        sb.append("Items: ").append(available).append(" available, ")
            .append(totalItems()).append(" total\n\n");
        browseItems().stream().limit(10).forEach(i ->
            sb.append("  [").append(i.itemId()).append("] ")
                .append(i.name()).append(" — ").append(i.price()).append(" credits")
                .append(" (by ").append(i.sellerName()).append(")\n"));
        return sb.toString().stripTrailing();
    }

    private void updateTrustAfterSale(String sellerId, String buyerId) {
        var sellerTrust = getTrustScore(sellerId);
        trustScores.put(sellerId, TrustScore.compute(sellerId,
            sellerTrust.completedSales() + 1, sellerTrust.completedPurchases(),
            sellerTrust.disputes()));

        var buyerTrust = getTrustScore(buyerId);
        trustScores.put(buyerId, TrustScore.compute(buyerId,
            buyerTrust.completedSales(), buyerTrust.completedPurchases() + 1,
            buyerTrust.disputes()));
    }
}
