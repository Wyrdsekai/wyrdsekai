package org.wyrdsekai.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotaPolicyTest {

    @Test
    void family_is_unlimited() {
        var quota = QuotaPolicy.family();
        assertTrue(quota.allowInference(Long.MAX_VALUE - 1, 1_000_000));
        assertTrue(quota.allowStorage(Long.MAX_VALUE - 1, 1_000_000));
        assertTrue(quota.allowBandwidth(Long.MAX_VALUE - 1, 1_000_000));
        assertTrue(quota.allowTransit());
        assertTrue(quota.allowTell());
        assertTrue(quota.allowInventory());
    }

    @Test
    void partner_has_moderate_limits() {
        var quota = QuotaPolicy.partner();
        assertTrue(quota.allowInference(0, 500_000));
        assertFalse(quota.allowInference(0, 500_001));
        assertTrue(quota.allowTell());
        assertTrue(quota.allowInventory());
    }

    @Test
    void tourist_is_restrictive() {
        var quota = QuotaPolicy.tourist();
        assertTrue(quota.allowInference(0, 50_000));
        assertFalse(quota.allowInference(0, 50_001));
        assertFalse(quota.allowInventory());  // tourists can't carry items
    }

    @Test
    void denied_blocks_everything() {
        var quota = QuotaPolicy.denied();
        assertFalse(quota.allowTransit());
        assertFalse(quota.allowTell());
        assertFalse(quota.allowInventory());
    }

    @Test
    void forTrustLevel_returns_correct_preset() {
        assertTrue(QuotaPolicy.forTrustLevel("family").allowInventory());
        assertTrue(QuotaPolicy.forTrustLevel("partner").allowInventory());
        assertFalse(QuotaPolicy.forTrustLevel("tourist").allowInventory());
        assertFalse(QuotaPolicy.forTrustLevel("unknown").allowInventory());  // defaults to tourist
    }

    @Test
    void concurrent_sessions_enforced() {
        var quota = QuotaPolicy.tourist();  // 3 max
        assertTrue(quota.allowNewSession(2));
        assertFalse(quota.allowNewSession(3));
    }

    @Test
    void quota_accumulation() {
        var quota = QuotaPolicy.partner();
        // 400K already used, 100K more fits (500K limit)
        assertTrue(quota.allowInference(400_000, 100_000));
        // 450K + 51K = 501K exceeds
        assertFalse(quota.allowInference(450_000, 51_000));
    }

    @Test
    void unlimited_when_quota_zero() {
        assertTrue(QuotaPolicy.isUnlimited(0));
        assertFalse(QuotaPolicy.isUnlimited(100));
    }
}
