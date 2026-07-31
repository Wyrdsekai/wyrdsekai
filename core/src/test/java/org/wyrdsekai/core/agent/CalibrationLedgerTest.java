package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationLedgerTest {

    @Test void initial_state_neutral() {
        var ledger = new CalibrationLedger();
        assertThat(ledger.getTimingBias("anomaly")).isEqualTo(0.0);
        assertThat(ledger.getSalienceWeight("pattern")).isEqualTo(1.0);
        assertThat(ledger.getIntrusionTolerance()).isEqualTo(0.5);
        assertThat(ledger.getPositiveFeedbackCount()).isEqualTo(0);
    }

    @Test void timing_sooner_decreases_bias() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("timing", "sooner", "anomaly", "why didn't you tell me?");
        assertThat(ledger.getTimingBias("anomaly")).isLessThan(0.0);
    }

    @Test void timing_later_increases_bias() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("timing", "later", "topic", "that can wait");
        assertThat(ledger.getTimingBias("topic")).isGreaterThan(0.0);
    }

    @Test void salience_lower_decreases_weight() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("salience", "lower", "topic", "not important");
        assertThat(ledger.getSalienceWeight("topic")).isLessThan(1.0);
    }

    @Test void salience_higher_increases_weight() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("salience", "higher", "anomaly", "always tell me about anomalies");
        assertThat(ledger.getSalienceWeight("anomaly")).isGreaterThan(1.0);
    }

    @Test void intrusion_lower_reduces_tolerance() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("intrusion", "lower", null, "stop bugging me");
        assertThat(ledger.getIntrusionTolerance()).isLessThan(0.5);
    }

    @Test void positive_feedback_increases_count_and_tolerance() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("positive", "good", null, "good catch");
        assertThat(ledger.getPositiveFeedbackCount()).isEqualTo(1);
        assertThat(ledger.getIntrusionTolerance()).isGreaterThan(0.5);
    }

    @Test void feedback_log_capped_at_20() {
        var ledger = new CalibrationLedger();
        for (int i = 0; i < 25; i++) {
            ledger.applyFeedback("positive", "good", null, "feedback " + i);
        }
        assertThat(ledger.getRecentFeedback()).hasSize(20);
        assertThat(ledger.getPositiveFeedbackCount()).isEqualTo(25);
    }

    @Test void timing_bias_clamped() {
        var ledger = new CalibrationLedger();
        for (int i = 0; i < 20; i++) {
            ledger.applyFeedback("timing", "sooner", "anomaly", "tell me faster");
        }
        assertThat(ledger.getTimingBias("anomaly")).isGreaterThanOrEqualTo(-1.0);
    }

    @Test void salience_weight_clamped() {
        var ledger = new CalibrationLedger();
        for (int i = 0; i < 20; i++) {
            ledger.applyFeedback("salience", "higher", "anomaly", "very important");
        }
        assertThat(ledger.getSalienceWeight("anomaly")).isLessThanOrEqualTo(2.0);
    }

    @Test void global_timing_applies_to_all_categories() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("timing", "sooner", null, "tell me everything sooner");
        assertThat(ledger.getTimingBias("anomaly")).isLessThan(0.0);
        assertThat(ledger.getTimingBias("pattern")).isLessThan(0.0);
        assertThat(ledger.getTimingBias("forecast")).isLessThan(0.0);
    }

    @Test void clearFeedbackLog_empties_log_but_preserves_state() {
        var ledger = new CalibrationLedger();
        ledger.applyFeedback("timing", "sooner", "anomaly", "tell me faster");
        ledger.applyFeedback("positive", "good", null, "good call");
        assertThat(ledger.getRecentFeedback()).isNotEmpty();

        ledger.clearFeedbackLog();
        assertThat(ledger.getRecentFeedback()).isEmpty();
        // But timing bias and positive count preserved
        assertThat(ledger.getTimingBias("anomaly")).isLessThan(0.0);
        assertThat(ledger.getPositiveFeedbackCount()).isEqualTo(1);
    }
}
