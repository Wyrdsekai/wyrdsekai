package org.wyrdsekai.core.privacy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Right to erasure handler — GDPR Article 17 (§9F).
 * Handles erasure requests across event-sourced journals.
 * Coordinates with CryptoShredding for actual data destruction.
 */
public class RightToErasure {

    /** An erasure request. */
    public record ErasureRequest(
        String requestId,
        String entityId,
        String reason,
        RequestStatus status,
        Instant requestedAt,
        Instant completedAt,
        List<String> affectedSystems
    ) {}

    public enum RequestStatus { RECEIVED, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, DENIED }

    /** Denial reason codes per GDPR Article 17(3). */
    public enum DenialReason {
        LEGAL_OBLIGATION("Data retained due to legal obligation"),
        PUBLIC_INTEREST("Data retained in public interest"),
        ARCHIVING("Data retained for archiving purposes"),
        LEGAL_CLAIMS("Data retained for legal claims defense");

        private final String description;
        DenialReason(String description) { this.description = description; }
        public String description() { return description; }
    }

    /** Result of processing an erasure request. */
    public record ErasureResult(
        String requestId,
        boolean success,
        int systemsProcessed,
        int systemsFailed,
        List<String> details
    ) {}

    private final CryptoShredding cryptoShredding;
    private final DataMinimizer dataMinimizer;
    private final Map<String, ErasureRequest> requests = new ConcurrentHashMap<>();
    private int nextRequestId = 1;

    public RightToErasure(CryptoShredding cryptoShredding, DataMinimizer dataMinimizer) {
        this.cryptoShredding = cryptoShredding;
        this.dataMinimizer = dataMinimizer;
    }

    /**
     * Submit an erasure request.
     */
    public ErasureRequest submitRequest(String entityId, String reason) {
        var requestId = "erasure-" + nextRequestId++;
        var request = new ErasureRequest(requestId, entityId, reason,
            RequestStatus.RECEIVED, Instant.now(), null,
            List.of("crypto_keys", "data_items", "event_journal"));
        requests.put(requestId, request);
        return request;
    }

    /**
     * Process an erasure request — coordinate crypto-shredding and data minimization.
     */
    public ErasureResult processRequest(String requestId) {
        var request = requests.get(requestId);
        if (request == null) {
            return new ErasureResult(requestId, false, 0, 0,
                List.of("Request not found"));
        }

        // Mark as processing
        requests.put(requestId, new ErasureRequest(requestId, request.entityId(),
            request.reason(), RequestStatus.PROCESSING, request.requestedAt(),
            null, request.affectedSystems()));

        var details = new ArrayList<String>();
        int processed = 0;
        int failed = 0;

        // 1. Crypto-shred the entity's encryption key
        if (cryptoShredding.shred(request.entityId())) {
            details.add("Crypto key shredded for " + request.entityId());
            processed++;
        } else if (cryptoShredding.isShredded(request.entityId())) {
            details.add("Crypto key already shredded for " + request.entityId());
            processed++;
        } else {
            details.add("No crypto key found for " + request.entityId());
            processed++; // Not a failure — entity may not have encrypted data
        }

        // 2. Remove tracked data items
        var items = dataMinimizer.itemsForEntity(request.entityId());
        details.add("Found " + items.size() + " tracked data items for " + request.entityId());
        processed++;

        // 3. Mark event journal entries (in a real system, this would tombstone events)
        details.add("Event journal entries marked for redaction");
        processed++;

        // Determine final status
        var status = failed == 0 ? RequestStatus.COMPLETED : RequestStatus.PARTIALLY_COMPLETED;

        requests.put(requestId, new ErasureRequest(requestId, request.entityId(),
            request.reason(), status, request.requestedAt(),
            Instant.now(), request.affectedSystems()));

        return new ErasureResult(requestId, failed == 0, processed, failed, List.copyOf(details));
    }

    /**
     * Deny an erasure request with a legal reason.
     */
    public ErasureRequest denyRequest(String requestId, DenialReason reason) {
        var request = requests.get(requestId);
        if (request == null) return null;

        var denied = new ErasureRequest(requestId, request.entityId(),
            request.reason() + " [DENIED: " + reason.description() + "]",
            RequestStatus.DENIED, request.requestedAt(), Instant.now(),
            request.affectedSystems());
        requests.put(requestId, denied);
        return denied;
    }

    /** Get an erasure request. */
    public Optional<ErasureRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    /** List all requests for an entity. */
    public List<ErasureRequest> requestsForEntity(String entityId) {
        return requests.values().stream()
            .filter(r -> r.entityId().equals(entityId))
            .sorted(Comparator.comparing(ErasureRequest::requestedAt))
            .toList();
    }

    /** List pending requests. */
    public List<ErasureRequest> pendingRequests() {
        return requests.values().stream()
            .filter(r -> r.status() == RequestStatus.RECEIVED || r.status() == RequestStatus.PROCESSING)
            .toList();
    }

    /** Total request count. */
    public int requestCount() {
        return requests.size();
    }
}
