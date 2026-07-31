package org.wyrdsekai.core.safety;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Manages tombstoned soul items (§96.5).
 * A tombstone marks an item as logically deleted while retaining a cryptographic
 * record for audit purposes. Tombstones propagate to buds during sleep sync
 * and are physically purged after the retention period.
 */
public class TombstoneManager {

    /** A cryptographic tombstone for a deleted soul item. */
    public record Tombstone(
        String itemId,
        String agentDid,
        String forgetRequestId,
        String reason,
        byte[] itemHash,
        Instant tombstonedAt,
        Instant purgeAfter
    ) {}

    private final Map<String, Tombstone> tombstones = new ConcurrentHashMap<>();
    private final Map<String, ForgetRequest> requests = new ConcurrentHashMap<>();
    private int nextRequestId = 1;

    /**
     * Submit a forget request and process it against available items.
     *
     * @param agentDid     the agent whose items to search
     * @param requestedBy  who requested the forget (human DID or agent DID)
     * @param query        the search query (topic/keyword)
     * @param reason       reason for the request
     * @param itemMatcher  predicate that checks if an item ID matches the query
     * @param allItemIds   all item IDs for the agent
     * @return the processed ForgetRequest
     */
    public ForgetRequest submitAndProcess(String agentDid, String requestedBy,
                                           String query, String reason,
                                           Predicate<String> itemMatcher,
                                           Collection<String> allItemIds) {
        var requestId = "forget-" + nextRequestId++;
        var request = ForgetRequest.create(requestId, agentDid, requestedBy, query, reason);
        requests.put(requestId, request);

        // Find matching items
        var matched = allItemIds.stream()
            .filter(itemMatcher)
            .toList();

        if (matched.isEmpty()) {
            var noMatch = request.withNoMatch();
            requests.put(requestId, noMatch);
            return noMatch;
        }

        // Tombstone each matched item
        for (var itemId : matched) {
            tombstone(itemId, agentDid, requestId, reason,
                ForgetRequest.DEFAULT_RETENTION_DAYS);
        }

        var tombstoned = request.withTombstoned(matched);
        requests.put(requestId, tombstoned);
        return tombstoned;
    }

    /** Create a tombstone for a single item. */
    public Tombstone tombstone(String itemId, String agentDid,
                                String forgetRequestId, String reason,
                                int retentionDays) {
        var now = Instant.now();
        var tombstone = new Tombstone(
            itemId, agentDid, forgetRequestId, reason,
            computeHash(itemId, agentDid),
            now, now.plusSeconds(retentionDays * 86400L)
        );
        tombstones.put(itemId, tombstone);
        return tombstone;
    }

    /** Check if an item has been tombstoned. */
    public boolean isTombstoned(String itemId) {
        return tombstones.containsKey(itemId);
    }

    /** Get the tombstone for an item. */
    public Optional<Tombstone> getTombstone(String itemId) {
        return Optional.ofNullable(tombstones.get(itemId));
    }

    /** Get all tombstones for an agent. */
    public List<Tombstone> tombstonesForAgent(String agentDid) {
        return tombstones.values().stream()
            .filter(t -> t.agentDid().equals(agentDid))
            .sorted(Comparator.comparing(Tombstone::tombstonedAt))
            .toList();
    }

    /** Get all tombstones that are ready for physical purge. */
    public List<Tombstone> readyForPurge() {
        var now = Instant.now();
        return tombstones.values().stream()
            .filter(t -> now.isAfter(t.purgeAfter()))
            .toList();
    }

    /** Purge a tombstone (physical deletion complete). */
    public boolean purge(String itemId) {
        return tombstones.remove(itemId) != null;
    }

    /** Get a forget request by ID. */
    public Optional<ForgetRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    /** Get all forget requests for an agent. */
    public List<ForgetRequest> requestsForAgent(String agentDid) {
        return requests.values().stream()
            .filter(r -> r.agentDid().equals(agentDid))
            .sorted(Comparator.comparing(ForgetRequest::requestedAt))
            .toList();
    }

    /** Get pending (non-terminal) requests. */
    public List<ForgetRequest> pendingRequests() {
        return requests.values().stream()
            .filter(r -> !r.isTerminal())
            .toList();
    }

    /** Total tombstone count. */
    public int tombstoneCount() {
        return tombstones.size();
    }

    /** Total request count. */
    public int requestCount() {
        return requests.size();
    }

    /** Export tombstones for bud sync propagation. */
    public List<Tombstone> exportForSync(String agentDid) {
        return tombstonesForAgent(agentDid);
    }

    /** Import tombstones from bud sync (merge, don't overwrite). */
    public int importFromSync(List<Tombstone> incoming) {
        int imported = 0;
        for (var t : incoming) {
            if (!tombstones.containsKey(t.itemId())) {
                tombstones.put(t.itemId(), t);
                imported++;
            }
        }
        return imported;
    }

    private byte[] computeHash(String itemId, String agentDid) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(itemId.getBytes(StandardCharsets.UTF_8));
            md.update(agentDid.getBytes(StandardCharsets.UTF_8));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            return new byte[0];
        }
    }
}
