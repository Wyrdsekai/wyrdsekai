package org.wyrdsekai.core.interop;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §97.9 — Dock Quarantine Forge Review Integration.
 */
class DockQuarantineForgeTest {

    private DockQuarantine quarantine;

    @BeforeEach
    void setup() {
        quarantine = new DockQuarantine();
        // Submit a mix of items
        quarantine.submit("i1", "did:trusted", TrustTier.TRUSTED, "Memory 1", "memory", 0.7);
        quarantine.submit("i2", "did:trusted", TrustTier.TRUSTED, "Memory 2", "memory", 0.5);
        quarantine.submit("i3", "did:anon", TrustTier.ANONYMOUS, "Memory 3", "memory", 0.9);
        quarantine.submit("i4", "did:trusted", TrustTier.TRUSTED, "Identity core", "identity-core", 1.0);
    }

    @Test
    void forge_review_accept_all() {
        // Policy that accepts everything
        var result = quarantine.forgeReview(item -> true);

        // Only 3 pending (identity-core was BLOCKED immediately)
        assertEquals(3, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(0, result.skipped());
        assertEquals(3, result.total());
        assertNotNull(result.reviewedAt());
    }

    @Test
    void forge_review_reject_all() {
        var result = quarantine.forgeReview(item -> false);

        assertEquals(0, result.accepted());
        assertEquals(3, result.rejected());
        assertEquals(0, quarantine.pendingCount());
    }

    @Test
    void forge_review_selective() {
        // Accept only items with capped significance > 0.3
        var result = quarantine.forgeReview(item -> item.cappedSignificance() > 0.3);

        // i1: trusted, sig 0.7, capped 0.7 → accepted
        // i2: trusted, sig 0.5, capped 0.5 → accepted
        // i3: anon, sig 0.9, capped 0.1 → rejected (capped below 0.3)
        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
    }

    @Test
    void forge_review_handles_policy_errors() {
        int[] callCount = {0};
        var result = quarantine.forgeReview(item -> {
            callCount[0]++;
            if (callCount[0] == 2) throw new RuntimeException("Policy crashed");
            return true;
        });

        // 1 accepted, 1 skipped (error), 1 accepted
        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
        assertEquals(1, result.skipped());
    }

    @Test
    void accepted_items_retrievable() {
        quarantine.forgeReview(item -> item.cappedSignificance() > 0.3);

        var accepted = quarantine.acceptedItems();
        assertEquals(2, accepted.size());
        assertTrue(accepted.stream().allMatch(i ->
            i.status() == DockQuarantine.QuarantineStatus.ACCEPTED));
    }

    @Test
    void clear_processed_removes_accepted_and_rejected() {
        quarantine.forgeReview(item -> item.cappedSignificance() > 0.3);

        int cleared = quarantine.clearProcessed();
        assertEquals(3, cleared); // 2 accepted + 1 rejected

        // Only blocked items remain
        assertEquals(1, quarantine.totalCount());
    }

    @Test
    void default_policy_behavior() {
        var policy = DockQuarantine.defaultPolicy();

        // Trusted + high significance → accept
        var trustedHigh = quarantine.pendingItems().stream()
            .filter(i -> "did:trusted".equals(i.sourceDid()) && i.cappedSignificance() > 0.5)
            .findFirst().orElseThrow();
        assertTrue(policy.evaluate(trustedHigh));

        // Anonymous + low capped significance → reject
        var anonLow = quarantine.pendingItems().stream()
            .filter(i -> "did:anon".equals(i.sourceDid()))
            .findFirst().orElseThrow();
        assertFalse(policy.evaluate(anonLow));
    }

    @Test
    void forge_review_idempotent() {
        quarantine.forgeReview(item -> true);
        assertEquals(0, quarantine.pendingCount());

        // Second review has nothing to process
        var result = quarantine.forgeReview(item -> true);
        assertEquals(0, result.total());
    }

    @Test
    void full_forge_cycle() {
        // 1. Items arrive (done in setup)
        assertEquals(3, quarantine.pendingCount());

        // 2. Forge reviews
        var reviewResult = quarantine.forgeReview(DockQuarantine.defaultPolicy());
        assertTrue(reviewResult.accepted() > 0);

        // 3. Get accepted items for integration
        var accepted = quarantine.acceptedItems();
        assertFalse(accepted.isEmpty());

        // 4. Clear processed
        quarantine.clearProcessed();

        // 5. Only blocked remain
        assertEquals(0, quarantine.pendingCount());
        var stats = quarantine.stats();
        assertEquals(1, stats.get(DockQuarantine.QuarantineStatus.BLOCKED));
    }
}
