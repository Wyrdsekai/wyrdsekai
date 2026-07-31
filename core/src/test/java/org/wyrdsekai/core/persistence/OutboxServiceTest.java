package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxServiceTest {

    private OutboxService service;

    @BeforeEach void setUp() {
        service = new OutboxService();
    }

    @Test void enqueue_creates_pending_message() {
        var msg = service.enqueue("zone-2", "{\"action\":\"sync\"}", "key-1");
        assertThat(msg).isPresent();
        assertThat(msg.get().status()).isEqualTo(OutboxService.MessageStatus.PENDING);
        assertThat(service.pendingCount()).isEqualTo(1);
    }

    @Test void enqueue_idempotent_rejects_duplicate_key() {
        service.enqueue("zone-2", "payload", "key-1");
        service.markDelivered(service.pendingMessages().getFirst().id());

        var duplicate = service.enqueue("zone-2", "payload2", "key-1");
        assertThat(duplicate).isEmpty();
    }

    @Test void markDelivered_updates_status() {
        var msg = service.enqueue("zone-2", "payload", "key-1").orElseThrow();
        var delivered = service.markDelivered(msg.id());
        assertThat(delivered).isPresent();
        assertThat(delivered.get().status()).isEqualTo(OutboxService.MessageStatus.DELIVERED);
        assertThat(service.pendingCount()).isEqualTo(0);
    }

    @Test void markFailed_allows_retry() {
        var msg = service.enqueue("zone-2", "payload", "key-1").orElseThrow();
        var failed = service.markFailed(msg.id());
        assertThat(failed).isPresent();
        assertThat(failed.get().status()).isEqualTo(OutboxService.MessageStatus.FAILED);
        assertThat(failed.get().retryCount()).isEqualTo(1);
        // Still in pending list for retry
        assertThat(service.pendingCount()).isEqualTo(1);
    }

    @Test void markFailed_expires_after_max_retries() {
        var msg = service.enqueue("zone-2", "payload", "key-1").orElseThrow();
        service.markFailed(msg.id()); // retry 1
        service.markFailed(msg.id()); // retry 2
        var expired = service.markFailed(msg.id()); // retry 3 → EXPIRED
        assertThat(expired.get().status()).isEqualTo(OutboxService.MessageStatus.EXPIRED);
        assertThat(service.pendingCount()).isEqualTo(0);
    }
}
