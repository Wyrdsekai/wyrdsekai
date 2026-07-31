package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 3: BondholderEngagementHistory tests.
 */
class BondholderEngagementHistoryTest {

    private static final String BH = "did:key:bondholder";

    @Test void empty_history_returns_null_baseline() {
        var h = new BondholderEngagementHistory();
        assertThat(h.medianInterval(BH)).isNull();
        assertThat(h.lastEngagement(BH)).isNull();
        assertThat(h.eventCount(BH)).isZero();
        assertThat(h.recentAvgSubstance(BH, 10)).isEqualTo(0.0);
    }

    @Test void single_event_insufficient_for_median() {
        var h = new BondholderEngagementHistory();
        h.record(BH, Instant.now(), 1.0, BondholderEngagementHistory.EventType.TELL);
        assertThat(h.medianInterval(BH)).isNull();
        assertThat(h.eventCount(BH)).isEqualTo(1);
    }

    @Test void daily_engagement_pattern_gives_median_24h() {
        var h = new BondholderEngagementHistory();
        var t = Instant.parse("2026-05-01T12:00:00Z");
        for (int i = 0; i < 10; i++) {
            h.record(BH, t.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        var median = h.medianInterval(BH);
        assertThat(median).isNotNull();
        assertThat(median.toHours()).isEqualTo(24);
    }

    @Test void weekly_engagement_pattern_gives_median_7d() {
        var h = new BondholderEngagementHistory();
        var t = Instant.parse("2026-04-01T00:00:00Z");
        for (int i = 0; i < 5; i++) {
            h.record(BH, t.plus(Duration.ofDays(i * 7L)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        var median = h.medianInterval(BH);
        assertThat(median).isNotNull();
        assertThat(median.toDays()).isEqualTo(7);
    }

    @Test void retention_prunes_old_events_on_add() {
        var h = new BondholderEngagementHistory();
        var old = Instant.parse("2026-01-01T00:00:00Z");
        var recent = Instant.parse("2026-05-15T00:00:00Z");
        h.record(BH, old, 1.0, BondholderEngagementHistory.EventType.TELL);
        h.record(BH, recent, 1.0, BondholderEngagementHistory.EventType.TELL);
        // Old event was beyond 30-day retention from the new event; should be pruned.
        assertThat(h.eventCount(BH)).isEqualTo(1);
        assertThat(h.lastEngagement(BH)).isEqualTo(recent);
    }

    @Test void declared_absence_active_while_in_window() {
        var h = new BondholderEngagementHistory();
        var declaredAt = Instant.parse("2026-05-15T00:00:00Z");
        h.declareAbsence(BH, declaredAt, Duration.ofDays(7));
        // Inside window — active.
        assertThat(h.activeDeclaredAbsence(BH, declaredAt.plus(Duration.ofDays(3)))).isNotNull();
        // Past window — inactive.
        assertThat(h.activeDeclaredAbsence(BH, declaredAt.plus(Duration.ofDays(8)))).isNull();
    }

    @Test void explicit_return_clears_declared_absence() {
        var h = new BondholderEngagementHistory();
        var declaredAt = Instant.parse("2026-05-15T00:00:00Z");
        h.declareAbsence(BH, declaredAt, Duration.ofDays(14));
        var earlyReturn = declaredAt.plus(Duration.ofDays(3));
        h.record(BH, earlyReturn, 1.0, BondholderEngagementHistory.EventType.EXPLICIT_RETURN);
        // Declared absence should be cleared even though we're still inside the original window.
        assertThat(h.activeDeclaredAbsence(BH, earlyReturn.plus(Duration.ofHours(1)))).isNull();
    }

    @Test void recent_substance_avg_uses_last_n_events() {
        var h = new BondholderEngagementHistory();
        var t = Instant.parse("2026-05-01T00:00:00Z");
        // Older events high-substance, recent events low — simulates drift.
        for (int i = 0; i < 7; i++) {
            h.record(BH, t.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        for (int i = 7; i < 10; i++) {
            h.record(BH, t.plus(Duration.ofDays(i)), 0.2,
                BondholderEngagementHistory.EventType.TELL);
        }
        var recent3 = h.recentAvgSubstance(BH, 3);
        var broader10 = h.recentAvgSubstance(BH, 10);
        assertThat(recent3).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(broader10).isGreaterThan(recent3);
    }
}
