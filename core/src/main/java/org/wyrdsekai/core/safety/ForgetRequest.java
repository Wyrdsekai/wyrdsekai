package org.wyrdsekai.core.safety;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Soul-level forget request (§96.5).
 * Extends the GDPR RightToErasure with soul-specific semantics:
 * items are tombstoned (not deleted), tombstones propagate to buds,
 * and physical deletion happens after a configurable retention period.
 * <p>
 * Flow:
 * Human: "Forget our conversation about X"
 *   → Agent searches items + family locker
 *   → Matching items tombstoned with cryptographic tombstone
 *   → Tombstones propagate to buds during next sync
 *   → Forge consolidation skips tombstoned items
 *   → After retention period: physical deletion
 */
public record ForgetRequest(
    @JsonProperty("requestId") String requestId,
    @JsonProperty("agentDid") String agentDid,
    @JsonProperty("requestedBy") String requestedBy,
    @JsonProperty("query") String query,
    @JsonProperty("status") Status status,
    @JsonProperty("matchedItemIds") List<String> matchedItemIds,
    @JsonProperty("requestedAt") Instant requestedAt,
    @JsonProperty("processedAt") Instant processedAt,
    @JsonProperty("retentionDays") int retentionDays,
    @JsonProperty("reason") String reason
) {

    @JsonCreator
    public ForgetRequest {}

    public enum Status {
        /** Request received, not yet processed. */
        PENDING,
        /** Items found and tombstoned. */
        TOMBSTONED,
        /** Tombstones propagated to all buds. */
        PROPAGATED,
        /** Retention period expired, items physically deleted. */
        PURGED,
        /** No matching items found. */
        NO_MATCH,
        /** Request denied (legal hold, etc.). */
        DENIED
    }

    /** Default retention period before physical deletion (days). */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    /** Create a new pending request. */
    public static ForgetRequest create(String requestId, String agentDid,
                                        String requestedBy, String query,
                                        String reason) {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.PENDING, List.of(), Instant.now(), null,
            DEFAULT_RETENTION_DAYS, reason);
    }

    /** Mark as tombstoned with matched item IDs. */
    public ForgetRequest withTombstoned(List<String> itemIds) {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.TOMBSTONED, List.copyOf(itemIds), requestedAt,
            Instant.now(), retentionDays, reason);
    }

    /** Mark as propagated to all buds. */
    public ForgetRequest withPropagated() {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.PROPAGATED, matchedItemIds, requestedAt,
            processedAt, retentionDays, reason);
    }

    /** Mark as purged (physically deleted). */
    public ForgetRequest withPurged() {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.PURGED, matchedItemIds, requestedAt,
            processedAt, retentionDays, reason);
    }

    /** Mark as no match. */
    public ForgetRequest withNoMatch() {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.NO_MATCH, List.of(), requestedAt,
            Instant.now(), retentionDays, reason);
    }

    /** Mark as denied. */
    public ForgetRequest withDenied(String denialReason) {
        return new ForgetRequest(requestId, agentDid, requestedBy, query,
            Status.DENIED, matchedItemIds, requestedAt,
            Instant.now(), retentionDays, denialReason);
    }

    /** Whether the request is terminal (no further processing needed). */
    public boolean isTerminal() {
        return status == Status.PURGED || status == Status.NO_MATCH || status == Status.DENIED;
    }

    /** Whether items are ready for physical deletion (retention expired). */
    public boolean isReadyForPurge() {
        if (status != Status.PROPAGATED && status != Status.TOMBSTONED) return false;
        if (processedAt == null) return false;
        return Instant.now().isAfter(processedAt.plusSeconds(retentionDays * 86400L));
    }
}
