package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutcomeTrackerTest {

    @Test
    void perfect_calibration() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("p1", "goal", "search", true, true);
        }
        assertEquals(1.0, tracker.calibrationScore(), 0.01);
    }

    @Test
    void overconfident_detected() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 15; i++) {
            tracker.record("p1", "goal", "search", true, i < 5); // predict all success, 5/15 actual
        }
        assertTrue(tracker.isOverconfident());
    }

    @Test
    void underconfident_not_flagged() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("p1", "goal", "search", false, true); // predict failure, actual success
        }
        assertFalse(tracker.isOverconfident());
    }

    @Test
    void insufficient_data_returns_default() {
        var tracker = new OutcomeTracker();
        tracker.record("p1", "goal", "search", true, true);
        assertEquals(0.5, tracker.calibrationScore(), 0.01); // < 5 records
    }

    @Test
    void domain_specific_calibration() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 5; i++) {
            tracker.record("p1", "g", "search", true, true);
            tracker.record("p1", "g", "navigation", true, false);
        }
        assertTrue(tracker.calibrationScore("search") > tracker.calibrationScore("navigation"));
    }

    @Test
    void retry_adjustment_reduces_for_poor_calibration() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("p1", "g", "search", true, false);
        }
        assertEquals(-1, tracker.retryAdjustment("search"));
    }

    @Test
    void retry_adjustment_increases_for_good_calibration() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 10; i++) {
            tracker.record("p1", "g", "search", true, true);
        }
        assertEquals(1, tracker.retryAdjustment("search"));
    }

    @Test
    void buffer_capacity_maintained() {
        var tracker = new OutcomeTracker();
        for (int i = 0; i < 150; i++) {
            tracker.record("p" + i, "g", "d", true, true);
        }
        assertEquals(100, tracker.recordCount());
    }
}
