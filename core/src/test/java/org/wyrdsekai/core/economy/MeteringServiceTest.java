package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.QuotaPolicy;

import static org.junit.jupiter.api.Assertions.*;

class MeteringServiceTest {

    @BeforeEach
    void setup() {
        MeteringService.init();
        MeteringService.get().clear();
    }

    @Test
    void records_event_and_returns_cu() {
        var cu = MeteringService.get().record(
            "alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL,
            5.0,  // 5K tokens
            "player-1");
        assertEquals(5.0, cu);
        assertEquals(1, MeteringService.get().eventCount());
    }

    @Test
    void bilateral_multiplier_applied() {
        // Family bilateral = 0 multiplier → free
        var cu = MeteringService.get().record(
            "alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL,
            5.0, "player-1", 0.0);
        assertEquals(0.0, cu);
    }

    @Test
    void tracks_daily_usage_per_partner() {
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 10.0, "p1");
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 5.0, "p1");

        var usage = MeteringService.get().usageToday(
            "beta", ReferenceRates.SERVICE_INFERENCE_SMALL);
        assertEquals(15, usage.totalUnits());
        assertEquals(15.0, usage.totalCU());
        assertEquals(2, usage.eventCount());
    }

    @Test
    void within_quota_allows_inference() {
        // 50K token/day tourist quota
        var quota = QuotaPolicy.tourist();
        // No usage yet — 40K request fits
        assertTrue(MeteringService.get().withinQuota(
            "beta", quota, ReferenceRates.SERVICE_INFERENCE_SMALL, 40));  // 40K tokens
    }

    @Test
    void within_quota_rejects_over_limit() {
        var quota = QuotaPolicy.tourist();  // 50K/day
        // Record usage
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 45.0, "p1"); // 45K tokens
        // Another 10K would exceed (45K + 10K = 55K > 50K)
        assertFalse(MeteringService.get().withinQuota(
            "beta", quota, ReferenceRates.SERVICE_INFERENCE_SMALL, 10));
    }

    @Test
    void unlimited_quota_always_allows() {
        var quota = QuotaPolicy.family();
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 1_000_000.0, "p1");
        assertTrue(MeteringService.get().withinQuota(
            "beta", quota, ReferenceRates.SERVICE_INFERENCE_SMALL, 1_000_000));
    }

    @Test
    void recent_events_bounded() {
        for (int i = 0; i < 50; i++) {
            MeteringService.get().record("alpha", "beta",
                ReferenceRates.SERVICE_BANDWIDTH, 1.0, "p1");
        }
        var recent = MeteringService.get().recentEvents(20);
        assertEquals(20, recent.size());
    }

    @Test
    void inference_tokens_today_aggregates() {
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_SMALL, 10.0, "p1");  // 10K tokens
        MeteringService.get().record("alpha", "beta",
            ReferenceRates.SERVICE_INFERENCE_LARGE, 5.0, "p1");   // 5K tokens
        var total = MeteringService.get().inferenceTokensToday("beta");
        assertEquals(15000, total);
    }
}
