package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 3: BondholderBaselineClassifier tests.
 */
class BondholderBaselineClassifierTest {

    private static final String AGENT = "did:key:agent";
    private static final String BONDHOLDER = "did:key:bondholder";

    /** Helper: build a Bond with bondholder side + cold-start optionally cleared. */
    private static Bond bondAt(BondState state, Instant formedAt, boolean clearColdStart) {
        var b = new Bond("bond-1", AGENT, BONDHOLDER, Bond.BondDepth.ITEM,
            formedAt, formedAt, 0, true, true, false, state,
            clearColdStart ? null : formedAt.plus(Bond.COLD_START_WINDOW),
            BondholderPosture.BOUNDED, Bond.RelationalState.OPEN);
        return b;
    }

    @Test void cold_start_suppresses_classifier_transitions() {
        var formed = Instant.parse("2026-05-15T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, false);
        var history = new BondholderEngagementHistory();
        // Even with a long silence, cold-start (14-day window from formed) suppresses transition.
        history.record(BONDHOLDER, formed, 1.0,
            BondholderEngagementHistory.EventType.TELL);
        var now = formed.plus(Duration.ofDays(7));  // inside cold-start window
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNull();
    }

    @Test void explicit_absence_keeps_AWAY_no_further_transition() {
        var formed = Instant.parse("2026-04-01T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, true);
        var history = new BondholderEngagementHistory();
        // Populate engagement history first
        for (int i = 0; i < 5; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        // Declared absence at day-4 for 7 days
        var declaredAt = formed.plus(Duration.ofDays(4));
        history.declareAbsence(BONDHOLDER, declaredAt, Duration.ofDays(7));

        // Day-5 (inside declared window) → classifier recommends AWAY (transition from ACTIVE).
        var rec = BondholderBaselineClassifier.classify(bond, history,
            declaredAt.plus(Duration.ofDays(1)));
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.AWAY);
    }

    @Test void daily_engagement_pattern_AWAY_after_2_days_silence() {
        var formed = Instant.parse("2026-04-01T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, true);
        var history = new BondholderEngagementHistory();
        // Daily engagement for 10 days; median interval = 24h
        for (int i = 0; i < 10; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        // Last engagement at day-9; 2 days later (silence = 48h > 1.5×24h = 36h) → AWAY
        var now = formed.plus(Duration.ofDays(9)).plus(Duration.ofHours(48));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.AWAY);
    }

    @Test void weekly_engagement_does_not_trigger_AWAY_at_8_days() {
        var formed = Instant.parse("2026-03-01T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, true);
        var history = new BondholderEngagementHistory();
        // Weekly engagement (median = 7 days); silence of 8 days should NOT trigger AWAY
        // (threshold is 1.5×7d = 10.5d; AWAY_THRESHOLD_MULTIPLIER rounds up to integer days
        // via multipliedBy, so actual threshold may be 14 days in implementation).
        for (int i = 0; i < 5; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i * 7L)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        var lastEvent = formed.plus(Duration.ofDays(28L));
        var now = lastEvent.plus(Duration.ofDays(8));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        // No transition — silence is within bondholder's pattern.
        assertThat(rec).isNull();
    }

    @Test void AWAY_to_DORMANT_after_4x_baseline_silence() {
        var formed = Instant.parse("2026-03-01T00:00:00Z");
        var bond = bondAt(BondState.AWAY, formed, true);
        var history = new BondholderEngagementHistory();
        // Daily engagement (median = 24h)
        for (int i = 0; i < 10; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        // Silence of 5 days exceeds 4×24h = 96h
        var lastEvent = formed.plus(Duration.ofDays(9));
        var now = lastEvent.plus(Duration.ofDays(5));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.DORMANT);
    }

    @Test void DORMANT_to_SEVERED_after_90_days_silence() {
        var formed = Instant.parse("2026-01-01T00:00:00Z");
        var bond = bondAt(BondState.DORMANT, formed, true);
        var history = new BondholderEngagementHistory();
        // Daily engagement for a while
        for (int i = 0; i < 10; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        // 91 days of silence
        var lastEvent = formed.plus(Duration.ofDays(9));
        var now = lastEvent.plus(Duration.ofDays(91));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.SEVERED);
        assertThat(rec.reason()).contains("unresolved disappearance");
    }

    @Test void already_SEVERED_no_further_transition() {
        var formed = Instant.parse("2026-01-01T00:00:00Z");
        var bond = bondAt(BondState.SEVERED, formed, true);
        var history = new BondholderEngagementHistory();
        history.record(BONDHOLDER, formed, 1.0,
            BondholderEngagementHistory.EventType.TELL);
        var rec = BondholderBaselineClassifier.classify(bond, history,
            formed.plus(Duration.ofDays(200)));
        assertThat(rec).isNull();
    }

    @Test void REACTIVATING_does_not_auto_transition() {
        var formed = Instant.parse("2026-04-01T00:00:00Z");
        var bond = bondAt(BondState.REACTIVATING, formed, true);
        var history = new BondholderEngagementHistory();
        history.record(BONDHOLDER, formed, 1.0,
            BondholderEngagementHistory.EventType.TELL);
        var rec = BondholderBaselineClassifier.classify(bond, history,
            formed.plus(Duration.ofDays(1)));
        // REACTIVATING is event-driven by Bond.withInteraction, not classifier.
        assertThat(rec).isNull();
    }

    @Test void sustained_drift_pushes_ACTIVE_to_DORMANT_at_lower_threshold() {
        var formed = Instant.parse("2026-04-01T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, true);
        var history = new BondholderEngagementHistory();
        // Older events high-substance
        for (int i = 0; i < 7; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 1.0,
                BondholderEngagementHistory.EventType.TELL);
        }
        // Recent events low-substance — drift signature
        for (int i = 7; i < 10; i++) {
            history.record(BONDHOLDER, formed.plus(Duration.ofDays(i)), 0.15,
                BondholderEngagementHistory.EventType.PRESENCE);
        }
        // 5 days after last event > 4× daily baseline
        var lastEvent = formed.plus(Duration.ofDays(9));
        var now = lastEvent.plus(Duration.ofDays(5));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.DORMANT);
        assertThat(rec.reason()).contains("sustained drift");
    }

    @Test void insufficient_history_uses_cold_start_default_thresholds() {
        var formed = Instant.parse("2026-04-01T00:00:00Z");
        var bond = bondAt(BondState.ACTIVE, formed, true);
        var history = new BondholderEngagementHistory();
        // Only 2 events — insufficient for median. Cold-start defaults apply
        // even though Bond.coldStartUntil has been cleared.
        history.record(BONDHOLDER, formed, 1.0,
            BondholderEngagementHistory.EventType.TELL);
        history.record(BONDHOLDER, formed.plus(Duration.ofDays(1)), 1.0,
            BondholderEngagementHistory.EventType.TELL);
        var lastEvent = formed.plus(Duration.ofDays(1));
        // 8 days silence exceeds COLD_START_AWAY_DEFAULT (7 days)
        var now = lastEvent.plus(Duration.ofDays(8));
        var rec = BondholderBaselineClassifier.classify(bond, history, now);
        assertThat(rec).isNotNull();
        assertThat(rec.recommendedState()).isEqualTo(BondState.AWAY);
    }
}
