package org.wyrdsekai.core.privacy;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data minimizer — auto-expire personal data per retention policy (§9F).
 * Implements GDPR Article 5(1)(e): data shall be kept no longer than necessary.
 */
public class DataMinimizer {

    /** A retention policy for a data category. */
    public record RetentionPolicy(
        String category,
        Duration maxRetention,
        boolean autoDelete,
        String justification
    ) {}

    /** A tracked data item with its creation time. */
    public record DataItem(
        String itemId,
        String entityId,
        String category,
        Instant createdAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** Result of a minimization sweep. */
    public record SweepResult(int itemsChecked, int itemsExpired, int itemsDeleted) {}

    private final Map<String, RetentionPolicy> policies = new ConcurrentHashMap<>();
    private final Map<String, DataItem> trackedItems = new ConcurrentHashMap<>();

    /** Default retention policies. */
    public static final RetentionPolicy CHAT_HISTORY = new RetentionPolicy(
        "chat_history", Duration.ofDays(90), true, "Conversation logs");
    public static final RetentionPolicy SESSION_DATA = new RetentionPolicy(
        "session_data", Duration.ofDays(7), true, "Session tokens and state");
    public static final RetentionPolicy ANALYTICS = new RetentionPolicy(
        "analytics", Duration.ofDays(365), true, "Usage analytics");
    public static final RetentionPolicy ACCOUNT_DATA = new RetentionPolicy(
        "account_data", Duration.ofDays(365 * 7), false, "Account profile data");

    /** Register a retention policy. */
    public void setPolicy(RetentionPolicy policy) {
        policies.put(policy.category(), policy);
    }

    /** Get the retention policy for a category. */
    public Optional<RetentionPolicy> getPolicy(String category) {
        return Optional.ofNullable(policies.get(category));
    }

    /**
     * Track a data item under a retention policy.
     */
    public DataItem track(String itemId, String entityId, String category) {
        var policy = policies.get(category);
        var retention = policy != null ? policy.maxRetention() : Duration.ofDays(365);
        var now = Instant.now();
        var item = new DataItem(itemId, entityId, category, now, now.plus(retention));
        trackedItems.put(itemId, item);
        return item;
    }

    /**
     * Run a minimization sweep — identify and remove expired items.
     * Only auto-deletes items in categories with autoDelete=true.
     */
    public SweepResult sweep() {
        int checked = 0;
        int expired = 0;
        int deleted = 0;
        var toRemove = new ArrayList<String>();

        for (var entry : trackedItems.entrySet()) {
            checked++;
            var item = entry.getValue();
            if (item.isExpired()) {
                expired++;
                var policy = policies.get(item.category());
                if (policy != null && policy.autoDelete()) {
                    toRemove.add(entry.getKey());
                    deleted++;
                }
            }
        }

        toRemove.forEach(trackedItems::remove);
        return new SweepResult(checked, expired, deleted);
    }

    /** Get all items for an entity. */
    public List<DataItem> itemsForEntity(String entityId) {
        return trackedItems.values().stream()
            .filter(i -> i.entityId().equals(entityId))
            .sorted(Comparator.comparing(DataItem::createdAt))
            .toList();
    }

    /** Get expired items. */
    public List<DataItem> expiredItems() {
        return trackedItems.values().stream()
            .filter(DataItem::isExpired)
            .toList();
    }

    /** Total tracked items. */
    public int trackedCount() {
        return trackedItems.size();
    }

    /** Total policies registered. */
    public int policyCount() {
        return policies.size();
    }
}
