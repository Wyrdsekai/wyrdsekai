package org.wyrdsekai.core.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RightToErasureTest {

    private CryptoShredding cryptoShredding;
    private DataMinimizer dataMinimizer;
    private RightToErasure erasure;

    @BeforeEach
    void setUp() {
        cryptoShredding = new CryptoShredding();
        dataMinimizer = new DataMinimizer();
        erasure = new RightToErasure(cryptoShredding, dataMinimizer);
    }

    @Test void submit_request() {
        var request = erasure.submitRequest("entity-1", "Right to be forgotten");
        assertThat(request.requestId()).startsWith("erasure-");
        assertThat(request.status()).isEqualTo(RightToErasure.RequestStatus.RECEIVED);
        assertThat(request.entityId()).isEqualTo("entity-1");
    }

    @Test void process_request_shreds_key() {
        cryptoShredding.generateKey("entity-1");
        var request = erasure.submitRequest("entity-1", "GDPR Article 17");

        var result = erasure.processRequest(request.requestId());
        assertThat(result.success()).isTrue();
        assertThat(result.systemsProcessed()).isGreaterThanOrEqualTo(1);
        assertThat(cryptoShredding.isShredded("entity-1")).isTrue();
    }

    @Test void process_request_updates_status() {
        var request = erasure.submitRequest("entity-1", "reason");
        erasure.processRequest(request.requestId());

        var updated = erasure.getRequest(request.requestId());
        assertThat(updated).isPresent();
        assertThat(updated.get().status()).isEqualTo(RightToErasure.RequestStatus.COMPLETED);
        assertThat(updated.get().completedAt()).isNotNull();
    }

    @Test void deny_request() {
        var request = erasure.submitRequest("entity-1", "reason");
        var denied = erasure.denyRequest(request.requestId(),
            RightToErasure.DenialReason.LEGAL_OBLIGATION);

        assertThat(denied.status()).isEqualTo(RightToErasure.RequestStatus.DENIED);
    }

    @Test void requests_for_entity() {
        erasure.submitRequest("entity-1", "first request");
        erasure.submitRequest("entity-1", "second request");
        erasure.submitRequest("entity-2", "other entity");

        assertThat(erasure.requestsForEntity("entity-1")).hasSize(2);
    }

    @Test void pending_requests() {
        erasure.submitRequest("entity-1", "pending");
        erasure.submitRequest("entity-2", "also pending");

        assertThat(erasure.pendingRequests()).hasSize(2);
    }

    @Test void processed_request_not_pending() {
        var request = erasure.submitRequest("entity-1", "reason");
        erasure.processRequest(request.requestId());

        assertThat(erasure.pendingRequests()).isEmpty();
    }

    @Test void request_count() {
        assertThat(erasure.requestCount()).isEqualTo(0);
        erasure.submitRequest("entity-1", "reason");
        assertThat(erasure.requestCount()).isEqualTo(1);
    }

    @Test void denial_reasons() {
        assertThat(RightToErasure.DenialReason.LEGAL_OBLIGATION.description())
            .contains("legal obligation");
        assertThat(RightToErasure.DenialReason.PUBLIC_INTEREST.description())
            .contains("public interest");
    }

    @Test void process_nonexistent_request() {
        var result = erasure.processRequest("nonexistent");
        assertThat(result.success()).isFalse();
    }
}
