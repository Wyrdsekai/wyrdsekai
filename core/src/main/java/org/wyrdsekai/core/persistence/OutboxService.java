package org.wyrdsekai.core.persistence;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbox pattern for cross-zone operations (§70).
 * WAL-based deferred delivery with idempotency keys.
 * In-memory for M0; WAL/JDBC persistence deferred to M1.
 */
public class OutboxService {

    public enum MessageStatus {
        PENDING, DELIVERED, FAILED, EXPIRED
    }

    /** An outbox message. */
    public record OutboxMessage(
        String id,
        String idempotencyKey,
        String targetZone,
        String payload,
        MessageStatus status,
        Instant createdAt,
        Instant deliveredAt,
        int retryCount
    ) {}

    private final Map<String, OutboxMessage> messages = new ConcurrentHashMap<>();
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();
    private int nextId = 1;
    private static final int MAX_RETRIES = 3;

    /** Enqueue a message for delivery. Returns empty if idempotency key already processed. */
    public Optional<OutboxMessage> enqueue(String targetZone, String payload, String idempotencyKey) {
        if (processedKeys.contains(idempotencyKey)) {
            return Optional.empty(); // Already processed — idempotent
        }

        var id = "outbox-" + nextId++;
        var msg = new OutboxMessage(id, idempotencyKey, targetZone, payload,
            MessageStatus.PENDING, Instant.now(), null, 0);
        messages.put(id, msg);
        return Optional.of(msg);
    }

    /** Mark a message as delivered. */
    public Optional<OutboxMessage> markDelivered(String messageId) {
        var msg = messages.get(messageId);
        if (msg == null) return Optional.empty();
        var delivered = new OutboxMessage(msg.id(), msg.idempotencyKey(), msg.targetZone(),
            msg.payload(), MessageStatus.DELIVERED, msg.createdAt(), Instant.now(), msg.retryCount());
        messages.put(messageId, delivered);
        processedKeys.add(msg.idempotencyKey());
        return Optional.of(delivered);
    }

    /** Mark a message as failed (may be retried). */
    public Optional<OutboxMessage> markFailed(String messageId) {
        var msg = messages.get(messageId);
        if (msg == null) return Optional.empty();
        var newRetry = msg.retryCount() + 1;
        var status = newRetry >= MAX_RETRIES ? MessageStatus.EXPIRED : MessageStatus.FAILED;
        var failed = new OutboxMessage(msg.id(), msg.idempotencyKey(), msg.targetZone(),
            msg.payload(), status, msg.createdAt(), null, newRetry);
        messages.put(messageId, failed);
        return Optional.of(failed);
    }

    /** Get all pending messages (for delivery). */
    public List<OutboxMessage> pendingMessages() {
        return messages.values().stream()
            .filter(m -> m.status() == MessageStatus.PENDING || m.status() == MessageStatus.FAILED)
            .sorted(Comparator.comparing(OutboxMessage::createdAt))
            .toList();
    }

    /** Check if an idempotency key has already been processed. */
    public boolean isProcessed(String idempotencyKey) {
        return processedKeys.contains(idempotencyKey);
    }

    /** Total message count. */
    public int messageCount() {
        return messages.size();
    }

    /** Pending message count. */
    public int pendingCount() {
        return (int) messages.values().stream()
            .filter(m -> m.status() == MessageStatus.PENDING || m.status() == MessageStatus.FAILED)
            .count();
    }
}
